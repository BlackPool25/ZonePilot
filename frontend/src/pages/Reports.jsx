import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell, Legend,
} from 'recharts';
import { reportsApi, zonesApi } from '../api/client.js';
import { DEMO_REPORT_SUMMARY, DEMO_ZONES, DEMO_ACTIVE_RESTRICTIONS } from '../data/demo.js';
import Badge from '../components/atoms/Badge.jsx';
import { Select } from '../components/atoms/Input.jsx';
import { LoadingOverlay, EmptyState } from '../components/atoms/Spinner.jsx';
import { useApp } from '../context/AppContext.jsx';
import { VEHICLE_CLASS_LABELS, RESTRICTION_TYPE_LABELS, formatRelative } from '../utils/helpers.js';
import styles from './Reports.module.css';

const CHART_COLORS = ['#dc2626', '#d97706', '#7c3aed'];

export default function Reports() {
  const { openDrawer } = useApp();
  const [searchParams] = useSearchParams();
  const vehicleIdParam = searchParams.get('vehicleId');

  const [summary, setSummary] = useState([]);
  const [restrictions, setRestrictions] = useState([]);
  const [zones, setZones] = useState([]);
  const [zoneViolations, setZoneViolations] = useState([]);
  const [selectedZoneId, setSelectedZoneId] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function load() {
      setLoading(true);
      const [s, r, z] = await Promise.allSettled([
        reportsApi.summary(),
        reportsApi.activeRestrictions(),
        zonesApi.list(),
      ]);
      setSummary(s.status === 'fulfilled' ? s.value : DEMO_REPORT_SUMMARY);
      setRestrictions(r.status === 'fulfilled' ? r.value : DEMO_ACTIVE_RESTRICTIONS);
      setZones(z.status === 'fulfilled' ? z.value : DEMO_ZONES);
      setLoading(false);
    }
    load();
  }, []);

  useEffect(() => {
    if (!selectedZoneId) { setZoneViolations([]); return; }
    reportsApi.zoneViolations(selectedZoneId)
      .then(setZoneViolations)
      .catch(() => setZoneViolations([]));
  }, [selectedZoneId]);

  if (loading) return <LoadingOverlay label="Loading reports…" />;

  // Aggregate for charts
  const totalBreaches = summary.reduce((s, v) => s + v.totalBreaches, 0);
  const totalUnacked = summary.reduce((s, v) => s + v.unacknowledgedBreaches, 0);
  const activeVehicles = summary.filter(v => v.totalBreaches > 0).length;

  const breachByType = [
    { name: 'No Entry', value: summary.reduce((s, v) => s + v.noEntryBreaches, 0) },
    { name: 'Time Window', value: summary.reduce((s, v) => s + v.timeWindowBreaches, 0) },
    { name: 'Class', value: summary.reduce((s, v) => s + v.classBreaches, 0) },
  ].filter(d => d.value > 0);

  const topVehicles = [...summary].sort((a, b) => b.totalBreaches - a.totalBreaches).slice(0, 8);

  const zoneViolationChartData = zoneViolations.map(v => ({
    name: v.zoneName,
    HCV: v.hcvViolations,
    LCV: v.lcvViolations,
    '2W': v.twoWheelerViolations,
  }));

  return (
    <div className={styles.page}>
      <div className={styles.pageHeader}>
        <h1 className={styles.title}>Fleet Reports</h1>
        <p className={styles.subtitle}>Compliance analytics and breach statistics</p>
      </div>

      {/* KPI strip */}
      <div className={styles.kpiStrip}>
        <div className={styles.kpiCard}>
          <span className={styles.kpiValue}>{totalBreaches}</span>
          <span className={styles.kpiLabel}>Total Breaches</span>
        </div>
        <div className={`${styles.kpiCard} ${totalUnacked > 0 ? styles.kpiAlert : ''}`}>
          <span className={styles.kpiValue}>{totalUnacked}</span>
          <span className={styles.kpiLabel}>Unacknowledged</span>
        </div>
        <div className={styles.kpiCard}>
          <span className={styles.kpiValue}>{activeVehicles}</span>
          <span className={styles.kpiLabel}>Vehicles with Breaches</span>
        </div>
        <div className={styles.kpiCard}>
          <span className={styles.kpiValue}>{restrictions.length}</span>
          <span className={styles.kpiLabel}>Active Restrictions</span>
        </div>
      </div>

      <div className={styles.grid}>
        {/* Fleet summary table */}
        <div className={`${styles.card} ${styles.fullWidth}`}>
          <h2 className={styles.cardTitle}>Fleet Breach Summary</h2>
          {summary.length === 0 ? (
            <EmptyState icon="📊" title="No data" description="No breach data available." />
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table} aria-label="Fleet breach summary">
                <thead>
                  <tr>
                    <th scope="col">Vehicle</th>
                    <th scope="col">Class</th>
                    <th scope="col">Total</th>
                    <th scope="col">No Entry</th>
                    <th scope="col">Time Window</th>
                    <th scope="col">Class</th>
                    <th scope="col">Unacked</th>
                    <th scope="col">Last Breach</th>
                    <th scope="col"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {summary.map(v => (
                    <tr
                      key={v.vehicleId}
                      className={`${styles.row} ${vehicleIdParam && String(v.vehicleId) === vehicleIdParam ? styles.highlighted : ''}`}
                    >
                      <td><span className={styles.regNumber}>{v.registrationNumber}</span></td>
                      <td><Badge variant="gray" size="sm">{VEHICLE_CLASS_LABELS[v.vehicleClass]}</Badge></td>
                      <td><strong>{v.totalBreaches}</strong></td>
                      <td>{v.noEntryBreaches > 0 ? <span className={styles.redNum}>{v.noEntryBreaches}</span> : '—'}</td>
                      <td>{v.timeWindowBreaches > 0 ? <span className={styles.amberNum}>{v.timeWindowBreaches}</span> : '—'}</td>
                      <td>{v.classBreaches > 0 ? <span className={styles.purpleNum}>{v.classBreaches}</span> : '—'}</td>
                      <td>
                        {v.unacknowledgedBreaches > 0
                          ? <Badge variant="red" size="sm">{v.unacknowledgedBreaches}</Badge>
                          : <Badge variant="green" size="sm">0</Badge>
                        }
                      </td>
                      <td className={styles.timeCell}>{formatRelative(v.lastBreachTime)}</td>
                      <td>
                        <button
                          className={styles.linkBtn}
                          onClick={() => openDrawer('vehicle', v.vehicleId)}
                          aria-label={`View vehicle ${v.registrationNumber}`}
                        >
                          View →
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* Breach by type pie */}
        <div className={styles.card}>
          <h2 className={styles.cardTitle}>Breaches by Type</h2>
          {breachByType.length === 0 ? (
            <EmptyState icon="✓" title="No breaches" description="Fleet is fully compliant." />
          ) : (
            <ResponsiveContainer width="100%" height={220}>
              <PieChart>
                <Pie data={breachByType} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={80} label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}>
                  {breachByType.map((_, i) => <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />)}
                </Pie>
                <Tooltip />
              </PieChart>
            </ResponsiveContainer>
          )}
        </div>

        {/* Top offenders bar chart */}
        <div className={styles.card}>
          <h2 className={styles.cardTitle}>Top Offenders</h2>
          {topVehicles.filter(v => v.totalBreaches > 0).length === 0 ? (
            <EmptyState icon="✓" title="No breaches" description="Fleet is fully compliant." />
          ) : (
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={topVehicles.filter(v => v.totalBreaches > 0)} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" />
                <XAxis dataKey="registrationNumber" tick={{ fontSize: 10 }} tickFormatter={v => v.split('-').pop()} />
                <YAxis tick={{ fontSize: 11 }} />
                <Tooltip formatter={(v) => [v, 'Breaches']} labelFormatter={l => `Vehicle: ${l}`} />
                <Bar dataKey="totalBreaches" fill="var(--red-500)" radius={[3, 3, 0, 0]} name="Breaches" />
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>

        {/* Active restrictions */}
        <div className={styles.card}>
          <h2 className={styles.cardTitle}>Active Restrictions Now</h2>
          {restrictions.length === 0 ? (
            <EmptyState icon="✓" title="No active restrictions" description="No zones are currently restricted." />
          ) : (
            <div className={styles.restrictionList}>
              {restrictions.map(r => (
                <div key={r.id} className={styles.restrictionItem}>
                  <div className={styles.restrictionInfo}>
                    <span className={styles.restrictionName}>{r.name}</span>
                    <span className={styles.restrictionMeta}>{r.applicableVehicleClass} · {r.restrictionStartTime}–{r.restrictionEndTime}</span>
                  </div>
                  <Badge
                    variant={r.restrictionType === 'NO_ENTRY' ? 'red' : r.restrictionType === 'TIME_RESTRICTED' ? 'amber' : 'purple'}
                    size="sm"
                  >
                    {RESTRICTION_TYPE_LABELS[r.restrictionType]}
                  </Badge>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Zone violation drill-down */}
        <div className={`${styles.card} ${styles.fullWidth}`}>
          <div className={styles.cardTitleRow}>
            <h2 className={styles.cardTitle}>Zone Violation Breakdown</h2>
            <Select id="zone-select" value={selectedZoneId} onChange={e => setSelectedZoneId(e.target.value)} aria-label="Select zone">
              <option value="">Select a zone…</option>
              {zones.map(z => <option key={z.id} value={z.id}>{z.name}</option>)}
            </Select>
          </div>
          {!selectedZoneId ? (
            <EmptyState icon="⬡" title="Select a zone" description="Choose a zone to see its violation breakdown." />
          ) : zoneViolationChartData.length === 0 ? (
            <EmptyState icon="✓" title="No violations" description="This zone has no recorded violations." />
          ) : (
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={zoneViolationChartData} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--border-subtle)" />
                <XAxis dataKey="name" tick={{ fontSize: 11 }} />
                <YAxis tick={{ fontSize: 11 }} />
                <Tooltip />
                <Legend />
                <Bar dataKey="HCV" fill="#dc2626" radius={[3, 3, 0, 0]} />
                <Bar dataKey="LCV" fill="#d97706" radius={[3, 3, 0, 0]} />
                <Bar dataKey="2W" fill="#7c3aed" radius={[3, 3, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>
    </div>
  );
}
