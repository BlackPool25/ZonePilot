import { useState, useEffect, useMemo } from 'react';
import { vehiclesApi, depotsApi } from '../api/client.js';
import { DEMO_VEHICLES, DEMO_DEPOTS } from '../data/demo.js';
import Badge from '../components/atoms/Badge.jsx';
import Button from '../components/atoms/Button.jsx';
import Input, { Select } from '../components/atoms/Input.jsx';
import { LoadingOverlay, EmptyState, ErrorState } from '../components/atoms/Spinner.jsx';
import { useApp } from '../context/AppContext.jsx';
import { VEHICLE_CLASS_LABELS, VEHICLE_CLASS_ICONS } from '../utils/helpers.js';
import RegisterVehicleModal from './RegisterVehicleModal.jsx';
import styles from './Vehicles.module.css';

export default function Vehicles() {
  const { openDrawer, addToast } = useApp();
  const [vehicles, setVehicles] = useState([]);
  const [depots, setDepots] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showRegister, setShowRegister] = useState(false);

  // Filters
  const [search, setSearch] = useState('');
  const [classFilter, setClassFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [depotFilter, setDepotFilter] = useState('');

  async function load() {
    setLoading(true); setError(null);
    try {
      const [v, d] = await Promise.allSettled([vehiclesApi.list(), depotsApi.list()]);
      setVehicles(v.status === 'fulfilled' ? v.value : DEMO_VEHICLES);
      setDepots(d.status === 'fulfilled' ? d.value : DEMO_DEPOTS);
    } catch (e) {
      setVehicles(DEMO_VEHICLES);
      setDepots(DEMO_DEPOTS);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  const filtered = useMemo(() => {
    return vehicles.filter(v => {
      if (search && !v.registrationNumber.toLowerCase().includes(search.toLowerCase()) && !v.ownerName.toLowerCase().includes(search.toLowerCase())) return false;
      if (classFilter && v.vehicleClass !== classFilter) return false;
      if (statusFilter === 'active' && !v.isActive) return false;
      if (statusFilter === 'inactive' && v.isActive) return false;
      if (depotFilter && String(v.depotId) !== depotFilter) return false;
      return true;
    });
  }, [vehicles, search, classFilter, statusFilter, depotFilter]);

  function clearFilters() {
    setSearch(''); setClassFilter(''); setStatusFilter(''); setDepotFilter('');
  }

  const hasFilters = search || classFilter || statusFilter || depotFilter;

  if (loading) return <LoadingOverlay label="Loading vehicles…" />;
  if (error) return <ErrorState message={error} onRetry={load} />;

  return (
    <div className={styles.page}>
      {/* Page header */}
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Vehicles</h1>
          <p className={styles.subtitle}>{vehicles.length} vehicles · {vehicles.filter(v => v.isActive).length} active</p>
        </div>
        <Button variant="primary" icon="+" onClick={() => setShowRegister(true)}>Register Vehicle</Button>
      </div>

      {/* Filters */}
      <div className={styles.filters} role="search" aria-label="Filter vehicles">
        <Input
          id="vehicle-search"
          placeholder="Search by registration or owner…"
          value={search}
          onChange={e => setSearch(e.target.value)}
          prefix="🔍"
          className={styles.searchInput}
          aria-label="Search vehicles"
        />
        <Select id="class-filter" value={classFilter} onChange={e => setClassFilter(e.target.value)} aria-label="Filter by class">
          <option value="">All classes</option>
          <option value="TWO_WHEELER">2-Wheeler</option>
          <option value="LCV">LCV</option>
          <option value="HCV">HCV</option>
        </Select>
        <Select id="status-filter" value={statusFilter} onChange={e => setStatusFilter(e.target.value)} aria-label="Filter by status">
          <option value="">All statuses</option>
          <option value="active">Active</option>
          <option value="inactive">Inactive</option>
        </Select>
        <Select id="depot-filter" value={depotFilter} onChange={e => setDepotFilter(e.target.value)} aria-label="Filter by depot">
          <option value="">All depots</option>
          {depots.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
        </Select>
        {hasFilters && (
          <Button variant="ghost" size="sm" onClick={clearFilters} aria-label="Clear all filters">Clear filters</Button>
        )}
      </div>

      {/* Results count */}
      <p className={styles.resultCount} aria-live="polite">
        {filtered.length === vehicles.length ? `${vehicles.length} vehicles` : `${filtered.length} of ${vehicles.length} vehicles`}
      </p>

      {/* Table */}
      {filtered.length === 0 ? (
        <EmptyState icon="🚛" title="No vehicles found" description="Try adjusting your filters." action={hasFilters && <Button size="sm" onClick={clearFilters}>Clear filters</Button>} />
      ) : (
        <div className={styles.tableWrap} role="region" aria-label="Vehicles table">
          <table className={styles.table} aria-label="Fleet vehicles">
            <thead>
              <tr>
                <th scope="col">Registration</th>
                <th scope="col">Class</th>
                <th scope="col">Owner</th>
                <th scope="col">Depot</th>
                <th scope="col">Status</th>
                <th scope="col"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(vehicle => (
                <tr
                  key={vehicle.id}
                  className={styles.row}
                  onClick={() => openDrawer('vehicle', vehicle.id)}
                  tabIndex={0}
                  onKeyDown={e => e.key === 'Enter' && openDrawer('vehicle', vehicle.id)}
                  aria-label={`Vehicle ${vehicle.registrationNumber}`}
                >
                  <td>
                    <div className={styles.regCell}>
                      <span className={styles.classEmoji} aria-hidden="true">{VEHICLE_CLASS_ICONS[vehicle.vehicleClass]}</span>
                      <span className={styles.regNumber}>{vehicle.registrationNumber}</span>
                    </div>
                  </td>
                  <td><Badge variant="gray">{VEHICLE_CLASS_LABELS[vehicle.vehicleClass]}</Badge></td>
                  <td className={styles.ownerCell}>{vehicle.ownerName}</td>
                  <td className={styles.depotCell}>{vehicle.depotName}</td>
                  <td>
                    <Badge variant={vehicle.isActive ? 'green' : 'gray'} dot>
                      {vehicle.isActive ? 'Active' : 'Inactive'}
                    </Badge>
                  </td>
                  <td>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={e => { e.stopPropagation(); openDrawer('vehicle', vehicle.id); }}
                      aria-label={`View details for ${vehicle.registrationNumber}`}
                    >
                      Details →
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showRegister && (
        <RegisterVehicleModal
          depots={depots}
          onClose={() => setShowRegister(false)}
          onSuccess={() => { setShowRegister(false); load(); addToast('success', 'Vehicle registered'); }}
        />
      )}
    </div>
  );
}
