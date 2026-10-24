import { useEffect, useState } from 'react';
import { authApi, driverApi, tokenStore } from '../api/client.js';
import { useApp } from '../context/AppContext.jsx';
import Button from '../components/atoms/Button.jsx';
import Input, { Select } from '../components/atoms/Input.jsx';
import Badge from '../components/atoms/Badge.jsx';
import { LoadingOverlay, EmptyState } from '../components/atoms/Spinner.jsx';
import styles from './Driver.module.css';

const QUEUE_KEY = 'zp-offline-queue';

const STRINGS = {
  en: { login: 'Driver login', email: 'Email', password: 'Password', logout: 'Logout',
    trips: 'My trips', accept: 'Accept', start: 'Start trip', slip: 'Add slip', sos: 'SOS',
    kind: 'Type', amount: 'Amount (₹)', fuel: 'Fuel', toll: 'Toll', send: 'Send SOS',
    sync: 'Sync pending', pending: 'pending', noTrips: 'No assigned trips right now.',
    synced: 'Queue synced', queued: 'Saved offline — will sync later', sosSent: 'SOS sent. Dispatch has been notified.' },
  kn: { login: 'ಚಾಲಕ ಲಾಗಿನ್', email: 'ಇಮೇಲ್', password: 'ಪಾಸ್‌ವರ್ಡ್', logout: 'ಲಾಗ್ಔಟ್',
    trips: 'ನನ್ನ ಪ್ರವಾಸಗಳು', accept: 'ಒಪ್ಪಿಕೊಳ್ಳಿ', start: 'ಪ್ರವಾಸ ಪ್ರಾರಂಭಿಸಿ', slip: 'ಚೀಟಿ ಸೇರಿಸಿ', sos: 'SOS',
    kind: 'ವಿಧ', amount: 'ಮೊತ್ತ (₹)', fuel: 'ಇಂಧನ', toll: 'ಸುಂಕ', send: 'SOS ಕಳುಹಿಸಿ',
    sync: 'ಬಾಕಿ ಸಿಂಕ್ ಮಾಡಿ', pending: 'ಬಾಕಿ', noTrips: 'ಸದ್ಯಕ್ಕೆ ಯಾವುದೇ ಪ್ರವಾಸಗಳಿಲ್ಲ.',
    synced: 'ಸಿಂಕ್ ಆಗಿದೆ', queued: 'ಆಫ್‌ಲೈನ್‌ನಲ್ಲಿ ಉಳಿಸಲಾಗಿದೆ', sosSent: 'SOS ಕಳುಹಿಸಲಾಗಿದೆ.' },
  hi: { login: 'ड्राइवर लॉगिन', email: 'ईमेल', password: 'पासवर्ड', logout: 'लॉगआउट',
    trips: 'मेरी यात्राएं', accept: 'स्वीकार करें', start: 'यात्रा शुरू करें', slip: 'पर्ची जोड़ें', sos: 'SOS',
    kind: 'प्रकार', amount: 'राशि (₹)', fuel: 'ईंधन', toll: 'टोल', send: 'SOS भेजें',
    sync: 'बकाया सिंक करें', pending: 'बकाया', noTrips: 'अभी कोई यात्रा नहीं है।',
    synced: 'सिंक हो गया', queued: 'ऑफ़लाइन सहेजा गया', sosSent: 'SOS भेजा गया।' },
};

const readQueue = () => { try { return JSON.parse(localStorage.getItem(QUEUE_KEY) || '[]'); } catch { return []; } };

