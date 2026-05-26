import { useEffect, useRef } from 'react';
import { MapContainer, TileLayer, Marker, Polygon, Polyline, Popup, useMap, useMapEvents } from 'react-leaflet';
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

function createPinIcon(label, color) {
  return L.divIcon({
    html: `<div style="
      width:30px;height:30px;
      background:${color};
      border:2px solid #ffffff;
      border-radius:50%;
      display:flex;align-items:center;justify-content:center;
      color:#ffffff;
      font-size:13px;font-weight:700;
      box-shadow:0 2px 6px rgba(0,0,0,0.3);
    ">${label}</div>`,
    className: '',
    iconSize: [30, 30],
    iconAnchor: [15, 15],
  });
}

function createVertexIcon() {
  return L.divIcon({
    html: `<div style="
      width:14px;height:14px;
      background:#3b82f6;
      border:2px solid #ffffff;
      border-radius:50%;
      box-shadow:0 1px 4px rgba(0,0,0,0.3);
      cursor:grab;
    "></div>`,
    className: '',
    iconSize: [14, 14],
    iconAnchor: [7, 7],
  });
}

// Inner component to sync map center from context AND save user pan/zoom back
function MapController() {
  const { state, setMapCenter } = useApp();
  const map = useMap();
  const prevCenter = useRef(null);
  const isUserInteracting = useRef(false);

  // Apply context-driven center changes (e.g. from setMapCenter calls)
  useEffect(() => {
    const [lat, lng] = state.mapCenter;
    if (prevCenter.current && prevCenter.current[0] === lat && prevCenter.current[1] === lng) return;
    prevCenter.current = state.mapCenter;
    map.flyTo([lat, lng], state.mapZoom, { duration: 0.8 });
  }, [state.mapCenter, state.mapZoom, map]);

  // Save user pan/zoom back to context so it persists across navigation
  useMapEvents({
    moveend() {
      if (!isUserInteracting.current) return;
      const c = map.getCenter();
      const z = map.getZoom();
      prevCenter.current = [c.lat, c.lng];
      setMapCenter([c.lat, c.lng], z);
    },
    mousedown() { isUserInteracting.current = true; },
    touchstart() { isUserInteracting.current = true; },
    flyend() { isUserInteracting.current = false; },
  });

  return null;
}

function MapClickHandler({ onMapClick }) {
  useMapEvents({
    click(e) {
      onMapClick?.(e.latlng);
    },
  });
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
  onMapClick,
  originPin,
  destPin,
  selectedVehicleId,
  selectedZoneId,
  layers,
  className = '',
  style = {},
  isDrawingZone = false,
  drawingPoints = [],
  onDrawingPointsChange,
}) {
  const { state } = useApp();
  const activeLayers = layers ?? state.mapLayers;

  const handleMapClick = (latlng) => {
    if (isDrawingZone) {
      onDrawingPointsChange?.([...drawingPoints, [latlng.lat, latlng.lng]]);
    } else {
      onMapClick?.(latlng);
    }
  };


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
      {activeLayers.routes && routes.flatMap((route, i) => {
        const paths = [];
        if (route.routeGeoJson) {
          const latlngs = wktToLatLngs(route.routeGeoJson);
          if (latlngs.length) {
            paths.push(
              <Polyline
                key={`route-${i}`}
                positions={latlngs}
                pathOptions={{
                  color: route.compliant || route.status === 'COMPLIANT' ? '#16a34a' : '#dc2626',
                  weight: route.compliant || route.status === 'COMPLIANT' ? 4 : 3,
                  dashArray: route.compliant || route.status === 'COMPLIANT' ? undefined : '8 4'
                }}
              >
                <Popup>
                  <div style={{ padding: '2px' }}>
                    <strong style={{ color: route.compliant || route.status === 'COMPLIANT' ? '#16a34a' : '#dc2626' }}>
                      {route.compliant || route.status === 'COMPLIANT' ? 'Compliant Route' : 'Original Non-Compliant Route'}
                    </strong>
                    {!route.compliant && route.status !== 'COMPLIANT' && (
                      <p style={{ fontSize: '11px', margin: '4px 0 0 0', color: '#64748b' }}>
                        This path intersects a restricted zone or curfew window.
                      </p>
                    )}
                  </div>
                </Popup>
              </Polyline>
            );
          }
        }
        if (route.alternativeRouteGeoJson && !(route.compliant || route.status === 'COMPLIANT')) {
          const altLatlngs = wktToLatLngs(route.alternativeRouteGeoJson);
          if (altLatlngs.length) {
            paths.push(
              <Polyline
                key={`alt-${i}`}
                positions={altLatlngs}
                pathOptions={{
                  color: '#16a34a',
                  weight: 4,
                  dashArray: '5 5'
                }}
              >
                <Popup>
                  <div style={{ padding: '2px' }}>
                    <strong style={{ color: '#16a34a' }}>✓ Compliant Alternative Route</strong>
                    <p style={{ fontSize: '11px', margin: '4px 0 0 0', color: '#64748b' }}>
                      This route was computed dynamically to completely bypass all active restricted zones.
                    </p>
                  </div>
                </Popup>
              </Polyline>
            );
          }
        }
        return paths;
      })}

      {/* Origin Pin drop */}
      {originPin && (
        <Marker
          position={[originPin.lat, originPin.lng]}
          icon={createPinIcon('A', '#16a34a')}
        >
          <Popup><strong>Origin Location</strong></Popup>
        </Marker>
      )}

      {/* Destination Pin drop */}
      {destPin && (
        <Marker
          position={[destPin.lat, destPin.lng]}
          icon={createPinIcon('B', '#dc2626')}
        >
          <Popup><strong>Destination Location</strong></Popup>
        </Marker>
      )}

      {/* Interactive Zone drawing and editing layers */}
      {isDrawingZone && drawingPoints.length > 0 && (
        <>
          {/* Draggable vertices control handles */}
          {drawingPoints.map((point, index) => (
            <Marker
              key={`vertex-${index}-${point[0]}-${point[1]}`}
              position={point}
              draggable={true}
              icon={createVertexIcon()}
              eventHandlers={{
                dragend(e) {
                  const latlng = e.target.getLatLng();
                  const newPoints = [...drawingPoints];
                  newPoints[index] = [latlng.lat, latlng.lng];
                  onDrawingPointsChange?.(newPoints);
                },
                click(e) {
                  if (e.originalEvent) {
                    e.originalEvent.stopPropagation();
                  }
                  // Click a vertex handle to remove it!
                  const newPoints = drawingPoints.filter((_, i) => i !== index);
                  onDrawingPointsChange?.(newPoints);
                }
              }}
            />
          ))}

          {/* Dotted border line if >= 2 points */}
          {drawingPoints.length >= 2 && (
            <Polyline
              positions={drawingPoints}
              pathOptions={{ color: '#3b82f6', weight: 2, dashArray: '5 5' }}
            />
          )}

          {/* Semicolored polygon fill if >= 3 points */}
          {drawingPoints.length >= 3 && (
            <Polygon
              positions={drawingPoints}
              pathOptions={{ color: '#3b82f6', fillColor: '#3b82f6', fillOpacity: 0.15, weight: 1 }}
            />
          )}
        </>
      )}

      <MapClickHandler onMapClick={handleMapClick} />
    </MapContainer>
  );
}


