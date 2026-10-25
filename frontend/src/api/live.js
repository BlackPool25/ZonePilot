import { Client } from '@stomp/stompjs';

// Slice 3: live position ticks over STOMP (/topic/fleet).
// Falls back to HTTP polling when the socket is unreachable.
const API_BASE = import.meta.env.VITE_API_BASE_URL || 'https://zonepilot-backend.onrender.com';

function brokerUrl() {
  const u = new URL(API_BASE);
  u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
  u.pathname = '/ws';
  u.search = '';
  return u.toString();
}

export function connectTicks(onTick) {
  const client = new Client({
    brokerURL: brokerUrl(),
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
  });
  client.onConnect = () => {
    client.subscribe('/topic/fleet', (msg) => {
      try {
        onTick(JSON.parse(msg.body));
      } catch {
        // ignore malformed frames; the HTTP poll below covers gaps
      }
    });
  };
  client.activate();
  return () => client.deactivate();
}
