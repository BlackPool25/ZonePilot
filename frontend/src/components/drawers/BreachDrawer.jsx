import { useState, useEffect } from 'react';
import { breachesApi } from '../../api/client.js';
import { DEMO_BREACHES } from '../../data/demo.js';
import Badge from '../atoms/Badge.jsx';
import Button from '../atoms/Button.jsx';
import { Spinner } from '../atoms/Spinner.jsx';
import { useApp } from '../../context/AppContext.jsx';
import { BREACH_TYPE_LABELS, BREACH_TYPE_COLORS, formatDateTime } from '../../utils/helpers.js';
import styles from './BreachDrawer.module.css';

export default function BreachDrawer({ entityId, data: initialData }) {
  const { addToast } = useApp();
  const [breach, setBreach] = useState(initialData ?? null);
  const [loading, setLoading] = useState(!initialData);
  const [acking, setAcking] = useState(false);

  useEffect(() => {
    if (initialData) { setBreach(initialData); setLoading(false); return; }
    if (!entityId) return;
    setLoading(true);
    breachesApi.get(entityId)
      .then(setBreach)
      .catch(() => setBreach(DEMO_BREACHES.find(b => b.id === entityId) ?? null))
      .finally(() => setLoading(false));
  }, [entityId, initialData]);

  async function acknowledge() {
    if (!breach || breach.isAcknowledged) return;
    setAcking(true);
    try {
      const updated = await breachesApi.acknowledge(breach.id);
      setBreach(updated);
      addToast('success', 'Breach acknowledged');
    } catch (err) {
      if (err.status === 409) {
        setBreach(b => ({ ...b, isAcknowledged: true }));
        addToast('info', 'Already acknowledged');
      } else {
        addToast('error', err.message || 'Failed to acknowledge');
      }
    } finally {
      setAcking(false);
    }
  }

  if (loading) return <Spinner />;
  if (!breach) return <p style={{ color: 'var(--text-secondary)', fontSize: 13 }}>Breach not found.</p>;

  const typeColor = BREACH_TYPE_COLORS[breach.breachType];
  const typeLabel = BREACH_TYPE_LABELS[breach.breachType];

  return (
    <div>
      {/* Header */}
      <div className={styles.header}>
        <span className={styles.typeIcon} style={{ background: `${typeColor}20`, color: typeColor }} aria-hidden="true">⚠</span>
        <div>
          <h2 className={styles.title}>Zone Breach</h2>
          <Badge variant={breach.breachType === 'NO_ENTRY' ? 'red' : breach.breachType === 'TIME_WINDOW' ? 'amber' : 'purple'}>
            {typeLabel}
          </Badge>
        </div>
      </div>

      {/* Acknowledgement status */}
      <div className={`${styles.ackBanner} ${breach.isAcknowledged ? styles.acked : styles.unacked}`}>
        {breach.isAcknowledged ? (
          <span>✓ Acknowledged</span>
        ) : (
          <>
            <span>⚠ Requires acknowledgement</span>
            <Button size="sm" variant="primary" loading={acking} onClick={acknowledge}>
              Acknowledge
            </Button>
          </>
        )}
      </div>

      {/* Details */}
      <div className="drawer-section">
        <p className="drawer-section-title">Breach Details</p>
        <div className="drawer-row"><span className="drawer-row-label">Vehicle</span><span className="drawer-row-value" style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{breach.registrationNumber}</span></div>
        <div className="drawer-row"><span className="drawer-row-label">Zone</span><span className="drawer-row-value">{breach.zoneName}</span></div>
        <div className="drawer-row"><span className="drawer-row-label">Type</span><span className="drawer-row-value">{typeLabel}</span></div>
        <div className="drawer-row"><span className="drawer-row-label">Time</span><span className="drawer-row-value">{formatDateTime(breach.breachTime)}</span></div>
      </div>

      {/* Reroute */}
      <div className="drawer-section">
        <p className="drawer-section-title">Reroute</p>
        {breach.rerouteGeoJson ? (
          <p className={styles.rerouteAvailable}>✓ Alternative route computed</p>
        ) : (
          <p className={styles.rerouteUnavailable}>Road network not loaded — reroute unavailable</p>
        )}
      </div>
    </div>
  );
}
