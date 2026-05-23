// Demo data matching the exact API response shapes from FRONTEND_API_REPORT.md
// Used as fallback when backend is unavailable

export const DEMO_DEPOTS = [
  { id: 1, name: 'Koramangala Depot', address: 'Koramangala, Bengaluru', latitude: 12.9352, longitude: 77.6245 },
  { id: 2, name: 'Yeshwantpur Depot', address: 'Yeshwantpur, Bengaluru', latitude: 13.0218, longitude: 77.5560 },
  { id: 3, name: 'Electronic City Depot', address: 'Electronic City, Bengaluru', latitude: 12.8456, longitude: 77.6603 },
];

export const DEMO_VEHICLES = [
  { id: 1, registrationNumber: 'KA01-TWO-0001', vehicleClass: 'TWO_WHEELER', ownerName: 'Fleet Owner 1', depotId: 1, depotName: 'Koramangala Depot', isActive: true },
  { id: 2, registrationNumber: 'KA01-TWO-0002', vehicleClass: 'TWO_WHEELER', ownerName: 'Fleet Owner 2', depotId: 1, depotName: 'Koramangala Depot', isActive: true },
  { id: 3, registrationNumber: 'KA01-LCV-0003', vehicleClass: 'LCV', ownerName: 'Fleet Owner 3', depotId: 2, depotName: 'Yeshwantpur Depot', isActive: true },
  { id: 4, registrationNumber: 'KA01-LCV-0004', vehicleClass: 'LCV', ownerName: 'Fleet Owner 4', depotId: 1, depotName: 'Koramangala Depot', isActive: true },
  { id: 5, registrationNumber: 'KA01-LCV-0005', vehicleClass: 'LCV', ownerName: 'Fleet Owner 5', depotId: 3, depotName: 'Electronic City Depot', isActive: true },
  { id: 6, registrationNumber: 'KA01-HCV-0006', vehicleClass: 'HCV', ownerName: 'Fleet Owner 6', depotId: 2, depotName: 'Yeshwantpur Depot', isActive: false },
  { id: 7, registrationNumber: 'KA01-HCV-0007', vehicleClass: 'HCV', ownerName: 'Fleet Owner 7', depotId: 2, depotName: 'Yeshwantpur Depot', isActive: true },
  { id: 8, registrationNumber: 'KA01-HCV-0008', vehicleClass: 'HCV', ownerName: 'Fleet Owner 8', depotId: 3, depotName: 'Electronic City Depot', isActive: true },
];

export const DEMO_ZONES = [
  {
    id: 1, name: 'MG Road No-Entry', description: 'No heavy vehicles on MG Road during peak hours',
    boundaryGeoJson: { type: 'Polygon', coordinates: [[[77.617, 12.976], [77.623, 12.976], [77.623, 12.971], [77.617, 12.971], [77.617, 12.976]]] },
    restrictionType: 'NO_ENTRY', isActive: true,
    rules: [{ id: 1, applicableVehicleClass: 'HCV', restrictionStartTime: '07:00', restrictionEndTime: '21:00', daysOfWeekBitmask: 31, isActive: true }],
  },
  {
    id: 2, name: 'Majestic Bus Terminal', description: 'LCV and HCV restricted during peak hours',
    boundaryGeoJson: { type: 'Polygon', coordinates: [[[77.572, 12.978], [77.578, 12.978], [77.578, 12.973], [77.572, 12.973], [77.572, 12.978]]] },
    restrictionType: 'TIME_RESTRICTED', isActive: true,
    rules: [
      { id: 2, applicableVehicleClass: 'HCV', restrictionStartTime: '08:00', restrictionEndTime: '22:00', daysOfWeekBitmask: 127, isActive: true },
      { id: 3, applicableVehicleClass: 'LCV', restrictionStartTime: '09:00', restrictionEndTime: '20:00', daysOfWeekBitmask: 31, isActive: true },
    ],
  },
  {
    id: 3, name: 'Indiranagar 100ft Road', description: 'Vehicle class restriction zone',
    boundaryGeoJson: { type: 'Polygon', coordinates: [[[77.638, 12.978], [77.645, 12.978], [77.645, 12.972], [77.638, 12.972], [77.638, 12.978]]] },
    restrictionType: 'VEHICLE_CLASS_RESTRICTED', isActive: true,
    rules: [{ id: 4, applicableVehicleClass: 'HCV', restrictionStartTime: '00:00', restrictionEndTime: '23:59', daysOfWeekBitmask: 127, isActive: true }],
  },
  {
    id: 4, name: 'Silk Board Junction', description: 'Time-restricted zone for heavy vehicles',
    boundaryGeoJson: { type: 'Polygon', coordinates: [[[77.621, 12.917], [77.628, 12.917], [77.628, 12.912], [77.621, 12.912], [77.621, 12.917]]] },
    restrictionType: 'TIME_RESTRICTED', isActive: true,
    rules: [{ id: 5, applicableVehicleClass: 'HCV', restrictionStartTime: '07:30', restrictionEndTime: '10:30', daysOfWeekBitmask: 31, isActive: true }],
  },
];

