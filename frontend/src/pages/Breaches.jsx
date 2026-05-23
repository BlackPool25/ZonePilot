import { useState, useEffect, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { breachesApi, vehiclesApi, zonesApi } from '../api/client.js';
import { DEMO_BREACHES, DEMO_VEHICLES, DEMO_ZONES } from '../data/demo.js';
import Badge from '../components/atoms/Badge.jsx';
import Button from '../components/atoms/Button.jsx';
import { Select } from '../components/atoms/Input.jsx';
import { LoadingOverlay, EmptyState } from '../components/atoms/Spinner.jsx';
import { useApp } from '../context/AppContext.jsx';
import { BREACH_TYPE_LABELS, BREACH_TYPE_COLORS, formatDateTime, formatRelative } from '../utils/helpers.js';
import styles from './Breaches.module.css';

export default function Breaches() {
  const { openDrawer, addToast } = useApp();
  const [searchParams] = useSearchParams();
  const vehicleIdParam = searchParams.get('vehicleId');

  const [breaches, setBreaches] = useState([]);
  const [vehicles, setVehicles] = useState([]);
  const [zones, setZones] = useState([]);
  const [loading, setLoading] = useState(true);
  const [ackingId, setAckingId] = useState(null);

  // Filters
  const [vehicleFilter, setVehicleFilter] = useState(vehicleIdParam ?? '');
  const [zoneFilter, setZoneFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [ackFilter, setAckFilter] = useState('unacknowledged');

  async function load() {
    setLoading(true);
    const [b, v, z] = await Promise.allSettled([
      breachesApi.list(),
      vehiclesApi.list(),
      zonesApi.list(),
    ]);
    setBreaches(b.status === 'fulfilled' ? b.value : DEMO_BREACHES);
    setVehicles(v.status === 'fulfilled' ? v.value : DEMO_VEHICLES);
    setZones(z.status === 'fulfilled' ? z.value : DEMO_ZONES);
    setLoading(false);
  }

  useEffect(() => { load(); }, []);

  const filtered = useMemo(() => {
    return breaches.filter(b => {
      if (vehicleFilter && String(b.vehicleId) !== vehicleFilter) return false;
      if (zoneFilter && String(b.zoneId) !== zoneFilter) return false;
      if (typeFilter && b.breachType !== typeFilter) return false;
      if (ackFilter === 'unacknowledged' && b.isAcknowledged) return false;
      if (ackFilter === 'acknowledged' && !b.isAcknowledged) return false;
      return true;
    });
  }, [breaches, vehicleFilter, zoneFilter, typeFilter, ackFilter]);

  async function acknowledge(e, breach) {
    e.stopPropagation();
    if (breach.isAcknowledged) return;
    setAckingId(breach.id);
    try {
      await breachesApi.acknowledge(breach.id);
      setBreaches(bs => bs.map(b => b.id === breach.id ? { ...b, isAcknowledged: true } : b));
      addToast('success', 'Breach acknowledged');
    } catch (err) {
      if (err.status === 409) {
        setBreaches(bs => bs.map(b => b.id === breach.id ? { ...b, isAcknowledged: true } : b));
        addToast('info', 'Already acknowledged');
      } else {
        addToast('error', err.message || 'Failed to acknowledge');
      }
    } finally {
      setAckingId(null);
    }
  }

  const unackedCount = breaches.filter(b => !b.isAcknowledged).length;

  if (loading) return <LoadingOverlay label="Loading breaches…" />;

  return (
    <div className={styles.page}>
      {/* Header */}
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Breaches</h1>
          <p className={styles.subtitle}>
            {unackedCount > 0
              ? <span className={styles.alertText}>⚠ {unackedCount} unacknowledged breach{unackedCount !== 1 ? 'es' : ''}</span>
              : <span>All breaches acknowledged</span>
            }
          </p>
        </div>
        <Button variant="secondary" size="sm" onClick={load}>Refresh</Button>
      </div>

      {/* Filters */}
      <div className={styles.filters} role="group" aria-label="Filter breaches">
        <div className={styles.ackTabs} role="tablist" aria-label="Acknowledgement filter">
          {[['', 'All'], ['unacknowledged', 'Unacknowledged'], ['acknowledged', 'Acknowledged']].map(([val, label]) => (
            <button
              key={val}
              role="tab"
              aria-selected={ackFilter === val}
              className={`${styles.tab} ${ackFilter === val ? styles.tabActive : ''}`}
              onClick={() => setAckFilter(val)}
            >
              {label}
              {val === 'unacknowledged' && unackedCount > 0 && (
                <span className={styles.tabBadge}>{unackedCount}</span>
              )}
            </button>
          ))}
        </div>

        <div className={styles.selectFilters}>
          <Select id="vehicle-filter" value={vehicleFilter} onChange={e => setVehicleFilter(e.target.value)} aria-label="Filter by vehicle">
            <option value="">All vehicles</option>
            {vehicles.map(v => <option key={v.id} value={v.id}>{v.registrationNumber}</option>)}
          </Select>
          <Select id="zone-filter" value={zoneFilter} onChange={e => setZoneFilter(e.target.value)} aria-label="Filter by zone">
            <option value="">All zones</option>
            {zones.map(z => <option key={z.id} value={z.id}>{z.name}</option>)}
          </Select>
          <Select id="type-filter" value={typeFilter} onChange={e => setTypeFilter(e.target.value)} aria-label="Filter by breach type">
            <option value="">All types</option>
            <option value="NO_ENTRY">No Entry</option>
            <option value="TIME_WINDOW">Time Window</option>
            <option value="VEHICLE_CLASS">Vehicle Class</option>
          </Select>
        </div>
      </div>

      <p className={styles.resultCount} aria-live="polite">{filtered.length} breach{filtered.length !== 1 ? 'es' : ''}</p>

      {/* Table */}
      {filtered.length === 0 ? (
        <EmptyState icon="✓" title="No breaches" description="No breaches match the current filters." />
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table} aria-label="Breach log">
            <thead>
              <tr>
                <th scope="col">Status</th>
                <th scope="col">Vehicle</th>
                <th scope="col">Zone</th>
                <th scope="col">Type</th>
                <th scope="col">Time</th>
                <th scope="col"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(breach => (
                <tr
                  key={breach.id}
                  className={`${styles.row} ${!breach.isAcknowledged ? styles.rowUnacked : ''}`}
                  onClick={() => openDrawer('breach', breach.id, breach)}
                  tabIndex={0}
                  onKeyDown={e => e.key === 'Enter' && openDrawer('breach', breach.id, breach)}
                  aria-label={`Breach by ${breach.registrationNumber} in ${breach.zoneName}`}
                >
                  <td>
                    {breach.isAcknowledged
                      ? <Badge variant="green" size="sm">Acknowledged</Badge>
                      : <Badge variant="red" size="sm" dot>Open</Badge>
                    }
                  </td>
                  <td>
                    <span className={styles.regNumber}>{breach.registrationNumber}</span>
                  </td>
                  <td className={styles.zoneCell}>{breach.zoneName}</td>
                  <td>
                    <span className={styles.breachType} style={{ color: BREACH_TYPE_COLORS[breach.breachType] }}>
                      {BREACH_TYPE_LABELS[breach.breachType]}
                    </span>
                  </td>
                  <td className={styles.timeCell}>
                    <span title={formatDateTime(breach.breachTime)}>{formatRelative(breach.breachTime)}</span>
                  </td>
                  <td>
                    <div className={styles.rowActions}>
                      {!breach.isAcknowledged && (
                        <Button
                          size="sm"
                          variant="secondary"
                          loading={ackingId === breach.id}
                          onClick={e => acknowledge(e, breach)}
                          aria-label={`Acknowledge breach by ${breach.registrationNumber}`}
                        >
                          Acknowledge
                        </Button>
                      )}
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={e => { e.stopPropagation(); openDrawer('breach', breach.id, breach); }}
                        aria-label="View breach details"
                      >
                        Details →
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
