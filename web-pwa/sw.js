// Service Worker for KB Loan Calculator PWA
const CACHE_NAME = 'kb-calc-pwa-v12';
const ASSETS_TO_CACHE = [
  './',
  './index.html',
  './styles.css',
  './calc-engine.js',
  './admin-crm.js',
  './app.js',
  './manifest.json',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png'
];

// Install: Cache assets resiliently per-file so one flaky mobile request doesn't abort install
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return Promise.allSettled(
        ASSETS_TO_CACHE.map((asset) =>
          fetch(new Request(asset, { cache: 'no-cache' }))
            .then((resp) => {
              if (resp && resp.ok) {
                return cache.put(asset, resp);
              }
            })
            .catch(() => {})
        )
      );
    }).then(() => self.skipWaiting())
  );
});

// Activate: Clean up old caches immediately and take control
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))
      );
    }).then(() => self.clients.claim())
  );
});

// Helper: Fetch with bounded timeout so slow mobile cellular networks don't stall on blank screen
function fetchWithTimeout(request, timeoutMs = 4500) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Network timeout')), timeoutMs);
    fetch(request).then(
      (res) => {
        clearTimeout(timer);
        resolve(res);
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      }
    );
  });
}

// Fetch: Timeout-protected network-first for same-origin app shell,
// never intercept POSTs or third-party Firebase/Auth/AppsScript/OSM endpoints.
self.addEventListener('fetch', (event) => {
  const url = event.request.url;
  if (
    event.request.method !== 'GET' ||
    url.includes('script.google.com') ||
    url.includes('script.googleusercontent.com') ||
    url.includes('googleapis.com') ||
    url.includes('gstatic.com') ||
    url.includes('firebase') ||
    url.includes('accounts.google.com') ||
    url.includes('openstreetmap.org')
  ) {
    return;
  }

  if (!url.startsWith(self.location.origin)) {
    return;
  }

  event.respondWith(
    fetchWithTimeout(event.request, 4500)
      .then((networkResponse) => {
        if (networkResponse && networkResponse.status === 200) {
          const responseToCache = networkResponse.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(event.request, responseToCache).catch(() => {});
          });
        }
        return networkResponse;
      })
      .catch(() => {
        return caches.match(event.request).then((cachedResponse) => {
          if (cachedResponse) return cachedResponse;
          if (event.request.mode === 'navigate') {
            return caches.match('./index.html') || caches.match('./');
          }
          return new Response('', { status: 504, statusText: 'Offline' });
        });
      })
  );
});
