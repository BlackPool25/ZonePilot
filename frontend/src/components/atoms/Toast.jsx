import { useApp } from '../../context/AppContext.jsx';
import styles from './Toast.module.css';

const ICONS = { success: '✓', error: '✕', warning: '⚠', info: 'ℹ' };

export default function ToastContainer() {
  const { state, addToast: _ } = useApp();
  return (
    <div className={styles.container} aria-live="polite" aria-atomic="false">
      {state.toasts.map(t => (
        <Toast key={t.id} toast={t} />
      ))}
    </div>
  );
}

function Toast({ toast }) {
  return (
    <div className={`${styles.toast} ${styles[toast.type]}`} role="alert">
      <span className={styles.icon} aria-hidden="true">{ICONS[toast.type] ?? 'ℹ'}</span>
      <span className={styles.message}>{toast.message}</span>
    </div>
  );
}
