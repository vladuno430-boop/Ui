// Persistence. Everything lives in one localStorage key, versioned so a future
// schema change can migrate rather than wipe.

import { defaultBuild } from './tuning.js';

const KEY = 'markii-drift:profile:v1';

function freshProfile() {
  return {
    version: 1,
    createdAt: Date.now(),
    name: 'NO NAME',
    credits: 12000,
    rep: 0,
    build: defaultBuild(),
    garageName: 'X71 #1',
    completed: {},          // eventId → { score, time, stars }
    records: {},            // `${track}:${mode}` → { score, time, date }
    unlockedTracks: ['shibuya'],
    settings: {
      quality: 'medium',
      steerMode: 'touch',
      sensitivity: 1,
      masterVolume: 0.85,
      musicVolume: 0.45,
      sfxVolume: 0.9,
      vibrate: true,
      showHud: true,
      units: 'metric',
      cameraMode: 'chase',
      hudSide: 'right',
    },
    stats: {
      distance: 0,
      driftDistance: 0,
      bestDrift: 0,
      runs: 0,
      totalScore: 0,
      playTime: 0,
    },
  };
}

export class Profile {
  constructor() {
    this.data = freshProfile();
    this.load();
  }

  load() {
    try {
      const raw = localStorage.getItem(KEY);
      if (!raw) return false;
      const parsed = JSON.parse(raw);
      if (parsed && parsed.version === 1) {
        // Merge so new fields added later still get defaults.
        this.data = deepMerge(freshProfile(), parsed);
        return true;
      }
    } catch (err) {
      console.warn('Profile load failed, starting fresh', err);
    }
    return false;
  }

  save() {
    try {
      localStorage.setItem(KEY, JSON.stringify(this.data));
      return true;
    } catch (err) {
      console.warn('Profile save failed', err);
      return false;
    }
  }

  reset() {
    this.data = freshProfile();
    this.save();
  }

  get credits() { return this.data.credits; }
  set credits(v) { this.data.credits = Math.max(0, Math.round(v)); }

  addCredits(n) {
    this.data.credits = Math.max(0, Math.round(this.data.credits + n));
    this.save();
    return this.data.credits;
  }

  spend(n) {
    if (this.data.credits < n) return false;
    this.data.credits -= n;
    this.save();
    return true;
  }

  addRep(n) {
    this.data.rep += Math.round(n);
    this.save();
    return this.data.rep;
  }

  get level() {
    // Rep needed per level grows quadratically.
    return Math.floor(Math.sqrt(this.data.rep / 450)) + 1;
  }

  levelProgress() {
    const lv = this.level;
    const cur = 450 * (lv - 1) * (lv - 1);
    const next = 450 * lv * lv;
    return { level: lv, current: this.data.rep - cur, needed: next - cur };
  }

  recordRun(trackId, mode, result) {
    const key = `${trackId}:${mode}`;
    const prev = this.data.records[key];
    const better = !prev || (result.score ?? 0) > (prev.score ?? 0);
    if (better) {
      this.data.records[key] = { ...result, date: Date.now() };
    }
    const s = this.data.stats;
    s.runs++;
    s.totalScore += result.score || 0;
    s.distance += result.distance || 0;
    s.driftDistance += result.driftDistance || 0;
    s.bestDrift = Math.max(s.bestDrift, result.bestSingle || 0);
    this.save();
    return better;
  }

  completeEvent(eventId, result) {
    const prev = this.data.completed[eventId];
    if (!prev || result.stars > prev.stars || result.score > prev.score) {
      this.data.completed[eventId] = result;
    }
    this.save();
  }

  unlockTrack(id) {
    if (!this.data.unlockedTracks.includes(id)) {
      this.data.unlockedTracks.push(id);
      this.save();
    }
  }

  setSetting(key, value) {
    this.data.settings[key] = value;
    this.save();
  }
}

function deepMerge(base, override) {
  if (Array.isArray(base)) return Array.isArray(override) ? override : base;
  if (typeof base !== 'object' || base === null) {
    return override === undefined ? base : override;
  }
  const out = { ...base };
  for (const k of Object.keys(override || {})) {
    out[k] = k in base ? deepMerge(base[k], override[k]) : override[k];
  }
  return out;
}
