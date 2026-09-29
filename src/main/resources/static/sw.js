// Service worker: caches the app shell so the field inspection screen opens with no network.
// Pages and assets: network-first (so updates arrive), falling back to cache when offline.
// API calls are never cached here; offline data lives in IndexedDB (js/idb.js).
const CACHE = 'lm-shell-v7';
const SHELL = [
  '/officer.html', '/index.html', '/login.html', '/verify.html', '/owner.html',
  '/css/app.css', '/css/wizard.css', '/css/landing.css', '/js/i18n.js', '/js/wizard.js', '/js/api.js', '/js/idb.js', '/js/errorcalc.js', '/js/officer.js', '/js/owner.js',
  '/webjars/bootstrap/5.3.3/css/bootstrap.min.css', '/webjars/bootstrap/5.3.3/js/bootstrap.bundle.min.js',
  '/webjars/bootstrap-icons/1.11.3/font/bootstrap-icons.min.css',
  '/webjars/bootstrap-icons/1.11.3/font/fonts/bootstrap-icons.woff2',
  '/manifest.json', '/icons/icon-192.png', '/icons/icon-512.png',
  '/i18n/hi.json', '/i18n/ta.json', '/i18n/te.json', '/i18n/kn.json', '/i18n/ml.json', '/i18n/mr.json', '/i18n/bn.json',
];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim()));
});

self.addEventListener('fetch', event => {
  const req = event.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin || url.pathname.startsWith('/api/')
      || url.pathname.startsWith('/h2-console')) {
    return; // let the browser handle it normally
  }
  event.respondWith(
    fetch(req)
      .then(res => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then(c => c.put(req, copy));
        }
        return res;
      })
      .catch(() => caches.match(req, { ignoreSearch: true })
        .then(hit => hit || (req.mode === 'navigate' ? caches.match('/officer.html') : Response.error()))));
});
