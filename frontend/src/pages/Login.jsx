import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import Button from '../components/atoms/Button.jsx';
import Input from '../components/atoms/Input.jsx';
import { authApi, tokenStore } from '../api/client.js';
import { useApp } from '../context/AppContext.jsx';
import { DEMO_ACCOUNTS, DEMO_PASSWORD, tokenEmail, isDemoEmail } from '../utils/demoUser.js';
import styles from './Login.module.css';

export default function Login() {
  const navigate = useNavigate();
  const { addToast } = useApp();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [demoBusy, setDemoBusy] = useState(null);
  const current = tokenEmail(tokenStore.get());

  async function doLogin(targetEmail, targetPassword, landing) {
    try {
      const data = await authApi.login(targetEmail, targetPassword);
      tokenStore.set(data.accessToken);
      addToast('success', `Signed in as ${targetEmail}`);
      navigate(landing, { replace: true });
    } catch (e) {
      addToast('error', e.message || 'Login failed — is the backend running and migrated?');
    }
  }

  async function onSubmit(e) {
    e.preventDefault();
    if (!email || !password) {
      addToast('warning', 'Enter email and password');
      return;
    }
    setBusy(true);
    try {
      await doLogin(email, password, '/');
    } finally {
      setBusy(false);
    }
  }

  async function onDemo(account) {
    setDemoBusy(account.email);
    try {
      await doLogin(account.email, DEMO_PASSWORD, account.landing);
    } finally {
      setDemoBusy(null);
    }
  }

  function logout() {
    tokenStore.set(null);
    addToast('info', 'Signed out');
    navigate('/login', { replace: true });
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>ZonePilot sign in</h1>
        <p className={styles.subtitle}>
          {current ? (
            <>Signed in as <strong>{current}</strong>{isDemoEmail(current) ? ' (demo account)' : ''}. <button className={styles.linkBtn} onClick={logout}>Sign out</button></>
          ) : (
            <>Use your fleet account, or try the demo in one click.</>
          )}
        </p>
        <form onSubmit={onSubmit} className={styles.form}>
          <Input id="login-email" label="Email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="dispatcher@fleet.io" type="email" />
          <Input id="login-password" label="Password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" type="password" />
          <Button type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</Button>
        </form>
        <div className={styles.demoBox}>
          <h2 className={styles.demoTitle}>Try the demo (password: <code>demo1234</code>)</h2>
          {DEMO_ACCOUNTS.map(a => (
            <button
              key={a.email}
              className={styles.demoBtn}
              disabled={demoBusy !== null}
              onClick={() => onDemo(a)}
            >
              {demoBusy === a.email ? 'Signing in…' : `${a.label} →`}
              <span className={styles.demoEmail}>{a.email}</span>
            </button>
          ))}
        </div>
        <p className={styles.hint}>
          New database? The first registered user becomes OWNER. Demo data ships with migration V53 — see <code>docs/DEMO.md</code> for the full walkthrough.
        </p>
        <Link className={styles.back} to="/">← Back to dashboard</Link>
      </div>
    </div>
  );
}
