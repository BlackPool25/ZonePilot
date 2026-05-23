import { Outlet, NavLink, useNavigate } from 'react-router-dom';
import { useEffect, useRef } from 'react';
import { useApp } from '../../context/AppContext.jsx';
import { breachesApi, reportsApi } from '../../api/client.js';
import ToastContainer from '../atoms/Toast.jsx';
import Drawer from './Drawer.jsx';
import styles from './AppShell.module.css';

const NAV_ITEMS = [
  { to: '/',           icon: '⬡',  label: 'Dashboard'  },
  { to: '/vehicles',   icon: '🚛', label: 'Vehicles'   },
  { to: '/zones',      icon: '⬡',  label: 'Zones'      },
  { to: '/routes',     icon: '↗',  label: 'Routes'     },
  { to: '/breaches',   icon: '⚠',  label: 'Breaches'   },
  { to: '/reports',    icon: '📊', label: 'Reports'    },
  { to: '/simulation', icon: '▶',  label: 'Simulation' },
];

export default function AppShell() {
  const { state, toggleNav, setBreachCount, setRestrictionCount } = useApp();
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
    <div className={`${styles.shell} ${state.navExpanded ? styles.navExpanded : ''}`}>
      {/* Left Navigation */}
      <nav className={styles.nav} aria-label="Main navigation">
        <div className={styles.navHeader}>
          <button className={styles.logoBtn} onClick={toggleNav} aria-label={state.navExpanded ? 'Collapse navigation' : 'Expand navigation'}>
            <span className={styles.logoIcon} aria-hidden="true">🗺</span>
            {state.navExpanded && <span className={styles.logoText}>ZonePilot</span>}
          </button>
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
                {state.navExpanded && <span className={styles.navLabel}>{item.label}</span>}
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
            <span className={styles.navIcon} aria-hidden="true">⚙</span>
            {state.navExpanded && <span className={styles.navLabel}>Settings</span>}
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