export default function Driver() {
  const { addToast } = useApp();
  const [lang, setLang] = useState('en');
  const t = STRINGS[lang];
  const [token, setToken] = useState(tokenStore.get());
  const [form, setForm] = useState({ email: '', password: '' });
  const [routes, setRoutes] = useState([]);
  const [loading, setLoading] = useState(false);
  const [queue, setQueue] = useState(readQueue());
  const [slip, setSlip] = useState({}); // routeId -> {kind, amount}
  const [busy, setBusy] = useState(null);

  const persistQueue = (q) => { setQueue(q); localStorage.setItem(QUEUE_KEY, JSON.stringify(q)); };
  const enqueue = (path, body) => {
    persistQueue([...readQueue(), { path, body, ts: Date.now() }]);
    addToast(t.queued, 'info');
  };

  const load = () => {
    setLoading(true);
    driverApi.myRoutes().then(setRoutes).catch((e) => addToast(e.message, 'error')).finally(() => setLoading(false));
  };
  useEffect(() => { if (token) load(); }, [token]); // eslint-disable-line react-hooks/exhaustive-deps

  const login = async (e) => {
    e.preventDefault();
    try {
      const data = await authApi.login(form.email, form.password);
      tokenStore.set(data.accessToken);
      setToken(data.accessToken);
    } catch (err) { addToast(err.message, 'error'); }
  };
  const logout = () => { tokenStore.set(null); setToken(null); setRoutes([]); };

  /** POST with offline fallback: network failure queues, 4xx drops with toast. */
  const guarded = async (routeId, label, fn, path, body) => {
    setBusy(`${routeId}-${label}`);
    try {
      await fn();
      load();
    } catch (e) {
      if (e.status >= 400 && e.status < 500) addToast(e.message, 'error');
      else enqueue(path, body);
    } finally { setBusy(null); }
  };

  const syncQueue = async () => {
    const pending = readQueue();
    const remaining = [];
    for (const item of pending) {
      try {
        await fetch(`${import.meta.env.VITE_API_BASE_URL || 'https://zonepilot-backend.onrender.com'}/api${item.path}`,
          { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokenStore.get()}` }, body: JSON.stringify(item.body) })
          .then(async (r) => { if (!r.ok) throw new Error(`HTTP ${r.status}`); });
      } catch { remaining.push(item); }
    }
    persistQueue(remaining);
    if (!remaining.length) addToast(t.synced, 'success');
    load();
  };

  const sendSos = (routeId) => {
    // No GPS (unsupported/denied/timeout): the distress signal still goes out
    // with unknown location — never (0,0).
    const sosWithoutFix = () => guarded(routeId, 'sos',
      () => driverApi.sos(null, null, routeId).then(() => addToast(t.sosSent, 'success')),
      '/driver/sos', { lat: null, lng: null, routeId });
    if (!navigator.geolocation) { sosWithoutFix(); return; }
    navigator.geolocation.getCurrentPosition(
      (pos) => guarded(routeId, 'sos',
        () => driverApi.sos(pos.coords.latitude, pos.coords.longitude, routeId).then(() => addToast(t.sosSent, 'success')),
        '/driver/sos', { routeId }), // offline SOS replays without coords => unknown location
      () => guarded(routeId, 'sos',
        () => driverApi.sos(null, null, routeId).then(() => addToast(t.sosSent, 'success')),
        '/driver/sos', { lat: null, lng: null, routeId }),
      { timeout: 8000 });
  };

  if (!token) {
    return (
      <div className={styles.wrap}>
        <div className={styles.langRow} role="group" aria-label="Language">
          {['en', 'kn', 'hi'].map((l) => (
            <Button key={l} variant={lang === l ? 'primary' : 'ghost'} size="sm" onClick={() => setLang(l)}>{l.toUpperCase()}</Button>
          ))}
        </div>
        <form className={styles.card} onSubmit={login}>
          <h2>{t.login}</h2>
          <Input label={t.email} id="drv-email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} required />
          <Input label={t.password} id="drv-pass" type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required />
          <Button type="submit" variant="primary">{t.login}</Button>
        </form>
      </div>
    );
  }

  return (
    <div className={styles.wrap}>
      <div className={styles.topbar}>
        <div className={styles.langRow} role="group" aria-label="Language">
          {['en', 'kn', 'hi'].map((l) => (
            <Button key={l} variant={lang === l ? 'primary' : 'ghost'} size="sm" onClick={() => setLang(l)}>{l.toUpperCase()}</Button>
          ))}
        </div>
        <Button variant="ghost" size="sm" onClick={logout}>{t.logout}</Button>
      </div>
      {queue.length > 0 && (
        <div className={styles.queueBar}>
          <Badge variant="amber">{queue.length} {t.pending}</Badge>
          <Button size="sm" variant="primary" onClick={syncQueue}>{t.sync}</Button>
        </div>
      )}
      <h2>{t.trips}</h2>
      {loading && <LoadingOverlay />}
      {!loading && routes.length === 0 && <EmptyState message={t.noTrips} />}
      {routes.map((r) => {
        const s = slip[r.id] || { kind: 'FUEL', amount: '' };
        return (
          <article key={r.id} className={styles.card}>
            <div className={styles.row}>
              <strong>#{r.id}</strong>
              <Badge variant={r.assignmentStatus === 'IN_TRANSIT' ? 'green' : 'blue'}>{r.assignmentStatus} · {r.status}</Badge>
            </div>
            <div className={styles.actions}>
              {r.assignmentStatus === 'ASSIGNED' && (
                <Button variant="primary" disabled={busy === `${r.id}-accept`}
                  onClick={() => guarded(r.id, 'accept', () => driverApi.accept(r.id), `/driver/routes/${r.id}/accept`, {})}>{t.accept}</Button>
              )}
              {r.assignmentStatus === 'ACCEPTED' && (
                <Button variant="primary" disabled={busy === `${r.id}-start`}
                  onClick={() => guarded(r.id, 'start', () => driverApi.start(r.id), `/driver/routes/${r.id}/start`, {})}>{t.start}</Button>
              )}
              <Button variant="danger" disabled={busy === `${r.id}-sos`} onClick={() => sendSos(r.id)}>{t.sos}</Button>
            </div>
            <div className={styles.slipRow}>
              <Select label={t.kind} id={`kind-${r.id}`} value={s.kind}
                onChange={(e) => setSlip({ ...slip, [r.id]: { ...s, kind: e.target.value } })}>
                <option value="FUEL">{t.fuel}</option>
                <option value="TOLL">{t.toll}</option>
              </Select>
              <Input label={t.amount} id={`amt-${r.id}`} type="number" min="1" step="0.01" value={s.amount}
                onChange={(e) => setSlip({ ...slip, [r.id]: { ...s, amount: e.target.value } })} />
              <Button size="sm" disabled={!s.amount || busy === `${r.id}-slip`}
                onClick={() => guarded(r.id, 'slip',
                  () => driverApi.slip(r.id, s.kind, Number(s.amount)),
                  `/driver/routes/${r.id}/slips`, { kind: s.kind, amount: Number(s.amount) })}>{t.slip}</Button>
            </div>
          </article>
        );
      })}
    </div>
  );
}
