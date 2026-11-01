import { useState, useEffect, useRef } from 'react';
import { vehiclesApi, zonesApi, breachesApi, groupsApi, sosApi } from '../api/client.js';
import { connectTicks } from '../api/live.js';
import LiveMap from '../components/map/LiveMap.jsx';
import Badge from '../components/atoms/Badge.jsx';
import { useApp } from '../context/AppContext.jsx';
import { RESTRICTION_TYPE_COLORS, BREACH_TYPE_LABELS, formatRelative } from '../utils/helpers.js';
import styles from './Dashboard.module.css';

const LAYER_LABELS = { vehicles: '🚛 Vehicles', zones: '⬡ Zones', breaches: '⚠ Breaches', routes: '↗ Routes', labels: '🏷 Labels' };

export default function Dashboard() {
  const { state, openDrawer, selectVehicle, selectZone, toggleMapLayer, setWsOnline } = useApp();
  const [vehicles, setVehicles] = useState([]);
  const [groups, setGroups] = useState([]);
  const [selectedGroupId, setSelectedGroupId] = useState('all');
  const [positions, setPositions] = useState({});
  const [zones, setZones] = useState([]);
  const [breaches, setBreaches] = useState([]);
  const [sosAlerts, setSosAlerts] = useState([]);
  const [loading, setLoading] = useState(true);
  const pollRef = useRef(null);

  async function loadData() {
    const [v, z, b, g, s] = await Promise.allSettled([
      vehiclesApi.list({ isActive: true }),
      zonesApi.active(),
      breachesApi.list({ unacknowledged: true }),
      groupsApi.list(),
      sosApi.list(),
    ]);
    const sosList = s.status === 'fulfilled' && Array.isArray(s.value) ? s.value : [];
    setSosAlerts(sosList.filter(a => a.status === 'OPEN'));
    const vehicleList = v.status === 'fulfilled' ? v.value : [];
    setVehicles(vehicleList);
    setZones(z.status === 'fulfilled' ? z.value : []);
    setBreaches(b.status === 'fulfilled' ? b.value : []);
    if (g.status === 'fulfilled' && Array.isArray(g.value)) {
      setGroups(g.value);
    }

    // Load latest positions for all active vehicles
    const posResults = await Promise.allSettled(
      vehicleList.map(v => vehiclesApi.latestPosition(v.id).then(p => ({ id: v.id, pos: p })))
    );
    const posMap = {};
    posResults.forEach(r => {
      if (r.status === 'fulfilled') posMap[r.value.id] = r.value.pos;
    });
    setPositions(posMap);
    setLoading(false);
  }

  useEffect(() => {
    loadData();
    pollRef.current = setInterval(loadData, 10000);
    return () => clearInterval(pollRef.current);
  }, []);

  // Live pipe: fan-in subscribe to ALL managed groups (or the selected one);
  // ticks merge into the positions map by vehicleId. The 10s HTTP poll
  // above stays as the reconnect-gap fallback.
  useEffect(() => {
    const ids = selectedGroupId === 'all'
      ? groups.map(g => g.id ?? g.groupId).filter(id => id != null)
      : (() => { const n = Number(selectedGroupId); return Number.isFinite(n) ? [n] : []; })();
    if (ids.length === 0 || vehicles.length === 0) return undefined;
    const onTick = (tick) => {
      if (tick?.type !== 'tick' || tick.vehicleId == null) return;
      const lat = Number(tick.lat);
      const lng = Number(tick.lng);
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
      setPositions(prev => ({
        ...prev,
        [tick.vehicleId]: {
          ...prev[tick.vehicleId],
          latitude: tick.lat,
          longitude: tick.lng,
          speedKmh: tick.speedKmh,
          recordedAt: tick.recordedAt,
        },
      }));
    };
    const disconnects = ids.map(id => connectTicks(id, onTick, setWsOnline));
    return () => disconnects.forEach(d => d());
  }, [groups, selectedGroupId, vehicles, setWsOnline]);

  function handleVehicleClick(vehicle) {
    selectVehicle(vehicle.id);
    openDrawer('vehicle', vehicle.id);
  }

  function handleZoneClick(zone) {
    selectZone(zone.id);
    openDrawer('zone', zone.id);
  }

  function handleBreachClick(breach) {
    openDrawer('breach', breach.id, breach);
  }

  const activeVehicles = vehicles.length;
  const activeZones = zones.length;
  const unackedBreaches = breaches.length;
  const openSos = sosAlerts.length;
  const sosUnknown = sosAlerts.filter(a => a.unknown || a.stale).length;

  return (
    <div className={styles.page}>
      {/* Map fills the entire page */}
      <div className={styles.mapContainer}>
        <LiveMap
          vehicles={vehicles}
          positions={positions}
          zones={zones}
          breaches={breaches}
          routes={state.lastValidatedRoute ? [state.lastValidatedRoute] : []}
          onVehicleClick={handleVehicleClick}
          onZoneClick={handleZoneClick}
          onBreachClick={handleBreachClick}
          selectedVehicleId={state.selectedVehicleId}
          selectedZoneId={state.selectedZoneId}
          layers={state.mapLayers}
        />

        {/* Floating top controls */}
        <div className={styles.mapTopBar}>
          <div className={styles.layerToggles} role="group" aria-label="Map layers">
            {Object.entries(LAYER_LABELS).map(([key, label]) => (
              <button
                key={key}
                className={`${styles.layerBtn} ${state.mapLayers[key] ? styles.layerActive : ''}`}
                onClick={() => toggleMapLayer(key)}
                aria-pressed={state.mapLayers[key]}
                aria-label={`Toggle ${key} layer`}
              >
                {label}
              </button>
            ))}
          </div>
          {groups.length > 1 && (
            <select
              aria-label="Group filter"
              className={styles.groupSelect}
              value={selectedGroupId}
              onChange={e => setSelectedGroupId(e.target.value)}
            >
              <option value="all">All groups</option>
              {groups.map(g => {
                const id = g.id ?? g.groupId;
                return <option key={id} value={id}>{g.name ?? `Group ${id}`}</option>;
              })}
            </select>
          )}
        </div>

        {/* Floating metric strip — reference image style */}
        <div className={styles.metricStrip}>
          <div className={styles.metricCard}>
            <span className={styles.metricValue}>{activeVehicles}</span>
            <span className={styles.metricLabel}>Active Vehicles</span>
          </div>
          <div className={styles.metricDivider} aria-hidden="true" />
          <div className={styles.metricCard}>
            <span className={styles.metricValue}>{activeZones}</span>
            <span className={styles.metricLabel}>Active Zones</span>
          </div>
          <div className={styles.metricDivider} aria-hidden="true" />
          <div className={`${styles.metricCard} ${unackedBreaches > 0 ? styles.metricAlert : ''}`}>
            <span className={styles.metricValue}>{unackedBreaches}</span>
            <span className={styles.metricLabel}>Open Breaches</span>
          </div>
          <div className={styles.metricDivider} aria-hidden="true" />
          <div className={`${styles.metricCard} ${openSos > 0 ? styles.metricAlert : ''}`}>
            <span className={styles.metricValue}>{openSos}</span>
            <span className={styles.metricLabel}>Open SOS</span>
          </div>
        </div>

        {/* SOS inbox: unknown/stale location warns the dispatcher to call the driver */}
        {sosAlerts.length > 0 && (
          <div className={styles.sosFeed} aria-label="Open SOS alerts">
            <p className={styles.feedTitle}>🆘 Open SOS{sosUnknown > 0 ? ` — ${sosUnknown} no live fix` : ''}</p>
            {sosAlerts.slice(0, 4).map(a => (
              <div key={a.id} className={styles.feedItem} role="alert">
                <span className={styles.feedDot} style={{ background: '#dc2626' }} aria-hidden="true" />
                <div className={styles.feedInfo}>
                  <span className={styles.feedReg}>{a.driverName ?? `Alert #${a.id}`}</span>
                  <span className={styles.feedZone}>
                    {a.unknown
                      ? '⚠ location unknown — call driver'
                      : a.stale
                        ? `⚠ stale fix${a.accuracyM != null ? ` ±${Math.round(a.accuracyM)}m` : ''} — confirm on call`
                        : `${Number(a.lat).toFixed(4)}, ${Number(a.lng).toFixed(4)}${a.accuracyM != null ? ` ±${Math.round(a.accuracyM)}m` : ''}`}
                  </span>
                </div>
                <span className={styles.feedTime}>{formatRelative(a.createdAt)}</span>
              </div>
            ))}
          </div>
        )}

        {/* Live activity feed */}
        {breaches.length > 0 && (
          <div className={styles.activityFeed} aria-label="Recent breach activity">
            <p className={styles.feedTitle}>⚠ Recent Breaches</p>
            {breaches.slice(0, 4).map(b => (
              <button
                key={b.id}
                className={styles.feedItem}
                onClick={() => handleBreachClick(b)}
                aria-label={`Breach: ${b.zoneName} by ${b.registrationNumber}`}
              >
                <span className={styles.feedDot} style={{ background: '#dc2626' }} aria-hidden="true" />
                <div className={styles.feedInfo}>
                  <span className={styles.feedReg}>{b.registrationNumber}</span>
                  <span className={styles.feedZone}>{b.zoneName}</span>
                </div>
                <span className={styles.feedTime}>{formatRelative(b.breachTime)}</span>
              </button>
            ))}
          </div>
        )}

        {/* Zone legend */}
        {state.mapLayers.zones && (
          <div className={styles.legend} aria-label="Zone type legend">
            {Object.entries(RESTRICTION_TYPE_COLORS).map(([type, color]) => (
              <div key={type} className={styles.legendItem}>
                <span className={styles.legendDot} style={{ background: color }} aria-hidden="true" />
                <span>{type.replace(/_/g, ' ')}</span>
              </div>
            ))}
          </div>
        )}

        {loading && (
          <div className={styles.loadingOverlay} aria-live="polite" aria-label="Loading map data">
            <span className={styles.loadingDot} aria-hidden="true" />
            Loading…
          </div>
        )}
      </div>
    </div>
  );
}
