import { useState, useEffect, useRef, useCallback } from 'react';
import { simulationApi, vehiclesApi, zonesApi } from '../api/client.js';
import { DEMO_SIMULATION_STATE, DEMO_VEHICLES, DEMO_ZONES } from '../data/demo.js';
import LiveMap from '../components/map/LiveMap.jsx';
import Badge from '../components/atoms/Badge.jsx';
import Button from '../components/atoms/Button.jsx';
import { useApp } from '../context/AppContext.jsx';
import { VEHICLE_CLASS_ICONS, BREACH_TYPE_LABELS, formatRelative } from '../utils/helpers.js';
import styles from './Simulation.module.css';

const SCENARIOS = [
  { id: 'A', label: 'Scenario A', description: 'HSR → Indiranagar (LCV, compliant path)', vehicleId: 4, reg: 'KA01-LCV-0004' },
  { id: 'B', label: 'Scenario B', description: 'Yeshwantpur → MG Road (HCV, time window breach)', vehicleId: 7, reg: 'KA01-HCV-0007' },
  { id: 'C', label: 'Scenario C', description: 'Electronic City → Koramangala (LCV, no-entry breach)', vehicleId: 5, reg: 'KA01-LCV-0005' },
];

export default function Simulation() {
  const { addToast } = useApp();
  const [selectedScenarios, setSelectedScenarios] = useState(['A', 'B', 'C']);
  const [simState, setSimState] = useState([]);
  const [zones, setZones] = useState([]);
  const [vehicles, setVehicles] = useState([]);
  const [positions, setPositions] = useState({});
  const [tickLog, setTickLog] = useState([]);
  const [running, setRunning] = useState(false);
  const [starting, setStarting] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [tickNumber, setTickNumber] = useState(0);
  const [exhausted, setExhausted] = useState(false);
  const runRef = useRef(false);

  useEffect(() => {
    vehiclesApi.list()
      .then(setVehicles)
      .catch(() => setVehicles(DEMO_VEHICLES));
    zonesApi.list()
      .then(setZones)
      .catch(() => setZones(DEMO_ZONES));
    loadState();
  }, []);

  async function loadState() {
    try {
      const state = await simulationApi.state();
      setSimState(state);
      // Build positions from state
      const posMap = {};
      state.forEach(s => {
        if (s.latitude && s.longitude) posMap[s.vehicleId] = { latitude: s.latitude, longitude: s.longitude, speedKmh: 0, headingDeg: 0, source: 'SIMULATED' };
      });
      setPositions(posMap);
    } catch {
      setSimState(DEMO_SIMULATION_STATE);
    }
  }

  function toggleScenario(id) {
    setSelectedScenarios(prev =>
      prev.includes(id) ? prev.filter(s => s !== id) : [...prev, id]
    );
  }

  async function startSimulation() {
    if (selectedScenarios.length === 0) { addToast('warning', 'Select at least one scenario'); return; }
    setStarting(true);
    try {
      await simulationApi.start(selectedScenarios);
      await loadState();
      setTickLog([]);
      setTickNumber(0);
      setExhausted(false);
      addToast('success', `Started ${selectedScenarios.length} scenario(s)`);
    } catch (err) {
      addToast('error', err.message || 'Failed to start simulation');
    } finally {
      setStarting(false);
    }
  }

  const tick = useCallback(async () => {
    try {
      const result = await simulationApi.tick();
      setTickNumber(result.tickNumber);

      // Update positions
      const posMap = {};
      result.vehicles.forEach(v => {
        posMap[v.vehicleId] = { latitude: v.latitude, longitude: v.longitude, speedKmh: 30, headingDeg: 0, source: 'SIMULATED' };
      });
      setPositions(posMap);

      // Update sim state progress
      setSimState(prev => prev.map(s => {
        const v = result.vehicles.find(rv => rv.vehicleId === s.vehicleId);
        if (!v) return s;
        return { ...s, currentStep: s.currentStep + 1, latitude: v.latitude, longitude: v.longitude, isActive: v.status === 'MOVING' };
      }));

      // Log breaches
      const breachEvents = result.vehicles.filter(v => v.breachDetected);
      if (breachEvents.length > 0) {
        setTickLog(prev => [
          ...breachEvents.map(v => ({
            id: `${result.tickNumber}-${v.vehicleId}`,
            tick: result.tickNumber,
            vehicleId: v.vehicleId,
            reg: v.registrationNumber,
            breaches: v.breaches,
            time: new Date().toISOString(),
          })),
          ...prev,
        ].slice(0, 50));
        addToast('warning', `Breach detected at tick ${result.tickNumber}`);
      }

      if (result.exhausted) {
        setExhausted(true);
        setRunning(false);
        runRef.current = false;
        addToast('success', 'Simulation complete — all vehicles finished');
      }

      return result.exhausted;
    } catch (err) {
      addToast('error', err.message || 'Tick failed');
      setRunning(false);
      runRef.current = false;
      return true;
    }
  }, [addToast]);

  async function runAuto() {
    if (running) { runRef.current = false; setRunning(false); return; }
    runRef.current = true;
    setRunning(true);
    while (runRef.current) {
      const done = await tick();
      if (done) break;
      await new Promise(r => setTimeout(r, 1000));
    }
    runRef.current = false;
    setRunning(false);
  }

  async function reset() {
    runRef.current = false;
    setRunning(false);
    setResetting(true);
    try {
      await simulationApi.reset();
      await loadState();
      setTickLog([]);
      setTickNumber(0);
      setExhausted(false);
      addToast('info', 'Simulation reset');
    } catch (err) {
      addToast('error', err.message || 'Reset failed');
    } finally {
      setResetting(false);
    }
  }

  const activeVehicles = vehicles.filter(v => simState.some(s => s.vehicleId === v.id && s.isActive));
  const simPositions = { ...positions };

  return (
    <div className={styles.page}>
      {/* Control panel */}
      <div className={styles.controlPanel}>
        <div className={styles.panelHeader}>
          <h1 className={styles.title}>Simulation</h1>
          <div className={styles.statusPill}>
            <span className={`${styles.statusDot} ${running ? styles.dotRunning : exhausted ? styles.dotDone : styles.dotIdle}`} aria-hidden="true" />
            {running ? 'Running' : exhausted ? 'Complete' : 'Idle'}
          </div>
        </div>

        {/* Scenario selection */}
        <div className={styles.section}>
          <p className={styles.sectionTitle}>Scenarios</p>
          <div className={styles.scenarioList}>
            {SCENARIOS.map(s => (
              <button
                key={s.id}
                className={`${styles.scenarioCard} ${selectedScenarios.includes(s.id) ? styles.scenarioSelected : ''}`}
                onClick={() => toggleScenario(s.id)}
                aria-pressed={selectedScenarios.includes(s.id)}
                aria-label={`Toggle ${s.label}`}
              >
                <div className={styles.scenarioHeader}>
                  <span className={styles.scenarioLabel}>{s.label}</span>
                  <span className={`${styles.scenarioCheck} ${selectedScenarios.includes(s.id) ? styles.checked : ''}`} aria-hidden="true">
                    {selectedScenarios.includes(s.id) ? '✓' : ''}
                  </span>
                </div>
                <p className={styles.scenarioDesc}>{s.description}</p>
                <span className={styles.scenarioReg}>{s.reg}</span>
              </button>
            ))}
          </div>
        </div>

        {/* Controls */}
        <div className={styles.controls}>
          <Button variant="primary" onClick={startSimulation} loading={starting} disabled={running}>
            Start
          </Button>
          <Button variant="secondary" onClick={tick} disabled={running || exhausted}>
            Step ▶
          </Button>
          <Button
            variant={running ? 'danger' : 'secondary'}
            onClick={runAuto}
            disabled={exhausted}
          >
            {running ? '⏸ Pause' : '▶▶ Auto'}
          </Button>
          <Button variant="ghost" onClick={reset} loading={resetting} disabled={running}>
            ↺ Reset
          </Button>
        </div>

        {/* Tick counter */}
        {tickNumber > 0 && (
          <div className={styles.tickCounter}>
            <span className={styles.tickLabel}>Tick</span>
            <span className={styles.tickValue}>{tickNumber}</span>
          </div>
        )}

        {/* Vehicle progress */}
        <div className={styles.section}>
          <p className={styles.sectionTitle}>Vehicle Progress</p>
          {simState.length === 0 ? (
            <p className={styles.emptyText}>Start a simulation to see progress.</p>
          ) : (
            simState.map(s => {
              const progress = s.totalSteps > 0 ? s.currentStep / s.totalSteps : 0;
              const isDone = !s.isActive && s.currentStep === s.totalSteps;
              return (
                <div key={s.pathId} className={styles.vehicleProgress}>
                  <div className={styles.vehicleProgressHeader}>
                    <span className={styles.vehicleReg}>{s.registrationNumber}</span>
                    <Badge variant={isDone ? 'green' : s.isActive ? 'blue' : 'gray'} size="sm">
                      {isDone ? 'Done' : s.isActive ? 'Moving' : 'Idle'}
                    </Badge>
                  </div>
                  <div className={styles.progressBar} role="progressbar" aria-valuenow={Math.round(progress * 100)} aria-valuemin={0} aria-valuemax={100} aria-label={`${s.registrationNumber} progress`}>
                    <div className={styles.progressFill} style={{ width: `${progress * 100}%`, background: isDone ? 'var(--green-500)' : 'var(--brand-500)' }} />
                  </div>
                  <span className={styles.progressLabel}>{s.currentStep}/{s.totalSteps} steps</span>
                </div>
              );
            })
          )}
        </div>

        {/* Breach log */}
        {tickLog.length > 0 && (
          <div className={styles.section}>
            <p className={styles.sectionTitle}>Breach Log</p>
            <div className={styles.breachLog}>
              {tickLog.slice(0, 10).map(entry => (
                <div key={entry.id} className={styles.breachLogItem}>
                  <span className={styles.breachLogTick}>T{entry.tick}</span>
                  <div className={styles.breachLogInfo}>
                    <span className={styles.breachLogReg}>{entry.reg}</span>
                    {entry.breaches.map((b, i) => (
                      <span key={i} className={styles.breachLogType}>{BREACH_TYPE_LABELS[b.breachType]} · {b.zoneName}</span>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Map */}
      <div className={styles.mapArea}>
        <LiveMap
          vehicles={vehicles.filter(v => simState.some(s => s.vehicleId === v.id))}
          positions={simPositions}
          zones={zones}
          breaches={[]}
          routes={simState.filter(s => s.routeGeoJson)}
          layers={{ vehicles: true, zones: true, breaches: true, routes: true, labels: true }}
        />
        {exhausted && (
          <div className={styles.exhaustedBanner} role="status">
            ✓ Simulation complete — all vehicles finished
          </div>
        )}
      </div>
    </div>
  );
}
