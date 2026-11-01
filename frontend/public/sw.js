// Minimal PWA shell: cache-first for same-origin GET assets, pass API through.
// Versioned cache so deploys pick up fresh shells. Install only pins files
// that always exist (SPA routes like /driver are cached lazily on first
// visit, so a missing route can never fail the install).
const CACHE = 'zp-shell-v2';
const SHELL = ['/manifest.webmanifest', '/favicon.svg'];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE)
      .then((c) => Promise.allSettled(SHELL.map((u) => c.add(u))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const { request } = e;
  if (request.method !== 'GET') return; // API writes go to the offline queue, never the cache
  const url = new URL(request.url);
  if (url.pathname.startsWith('/api/')) return; // network-only for live data
  if (url.origin !== self.location.origin) return;
  e.respondWith(
    caches.match(request).then(
      (hit) => hit || fetch(request).then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(request, copy));
        }
        return res;
      }),
    ),
  );
});
