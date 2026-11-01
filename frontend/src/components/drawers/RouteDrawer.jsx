import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { routesApi, simulationApi, toursApi, breachesApi } from '../../api/client.js';
import Badge from '../atoms/Badge.jsx';
import { Spinner } from '../atoms/Spinner.jsx';
import { useApp } from '../../context/AppContext.jsx';
import { formatDateTime, VEHICLE_CLASS_LABELS, VEHICLE_CLASS_ICONS } from '../../utils/helpers.js';
import styles from './RouteDrawer.module.css';

export default function RouteDrawer({ entityId, data: initialData }) {
  const { closeDrawer, addToast } = useApp();
  const navigate = useNavigate();
  const [route, setRoute] = useState(initialData ?? null);
  const [loading, setLoading] = useState(!initialData || (initialData && !initialData.vehicle));
  const [deleting, setDeleting] = useState(false);
  const [simulating, setSimulating] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [track, setTrack] = useState(null);
  const [tourBreaches, setTourBreaches] = useState([]);
  const [approving, setApproving] = useState(false);
  const [timelineError, setTimelineError] = useState(null);
  const tourId = route?.tour?.id ?? route?.tourId ?? null;
  const timelineVehicleId = route?.vehicle?.id ?? null;
  const trackStatus = track?.status ?? null;

  // Manager tour timeline: stops + PoD stamps from the track endpoint.
  // Breach rows arrive eventually via the background worker, so poll them
  // separately rather than expecting them on the tour payload. Polling stops
  // once the tour is COMPLETED — nothing more will arrive.
  useEffect(() => {
    if (!tourId) return;
    let live = true;
    const load = () => {
      toursApi.track(tourId)
        .then(t => { if (live) { setTrack(t); setTimelineError(null); } })
        .catch((err) => { if (live) setTimelineError(err.message || 'Failed to load tour timeline.'); });
      if (timelineVehicleId) {
        breachesApi.list({ vehicleId: timelineVehicleId })
          .then(b => { if (live) setTourBreaches(b ?? []); })
          .catch(() => {});
      }
    };
    load();
    if (trackStatus === 'COMPLETED') return () => { live = false; };
    const timer = setInterval(load, 15000);
    return () => { live = false; clearInterval(timer); };
  }, [tourId, timelineVehicleId, trackStatus]);

  async function handleApprove() {
    if (!tourId || approving) return;
    setApproving(true);
    try {
      const updated = await toursApi.approve(tourId);
      setTrack(t => (t ? { ...t, status: updated.status } : t));
      // Refresh the route payload too so the history badge follows the tour.
      const routeId = route?.id ?? route?.dispatchRouteId ?? entityId;
      if (routeId) {
        try {
          const fresh = await routesApi.get(routeId);
          setRoute(prev => ({ ...prev, ...fresh }));
        } catch {
          // track already updated; route refresh is best-effort
        }
      }
      addToast('success', `Tour #${tourId} approved — vehicle released`);
    } catch (err) {
      addToast('error', err.message || 'Failed to approve tour.');
    } finally {
      setApproving(false);
    }
  }

  async function handleSimulate() {
    const routeId = route?.id ?? route?.dispatchRouteId ?? entityId;
    if (!routeId) return;
    setSimulating(true);
    try {
      await simulationApi.startFromRoute({ dispatchRouteId: routeId });
      addToast('success', 'Simulation started — go to Simulation page');
      closeDrawer();
      navigate('/simulation');
    } catch (err) {
      addToast('error', err.message || 'Failed to start simulation.');
    } finally {
      setSimulating(false);
    }
  }

  async function handleDelete() {
    const routeId = route?.id ?? route?.dispatchRouteId ?? entityId;
    if (!routeId) return;
    setDeleting(true);
    try {
      await routesApi.delete(routeId);
      addToast('success', `Route #${routeId} deleted successfully.`);
      window.dispatchEvent(new CustomEvent('route-deleted', { detail: { id: routeId } }));
      closeDrawer();
    } catch (err) {
      addToast('error', err.message || 'Failed to delete route.');
    } finally {
      setDeleting(false);
    }
  }



  useEffect(() => {
    // If we have initialData and it has vehicle details, we can skip fetching
    if (initialData && initialData.vehicle) {
      setRoute(initialData);
      setLoading(false);
      return;
    }
    if (!entityId) return;
    setLoading(true);
    routesApi.get(entityId)
      .then(fetchedRoute => {
        setRoute(prev => ({
          ...prev,
          ...fetchedRoute,
          // Merge violations if present in initialData
          violations: prev?.violations ?? fetchedRoute.violations ?? []
        }));
      })
      .catch(() => setRoute(initialData ?? null))
      .finally(() => setLoading(false));
  }, [entityId, initialData]);

  if (loading) return <Spinner />;
  if (!route) return <p style={{ color: 'var(--text-secondary)', fontSize: 13 }}>Route not found.</p>;

  const vehicle = route.vehicle;
  const isCompliant = route.compliant || route.status === 'COMPLIANT';

  return (
    <div>
      <div className={styles.header}>
        <h2 className={styles.title}>Route #{route.id ?? route.dispatchRouteId ?? entityId}</h2>
        <Badge variant={isCompliant ? 'green' : 'red'}>
          {isCompliant ? 'Compliant' : 'Non-compliant'}
        </Badge>
      </div>

      {vehicle && (
        <div className="drawer-section">
          <p className="drawer-section-title">Vehicle Details</p>
          <div className="drawer-row">
            <span className="drawer-row-label">Registration</span>
            <span className="drawer-row-value">
              <span style={{ marginRight: 6 }} aria-hidden="true">{VEHICLE_CLASS_ICONS[vehicle.vehicleClass]}</span>
              <strong>{vehicle.registrationNumber}</strong>
            </span>
          </div>
          <div className="drawer-row">
            <span className="drawer-row-label">Class</span>
            <span className="drawer-row-value">{VEHICLE_CLASS_LABELS[vehicle.vehicleClass] ?? vehicle.vehicleClass}</span>
          </div>
          <div className="drawer-row">
            <span className="drawer-row-label">Owner</span>
            <span className="drawer-row-value">{vehicle.ownerName}</span>
          </div>
        </div>
      )}

      <div className="drawer-section">
        <p className="drawer-section-title">Route Info</p>
        <div className="drawer-row">
          <span className="drawer-row-label">Status</span>
          <span className="drawer-row-value">
            <Badge variant={isCompliant ? 'green' : 'red'} size="sm">
              {route.status ?? (isCompliant ? 'COMPLIANT' : 'NON_COMPLIANT')}
            </Badge>
          </span>
        </div>
        <div className="drawer-row">
          <span className="drawer-row-label">Created At</span>
          <span className="drawer-row-value">{formatDateTime(route.createdAt || route.validationTimestamp)}</span>
        </div>
        {route.waitUntil && (
          <div className="drawer-row">
            <span className="drawer-row-label">Wait until</span>
            <span className="drawer-row-value" style={{ color: 'var(--amber-500)', fontWeight: 'bold' }}>{formatDateTime(route.waitUntil)}</span>
          </div>
        )}
        {route.waitDurationSec && (
          <div className="drawer-row">
            <span className="drawer-row-label">Wait duration</span>
            <span className="drawer-row-value">{Math.round(route.waitDurationSec / 60)} min</span>
          </div>
        )}
        {route.alternativeRouteUnavailable && (
          <div className={styles.altUnavailable}>⚠ No compliant alternative route found</div>
        )}
      </div>

      {route.violations?.length > 0 && (
        <div className="drawer-section">
          <p className="drawer-section-title">Violations ({route.violations.length})</p>
          {route.violations.map((v, i) => (
            <div key={i} className={styles.violation}>
              <span className={styles.violationDot} aria-hidden="true" />
              <div>
                <span className={styles.violationZone}>{v.zoneName}</span>
                <span className={styles.violationType}>{v.breachType}</span>
              </div>
            </div>
          ))}
        </div>
      )}

      {tourId && (
        <div className="drawer-section">
          <p className="drawer-section-title">Tour #{tourId} timeline</p>
          {timelineError && !track && (
            <p style={{ color: 'var(--red-500)', fontSize: 13 }}>{timelineError}</p>
          )}
          {track ? (
            <>
              <div className="drawer-row">
                <span className="drawer-row-label">Status</span>
                <span className="drawer-row-value">
                  <Badge variant={track.status === 'COMPLETED' ? 'green' : track.status === 'PENDING_APPROVAL' ? 'amber' : 'blue'} size="sm">
                    {track.status}
                  </Badge>
                </span>
              </div>
              {(track.stops ?? []).map(s => (
                <div key={s.id} className={styles.violation}>
                  <span className={styles.violationDot} aria-hidden="true" />
                  <div>
                    <span className={styles.violationZone}>Stop #{s.seq} {s.address || ''}</span>
                    <span className={styles.violationType}>
                      {s.status}
                      {s.status === 'DELIVERED' && s.deliveredAt ? ` · ${formatDateTime(s.deliveredAt)}` : ''}
                      {s.status === 'DELIVERED' && s.podLat != null ? ` · PoD ${Number(s.podLat).toFixed(5)},${Number(s.podLng).toFixed(5)}` : ''}
                      {s.status === 'SKIPPED' && s.skipReason ? ` · ${s.skipReason}` : ''}
                    </span>
                  </div>
                </div>
              ))}
              {tourBreaches.length > 0 && (
                <div className="drawer-row">
                  <span className="drawer-row-label">Breaches</span>
                  <span className="drawer-row-value">
                    <Badge variant="red" size="sm">{tourBreaches.length} on this vehicle</Badge>
                  </span>
                </div>
              )}
              {track.status === 'PENDING_APPROVAL' && (
                <button
                  className={styles.simulateBtn}
                  onClick={handleApprove}
                  disabled={approving}
                  type="button"
                >
                  {approving ? 'Approving…' : '✓ Approve tour — release vehicle'}
                </button>
              )}
            </>
          ) : (
            <p style={{ color: 'var(--text-secondary)', fontSize: 13 }}>Loading tour timeline…</p>
          )}
        </div>
      )}

      {/* Actions */}
      <div className={styles.actions}>
        {!showConfirm ? (
          <>
            <button
              className={styles.simulateBtn}
              onClick={handleSimulate}
              disabled={simulating || deleting}
              type="button"
            >
              {simulating ? 'Starting...' : '▶ Simulate Route'}
            </button>
            <button
              className={styles.deleteBtn}
              onClick={() => setShowConfirm(true)}
              disabled={simulating || deleting}
            >
              🗑 Delete Route
            </button>
          </>
        ) : (
          <div className={styles.confirmBox}>
            <p className={styles.confirmText}>Are you sure you want to delete this route? This will permanently purge the route records and automatically clear any active route assignment for vehicles.</p>
            <div className={styles.confirmActions}>
              <button
                className={styles.cancelConfirmBtn}
                onClick={() => setShowConfirm(false)}
                disabled={deleting}
                type="button"
              >
                Cancel
              </button>
              <button
                className={styles.dangerConfirmBtn}
                onClick={handleDelete}
                disabled={deleting}
                type="button"
              >
                {deleting ? 'Deleting...' : 'Yes, Delete'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
