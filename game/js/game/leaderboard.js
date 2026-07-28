// Leaderboards and ghosts.
//
// The board has two backends. `LocalBackend` keeps everything in localStorage
// and is always available. `RemoteBackend` talks to a REST endpoint if one is
// configured (see docs/ONLINE.md) — no server ships with the game, so online
// competition is opt-in and the UI says plainly which backend it is showing.

import { rng, hashString, clamp, lerp } from '../core/math.js';

const LOCAL_KEY = 'markii-drift:boards:v1';
const ENDPOINT_KEY = 'markii-drift:endpoint';

/* ---------------------------------------------------------------- ghosts -- */

const GHOST_HZ = 20;

export class GhostRecorder {
  constructor(hz = GHOST_HZ) {
    this.interval = 1 / hz;
    this.acc = 0;
    this.frames = [];
    this.time = 0;
  }

  reset() { this.frames.length = 0; this.acc = 0; this.time = 0; }

  update(dt, vehicle) {
    this.time += dt;
    this.acc += dt;
    if (this.acc < this.interval) return;
    this.acc -= this.interval;
    const s = vehicle.snapshot();
    // Quantise hard — a two-minute ghost has to fit in localStorage.
    this.frames.push([
      Math.round(s.x * 100), Math.round(s.y * 100), Math.round(s.z * 100),
      Math.round(s.yaw * 1000), Math.round(s.pitch * 1000), Math.round(s.roll * 1000),
      Math.round(s.r), s.w,
    ]);
  }

  serialize(meta = {}) {
    return { hz: 1 / this.interval, frames: this.frames, duration: this.time, ...meta };
  }
}

export class GhostPlayer {
  constructor(data, color = [0.15, 0.85, 1.0]) {
    this.data = data;
    this.color = color;
    this.time = 0;
    this.visible = false;
    this.x = 0; this.y = 0; this.z = 0;
    this.yaw = 0; this.pitch = 0; this.roll = 0;
    this.rpm = 0;
  }

  reset() { this.time = 0; this.visible = false; }

  update(dt) {
    if (!this.data || !this.data.frames.length) return;
    this.time += dt;
    const step = 1 / (this.data.hz || GHOST_HZ);
    const idx = this.time / step;
    const i0 = Math.floor(idx);
    if (i0 >= this.data.frames.length - 1) { this.visible = false; return; }
    const f0 = this.data.frames[i0];
    const f1 = this.data.frames[i0 + 1];
    const t = idx - i0;
    this.x = lerp(f0[0], f1[0], t) / 100;
    this.y = lerp(f0[1], f1[1], t) / 100;
    this.z = lerp(f0[2], f1[2], t) / 100;
    this.yaw = lerpAngle(f0[3] / 1000, f1[3] / 1000, t);
    this.pitch = lerp(f0[4], f1[4], t) / 1000;
    this.roll = lerp(f0[5], f1[5], t) / 1000;
    this.rpm = lerp(f0[6], f1[6], t);
    this.visible = true;
  }
}

function lerpAngle(a, b, t) {
  let d = b - a;
  while (d > Math.PI) d -= Math.PI * 2;
  while (d < -Math.PI) d += Math.PI * 2;
  return a + d * t;
}

/* -------------------------------------------------------------- backends -- */

export class LocalBackend {
  constructor() {
    this.name = 'local';
    this.label = 'This device';
  }

  read() {
    try { return JSON.parse(localStorage.getItem(LOCAL_KEY) || '{}'); }
    catch { return {}; }
  }

  write(data) {
    try { localStorage.setItem(LOCAL_KEY, JSON.stringify(data)); } catch { /* quota */ }
  }

  async submit(entry) {
    const data = this.read();
    const key = `${entry.track}:${entry.mode}`;
    const list = data[key] || [];
    const existing = list.findIndex((e) => e.name === entry.name && e.local);
    if (existing >= 0) {
      if (list[existing].score >= entry.score) return { accepted: false, rank: existing + 1 };
      list.splice(existing, 1);
    }
    list.push({ ...entry, local: true, date: Date.now() });
    list.sort((a, b) => b.score - a.score);
    data[key] = list.slice(0, 50);
    this.write(data);
    return { accepted: true, rank: data[key].findIndex((e) => e === entry) + 1 };
  }

  async top(track, mode, limit = 20) {
    const data = this.read();
    return (data[`${track}:${mode}`] || []).slice(0, limit);
  }
}

export class RemoteBackend {
  constructor(endpoint) {
    this.name = 'remote';
    this.label = 'Online';
    this.endpoint = endpoint.replace(/\/$/, '');
    this.timeout = 6000;
  }

