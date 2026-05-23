import styles from './Badge.module.css';

/**
 * @param {'green'|'amber'|'red'|'blue'|'gray'|'purple'} variant
 * @param {'sm'|'md'} size
 */
export default function Badge({ children, variant = 'gray', size = 'md', dot = false }) {
  return (
    <span className={`${styles.badge} ${styles[variant]} ${styles[size]}`} aria-label={typeof children === 'string' ? children : undefined}>
      {dot && <span className={styles.dot} aria-hidden="true" />}
      {children}
    </span>
  );
}
