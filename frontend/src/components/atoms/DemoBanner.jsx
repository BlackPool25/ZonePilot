import { Link } from 'react-router-dom';
import { tokenEmail, isDemoEmail } from '../../utils/demoUser.js';
import { tokenStore } from '../../api/client.js';
import styles from './DemoBanner.module.css';

export default function DemoBanner() {
  const email = tokenEmail(tokenStore.get());
  if (!isDemoEmail(email)) return null;
  return (
    <div className={styles.banner} role="status">
      <span aria-hidden="true">🧪</span>
      <span>
        Demo mode — signed in as <strong>{email}</strong> (password <code>demo1234</code>).
        Data resets when the database is reseeded.
      </span>
      <Link className={styles.link} to="/replay">Replay a tour →</Link>
    </div>
  );
}
