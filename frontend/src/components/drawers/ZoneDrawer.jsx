import { useState, useEffect } from 'react';
import { zonesApi, reportsApi } from '../../api/client.js';
import { DEMO_ZONES } from '../../data/demo.js';
import Badge from '../atoms/Badge.jsx';
import { Spinner } from '../atoms/Spinner.jsx';
import { useApp } from '../../context/AppContext.jsx';
import {
  RESTRICTION_TYPE_LABELS, RESTRICTION_TYPE_COLORS,
  VEHICLE_CLASS_LABELS, decodeDays, formatRelative,
} from '../../utils/helpers.js';
import styles from './ZoneDrawer.module.css';

export default function ZoneDrawer({ entityId }) {
  const { closeDrawer, addToast } = useApp();
  const [zone, setZone] = useState(null);
  const [violations, setViolations] = useState(null);
  const [loading, setLoading] = useState(true);
  const [deleting, setDeleting] = useState(false);

  async function handleDelete() {
    if (!zone) return;
    if (!window.confirm(`Are you sure you want to delete the zone "${zone.name}"? This will permanently remove all associated rules and logged breaches.`)) {
      return;
    }
    setDeleting(true);
    try {
      await zonesApi.delete(zone.id);
      addToast('success', `Zone "${zone.name}" deleted successfully.`);
      window.dispatchEvent(new CustomEvent('zone-deleted', { detail: { id: zone.id } }));
      closeDrawer();
    } catch (err) {
      addToast('danger', err.message || 'Failed to delete zone.');
    } finally {
      setDeleting(false);
    }
  }


  useEffect(() => {
    if (!entityId) return;
    setLoading(true);
    Promise.allSettled([
      zonesApi.get(entityId),
      reportsApi.zoneViolations(entityId),
    ]).then(([z, v]) => {
      setZone(z.status === 'fulfilled' ? z.value : DEMO_ZONES.find(x => x.id === entityId));
      setViolations(v.status === 'fulfilled' ? v.value : null);
      setLoading(false);
    });
  }, [entityId]);

  if (loading) return <Spinner />;
  if (!zone) return <p style={{ color: 'var(--text-secondary)', fontSize: 13 }}>Zone not found.</p>;

  const typeColor = RESTRICTION_TYPE_COLORS[zone.restrictionType];
  const typeLabel = RESTRICTION_TYPE_LABELS[zone.restrictionType];

  return (
    <div>
      {/* Header */}
      <div className={styles.header}>
        <span className={styles.typeIndicator} style={{ background: typeColor }} aria-hidden="true" />
        <div>
          <h2 className={styles.zoneName}>{zone.name}</h2>
          <Badge variant={zone.restrictionType === 'NO_ENTRY' ? 'red' : zone.restrictionType === 'TIME_RESTRICTED' ? 'amber' : 'purple'}>
            {typeLabel}
          </Badge>
        </div>
      </div>

      {zone.description && <p className={styles.description}>{zone.description}</p>}

      {/* Status */}
      <div className="drawer-section">
        <p className="drawer-section-title">Status</p>
        <div className="drawer-row">
          <span className="drawer-row-label">Zone active</span>
          <span className="drawer-row-value">
            <Badge variant={zone.isActive ? 'green' : 'gray'} dot>{zone.isActive ? 'Active' : 'Inactive'}</Badge>
          </span>
        </div>
        <div className="drawer-row">
          <span className="drawer-row-label">Restriction type</span>
          <span className="drawer-row-value">{typeLabel}</span>
        </div>
        <div className="drawer-row">
          <span className="drawer-row-label">Rules</span>
          <span className="drawer-row-value">{zone.rules?.length ?? 0} rule{zone.rules?.length !== 1 ? 's' : ''}</span>
        </div>
      </div>

      {/* Rules */}
      {zone.rules?.length > 0 && (
        <div className="drawer-section">
          <p className="drawer-section-title">Restriction Rules</p>
          {zone.rules.map(rule => (
            <div key={rule.id} className={styles.ruleCard}>
              <div className={styles.ruleHeader}>
                <Badge variant={rule.isActive ? 'green' : 'gray'} size="sm">{rule.isActive ? 'Active' : 'Inactive'}</Badge>
                <span className={styles.ruleClass}>{rule.applicableVehicleClass ? VEHICLE_CLASS_LABELS[rule.applicableVehicleClass] : 'All classes'}</span>
              </div>
              <div className={styles.ruleDetail}>
                <span>🕐 {rule.restrictionStartTime} – {rule.restrictionEndTime}</span>
                <span>📅 {decodeDays(rule.daysOfWeekBitmask).join(', ')}</span>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Violation stats */}
      {violations && violations.length > 0 && (
        <div className="drawer-section">
          <p className="drawer-section-title">Violation Statistics</p>
          {violations.map(v => (
            <div key={v.zoneId}>
              <div className="drawer-row"><span className="drawer-row-label">Total violations</span><span className="drawer-row-value">{v.totalViolations}</span></div>
              <div className="drawer-row"><span className="drawer-row-label">HCV</span><span className="drawer-row-value">{v.hcvViolations}</span></div>
              <div className="drawer-row"><span className="drawer-row-label">LCV</span><span className="drawer-row-value">{v.lcvViolations}</span></div>
              <div className="drawer-row"><span className="drawer-row-label">2-Wheeler</span><span className="drawer-row-value">{v.twoWheelerViolations}</span></div>
              <div className="drawer-row"><span className="drawer-row-label">Last violation</span><span className="drawer-row-value">{formatRelative(v.lastViolationTime)}</span></div>
            </div>
          ))}
        </div>
      )}

      {/* Actions */}
      <div className={styles.actions}>
        <button
          className={styles.deleteBtn}
          onClick={handleDelete}
          disabled={deleting}
        >
          {deleting ? 'Deleting...' : '🗑 Delete Zone'}
        </button>
      </div>
    </div>
  );
}

