import { useState } from 'react';
import { vehiclesApi } from '../api/client.js';
import Button from '../components/atoms/Button.jsx';
import Input, { Select } from '../components/atoms/Input.jsx';
import styles from './RegisterVehicleModal.module.css';

export default function RegisterVehicleModal({ depots, onClose, onSuccess }) {
  const [form, setForm] = useState({ registrationNumber: '', vehicleClass: '', ownerName: '', depotId: '', isActive: true });
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);

  function set(field, value) {
    setForm(f => ({ ...f, [field]: value }));
    setErrors(e => ({ ...e, [field]: undefined }));
  }

  async function submit(e) {
    e.preventDefault();
    const errs = {};
    if (!form.registrationNumber.trim()) errs.registrationNumber = 'Required';
    if (!form.vehicleClass) errs.vehicleClass = 'Required';
    if (!form.ownerName.trim()) errs.ownerName = 'Required';
    if (!form.depotId) errs.depotId = 'Required';
    if (Object.keys(errs).length) { setErrors(errs); return; }

    setSubmitting(true);
    try {
      await vehiclesApi.create({ ...form, depotId: Number(form.depotId) });
      onSuccess();
    } catch (err) {
      if (err.fields) setErrors(err.fields);
      else setErrors({ _global: err.message });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.overlay} role="dialog" aria-modal="true" aria-label="Register vehicle">
      <div className={styles.modal}>
        <div className={styles.modalHeader}>
          <h2 className={styles.modalTitle}>Register Vehicle</h2>
          <button className={styles.closeBtn} onClick={onClose} aria-label="Close">✕</button>
        </div>
        <form onSubmit={submit} noValidate>
          <div className={styles.body}>
            {errors._global && <p className={styles.globalError} role="alert">{errors._global}</p>}
            <Input
              id="reg-number" label="Registration Number" placeholder="KA01-LCV-0099"
              value={form.registrationNumber} onChange={e => set('registrationNumber', e.target.value)}
              error={errors.registrationNumber} required
            />
            <Select id="reg-class" label="Vehicle Class" value={form.vehicleClass} onChange={e => set('vehicleClass', e.target.value)} error={errors.vehicleClass} required>
              <option value="">Select class…</option>
              <option value="TWO_WHEELER">Two Wheeler</option>
              <option value="LCV">LCV (Light Commercial)</option>
              <option value="HCV">HCV (Heavy Commercial)</option>
            </Select>
            <Input
              id="reg-owner" label="Owner Name" placeholder="Fleet Owner Name"
              value={form.ownerName} onChange={e => set('ownerName', e.target.value)}
              error={errors.ownerName} required
            />
            <Select id="reg-depot" label="Depot" value={form.depotId} onChange={e => set('depotId', e.target.value)} error={errors.depotId} required>
              <option value="">Select depot…</option>
              {depots.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
            </Select>
          </div>
          <div className={styles.footer}>
            <Button variant="secondary" onClick={onClose} type="button">Cancel</Button>
            <Button variant="primary" type="submit" loading={submitting}>Register</Button>
          </div>
        </form>
      </div>
    </div>
  );
}
