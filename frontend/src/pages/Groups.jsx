import { useState, useEffect } from 'react';
import { groupsApi } from '../api/client.js';
import Badge from '../components/atoms/Badge.jsx';
import Button from '../components/atoms/Button.jsx';
import Input from '../components/atoms/Input.jsx';
import { LoadingOverlay, EmptyState, ErrorState } from '../components/atoms/Spinner.jsx';
import { useApp } from '../context/AppContext.jsx';
import styles from './Vehicles.module.css';

export default function Groups() {
  const { addToast } = useApp();
  const [groups, setGroups] = useState([]);
  const [selected, setSelected] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [newName, setNewName] = useState('');
  const [creating, setCreating] = useState(false);
  const [dispatching, setDispatching] = useState(false);
  const [dispatch, setDispatch] = useState({ originLat: '', originLng: '', destLat: '', destLng: '' });
  const [result, setResult] = useState(null);

  async function load() {
    setLoading(true); setError(null);
    try {
      const list = await groupsApi.list();
      setGroups(list);
      if (selected) {
        try { setSelected({ ...(await groupsApi.detail(selected.groupId)), name: selected.name }); }
        catch { setSelected(null); }
      }
    } catch (e) {
      setError(e.message || 'Failed to load groups');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, []);

  async function createGroup(e) {
    e.preventDefault();
    if (!newName.trim()) return;
    setCreating(true);
    try {
      const g = await groupsApi.create(newName.trim());
      setNewName('');
      addToast('success', 'Group created');
      const list = await groupsApi.list();
      setGroups(list);
      setSelected(await groupsApi.detail(g.id));
    } catch (err) {
      addToast('error', err.message || 'Create failed');
    } finally {
      setCreating(false);
    }
  }

  async function openGroup(g) {
    try {
      setSelected({ ...(await groupsApi.detail(g.id)), name: g.name });
      setResult(null);
    } catch (err) {
      addToast('error', err.message || 'Failed to load group');
    }
  }

  async function runDispatch(e) {
    e.preventDefault();
    if (!selected) return;
    setDispatching(true);
    setResult(null);
    try {
      const body = {
        originLat: Number(dispatch.originLat),
        originLng: Number(dispatch.originLng),
        destLat: Number(dispatch.destLat),
        destLng: Number(dispatch.destLng),
      };
      const res = await groupsApi.dispatch(selected.groupId, body);
      setResult(res);
      setSelected({ ...(await groupsApi.detail(selected.groupId)), name: selected.name });
      addToast('success', `Dispatched ${res.dispatched?.length ?? 0}, skipped ${res.skipped?.length ?? 0}`);
    } catch (err) {
      addToast('error', err.message || 'Dispatch failed');
    } finally {
      setDispatching(false);
    }
  }

  if (loading) return <LoadingOverlay label="Loading groups…" />;
  if (error) return <ErrorState message={error} onRetry={load} />;

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.title}>Groups</h1>
          <p className={styles.subtitle}>{groups.length} groups · flat fleet, no depots</p>
        </div>
      </div>

      <form onSubmit={createGroup} className={styles.filters} aria-label="Create group">
        <Input
          id="group-name"
          placeholder="New group name…"
          value={newName}
          onChange={e => setNewName(e.target.value)}
          className={styles.searchInput}
          aria-label="Group name"
        />
        <Button variant="primary" type="submit" loading={creating}>Create Group</Button>
      </form>

      {groups.length === 0 ? (
        <EmptyState icon="👥" title="No groups yet" description="Create your first fleet group above." />
      ) : (
        <div className={styles.tableWrap} role="region" aria-label="Groups table">
          <table className={styles.table} aria-label="Fleet groups">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {groups.map(g => (
                <tr key={g.id} className={styles.row} onClick={() => openGroup(g)} tabIndex={0}
                    onKeyDown={e => e.key === 'Enter' && openGroup(g)}>
                  <td className={styles.regNumber}>{g.name}</td>
                  <td>
                    <Button size="sm" variant="ghost" onClick={e => { e.stopPropagation(); openGroup(g); }}>
                      Open →
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selected && (
        <div className={styles.header} style={{ marginTop: 24 }}>
          <div>
            <h2 className={styles.title}>{selected.name}</h2>
            <p className={styles.subtitle}>
              {selected.vehicles?.length ?? 0} vehicles · {selected.drivers?.length ?? 0} drivers
            </p>
          </div>
        </div>
      )}

      {selected?.vehicles?.map(v => (
        <div key={`v-${v.id}`} className={styles.filters} style={{ marginBottom: 4 }}>
          <span className={styles.regNumber}>🚛 {v.label}</span>
          <Badge variant={v.busy ? 'amber' : 'green'} dot>{v.busy ? 'BUSY' : 'FREE'}</Badge>
        </div>
      ))}
      {selected?.drivers?.map(d => (
        <div key={`d-${d.id}`} className={styles.filters} style={{ marginBottom: 4 }}>
          <span className={styles.regNumber}>🧑 {d.label}</span>
          <Badge variant={d.busy ? 'amber' : 'green'} dot>{d.busy ? 'BUSY' : 'FREE'}</Badge>
        </div>
      ))}

      {selected && (
        <form onSubmit={runDispatch} className={styles.filters} aria-label="Dispatch group" style={{ marginTop: 12 }}>
          <Input id="dispatch-olat" placeholder="Origin lat" value={dispatch.originLat}
            onChange={e => setDispatch({ ...dispatch, originLat: e.target.value })} aria-label="Origin latitude" />
          <Input id="dispatch-olng" placeholder="Origin lng" value={dispatch.originLng}
            onChange={e => setDispatch({ ...dispatch, originLng: e.target.value })} aria-label="Origin longitude" />
          <Input id="dispatch-dlat" placeholder="Dest lat" value={dispatch.destLat}
            onChange={e => setDispatch({ ...dispatch, destLat: e.target.value })} aria-label="Destination latitude" />
          <Input id="dispatch-dlng" placeholder="Dest lng" value={dispatch.destLng}
            onChange={e => setDispatch({ ...dispatch, destLng: e.target.value })} aria-label="Destination longitude" />
          <Button variant="primary" type="submit" loading={dispatching}>Dispatch Group</Button>
        </form>
      )}

      {result && (
        <div className={styles.tableWrap} style={{ marginTop: 12 }}>
          <table className={styles.table} aria-label="Dispatch result">
            <thead>
              <tr><th scope="col">Outcome</th><th scope="col">Vehicle</th><th scope="col">Driver</th><th scope="col">Route / Reason</th></tr>
            </thead>
            <tbody>
              {(result.dispatched ?? []).map(r => (
                <tr key={`ok-${r.vehicleId}`} className={styles.row}>
                  <td><Badge variant="green">Dispatched</Badge></td>
                  <td>{r.vehicleId}</td><td>{r.driverId}</td><td>route {r.dispatchRouteId}</td>
                </tr>
              ))}
              {(result.skipped ?? []).map((s, i) => (
                <tr key={`skip-${i}`} className={styles.row}>
                  <td><Badge variant="amber">Skipped</Badge></td>
                  <td>{s.vehicleId ?? '—'}</td><td>{s.driverId ?? '—'}</td><td>{s.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
