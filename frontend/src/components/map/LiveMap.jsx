import { useEffect, useRef } from 'react';
import { MapContainer, TileLayer, Marker, Polygon, Polyline, Popup, useMap } from 'react-leaflet';
import L from 'leaflet';
import { useApp } from '../../context/AppContext.jsx';
import {
  VEHICLE_CLASS_ICONS, RESTRICTION_TYPE_COLORS, BREACH_TYPE_COLORS,
  geoJsonToLatLngs, wktToLatLngs,
} from '../../utils/helpers.js';

// Fix Leaflet default icon path issue with Vite
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
  iconUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
  shadowUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
});

function createVehicleIcon(vehicleClass, isSelected, hasBreach) {
  const emoji = VEHICLE_CLASS_ICONS[vehicleClass] ?? '🚗';
  const bg = hasBreach ? '#fef2f2' : isSelected ? '#eff6ff' : '#ffffff';
  const border = hasBreach ? '#dc2626' : isSelected ? '#2563eb' : '#e2e8f0';
  return L.divIcon({
    html: `<div style="
      width:36px;height:36px;
      background:${bg};
      border:2px solid ${border};
      border-radius:50%;
      display:flex;align-items:center;justify-content:center;
      font-size:16px;
      box-shadow:0 2px 6px rgba(0,0,0,0.15);
      transition:all 0.2s;
    ">${emoji}</div>`,
    className: '',
    iconSize: [36, 36],
    iconAnchor: [18, 18],
    popupAnchor: [0, -20],
  });
}

function createBreachIcon() {
  return L.divIcon({
    html: `<div style="
      width:28px;height:28px;
      background:#fef2f2;
      border:2px solid #dc2626;
      border-radius:50%;
      display:flex;align-items:center;justify-content:center;
      font-size:13px;
      box-shadow:0 2px 6px rgba(220,38,38,0.3);
    ">⚠</div>`,
    className: '',
    iconSize: [28, 28],
    iconAnchor: [14, 14],
  });
}

// Inner component to sync map center from context
function MapController() {
  const { state } = useApp();
  const map = useMap();
  const prevCenter = useRef(null);

  useEffect(() => {
    const [lat, lng] = state.mapCenter;
    if (prevCenter.current && prevCenter.current[0] === lat && prevCenter.current[1] === lng) return;
    prevCenter.current = state.mapCenter;
    map.flyTo([lat, lng], state.mapZoom, { duration: 0.8 });
  }, [state.mapCenter, state.mapZoom, map]);

  return null;
}

export default function LiveMap({
  vehicles = [],
  positions = {},
  zones = [],
  breaches = [],
  routes = [],
  onVehicleClick,
  onZoneClick,
  onBreachClick,
  selectedVehicleId,
  selectedZoneId,
  layers,
  className = '',
  style = {},
}) {
  const { state } = useApp();
  const activeLayers = layers ?? state.mapLayers;

  return (
    <MapContainer
      center={state.mapCenter}
      zoom={state.mapZoom}
      style={{ height: '100%', width: '100%', ...style }}
      className={className}
      zoomControl={true}
      attributionControl={false}
    >
      <TileLayer
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution='© OpenStreetMap contributors'
      />
      <MapController />

      {/* Zone polygons */}
      {activeLayers.zones && zones.map(zone => {
        const positions = geoJsonToLatLngs(zone.boundaryGeoJson);
        if (!positions.length) return null;
        const color = RESTRICTION_TYPE_COLORS[zone.restrictionType] ?? '#6b7280';
        const isSelected = zone.id === selectedZoneId;
        return (
          <Polygon
            key={zone.id}
            positions={positions}
            pathOptions={{
              color,
              fillColor: color,
              fillOpacity: isSelected ? 0.25 : 0.12,
              weight: isSelected ? 2.5 : 1.5,
              dashArray: zone.restrictionType === 'TIME_RESTRICTED' ? '6 4' : undefined,
            }}
            eventHandlers={{ click: () => onZoneClick?.(zone) }}
          >
            {activeLayers.labels && (
              <Popup>
                <strong>{zone.name}</strong><br />
                <span style={{ fontSize: 12, color: '#64748b' }}>{zone.restrictionType.replace(/_/g, ' ')}</span>
              </Popup>
            )}
          </Polygon>
        );
      })}

      {/* Vehicle markers */}
      {activeLayers.vehicles && vehicles.map(vehicle => {
        const pos = positions[vehicle.id];
        if (!pos) return null;
        const hasBreach = activeLayers.breaches && breaches.some(b => b.vehicleId === vehicle.id && !b.isAcknowledged);
        const isSelected = vehicle.id === selectedVehicleId;
        return (
          <Marker
            key={vehicle.id}
            position={[pos.latitude, pos.longitude]}
            icon={createVehicleIcon(vehicle.vehicleClass, isSelected, hasBreach)}
            eventHandlers={{ click: () => onVehicleClick?.(vehicle) }}
          >
            {activeLayers.labels && (
              <Popup>
                <strong style={{ fontFamily: 'monospace', fontSize: 12 }}>{vehicle.registrationNumber}</strong><br />
                <span style={{ fontSize: 12, color: '#64748b' }}>{vehicle.vehicleClass} · {pos.speedKmh ?? 0} km/h</span>
              </Popup>
            )}
          </Marker>
        );
      })}

      {/* Breach markers */}
      {activeLayers.breaches && breaches.filter(b => !b.isAcknowledged).map(breach => {
        const pos = positions[breach.vehicleId];
        if (!pos) return null;
        return (
          <Marker
            key={`breach-${breach.id}`}
            position={[pos.latitude + 0.0003, pos.longitude + 0.0003]}
            icon={createBreachIcon()}
            eventHandlers={{ click: () => onBreachClick?.(breach) }}
          />
        );
      })}

      {/* Route overlays */}
      {activeLayers.routes && routes.map((route, i) => {
        const latlngs = wktToLatLngs(route.routeGeoJson ?? route.alternativeRouteGeoJson);
        if (!latlngs.length) return null;
        return (
          <Polyline
            key={i}
            positions={latlngs}
            pathOptions={{ color: route.compliant ? '#16a34a' : '#dc2626', weight: 3, dashArray: route.compliant ? undefined : '8 4' }}
          />
        );
      })}
    </MapContainer>
  );
}
