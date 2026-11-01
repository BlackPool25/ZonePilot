import { createBrowserRouter } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import AppShell from './components/layout/AppShell.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Vehicles from './pages/Vehicles.jsx';
import Zones from './pages/Zones.jsx';
import Routes from './pages/Routes.jsx';
import Breaches from './pages/Breaches.jsx';
import Reports from './pages/Reports.jsx';
import Driver from './pages/Driver.jsx';
import Groups from './pages/Groups.jsx';

// Lazy: Replay pulls its own chunk so the initial bundle stays under budget.
const Replay = lazy(() => import('./pages/Replay.jsx'));

const replayElement = (
  <Suspense fallback={<p style={{ padding: 24 }}>Loading replay…</p>}>
    <Replay />
  </Suspense>
);

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Dashboard /> },
      { path: 'vehicles', element: <Vehicles /> },
      { path: 'zones', element: <Zones /> },
      { path: 'routes', element: <Routes /> },
      { path: 'breaches', element: <Breaches /> },
      { path: 'reports', element: <Reports /> },
      { path: 'replay', element: replayElement },
      { path: 'driver', element: <Driver /> },
      { path: 'groups', element: <Groups /> },
    ],
  },
]);
