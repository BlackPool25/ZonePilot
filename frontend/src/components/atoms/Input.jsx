import styles from './Input.module.css';

export default function Input({
  label, id, error, hint, prefix, suffix,
  className = '', ...props
}) {
  return (
    <div className={`${styles.field} ${className}`}>
      {label && <label htmlFor={id} className={styles.label}>{label}</label>}
      <div className={`${styles.inputWrap} ${error ? styles.hasError : ''}`}>
        {prefix && <span className={styles.prefix} aria-hidden="true">{prefix}</span>}
        <input id={id} className={styles.input} aria-describedby={error ? `${id}-err` : hint ? `${id}-hint` : undefined} aria-invalid={!!error} {...props} />
        {suffix && <span className={styles.suffix} aria-hidden="true">{suffix}</span>}
      </div>
      {error && <span id={`${id}-err`} className={styles.error} role="alert">{error}</span>}
      {hint && !error && <span id={`${id}-hint`} className={styles.hint}>{hint}</span>}
    </div>
  );
}

export function Select({ label, id, error, children, className = '', ...props }) {
  return (
    <div className={`${styles.field} ${className}`}>
      {label && <label htmlFor={id} className={styles.label}>{label}</label>}
      <div className={`${styles.inputWrap} ${error ? styles.hasError : ''}`}>
        <select id={id} className={`${styles.input} ${styles.select}`} aria-invalid={!!error} {...props}>
          {children}
        </select>
        <span className={styles.selectArrow} aria-hidden="true">▾</span>
      </div>
      {error && <span className={styles.error} role="alert">{error}</span>}
    </div>
  );
}
