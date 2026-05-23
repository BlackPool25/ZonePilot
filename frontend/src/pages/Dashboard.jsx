import { useState, useEffect, useRef } from 'react';
import { vehiclesApi, zonesApi, breachesApi } from '../api/client.js';
import { DEMO_VEHICLES, DEMO_ZONES, DEMO_POSITIONS, DEMO_BREACHES } from '../data/demo.js';
import LiveMap from '../components/map/LiveMap.jsx';
import Badge from '../components/atoms/Badge.jsx';
import { useApp } from '../context/AppContext.jsx';
import { RESTRICTION_TYPE_COLORS, BREACH_TYPE_LABELS, formatRelative } from '../utils/helpers.js';
import styles from './Dashboard.module.css';

const LAYER_LABELS = { vehicles: '🚛 Vehicles', zones: '⬡ Zones', breaches: '⚠ Breaches', routes: '↗ Routes', labels: '🏷 Labels' };

export default function Dashboard() {
  const { state, openDrawer, selectVehicle, selectZone, toggleMapLayer } = useApp();
  const [vehicles, setVehicles] = useState([]);
  const [positions, setPositions] = useState({});
  const [zones, setZones] = useState([]);
  const [breaches, setBreaches] = useState([]);
  const [loading, setLoading] = useState(true);
  const pollRef = useRef(null);

  async function loadData() {
    const [v, z, b] = await Promise.allSettled([
      vehiclesApi.list({ isActive: true }),
      zonesApi.active(),
      breachesApi.list({ unacknowledged: true }),
    ]);
    const vehicleList = v.status === 'fulfilled' ? v.value : DEMO_VEHICLES.filter(x => x.isActive);
    setVehicles(vehicleList);
    setZones(z.status === 'fulfilled' ? z.value : DEMO_ZONES);
    setBreaches(b.status === 'fulfilled' ? b.value : DEMO_BREACHES.filter(x => !x.isAcknowledged));

    // Load latest positions for all active vehicles
    const posResults = await Promise.allSettled(
      vehicleList.map(v => vehiclesApi.latestPosition(v.id).then(p => ({ id: v.id, pos: p })))
    );
    const posMap = {};
    posResults.forEach(r => {
      if (r.status === 'fulfilled') posMap[r.value.id] = r.value.pos;
    });
    // Fallback to demo positions
    vehicleList.forEach(v => { if (!posMap[v.id] && DEMO_POSITIONS[v.id]) posMap[v.id] = DEMO_POSITIONS[v.id]; });
    setPositions(posMap);
    setLoading(false);
  }

  useEffect(() => {
    loadData();
    pollRef.current = setInterval(loadData, 10000);
    return () => clearInterval(pollRef.current);
  }, []);

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

  return (
    <div className={styles.page}>
      {/* Map fills the entire page */}
      <div className={styles.mapContainer}>
        <LiveMap
          vehicles={vehicles}
          positions={positions}
          zones={zones}
          breaches={breaches}
          onVehicleClick={handleVehicleClick}
          onZoneClick={handleZoneClick}
          onBreachClick={handleBreachClick}
          selectedVehicleId={state.selectedVehicleId}
          selectedZoneId={state.selectedZoneId}
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
        </div>

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