  async request(path, options = {}) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), this.timeout);
    try {
      const res = await fetch(this.endpoint + path, {
        ...options,
        signal: ctrl.signal,
        headers: { 'content-type': 'application/json', ...(options.headers || {}) },
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return await res.json();
    } finally {
      clearTimeout(timer);
    }
  }

  async submit(entry) {
    return this.request('/scores', { method: 'POST', body: JSON.stringify(entry) });
  }

  async top(track, mode, limit = 20) {
    const q = new URLSearchParams({ track, mode, limit: String(limit) });
    const data = await this.request(`/scores?${q}`);
    return Array.isArray(data) ? data : (data.entries || []);
  }

  async ghost(id) {
    return this.request(`/ghosts/${encodeURIComponent(id)}`);
  }
}

/* ----------------------------------------------------------------- board -- */

const RIVAL_NAMES = [
  'KEISUKE', 'NIGHT KIDS', 'ZENKI', 'S13 ONLY', 'TAKUMI86', 'BOSOZOKU',
  'R32 GHOST', 'KANJO', 'SHIBUYA_09', 'AE86 LVR', 'FC3S', 'JZX100',
  'DORIKIN', 'MIDNIGHT', 'C1 RUNNER', 'HACHIROKU', 'S15 SPEC-R', 'TOUGE MAX',
];

export class Leaderboard {
  constructor() {
    this.local = new LocalBackend();
    this.remote = null;
    const saved = typeof localStorage !== 'undefined' ? localStorage.getItem(ENDPOINT_KEY) : null;
    const endpoint = window.DRIFT_ONLINE_ENDPOINT || saved;
    if (endpoint) this.setEndpoint(endpoint);
    this.lastError = null;
  }

  setEndpoint(url) {
    if (!url) {
      this.remote = null;
      localStorage.removeItem(ENDPOINT_KEY);
      return;
    }
    this.remote = new RemoteBackend(url);
    try { localStorage.setItem(ENDPOINT_KEY, url); } catch { /* ignore */ }
  }

  get online() { return !!this.remote; }

  async submit(entry) {
    const results = { local: await this.local.submit(entry), remote: null };
    if (this.remote) {
      try { results.remote = await this.remote.submit(entry); this.lastError = null; }
      catch (err) { this.lastError = err.message; }
    }
    return results;
  }

  /**
   * Returns { source, entries }. Falls back to the local board, topped up with
   * deterministic offline rivals so a fresh install has something to chase.
   */
  async top(track, mode, limit = 20) {
    if (this.remote) {
      try {
        const entries = await this.remote.top(track, mode, limit);
        this.lastError = null;
        return { source: 'online', entries };
      } catch (err) {
        this.lastError = err.message;
      }
    }
    const mine = await this.local.top(track, mode, limit);
    const rivals = generateRivals(track, mode, limit);
    const merged = [...mine, ...rivals]
      .sort((a, b) => b.score - a.score)
      .slice(0, limit);
    return { source: this.remote ? 'offline-fallback' : 'offline', entries: merged };
  }
}

/** Deterministic "ghost of the machine" rivals for offline play. */
export function generateRivals(track, mode, count = 20) {
  const r = rng(hashString(`${track}|${mode}`));
  const out = [];
  const base = mode === 'career' ? 90000 : 140000;
  for (let i = 0; i < count; i++) {
    const skill = Math.pow(1 - i / (count + 2), 2.1);
    out.push({
      name: RIVAL_NAMES[Math.floor(r() * RIVAL_NAMES.length)] + (r() < 0.3 ? `_${Math.floor(r() * 90 + 10)}` : ''),
      score: Math.round(base * (0.25 + skill * 2.4) * (0.85 + r() * 0.3)),
      car: 'MARK II X71',
      angle: Math.round(38 + r() * 26),
      rival: true,
      date: Date.now() - Math.floor(r() * 6e8),
    });
  }
  return out.sort((a, b) => b.score - a.score);
}

/* ---------------------------------------------------------- ghost storage -- */

const GHOST_KEY = 'markii-drift:ghosts:v1';

export function saveGhost(track, mode, ghost) {
  try {
    const all = JSON.parse(localStorage.getItem(GHOST_KEY) || '{}');
    const key = `${track}:${mode}`;
    const prev = all[key];
    if (!prev || ghost.score > prev.score) {
      all[key] = ghost;
      localStorage.setItem(GHOST_KEY, JSON.stringify(all));
      return true;
    }
  } catch (err) {
    // Ghost data is the first thing to sacrifice when storage is full.
    console.warn('Ghost save failed', err);
  }
  return false;
}

export function loadGhost(track, mode) {
  try {
    const all = JSON.parse(localStorage.getItem(GHOST_KEY) || '{}');
    return all[`${track}:${mode}`] || null;
  } catch { return null; }
}

export function clearGhosts() {
  try { localStorage.removeItem(GHOST_KEY); } catch { /* ignore */ }
}
