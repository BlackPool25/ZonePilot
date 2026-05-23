import { createBrowserRouter } from 'react-router-dom';
import AppShell from './components/layout/AppShell.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Vehicles from './pages/Vehicles.jsx';
import Zones from './pages/Zones.jsx';
import Routes from './pages/Routes.jsx';
import Breaches from './pages/Breaches.jsx';
import Reports from './pages/Reports.jsx';
import Simulation from './pages/Simulation.jsx';

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
      { path: 'simulation', element: <Simulation /> },
    ],
  },
]);
