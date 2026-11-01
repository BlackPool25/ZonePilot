const BASE = (import.meta.env.VITE_API_BASE_URL || 'https://zonepilot-backend.onrender.com') + '/api';

const TOKEN_KEY = 'zp-access-token';
export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t) => (t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY)),
};

async function request(path, options = {}) {
  const token = tokenStore.get();
  const res = await fetch(`${BASE}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
    ...options,
  });
  let json = null;
  try {
    json = await res.json();
  } catch {
    throw Object.assign(new Error(`HTTP ${res.status}`), { status: res.status });
  }
  if (!res.ok || !json.success) {
    const err = new Error(json.error?.message || `HTTP ${res.status}`);
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
  setAvailability: (id, availability) => post(`/vehicles/${id}/availability`, { availability }),
};

// ─── Groups ──────────────────────────────────────────────────────────
export const groupsApi = {
  list: () => get('/groups'),
  create: (name) => post('/groups', { name }),
  detail: (id) => get(`/groups/${id}`),
  addVehicle: (id, vehicleId) => post(`/groups/${id}/vehicles/${vehicleId}`),
  addDriver: (id, driverId) => post(`/groups/${id}/drivers/${driverId}`),
  removeVehicle: (id, vehicleId) => del(`/groups/${id}/vehicles/${vehicleId}`),
  removeDriver: (id, driverId) => del(`/groups/${id}/drivers/${driverId}`),
  dispatch: (id, body) => post(`/groups/${id}/dispatch`, body),
  dispatchSolo: (body) => post('/groups/dispatch-solo', body),
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

// ─── Tours (manager timeline + approve) ─────────────────────────────────
export const toursApi = {
  get: (id) => get(`/tours/${id}`),
  track: (id) => get(`/tours/${id}/track`),
  approve: (id) => post(`/tours/${id}/approve`),
};

// ─── Auth ─────────────────────────────────────────────────────────────
export const authApi = {
  login: (email, password) => post('/auth/login', { email, password }),
};

// ─── Replay ───────────────────────────────────────────────────────────
export const replayApi = {
  start: (tourId, speed) => post('/replay/start', { tourId, speed }),
};

// ─── Driver app (Slice 2) ─────────────────────────────────────────────
export const driverApi = {
  myRoutes: () => get('/driver/routes'),
  accept: (routeId) => post(`/driver/routes/${routeId}/accept`, {}),
  start: (routeId) => post(`/driver/routes/${routeId}/start`, {}),
  slip: (routeId, kind, amount) => post(`/driver/routes/${routeId}/slips`, { kind, amount }),
  sos: (lat, lng, routeId) => post('/driver/sos', { lat, lng, routeId }),
  myTours: () => get('/driver/tours'),
  tourDuty: (tourId, status) => post(`/driver/tours/${tourId}/duty`, { status }),
  dutyLog: (tourId) => get(`/driver/tours/${tourId}/duty-log`),
  deliverStop: (tourId, stopId, lat, lng) => post(`/driver/tours/${tourId}/stops/${stopId}/deliver`, { lat, lng }),
  skipStop: (tourId, stopId, reason) => post(`/driver/tours/${tourId}/stops/${stopId}/skip`, { reason }),
};
