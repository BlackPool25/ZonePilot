import styles from './Button.module.css';

/**
 * @param {'primary'|'secondary'|'ghost'|'danger'} variant
 * @param {'sm'|'md'|'lg'} size
 */
export default function Button({
  children, variant = 'secondary', size = 'md',
  disabled = false, loading = false, icon, iconOnly = false,
  onClick, type = 'button', className = '', ...rest
}) {
  return (
    <button
      type={type}
      className={`${styles.btn} ${styles[variant]} ${styles[size]} ${iconOnly ? styles.iconOnly : ''} ${className}`}
      disabled={disabled || loading}
      onClick={onClick}
      aria-busy={loading}
      {...rest}
    >
      {loading ? <span className={styles.spinner} aria-hidden="true" /> : icon && <span className={styles.icon} aria-hidden="true">{icon}</span>}
      {!iconOnly && <span>{children}</span>}
    </button>
  );
}
