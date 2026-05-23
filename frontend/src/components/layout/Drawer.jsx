import { useEffect, useRef } from 'react';
import { useApp } from '../../context/AppContext.jsx';
import VehicleDrawer from '../drawers/VehicleDrawer.jsx';
import ZoneDrawer from '../drawers/ZoneDrawer.jsx';
import BreachDrawer from '../drawers/BreachDrawer.jsx';
import RouteDrawer from '../drawers/RouteDrawer.jsx';
import styles from './Drawer.module.css';

const DRAWER_COMPONENTS = {
  vehicle: VehicleDrawer,
  zone: ZoneDrawer,
  breach: BreachDrawer,
  route: RouteDrawer,
};

export default function Drawer() {
  const { state, closeDrawer } = useApp();
  const { drawer } = state;
  const isOpen = !!drawer.type;
  const closeRef = useRef(null);

  // Focus close button when drawer opens
  useEffect(() => {
    if (isOpen) closeRef.current?.focus();
  }, [isOpen, drawer.type]);

  // Close on Escape
  useEffect(() => {
    if (!isOpen) return;
    const handler = (e) => { if (e.key === 'Escape') closeDrawer(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [isOpen, closeDrawer]);

  const DrawerContent = drawer.type ? DRAWER_COMPONENTS[drawer.type] : null;

  return (
    <>
      {/* Backdrop */}
      {isOpen && (
        <div
          className={styles.backdrop}
          onClick={closeDrawer}
          aria-hidden="true"
        />
      )}

      {/* Drawer panel */}
      <aside
        className={`${styles.drawer} ${isOpen ? styles.open : ''}`}
        aria-label={drawer.type ? `${drawer.type} details` : 'Details panel'}
        aria-hidden={!isOpen}
        role="complementary"
      >
        <div className={styles.drawerHeader}>
          <button
            ref={closeRef}
            className={styles.closeBtn}
            onClick={closeDrawer}
            aria-label="Close panel"
          >
            ✕
          </button>
        </div>
        <div className={styles.drawerBody}>
          {DrawerContent && <DrawerContent entityId={drawer.entityId} data={drawer.data} />}
        </div>
      </aside>
    </>
  );
}