export const DEMO_POSITIONS = {
  1: { vehicleId: 1, latitude: 12.9352, longitude: 77.6245, recordedAt: new Date().toISOString(), speedKmh: 0, headingDeg: 0, source: 'GPS' },
  2: { vehicleId: 2, latitude: 12.9400, longitude: 77.6100, recordedAt: new Date().toISOString(), speedKmh: 35, headingDeg: 90, source: 'GPS' },
  3: { vehicleId: 3, latitude: 13.0218, longitude: 77.5560, recordedAt: new Date().toISOString(), speedKmh: 0, headingDeg: 0, source: 'GPS' },
  4: { vehicleId: 4, latitude: 12.9500, longitude: 77.6300, recordedAt: new Date().toISOString(), speedKmh: 42, headingDeg: 180, source: 'GPS' },
  5: { vehicleId: 5, latitude: 12.8456, longitude: 77.6603, recordedAt: new Date().toISOString(), speedKmh: 0, headingDeg: 0, source: 'GPS' },
  7: { vehicleId: 7, latitude: 12.9716, longitude: 77.5946, recordedAt: new Date().toISOString(), speedKmh: 28, headingDeg: 270, source: 'GPS' },
  8: { vehicleId: 8, latitude: 12.9600, longitude: 77.6400, recordedAt: new Date().toISOString(), speedKmh: 55, headingDeg: 45, source: 'GPS' },
};

export const DEMO_BREACHES = [
  { id: 1, vehicleId: 7, registrationNumber: 'KA01-HCV-0007', zoneId: 1, zoneName: 'MG Road No-Entry', breachType: 'NO_ENTRY', breachTime: new Date(Date.now() - 3600000).toISOString(), rerouteGeoJson: null, isAcknowledged: false },
  { id: 2, vehicleId: 8, registrationNumber: 'KA01-HCV-0008', zoneId: 2, zoneName: 'Majestic Bus Terminal', breachType: 'TIME_WINDOW', breachTime: new Date(Date.now() - 7200000).toISOString(), rerouteGeoJson: null, isAcknowledged: false },
  { id: 3, vehicleId: 5, registrationNumber: 'KA01-LCV-0005', zoneId: 2, zoneName: 'Majestic Bus Terminal', breachType: 'TIME_WINDOW', breachTime: new Date(Date.now() - 10800000).toISOString(), rerouteGeoJson: null, isAcknowledged: true },
  { id: 4, vehicleId: 7, registrationNumber: 'KA01-HCV-0007', zoneId: 3, zoneName: 'Indiranagar 100ft Road', breachType: 'VEHICLE_CLASS', breachTime: new Date(Date.now() - 86400000).toISOString(), rerouteGeoJson: null, isAcknowledged: true },
  { id: 5, vehicleId: 8, registrationNumber: 'KA01-HCV-0008', zoneId: 4, zoneName: 'Silk Board Junction', breachType: 'TIME_WINDOW', breachTime: new Date(Date.now() - 172800000).toISOString(), rerouteGeoJson: null, isAcknowledged: false },
];

export const DEMO_REPORT_SUMMARY = [
  { vehicleId: 7, registrationNumber: 'KA01-HCV-0007', vehicleClass: 'HCV', totalBreaches: 12, noEntryBreaches: 5, timeWindowBreaches: 7, classBreaches: 0, unacknowledgedBreaches: 3, lastBreachTime: new Date(Date.now() - 3600000).toISOString() },
  { vehicleId: 8, registrationNumber: 'KA01-HCV-0008', vehicleClass: 'HCV', totalBreaches: 8, noEntryBreaches: 2, timeWindowBreaches: 4, classBreaches: 2, unacknowledgedBreaches: 2, lastBreachTime: new Date(Date.now() - 7200000).toISOString() },
  { vehicleId: 5, registrationNumber: 'KA01-LCV-0005', vehicleClass: 'LCV', totalBreaches: 3, noEntryBreaches: 0, timeWindowBreaches: 3, classBreaches: 0, unacknowledgedBreaches: 0, lastBreachTime: new Date(Date.now() - 10800000).toISOString() },
  { vehicleId: 4, registrationNumber: 'KA01-LCV-0004', vehicleClass: 'LCV', totalBreaches: 1, noEntryBreaches: 0, timeWindowBreaches: 1, classBreaches: 0, unacknowledgedBreaches: 0, lastBreachTime: new Date(Date.now() - 86400000).toISOString() },
  { vehicleId: 3, registrationNumber: 'KA01-LCV-0003', vehicleClass: 'LCV', totalBreaches: 0, noEntryBreaches: 0, timeWindowBreaches: 0, classBreaches: 0, unacknowledgedBreaches: 0, lastBreachTime: null },
];

export const DEMO_ACTIVE_RESTRICTIONS = [
  { id: 1, name: 'MG Road No-Entry', restrictionType: 'NO_ENTRY', applicableVehicleClass: 'HCV', restrictionStartTime: '07:00', restrictionEndTime: '21:00' },
  { id: 2, name: 'Majestic Bus Terminal', restrictionType: 'TIME_RESTRICTED', applicableVehicleClass: 'HCV', restrictionStartTime: '08:00', restrictionEndTime: '22:00' },
];

export const DEMO_SIMULATION_STATE = [
  { pathId: 1, vehicleId: 4, registrationNumber: 'KA01-LCV-0004', scenarioName: 'SCENARIO_A', currentStep: 0, totalSteps: 20, isActive: false, latitude: 12.9352, longitude: 77.6245 },
  { pathId: 2, vehicleId: 7, registrationNumber: 'KA01-HCV-0007', scenarioName: 'SCENARIO_B', currentStep: 0, totalSteps: 17, isActive: false, latitude: 13.0218, longitude: 77.5560 },
  { pathId: 3, vehicleId: 5, registrationNumber: 'KA01-LCV-0005', scenarioName: 'SCENARIO_C', currentStep: 0, totalSteps: 15, isActive: false, latitude: 12.8456, longitude: 77.6603 },
];
