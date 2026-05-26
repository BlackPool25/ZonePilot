import React, { createContext, useContext, useReducer, useCallback } from 'react';

// ─── State Shape ─────────────────────────────────────────────────────
const initialState = {
  // Drawer
  drawer: { type: null, entityId: null, data: null }, // type: 'vehicle' | 'zone' | 'breach' | 'route'

  // Map
  mapCenter: [12.9716, 77.5946], // Bangalore center
  mapZoom: 12,
  mapLayers: { vehicles: true, zones: true, breaches: true, routes: false, labels: true },
  selectedVehicleId: null,
  selectedZoneId: null,

  // Global alerts
  toasts: [], // { id, type, message }

  // Unacknowledged breach count (polled)
  unacknowledgedBreachCount: 0,

  // Active restrictions count (polled)
  activeRestrictionsCount: 0,

  // Navigation
  navExpanded: false,

  // Last validated route — shown on Dashboard map
  lastValidatedRoute: null,
};

// ─── Actions ─────────────────────────────────────────────────────────
const actions = {
  OPEN_DRAWER: 'OPEN_DRAWER',
  CLOSE_DRAWER: 'CLOSE_DRAWER',
  SET_MAP_CENTER: 'SET_MAP_CENTER',
  TOGGLE_MAP_LAYER: 'TOGGLE_MAP_LAYER',
  SELECT_VEHICLE: 'SELECT_VEHICLE',
  SELECT_ZONE: 'SELECT_ZONE',
  ADD_TOAST: 'ADD_TOAST',
  REMOVE_TOAST: 'REMOVE_TOAST',
  SET_BREACH_COUNT: 'SET_BREACH_COUNT',
  SET_RESTRICTION_COUNT: 'SET_RESTRICTION_COUNT',
  TOGGLE_NAV: 'TOGGLE_NAV',
  SET_LAST_ROUTE: 'SET_LAST_ROUTE',
};

function reducer(state, action) {
  switch (action.type) {
    case actions.OPEN_DRAWER:
      return { ...state, drawer: { type: action.drawerType, entityId: action.entityId, data: action.data ?? null } };
    case actions.CLOSE_DRAWER:
      return { ...state, drawer: { type: null, entityId: null, data: null }, selectedVehicleId: null, selectedZoneId: null };
    case actions.SET_MAP_CENTER:
      return { ...state, mapCenter: action.center, mapZoom: action.zoom ?? state.mapZoom };
    case actions.TOGGLE_MAP_LAYER:
      return { ...state, mapLayers: { ...state.mapLayers, [action.layer]: !state.mapLayers[action.layer] } };
    case actions.SELECT_VEHICLE:
      return { ...state, selectedVehicleId: action.id };
    case actions.SELECT_ZONE:
      return { ...state, selectedZoneId: action.id };
    case actions.ADD_TOAST:
      return { ...state, toasts: [...state.toasts, { id: Date.now(), ...action.toast }] };
    case actions.REMOVE_TOAST:
      return { ...state, toasts: state.toasts.filter(t => t.id !== action.id) };
    case actions.SET_BREACH_COUNT:
      return { ...state, unacknowledgedBreachCount: action.count };
    case actions.SET_RESTRICTION_COUNT:
      return { ...state, activeRestrictionsCount: action.count };
    case actions.TOGGLE_NAV:
      return { ...state, navExpanded: !state.navExpanded };
    case actions.SET_LAST_ROUTE:
      return { ...state, lastValidatedRoute: action.route };
    default:
      return state;
  }
}

// ─── Context ─────────────────────────────────────────────────────────
const AppContext = createContext(null);

export function AppProvider({ children }) {
  const [state, dispatch] = useReducer(reducer, initialState);

  const openDrawer = useCallback((drawerType, entityId, data) =>
    dispatch({ type: actions.OPEN_DRAWER, drawerType, entityId, data }), []);

  const closeDrawer = useCallback(() =>
    dispatch({ type: actions.CLOSE_DRAWER }), []);

  const setMapCenter = useCallback((center, zoom) =>
    dispatch({ type: actions.SET_MAP_CENTER, center, zoom }), []);

  const toggleMapLayer = useCallback((layer) =>
    dispatch({ type: actions.TOGGLE_MAP_LAYER, layer }), []);

  const selectVehicle = useCallback((id) =>
    dispatch({ type: actions.SELECT_VEHICLE, id }), []);

  const selectZone = useCallback((id) =>
    dispatch({ type: actions.SELECT_ZONE, id }), []);

  const addToast = useCallback((type, message) => {
    const id = Date.now();
    dispatch({ type: actions.ADD_TOAST, toast: { id, type, message } });
    setTimeout(() => dispatch({ type: actions.REMOVE_TOAST, id }), 4000);
  }, []);

  const setBreachCount = useCallback((count) =>
    dispatch({ type: actions.SET_BREACH_COUNT, count }), []);

  const setRestrictionCount = useCallback((count) =>
    dispatch({ type: actions.SET_RESTRICTION_COUNT, count }), []);

  const toggleNav = useCallback(() =>
    dispatch({ type: actions.TOGGLE_NAV }), []);

  const setLastValidatedRoute = useCallback((route) =>
    dispatch({ type: actions.SET_LAST_ROUTE, route }), []);

  return (
    <AppContext.Provider value={{
      state,
      openDrawer, closeDrawer,
      setMapCenter, toggleMapLayer,
      selectVehicle, selectZone,
      addToast,
      setBreachCount, setRestrictionCount,
      toggleNav,
      setLastValidatedRoute,
    }}>
      {children}
    </AppContext.Provider>
  );
}

export function useApp() {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useApp must be used within AppProvider');
  return ctx;
}
