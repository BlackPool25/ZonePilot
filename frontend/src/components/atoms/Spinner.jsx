import styles from './Spinner.module.css';

export function Spinner({ size = 'md', label = 'Loading…' }) {
  return (
    <span className={`${styles.spinner} ${styles[size]}`} role="status" aria-label={label} />
  );
}

export function LoadingOverlay({ label = 'Loading…' }) {
  return (
    <div className={styles.overlay} role="status" aria-label={label}>
      <Spinner size="lg" />
      <span className={styles.label}>{label}</span>
    </div>
  );
}

export function EmptyState({ icon = '📭', title, description, action }) {
  return (
    <div className={styles.empty} role="status">
      <span className={styles.emptyIcon} aria-hidden="true">{icon}</span>
      <p className={styles.emptyTitle}>{title}</p>
      {description && <p className={styles.emptyDesc}>{description}</p>}
      {action}
    </div>
  );
}

export function ErrorState({ message, onRetry }) {
  return (
    <div className={styles.empty} role="alert">
      <span className={styles.emptyIcon} aria-hidden="true">⚠️</span>
      <p className={styles.emptyTitle}>Something went wrong</p>
      <p className={styles.emptyDesc}>{message}</p>
      {onRetry && (
        <button className={styles.retryBtn} onClick={onRetry}>Try again</button>
      )}
    </div>
  );
}
