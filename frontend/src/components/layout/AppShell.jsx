import { Outlet, NavLink, useNavigate } from 'react-router-dom';
import { useEffect, useRef } from 'react';
import { useApp } from '../../context/AppContext.jsx';
import { breachesApi, reportsApi } from '../../api/client.js';
import ToastContainer from '../atoms/Toast.jsx';
import Drawer from './Drawer.jsx';
import styles from './AppShell.module.css';

const ICONS = {
  dashboard: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="3" width="7" height="9" rx="1.5" />
      <rect x="14" y="3" width="7" height="5" rx="1.5" />
      <rect x="14" y="12" width="7" height="9" rx="1.5" />
      <rect x="3" y="16" width="7" height="5" rx="1.5" />
    </svg>
  ),
  vehicles: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 18H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2z" />
      <path d="M16 8h4a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-4" />
      <circle cx="7.5" cy="18.5" r="2" />
      <circle cx="16.5" cy="18.5" r="2" />
    </svg>
  ),
  zones: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="12 2 22 8.5 22 15.5 12 22 2 15.5 2 8.5 12 2" />
    </svg>
  ),
  routes: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="19" r="3" />
      <circle cx="18" cy="5" r="3" />
      <path d="M9 19h4a2 2 0 0 0 2-2V7a2 2 0 0 1 2-2h1" />
    </svg>
  ),
  breaches: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
      <line x1="12" y1="9" x2="12" y2="13" />
      <line x1="12" y1="17" x2="12.01" y2="17" />
    </svg>
  ),
  reports: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <line x1="18" y1="20" x2="18" y2="10" />
      <line x1="12" y1="20" x2="12" y2="4" />
      <line x1="6" y1="20" x2="6" y2="14" />
    </svg>
  ),
  simulation: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="6 3 20 12 6 21 6 3" />
    </svg>
  ),
  settings: (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  ),
  logo: (
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ color: 'var(--brand-500)' }}>
      <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
      <circle cx="12" cy="10" r="3" />
    </svg>
  )
};

const NAV_ITEMS = [
  { to: '/',           icon: ICONS.dashboard,  label: 'Dashboard'  },
  { to: '/vehicles',   icon: ICONS.vehicles,   label: 'Vehicles'   },
  { to: '/zones',      icon: ICONS.zones,      label: 'Zones'      },
  { to: '/routes',     icon: ICONS.routes,     label: 'Routes'     },
  { to: '/breaches',   icon: ICONS.breaches,   label: 'Breaches'   },
  { to: '/reports',    icon: ICONS.reports,    label: 'Reports'    },
  { to: '/simulation', icon: ICONS.simulation, label: 'Simulation' },
];

export default function AppShell() {
  const { state, setBreachCount, setRestrictionCount } = useApp();
  const navigate = useNavigate();
  const pollRef = useRef(null);

  // Poll unacknowledged breaches and active restrictions
  useEffect(() => {
    async function poll() {
      try {
        const [breaches, restrictions] = await Promise.allSettled([
          breachesApi.list({ unacknowledged: true }),
          reportsApi.activeRestrictions(),
        ]);
        if (breaches.status === 'fulfilled') setBreachCount(breaches.value?.length ?? 0);
        if (restrictions.status === 'fulfilled') setRestrictionCount(restrictions.value?.length ?? 0);
      } catch { /* silent — backend may be offline */ }
    }
    poll();
    pollRef.current = setInterval(poll, 30000);
    return () => clearInterval(pollRef.current);
  }, [setBreachCount, setRestrictionCount]);

  return (
    <div className={`${styles.shell} ${styles.navExpanded}`}>
      {/* Left Navigation */}
      <nav className={styles.nav} aria-label="Main navigation">
        <div className={styles.navHeader}>
          <div className={styles.logoBtn} style={{ cursor: 'default' }}>
            <span className={styles.logoIcon} aria-hidden="true">{ICONS.logo}</span>
            <span className={styles.logoText}>ZonePilot</span>
          </div>
        </div>

        <ul className={styles.navList} role="list">
          {NAV_ITEMS.map(item => (
            <li key={item.to}>
              <NavLink
                to={item.to}
                end={item.to === '/'}
                className={({ isActive }) => `${styles.navItem} ${isActive ? styles.active : ''}`}
                aria-label={item.label}
              >
                <span className={styles.navIcon} aria-hidden="true">{item.icon}</span>
                <span className={styles.navLabel}>{item.label}</span>
                {item.to === '/breaches' && state.unacknowledgedBreachCount > 0 && (
                  <span className={styles.navBadge} aria-label={`${state.unacknowledgedBreachCount} unacknowledged breaches`}>
                    {state.unacknowledgedBreachCount > 9 ? '9+' : state.unacknowledgedBreachCount}
                  </span>
                )}
              </NavLink>
            </li>
          ))}
        </ul>

        <div className={styles.navFooter}>
          <button className={styles.navItem} aria-label="Settings" onClick={() => {}}>
            <span className={styles.navIcon} aria-hidden="true">{ICONS.settings}</span>
            <span className={styles.navLabel}>Settings</span>
          </button>
        </div>
      </nav>

      {/* Main area */}
      <div className={styles.main}>
        {/* Top Bar */}
        <header className={styles.topbar} role="banner">
          <div className={styles.topbarLeft}>
            <span className={styles.pageTitle} aria-live="polite" />
          </div>
          <div className={styles.topbarRight}>
            {state.activeRestrictionsCount > 0 && (
              <button
                className={styles.restrictionPill}
                onClick={() => navigate('/zones')}
                aria-label={`${state.activeRestrictionsCount} active restrictions`}
              >
                <span className={styles.restrictionDot} aria-hidden="true" />
                {state.activeRestrictionsCount} active restriction{state.activeRestrictionsCount !== 1 ? 's' : ''}
              </button>
            )}
            {state.unacknowledgedBreachCount > 0 && (
              <button
                className={styles.breachPill}
                onClick={() => navigate('/breaches')}
                aria-label={`${state.unacknowledgedBreachCount} unacknowledged breaches`}
              >
                ⚠ {state.unacknowledgedBreachCount} breach{state.unacknowledgedBreachCount !== 1 ? 'es' : ''}
              </button>
            )}
            <div className={styles.userAvatar} aria-label="User menu" role="img">FO</div>
          </div>
        </header>

        {/* Page content */}
        <main className={styles.content} id="main-content" tabIndex={-1}>
          <Outlet />
        </main>
      </div>

      {/* Right Drawer */}
      <Drawer />

      {/* Toast notifications */}
      <ToastContainer />
    </div>
  );
}
