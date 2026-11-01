import { useState, useEffect, useRef } from 'react';
import { replayApi, toursApi, breachesApi } from '../api/client.js';
import Button from '../components/atoms/Button.jsx';
import { useApp } from '../context/AppContext.jsx';
import { BREACH_TYPE_LABELS } from '../utils/helpers.js';
import styles from './Replay.module.css';

// Replays a tour's stored fixes as REPLAY-source inserts at 1-30x.
// Breach detection is eventual (background worker), so breaches are read
// separately here — never expected on the replay response itself.
export default function Replay() {
  const { addToast } = useApp();
  const [tourId, setTourId] = useState('');
  const [speed, setSpeed] = useState(5);
  const [starting, setStarting] = useState(false);
  const [result, setResult] = useState(null);
  const [vehicleId, setVehicleId] = useState(null);
  const [breaches, setBreaches] = useState([]);
  const pollRef = useRef(null);

  useEffect(() => () => clearInterval(pollRef.current), []);

  async function startReplay() {
    const id = Number(tourId);
    if (!id) { addToast('warning', 'Enter a tour id'); return; }
    setStarting(true);
    try {
      const r = await replayApi.start(id, speed);
      setResult(r);
      setBreaches([]);
      addToast('success', `Replaying ${r.points} fixes at ${r.speed}x`);
      // Resolve the tour's vehicle for the separate breach feed.
      try {
        const tour = await toursApi.get(id);
        setVehicleId(tour.vehicleId);
      } catch {
        setVehicleId(null);
      }
    } catch (err) {
      addToast('error', err.message || 'Failed to start replay');
    } finally {
      setStarting(false);
    }
  }

  // Separate breach feed: the worker evaluates REPLAY fixes in the
  // background, so poll until the operator leaves the page.
  useEffect(() => {
    clearInterval(pollRef.current);
    if (!vehicleId) return;
    const load = () => {
      breachesApi.list({ vehicleId })
        .then(b => setBreaches(b ?? []))
        .catch(() => {});
    };
    load();
    pollRef.current = setInterval(load, 15000);
    return () => clearInterval(pollRef.current);
  }, [vehicleId]);

  return (
    <div className={styles.page}>
      <div className={styles.controlPanel}>
        <div className={styles.panelHeader}>
          <h1 className={styles.title}>Replay</h1>
        </div>

        <div className={styles.section}>
          <p className={styles.sectionTitle}>Tour replay</p>
          <div>
            <label htmlFor="replay-tour" className={styles.customLabel}>Tour ID</label>
            <input
              id="replay-tour"
              className={styles.customSelect}
              value={tourId}
              onChange={e => setTourId(e.target.value)}
              placeholder="e.g. 12"
              inputMode="numeric"
            />
          </div>
          <div style={{ marginTop: '12px' }}>
            <label htmlFor="replay-speed" className={styles.customLabel}>Speed: {speed}x</label>
            <input
              id="replay-speed"
              type="range"
              min="1"
              max="30"
              value={speed}
              onChange={e => setSpeed(Number(e.target.value))}
              style={{ width: '100%' }}
            />
          </div>
          <div className={styles.controls} style={{ marginTop: '12px' }}>
            <Button variant="primary" onClick={startReplay} loading={starting}>
              ▶ Start replay
            </Button>
          </div>
          {result && (
            <p className={styles.emptyText}>
              Re-inserted {result.points} fixes for tour #{result.tourId} at {result.speed}x.
              Breaches arrive separately below.
            </p>
          )}
        </div>

        <div className={styles.section}>
          <p className={styles.sectionTitle}>Breaches (separate feed)</p>
          {breaches.length === 0 ? (
            <p className={styles.emptyText}>No breaches yet for this vehicle.</p>
          ) : (
            <div className={styles.breachLog}>
              {breaches.slice(0, 10).map(b => (
                <div key={b.id} className={styles.breachLogItem}>
                  <div className={styles.breachLogInfo}>
                    <span className={styles.breachLogReg}>Breach #{b.id}</span>
                    <span className={styles.breachLogType}>
                      {BREACH_TYPE_LABELS[b.breachType] ?? b.breachType} · {b.zoneName ?? ''}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
