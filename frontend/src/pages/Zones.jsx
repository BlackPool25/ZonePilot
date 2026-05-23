import { useState, useEffect } from 'react';
import { zonesApi } from '../api/client.js';
import { DEMO_ZONES } from '../data/demo.js';
import LiveMap from '../components/map/LiveMap.jsx';
import Badge from '../components/atoms/Badge.jsx';
import Button from '../components/atoms/Button.jsx';
import Input, { Select } from '../components/atoms/Input.jsx';
import { LoadingOverlay, EmptyState } from '../components/atoms/Spinner.jsx';
import { useApp } from '../context/AppContext.jsx';
import { RESTRICTION_TYPE_LABELS, RESTRICTION_TYPE_COLORS, decodeDays } from '../utils/helpers.js';
import styles from './Zones.module.css';

export default function Zones() {
  const { openDrawer, addToast } = useApp();
  const [zones, setZones] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selectedZone, setSelectedZone] = useState(null);
  const [showCreate, setShowCreate] = useState(false);
  const [typeFilter, setTypeFilter] = useState('');

  async function load() {
    setLoading(true);
    try {
      const data = await zonesApi.list();
      setZones(data);
    } catch {
      setZones(DEMO_ZONES);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  const filtered = typeFilter ? zones.filter(z => z.restrictionType === typeFilter) : zones;

  function handleZoneClick(zone) {
    setSelectedZone(zone);
    openDrawer('zone', zone.id);
  }

  if (loading) return <LoadingOverlay label="Loading zones…" />;

  return (
    <div className={styles.page}>
      {/* Left panel */}
      <div className={styles.sidebar}>
        <div className={styles.sidebarHeader}>
          <div>
            <h1 className={styles.title}>Zones</h1>
            <p className={styles.subtitle}>{zones.length} zones configured</p>
          </div>
          <Button variant="primary" size="sm" icon="+" onClick={() => setShowCreate(true)}>New Zone</Button>
        </div>

        <Select id="type-filter" value={typeFilter} onChange={e => setTypeFilter(e.target.value)} aria-label="Filter by restriction type">
          <option value="">All types</option>
          <option value="NO_ENTRY">No Entry</option>
          <option value="TIME_RESTRICTED">Time Restricted</option>
          <option value="VEHICLE_CLASS_RESTRICTED">Class Restricted</option>
        </Select>

        <div className={styles.zoneList} role="list" aria-label="Zone list">
          {filtered.length === 0 ? (
            <EmptyState icon="⬡" title="No zones" description="Create your first zone." />
          ) : (
            filtered.map(zone => (
              <button
                key={zone.id}
                className={`${styles.zoneCard} ${selectedZone?.id === zone.id ? styles.selected : ''}`}
                onClick={() => handleZoneClick(zone)}
                role="listitem"
                aria-label={`Zone: ${zone.name}`}
                aria-pressed={selectedZone?.id === zone.id}
              >
                <span
                  className={styles.zoneTypeBar}
                  style={{ background: RESTRICTION_TYPE_COLORS[zone.restrictionType] }}
                  aria-hidden="true"
                />
                <div className={styles.zoneInfo}>
                  <div className={styles.zoneNameRow}>
                    <span className={styles.zoneName}>{zone.name}</span>
                    <Badge
                      variant={zone.isActive ? 'green' : 'gray'}
                      size="sm"
                    >{zone.isActive ? 'Active' : 'Off'}</Badge>
                  </div>
                  <Badge
                    variant={zone.restrictionType === 'NO_ENTRY' ? 'red' : zone.restrictionType === 'TIME_RESTRICTED' ? 'amber' : 'purple'}
                    size="sm"
                  >
                    {RESTRICTION_TYPE_LABELS[zone.restrictionType]}
                  </Badge>
                  {zone.rules?.length > 0 && (
                    <p className={styles.rulesSummary}>
                      {zone.rules.length} rule{zone.rules.length !== 1 ? 's' : ''} · {zone.rules[0].restrictionStartTime}–{zone.rules[0].restrictionEndTime}
                    </p>
                  )}
                </div>
              </button>
            ))
          )}
        </div>
      </div>

      {/* Map */}
      <div className={styles.mapArea}>
        <LiveMap
          zones={zones}
          vehicles={[]}
          positions={{}}
          breaches={[]}
          onZoneClick={handleZoneClick}
          selectedZoneId={selectedZone?.id}
          layers={{ vehicles: false, zones: true, breaches: false, routes: false, labels: true }}
        />
      </div>

      {/* Create zone form */}
      {showCreate && (
        <CreateZonePanel
          onClose={() => setShowCreate(false)}
          onSuccess={() => { setShowCreate(false); load(); addToast('success', 'Zone created'); }}
        />
      )}
    </div>
  );
}

function CreateZonePanel({ onClose, onSuccess }) {
  const [form, setForm] = useState({
    name: '', description: '', restrictionType: 'NO_ENTRY', isActive: true,
    boundaryGeoJson: '',
    // Rule fields
    addRule: false,
    ruleClass: 'HCV', ruleStart: '08:00', ruleEnd: '22:00', ruleDays: 31,
  });
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);

  function set(field, value) {
    setForm(f => ({ ...f, [field]: value }));
    setErrors(e => ({ ...e, [field]: undefined }));
  }

  async function submit(e) {
    e.preventDefault();
    const errs = {};
    if (!form.name.trim()) errs.name = 'Required';
    if (!form.boundaryGeoJson.trim()) errs.boundaryGeoJson = 'Required — paste a GeoJSON Polygon string';
    if (Object.keys(errs).length) { setErrors(errs); return; }

    // Validate GeoJSON
    let parsedGeoJson;
    try { parsedGeoJson = JSON.parse(form.boundaryGeoJson); } catch {
      setErrors({ boundaryGeoJson: 'Invalid JSON' }); return;
    }
    if (parsedGeoJson.type !== 'Polygon') {
      setErrors({ boundaryGeoJson: 'Must be a GeoJSON Polygon' }); return;
    }

    setSubmitting(true);
    try {
      const body = {
        name: form.name,
        description: form.description,
        boundaryGeoJson: form.boundaryGeoJson,
        restrictionType: form.restrictionType,
        isActive: form.isActive,
        rules: form.addRule ? [{
          applicableVehicleClass: form.ruleClass,
          restrictionStartTime: form.ruleStart,
          restrictionEndTime: form.ruleEnd,
          daysOfWeekBitmask: Number(form.ruleDays),
          isActive: true,
        }] : [],
      };
      await zonesApi.create(body);
      onSuccess();
    } catch (err) {
      if (err.fields) setErrors(err.fields);
      else setErrors({ _global: err.message });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.createPanel}>
      <div className={styles.createHeader}>
        <h2 className={styles.createTitle}>New Zone</h2>
        <button className={styles.closeBtn} onClick={onClose} aria-label="Close">✕</button>
      </div>
      <form onSubmit={submit} noValidate className={styles.createForm}>
        {errors._global && <p className={styles.globalError} role="alert">{errors._global}</p>}

        <Input id="zone-name" label="Zone Name" placeholder="MG Road No-Entry" value={form.name} onChange={e => set('name', e.target.value)} error={errors.name} required />
        <Input id="zone-desc" label="Description (optional)" placeholder="Brief description…" value={form.description} onChange={e => set('description', e.target.value)} />
        <Select id="zone-type" label="Restriction Type" value={form.restrictionType} onChange={e => set('restrictionType', e.target.value)}>
          <option value="NO_ENTRY">No Entry</option>
          <option value="TIME_RESTRICTED">Time Restricted</option>
          <option value="VEHICLE_CLASS_RESTRICTED">Vehicle Class Restricted</option>
        </Select>

        <div className={styles.field}>
          <label htmlFor="zone-geojson" className={styles.fieldLabel}>Boundary GeoJSON <span className={styles.required}>*</span></label>
          <textarea
            id="zone-geojson"
            className={`${styles.textarea} ${errors.boundaryGeoJson ? styles.textareaError : ''}`}
            placeholder={'{"type":"Polygon","coordinates":[[[77.617,12.976],[77.623,12.976],[77.623,12.971],[77.617,12.971],[77.617,12.976]]]}'}
            value={form.boundaryGeoJson}
            onChange={e => set('boundaryGeoJson', e.target.value)}
            rows={4}
            aria-describedby={errors.boundaryGeoJson ? 'geojson-err' : undefined}
            aria-invalid={!!errors.boundaryGeoJson}
          />
          {errors.boundaryGeoJson && <span id="geojson-err" className={styles.fieldError} role="alert">{errors.boundaryGeoJson}</span>}
          <span className={styles.fieldHint}>Paste a valid GeoJSON Polygon. First and last coordinate must be identical.</span>
        </div>

        {/* Rule section — progressive disclosure */}
        <div className={styles.ruleToggle}>
          <label className={styles.checkLabel}>
            <input type="checkbox" checked={form.addRule} onChange={e => set('addRule', e.target.checked)} />
            Add restriction rule
          </label>
        </div>

        {form.addRule && (
          <div className={styles.ruleFields}>
            <Select id="rule-class" label="Vehicle Class" value={form.ruleClass} onChange={e => set('ruleClass', e.target.value)}>
              <option value="TWO_WHEELER">Two Wheeler</option>
              <option value="LCV">LCV</option>
              <option value="HCV">HCV</option>
            </Select>
            <div className={styles.timeRow}>
              <Input id="rule-start" label="Start Time" type="time" value={form.ruleStart} onChange={e => set('ruleStart', e.target.value)} />
              <Input id="rule-end" label="End Time" type="time" value={form.ruleEnd} onChange={e => set('ruleEnd', e.target.value)} />
            </div>
            <div className={styles.field}>
              <label className={styles.fieldLabel}>Days (bitmask)</label>
              <DaysPicker value={form.ruleDays} onChange={v => set('ruleDays', v)} />
            </div>
          </div>
        )}

        <div className={styles.createFooter}>
          <Button variant="secondary" type="button" onClick={onClose}>Cancel</Button>
          <Button variant="primary" type="submit" loading={submitting}>Create Zone</Button>
        </div>
      </form>
    </div>
  );
}

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
function DaysPicker({ value, onChange }) {
  function toggle(i) {
    const bit = 1 << i;
    onChange(value ^ bit);
  }
  return (
    <div className={styles.daysPicker} role="group" aria-label="Days of week">
      {DAY_NAMES.map((d, i) => (
        <button
          key={d}
          type="button"
          className={`${styles.dayBtn} ${(value >> i) & 1 ? styles.dayActive : ''}`}
          onClick={() => toggle(i)}
          aria-pressed={(value >> i) & 1 ? true : false}
          aria-label={d}
        >
          {d}
        </button>
      ))}
    </div>
  );
}
