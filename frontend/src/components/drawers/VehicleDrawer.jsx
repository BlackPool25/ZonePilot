import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { vehiclesApi, routesApi, breachesApi } from '../../api/client.js';
import { DEMO_VEHICLES, DEMO_POSITIONS, DEMO_BREACHES } from '../../data/demo.js';
import Badge from '../atoms/Badge.jsx';
import Button from '../atoms/Button.jsx';
import { Spinner } from '../atoms/Spinner.jsx';
import { useApp } from '../../context/AppContext.jsx';
import {
  VEHICLE_CLASS_LABELS, VEHICLE_CLASS_ICONS,
  BREACH_TYPE_LABELS, BREACH_TYPE_COLORS,
  formatRelative, formatSpeed, formatDateTime,
} from '../../utils/helpers.js';
import styles from './VehicleDrawer.module.css';

export default function VehicleDrawer({ entityId }) {
  const { openDrawer, setMapCenter, addToast } = useApp();
  const navigate = useNavigate();
  const [vehicle, setVehicle] = useState(null);
  const [position, setPosition] = useState(null);
  const [breaches, setBreaches] = useState([]);
  const [routes, setRoutes] = useState([]);
  const [loading, setLoading] = useState(true);
  const [clearing, setClearing] = useState(false);
  const [showClearConfirm, setShowClearConfirm] = useState(false);

  async function handleClearRoute() {
    if (!vehicle) return;
    setClearing(true);
    try {
      await vehiclesApi.clearRoute(vehicle.id);
      addToast('success', 'Active route cleared successfully.');
      const updated = await vehiclesApi.get(vehicle.id);
      setVehicle(updated);
      const updatedHistory = await routesApi.vehicleHistory(vehicle.id);
      setRoutes(updatedHistory);
      setShowClearConfirm(false);
    } catch (err) {
      addToast('danger', err.message || 'Failed to clear active route.');
    } finally {
      setClearing(false);
    }
  }


  useEffect(() => {
    if (!entityId) return;
    setLoading(true);
    Promise.allSettled([
      vehiclesApi.get(entityId),
      vehiclesApi.latestPosition(entityId),
      breachesApi.list({ vehicleId: entityId }),
      routesApi.vehicleHistory(entityId),
    ]).then(([v, p, b, r]) => {
      setVehicle(v.status === 'fulfilled' ? v.value : DEMO_VEHICLES.find(x => x.id === entityId));
      setPosition(p.status === 'fulfilled' ? p.value : DEMO_POSITIONS[entityId] ?? null);
      setBreaches(b.status === 'fulfilled' ? b.value : DEMO_BREACHES.filter(x => x.vehicleId === entityId));
      setRoutes(r.status === 'fulfilled' ? r.value : []);
      setLoading(false);
    });
  }, [entityId]);

  if (loading) return <Spinner />;
  if (!vehicle) return <p style={{ color: 'var(--text-secondary)', fontSize: 13 }}>Vehicle not found.</p>;


  const unacked = breaches.filter(b => !b.isAcknowledged);
  const statusVariant = vehicle.isActive ? 'green' : 'gray';

  function jumpToMap() {
    if (position) setMapCenter([position.latitude, position.longitude], 15);
    navigate('/');
  }

  return (
    <div>
      {/* Header */}
      <div className={styles.header}>
        <span className={styles.classIcon} aria-hidden="true">{VEHICLE_CLASS_ICONS[vehicle.vehicleClass]}</span>
        <div className={styles.headerInfo}>
          <h2 className={styles.regNumber}>{vehicle.registrationNumber}</h2>
          <div className={styles.headerMeta}>
            <Badge variant={statusVariant} dot>{vehicle.isActive ? 'Active' : 'Inactive'}</Badge>
            <Badge variant="gray">{VEHICLE_CLASS_LABELS[vehicle.vehicleClass]}</Badge>
          </div>
        </div>
      </div>

      {/* Quick actions */}
      <div className={styles.actions}>
        <Button size="sm" icon="🗺" onClick={jumpToMap}>View on Map</Button>
        <Button size="sm" icon="📊" onClick={() => navigate(`/reports?vehicleId=${entityId}`)}>Reports</Button>
        <Button size="sm" icon="↗" onClick={() => navigate(`/routes?vehicleId=${entityId}`)}>Routes</Button>
      </div>

      {/* Details */}
      <div className="drawer-section">
        <p className="drawer-section-title">Vehicle Details</p>
        <div className="drawer-row"><span className="drawer-row-label">Owner</span><span className="drawer-row-value">{vehicle.ownerName}</span></div>
        <div className="drawer-row"><span className="drawer-row-label">Depot</span><span className="drawer-row-value">{vehicle.depotName}</span></div>
        <div className="drawer-row"><span className="drawer-row-label">Class</span><span className="drawer-row-value">{VEHICLE_CLASS_LABELS[vehicle.vehicleClass]}</span></div>
      </div>

      {/* Active Route */}
      {vehicle.activeDispatchRouteId && (
        <div className="drawer-section" style={{ background: 'var(--brand-50)', border: '1px dashed var(--brand-300)', padding: 12, borderRadius: 8, marginBottom: 16 }}>
          <p className="drawer-section-title" style={{ color: 'var(--brand-800)', margin: 0, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span>Active Dispatch Route</span>
            <span style={{ fontSize: 10, background: 'var(--brand-500)', color: 'white', padding: '2px 6px', borderRadius: 4, fontWeight: 700 }}>DISPATCHED</span>
          </p>
          {!showClearConfirm ? (
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 10 }}>
              <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--brand-900)' }}>Route #{vehicle.activeDispatchRouteId}</span>
              <Button
                size="xs"
                variant="secondary"
                onClick={() => setShowClearConfirm(true)}
                disabled={clearing}
                style={{
                  background: 'var(--red-50)',
                  color: 'var(--red-600)',
                  border: '1px solid var(--red-200)',
                  fontSize: 11,
                  padding: '3px 8px',
                  fontWeight: 600
                }}
              >
                Clear Route
              </Button>
            </div>
          ) : (
            <div className={styles.confirmBox}>
              <p className={styles.confirmText}>Are you sure you want to unassign and clear this active dispatch route?</p>
              <div className={styles.confirmActions}>
                <button
                  className={styles.cancelConfirmBtn}
                  onClick={() => setShowClearConfirm(false)}
                  disabled={clearing}
                  type="button"
                >
                  Cancel
                </button>
                <button
                  className={styles.dangerConfirmBtn}
                  onClick={handleClearRoute}
                  disabled={clearing}
                  type="button"
                >
                  {clearing ? 'Clearing...' : 'Yes, Clear'}
                </button>
              </div>
            </div>
          )}
        </div>
      )}


      {/* Live position */}

      {position && (
        <div className="drawer-section">
          <p className="drawer-section-title">Live Position</p>
          <div className="drawer-row"><span className="drawer-row-label">Speed</span><span className="drawer-row-value">{formatSpeed(position.speedKmh)}</span></div>
          <div className="drawer-row"><span className="drawer-row-label">Heading</span><span className="drawer-row-value">{position.headingDeg ?? '—'}°</span></div>
          <div className="drawer-row"><span className="drawer-row-label">Last seen</span><span className="drawer-row-value">{formatRelative(position.recordedAt)}</span></div>
          <div className="drawer-row"><span className="drawer-row-label">Source</span><span className="drawer-row-value">{position.source}</span></div>
        </div>
      )}

      {/* Recent breaches */}
      <div className="drawer-section">
        <p className="drawer-section-title">
          Recent Breaches
          {unacked.length > 0 && <span className={styles.unackedBadge}>{unacked.length} unacknowledged</span>}
        </p>
        {breaches.length === 0 ? (
          <p className={styles.emptyText}>No breaches recorded.</p>
        ) : (
          <div className={styles.breachList}>
            {breaches.slice(0, 5).map(b => (
              <button
                key={b.id}
                className={styles.breachItem}
                onClick={() => openDrawer('breach', b.id, b)}
                aria-label={`Breach: ${b.zoneName}`}
              >
                <span className={styles.breachDot} style={{ background: BREACH_TYPE_COLORS[b.breachType] }} aria-hidden="true" />
                <div className={styles.breachInfo}>
                  <span className={styles.breachZone}>{b.zoneName}</span>
                  <span className={styles.breachMeta}>{BREACH_TYPE_LABELS[b.breachType]} · {formatRelative(b.breachTime)}</span>
                </div>
                {!b.isAcknowledged && <span className={styles.unackedDot} aria-label="Unacknowledged" />}
              </button>
            ))}
          </div>
        )}
        {breaches.length > 0 && (
          <Button size="sm" variant="ghost" onClick={() => navigate(`/breaches?vehicleId=${entityId}`)}>
            View all breaches →
          </Button>
        )}
      </div>

      {/* Route history */}
      {routes.length > 0 && (
        <div className="drawer-section">
          <p className="drawer-section-title">Recent Routes</p>
          {routes.slice(0, 3).map(r => (
            <button
              key={r.id}
              className={styles.routeItem}
              onClick={() => openDrawer('route', r.id, r)}
            >
              <span className={styles.routeStatus} style={{ color: r.compliant ? 'var(--green-500)' : 'var(--red-500)' }}>
                {r.compliant ? '✓' : '✕'}
              </span>
              <span className={styles.routeLabel}>Route #{r.id}</span>
              <span className={styles.routeDate}>{formatDateTime(r.createdAt)}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
