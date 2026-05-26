import { useState, useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { routesApi, vehiclesApi, zonesApi, simulationApi } from '../api/client.js';
import { DEMO_VEHICLES, DEMO_ZONES } from '../data/demo.js';
import LiveMap from '../components/map/LiveMap.jsx';
import Badge from '../components/atoms/Badge.jsx';
import Button from '../components/atoms/Button.jsx';
import Input, { Select } from '../components/atoms/Input.jsx';
import { LoadingOverlay, EmptyState } from '../components/atoms/Spinner.jsx';
import { useApp } from '../context/AppContext.jsx';
import { formatDateTime, wktToLatLngs } from '../utils/helpers.js';
import styles from './Routes.module.css';

// Bangalore landmark presets for easy demo
const PRESETS = [
  { label: 'HSR → Indiranagar', originLat: 12.9352, originLng: 77.6245, destLat: 12.9784, destLng: 77.6408 },
  { label: 'Yeshwantpur → MG Road', originLat: 13.0218, originLng: 77.5560, destLat: 12.9757, destLng: 77.6011 },
  { label: 'Electronic City → Koramangala', originLat: 12.8456, originLng: 77.6603, destLat: 12.9352, destLng: 77.6245 },
];

export default function Routes() {
  const { openDrawer, addToast, setMapCenter, setLastValidatedRoute } = useApp();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const vehicleIdParam = searchParams.get('vehicleId');

  const [vehicles, setVehicles] = useState([]);
  const [zones, setZones] = useState([]);
  const [history, setHistory] = useState([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [validating, setValidating] = useState(false);
  const [simulating, setSimulating] = useState(false);
  const [result, setResult] = useState(null);
  const [selectedRoute, setSelectedRoute] = useState(null);

  const [form, setForm] = useState({
    vehicleId: vehicleIdParam ?? '',
    originLat: '', originLng: '',
    destLat: '', destLng: '',
  });
  const [errors, setErrors] = useState({});
  const [pinMode, setPinMode] = useState(null); // 'origin' | 'dest' | null

  const originPin = form.originLat && form.originLng ? { lat: Number(form.originLat), lng: Number(form.originLng) } : null;
  const destPin = form.destLat && form.destLng ? { lat: Number(form.destLat), lng: Number(form.destLng) } : null;

  const handleMapClick = (latlng) => {
    if (pinMode === 'origin') {
      setForm(f => ({ ...f, originLat: latlng.lat.toFixed(6), originLng: latlng.lng.toFixed(6) }));
      setPinMode(null);
    } else if (pinMode === 'dest') {
      setForm(f => ({ ...f, destLat: latlng.lat.toFixed(6), destLng: latlng.lng.toFixed(6) }));
      setPinMode(null);
    }
  };


  useEffect(() => {
    vehiclesApi.list({ isActive: true })
      .then(setVehicles)
      .catch(() => setVehicles(DEMO_VEHICLES.filter(v => v.isActive)));
    zonesApi.list()
      .then(setZones)
      .catch(() => setZones(DEMO_ZONES));
  }, []);

  const loadHistory = () => {
    if (!form.vehicleId) {
      setHistory([]);
      return;
    }
    setHistoryLoading(true);
    routesApi.vehicleHistory(form.vehicleId)
      .then(setHistory)
      .catch(() => setHistory([]))
      .finally(() => setHistoryLoading(false));
  };

  useEffect(() => {
    loadHistory();
  }, [form.vehicleId]);

  useEffect(() => {
    const handleRouteDeleted = () => {
      loadHistory();
      setSelectedRoute(null);
    };
    window.addEventListener('route-deleted', handleRouteDeleted);
    return () => window.removeEventListener('route-deleted', handleRouteDeleted);
  }, [form.vehicleId]);


  // Auto-center map on selected route
  useEffect(() => {
    if (!selectedRoute) return;
    const geom = selectedRoute.routeGeoJson ?? selectedRoute.alternativeRouteGeoJson;
    if (!geom) return;
    try {
      const latlngs = wktToLatLngs(geom);
      if (latlngs && latlngs.length > 0) {
        const midIndex = Math.floor(latlngs.length / 2);
        const [lat, lng] = latlngs[midIndex];
        setMapCenter([lat, lng], 12);
      }
    } catch (err) {
      console.error('Failed to parse route for centering', err);
    }
  }, [selectedRoute, setMapCenter]);

  function applyPreset(preset) {
    setForm(f => ({ ...f, ...preset }));
    setErrors({});
    setResult(null);
  }

  async function simulateRoute() {
    if (!result?.dispatchRouteId) return;
    setSimulating(true);
    try {
      const body = { dispatchRouteId: result.dispatchRouteId };
      if (form.originLat && form.originLng) {
        body.startLat = Number(form.originLat);
        body.startLng = Number(form.originLng);
      }
      await simulationApi.startFromRoute(body);
      addToast('success', 'Simulation started — go to Simulation page');
      navigate('/simulation');
    } catch (err) {
      addToast('error', err.message || 'Failed to start simulation');
    } finally {
      setSimulating(false);
    }
  }

  function setField(field, value) {
    setForm(f => ({ ...f, [field]: value }));
    setErrors(e => ({ ...e, [field]: undefined }));
  }

  async function validate(e) {
    e.preventDefault();
    const errs = {};
    if (!form.vehicleId) errs.vehicleId = 'Required';
    if (!form.originLat || !form.originLng) errs.origin = 'Required';
    if (!form.destLat || !form.destLng) errs.dest = 'Required';
    if (Object.keys(errs).length) { setErrors(errs); return; }

    setValidating(true);
    setResult(null);
    try {
      const data = await routesApi.validate({
        vehicleId: Number(form.vehicleId),
        originLat: Number(form.originLat),
        originLng: Number(form.originLng),
        destLat: Number(form.destLat),
        destLng: Number(form.destLng),
      });
      setResult(data);
      setSelectedRoute(data);
      setLastValidatedRoute(data); // persist to Dashboard
      if (data.compliant) addToast('success', 'Route is compliant');
      else addToast('warning', `${data.violations?.length ?? 0} violation(s) found`);
      
      // Auto-refresh history list
      routesApi.vehicleHistory(form.vehicleId)
        .then(setHistory)
        .catch(() => {});
    } catch (err) {
      if (err.code === 'ROAD_NETWORK_UNAVAILABLE') {
        setResult({ _error: 'Road network not loaded. Route validation requires the Bangalore OSM road network.' });
      } else {
        setResult({ _error: err.message });
      }
    } finally {
      setValidating(false);
    }
  }

  return (
    <div className={styles.page}>
      {/* Left: form */}
      <div className={styles.formPanel}>
        <h1 className={styles.title}>Route Validation</h1>
        <p className={styles.subtitle}>Validate a route before dispatch to check zone compliance.</p>

        {/* Presets */}
        <div className={styles.presets}>
          <p className={styles.presetsLabel}>Quick presets:</p>
          <div className={styles.presetBtns}>
            {PRESETS.map(p => (
              <button key={p.label} className={styles.presetBtn} onClick={() => applyPreset(p)} type="button">
                {p.label}
              </button>
            ))}
          </div>
        </div>

        <form onSubmit={validate} noValidate className={styles.form}>
          <Select id="route-vehicle" label="Vehicle" value={form.vehicleId} onChange={e => setField('vehicleId', e.target.value)} error={errors.vehicleId} required>
            <option value="">Select vehicle…</option>
            {vehicles.map(v => <option key={v.id} value={v.id}>{v.registrationNumber} ({v.vehicleClass})</option>)}
          </Select>

          <fieldset className={styles.coordGroup}>
            <legend className={styles.coordLegend}>
              <span>Origin</span>
              <button
                type="button"
                className={`${styles.pinBtn} ${pinMode === 'origin' ? styles.pinActive : ''}`}
                onClick={() => setPinMode(pinMode === 'origin' ? null : 'origin')}
              >
                {pinMode === 'origin' ? '📍 Clicking Map...' : '📍 Drop Pin'}
              </button>
            </legend>
            <div className={styles.coordRow}>
              <Input id="origin-lat" label="Latitude" type="number" step="0.0001" placeholder="12.9716" value={form.originLat} onChange={e => setField('originLat', e.target.value)} error={errors.origin} />
              <Input id="origin-lng" label="Longitude" type="number" step="0.0001" placeholder="77.5946" value={form.originLng} onChange={e => setField('originLng', e.target.value)} />
            </div>
          </fieldset>

          <fieldset className={styles.coordGroup}>
            <legend className={styles.coordLegend}>
              <span>Destination</span>
              <button
                type="button"
                className={`${styles.pinBtn} ${pinMode === 'dest' ? styles.pinActive : ''}`}
                onClick={() => setPinMode(pinMode === 'dest' ? null : 'dest')}
              >
                {pinMode === 'dest' ? '📍 Clicking Map...' : '📍 Drop Pin'}
              </button>
            </legend>
            <div className={styles.coordRow}>
              <Input id="dest-lat" label="Latitude" type="number" step="0.0001" placeholder="12.9784" value={form.destLat} onChange={e => setField('destLat', e.target.value)} error={errors.dest} />
              <Input id="dest-lng" label="Longitude" type="number" step="0.0001" placeholder="77.6408" value={form.destLng} onChange={e => setField('destLng', e.target.value)} />
            </div>
          </fieldset>


          <Button variant="primary" type="submit" loading={validating} size="lg" className={styles.submitBtn}>
            Validate Route
          </Button>
        </form>

        {/* Result */}
        {result && (
          <div className={`${styles.result} ${result._error ? styles.resultError : result.compliant ? styles.resultOk : styles.resultWarn}`}>
            {result._error ? (
              <p className={styles.resultMsg}>⚠ {result._error}</p>
            ) : (
              <>
                <div className={styles.resultHeader}>
                  <span className={styles.resultIcon}>{result.compliant ? '✓' : '✕'}</span>
                  <div>
                    <p className={styles.resultTitle}>{result.compliant ? 'Route is compliant' : 'Route has violations'}</p>
                    {result.waitUntil && <p className={styles.resultSub}>Wait until {formatDateTime(result.waitUntil)} ({Math.round(result.waitDurationSec / 60)} min)</p>}
                    {result.alternativeRouteGeoJson && !result.compliant && (
                      <p className={styles.resultSub} style={{ color: 'var(--green-600)', fontWeight: 600 }}>
                        ✓ Compliant alternative route found (shown in green on map)
                      </p>
                    )}
                    {result.alternativeRouteUnavailable && <p className={styles.resultSub}>No compliant alternative found</p>}
                  </div>
                  {result.dispatchRouteId && (
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <Button size="sm" variant="secondary" onClick={() => { setSelectedRoute(result); openDrawer('route', result.dispatchRouteId, result); }}>
                        View Route
                      </Button>
                      <Button size="sm" variant="primary" loading={simulating} onClick={simulateRoute}>
                        ▶ Simulate
                      </Button>
                    </div>
                  )}
                </div>
                {result.violations?.length > 0 && (
                  <ul className={styles.violationList}>
                    {result.violations.map((v, i) => (
                      <li key={i} className={styles.violationItem}>
                        <span className={styles.violationDot} aria-hidden="true" />
                        <span>{v.zoneName}</span>
                        <Badge variant="red" size="sm">{v.breachType}</Badge>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </div>
        )}
      </div>

      {/* Middle: history */}
      <div className={styles.historyPanel}>
        <h2 className={styles.historyTitle}>Route History</h2>
        {!form.vehicleId ? (
          <EmptyState icon="↗" title="Select a vehicle" description="Choose a vehicle to see its route history." />
        ) : historyLoading ? (
          <LoadingOverlay label="Loading history…" />
        ) : history.length === 0 ? (
          <EmptyState icon="📋" title="No routes" description="No dispatch routes for this vehicle." />
        ) : (
          <div className={styles.historyList}>
            {history.map(r => (
              <button
                key={r.id}
                className={styles.historyItem}
                onClick={() => { setSelectedRoute(r); openDrawer('route', r.id, r); }}
                aria-label={`Route #${r.id}`}
              >
                <span className={`${styles.historyStatus} ${r.compliant ? styles.ok : styles.warn}`}>
                  {r.compliant ? '✓' : '✕'}
                </span>
                <div className={styles.historyInfo}>
                  <span className={styles.historyId}>Route #{r.id}</span>
                  <span className={styles.historyDate}>{formatDateTime(r.createdAt)}</span>
                </div>
                <Badge variant={r.compliant ? 'green' : 'red'} size="sm">{r.compliant ? 'Compliant' : 'Violations'}</Badge>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Right: Map */}
      <div className={styles.mapArea}>
        <LiveMap
          vehicles={vehicles}
          positions={{}}
          zones={zones}
          breaches={[]}
          routes={selectedRoute ? [selectedRoute] : []}
          layers={{ vehicles: false, zones: true, breaches: false, routes: true, labels: true }}
          onMapClick={handleMapClick}
          originPin={originPin}
          destPin={destPin}
        />

      </div>
    </div>
  );
}
