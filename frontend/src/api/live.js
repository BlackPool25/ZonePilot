import { Client } from '@stomp/stompjs';
import { tokenStore } from './client.js';

// Live pipe: per-group STOMP ticks (/topic/group/{id}) with the JWT on the
// CONNECT frame. Same-origin WS by default (VITE_API_BASE_URL override for
// dev). No polling or demo fallback: when the socket is down the offline
// banner shows instead of fake data.
function brokerUrl() {
  const base = import.meta.env.VITE_API_BASE_URL || window.location.origin;
  const u = new URL(base);
  u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
  u.pathname = '/ws';
  u.search = '';
  return u.toString();
}

export function connectTicks(groupId, onTick, onStatus) {
  const token = tokenStore.get();
  const client = new Client({
    brokerURL: brokerUrl(),
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
  });
  let sub = null;
  let backfillSub = null;
  const feed = (body) => {
    // Live tick object, or a backfill array pushed to our private queue.
    const frames = Array.isArray(body) ? body : [body];
    for (const tick of frames) {
      try {
        onTick(typeof tick === 'string' ? JSON.parse(tick) : tick);
      } catch {
        // ignore malformed frames; the next tick repairs the map
      }
    }
  };
  client.onConnect = () => {
    onStatus?.(true);
    // Reconnect backfill first: the server pushes buffered ticks missed
    // while offline to this private queue on SUBSCRIBE.
    backfillSub = client.subscribe('/user/queue/ticks-backfill', (msg) => {
      try {
        feed(JSON.parse(msg.body));
      } catch {
        // ignore malformed backfill; live ticks keep flowing
      }
    });
    sub = client.subscribe(`/topic/group/${groupId}`, (msg) => {
      try {
        feed(JSON.parse(msg.body));
      } catch {
        // ignore malformed frames; the next tick repairs the map
      }
    });
  };
  client.onWebSocketClose = () => onStatus?.(false);
  client.onStompError = () => onStatus?.(false);
  client.activate();
  return () => {
    try {
      sub?.unsubscribe();
    } catch {
      // already gone
    }
    try {
      backfillSub?.unsubscribe();
    } catch {
      // already gone
    }
    client.deactivate();
  };
}
