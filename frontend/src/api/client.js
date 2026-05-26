const BASE = (import.meta.env.VITE_API_BASE_URL || 'https://zonepilot-backend.onrender.com') + '/api';

async function request(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  });
  const json = await res.json();
  if (!json.success) {
    const err = new Error(json.error?.message || 'Request failed');
    err.code = json.error?.code;
    err.fields = json.error?.fields;
    err.status = res.status;
    throw err;
  }
  return json.data;
}

const get = (path) => request(path);
const post = (path, body) => request(path, { method: 'POST', body: body !== undefined ? JSON.stringify(body) : undefined });
const put = (path, body) => request(path, { method: 'PUT', body: body !== undefined ? JSON.stringify(body) : undefined });
const del = (path) => request(path, { method: 'DELETE' });

// ─── Vehicles ────────────────────────────────────────────────────────
export const vehiclesApi = {
  list: (params = {}) => {
    const q = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== '' && v !== undefined));
    return get(`/vehicles${q.toString() ? '?' + q : ''}`);
  },
  get: (id) => get(`/vehicles/${id}`),
  create: (body) => post('/vehicles', body),
  update: (id, body) => put(`/vehicles/${id}`, body),
  zonesAtLocation: (id, lat, lng) => get(`/vehicles/${id}/zones-at-location?lat=${lat}&lng=${lng}`),
  latestPosition: (id) => get(`/vehicles/${id}/positions/latest`),
  positionHistory: (id, from, to) => {
    const q = new URLSearchParams();
    if (from) q.set('from', from);
    if (to) q.set('to', to);
    return get(`/vehicles/${id}/positions${q.toString() ? '?' + q : ''}`);
  },
  recordPosition: (id, body) => post(`/vehicles/${id}/positions`, body),
  clearRoute: (id) => post(`/vehicles/${id}/clear-route`),
};

// ─── Depots ───────────────────────────────────────────────────────────
export const depotsApi = {
  list: () => get('/depots'),
  get: (id) => get(`/depots/${id}`),
  create: (body) => post('/depots', body),
};

// ─── Zones ────────────────────────────────────────────────────────────
export const zonesApi = {
  list: () => get('/zones'),
  get: (id) => get(`/zones/${id}`),
  active: () => get('/zones/active'),
  create: (body) => post('/zones', body),
  delete: (id) => del(`/zones/${id}`),
};


// ─── Breaches ─────────────────────────────────────────────────────────
export const breachesApi = {
  list: (params = {}) => {
    const q = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== '' && v !== undefined && v !== null));
    return get(`/breaches${q.toString() ? '?' + q : ''}`);
  },
  get: (id) => get(`/breaches/${id}`),
  acknowledge: (id) => put(`/breaches/${id}/acknowledge`),
};

// ─── Routes ───────────────────────────────────────────────────────────
export const routesApi = {
  validate: (body) => post('/routes/validate', body),
  get: (id) => get(`/routes/${id}`),
  vehicleHistory: (vehicleId) => get(`/routes/vehicle/${vehicleId}`),
  delete: (id) => del(`/routes/${id}`),
};

// ─── Reports ──────────────────────────────────────────────────────────
export const reportsApi = {
  summary: () => get('/reports/summary'),
  zoneViolations: (zoneId) => get(`/reports/zones/${zoneId}/violations`),
  activeRestrictions: () => get('/reports/active-restrictions'),
};

// ─── Simulation ───────────────────────────────────────────────────────
export const simulationApi = {
  start: (scenarios) => post('/simulation/start', { scenarios }),
  tick: () => post('/simulation/tick'),
  state: () => get('/simulation/state'),
  reset: () => post('/simulation/reset'),
};
