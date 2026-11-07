// Demo account helpers: decode the JWT payload client-side (no extra
// request) to identify demo logins and offer one-click demo sign-in.
import { authApi, tokenStore } from '../api/client.js';

export const DEMO_PASSWORD = 'demo1234';

export const DEMO_ACCOUNTS = [
  { label: 'Dispatcher (manager flow)', email: 'demo.disp@zonepilot.io', landing: '/' },
  { label: 'Driver One (active tour + breach)', email: 'demo.driver1@zonepilot.io', landing: '/driver' },
  { label: 'Driver Two (planned tour)', email: 'demo.driver2@zonepilot.io', landing: '/driver' },
  { label: 'Owner (full admin view)', email: 'demo.owner@zonepilot.io', landing: '/' },
];

export function tokenEmail(token) {
  if (!token) return null;
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return payload.sub || payload.email || payload.username || null;
  } catch {
    return null;
  }
}

export function isDemoEmail(email) {
  return !!email && email.startsWith('demo.');
}

export async function loginAs(email, password = DEMO_PASSWORD) {
  const data = await authApi.login(email, password);
  tokenStore.set(data.accessToken);
  return data;
}
