// ─── Domain helpers ───────────────────────────────────────────────────

export const VEHICLE_CLASS_LABELS = {
  TWO_WHEELER: '2-Wheeler',
  LCV: 'LCV',
  HCV: 'HCV',
};

export const VEHICLE_CLASS_ICONS = {
  TWO_WHEELER: '🛵',
  LCV: '🚐',
  HCV: '🚛',
};

export const RESTRICTION_TYPE_LABELS = {
  NO_ENTRY: 'No Entry',
  TIME_RESTRICTED: 'Time Restricted',
  VEHICLE_CLASS_RESTRICTED: 'Class Restricted',
};

export const RESTRICTION_TYPE_COLORS = {
  NO_ENTRY: '#dc2626',
  TIME_RESTRICTED: '#d97706',
  VEHICLE_CLASS_RESTRICTED: '#ca8a04',
};

export const BREACH_TYPE_LABELS = {
  NO_ENTRY: 'No Entry',
  TIME_WINDOW: 'Time Window',
  VEHICLE_CLASS: 'Vehicle Class',
};

export const BREACH_TYPE_COLORS = {
  NO_ENTRY: '#dc2626',
  TIME_WINDOW: '#d97706',
  VEHICLE_CLASS: '#7c3aed',
};

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
export function decodeDays(bitmask) {
  return DAYS.filter((_, i) => (bitmask >> i) & 1);
}

export function formatTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true });
}

export function formatDateTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: true });
}

export function formatRelative(iso) {
  if (!iso) return '—';
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}

export function formatSpeed(kmh) {
  if (kmh === null || kmh === undefined) return '—';
  return `${Math.round(kmh)} km/h`;
}

// Convert WKT LINESTRING to Leaflet LatLng array
export function wktToLatLngs(wkt) {
  if (!wkt) return [];
  const match = wkt.match(/LINESTRING\s*\(([^)]+)\)/i);
  if (!match) return [];
  return match[1].split(',').map(pair => {
    const [lng, lat] = pair.trim().split(/\s+/).map(Number);
    return [lat, lng];
  });
}

// Convert GeoJSON Polygon coordinates to Leaflet positions
export function geoJsonToLatLngs(geoJson) {
  if (!geoJson?.coordinates?.[0]) return [];
  return geoJson.coordinates[0].map(([lng, lat]) => [lat, lng]);
}

export function classNames(...args) {
  return args.filter(Boolean).join(' ');
}

export function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}
