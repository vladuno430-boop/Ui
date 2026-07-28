// Offline cache. The game has no runtime downloads, so precaching the shell is
// enough to make it fully playable with the network off — which is the point of
// installing it to a phone.

const VERSION = 'x71-night-run-v1';

const SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './css/game.css',
  './assets/icon.svg',
  './js/main.js',
  './js/core/math.js',
  './js/core/gl.js',
  './js/core/geometry.js',
  './js/physics/tire.js',
  './js/physics/vehicle.js',
  './js/render/shaders.js',
  './js/render/renderer.js',
  './js/render/environment.js',
  './js/render/effects.js',
  './js/render/car-model.js',
  './js/world/track.js',
  './js/world/tracks.js',
  './js/world/scenery.js',
  './js/game/camera.js',
  './js/game/input.js',
  './js/game/scoring.js',
  './js/game/tuning.js',
  './js/game/career.js',
  './js/game/save.js',
  './js/game/leaderboard.js',
  './js/audio/audio.js',
  './js/ui/ui.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(VERSION)
      .then((cache) => cache.addAll(SHELL))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;   // never cache leaderboard traffic

  event.respondWith(
    caches.match(req).then((hit) => {
      if (hit) {
        // Refresh in the background so an updated build lands next launch.
        fetch(req).then((res) => {
          if (res.ok) caches.open(VERSION).then((c) => c.put(req, res.clone()));
        }).catch(() => { });
        return hit;
      }
      return fetch(req).then((res) => {
        if (res.ok) {
          const clone = res.clone();
          caches.open(VERSION).then((c) => c.put(req, clone));
        }
        return res;
      }).catch(() => caches.match('./index.html'));
    }),
  );
});
