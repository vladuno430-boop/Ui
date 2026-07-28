// Time of day and weather. Drives every colour in the renderer plus the grip
// multiplier the physics uses, so a wet 3am run genuinely is a different car.

import { clamp, lerp, approach, TAU, DEG } from '../core/math.js';

export const WEATHER = {
  clear: {
    label: 'Clear', cloudCover: 0.08, rainRate: 0, wetness: 0,
    fogScale: 1.0, gripLoss: 0, windSpeed: 0.4,
  },
  cloudy: {
    label: 'Overcast', cloudCover: 0.72, rainRate: 0, wetness: 0.08,
    fogScale: 1.35, gripLoss: 0.03, windSpeed: 1.2,
  },
  wet: {
    label: 'Rain', cloudCover: 0.9, rainRate: 0.7, wetness: 0.85,
    fogScale: 1.45, gripLoss: 0.28, windSpeed: 2.0,
  },
  storm: {
    label: 'Downpour', cloudCover: 1.0, rainRate: 1.0, wetness: 1.0,
    fogScale: 2.1, gripLoss: 0.38, windSpeed: 4.5,
  },
  fog: {
    label: 'Sea fog', cloudCover: 0.55, rainRate: 0.05, wetness: 0.3,
    fogScale: 4.2, gripLoss: 0.12, windSpeed: 0.6,
  },
};

// Key-framed sky palette by hour. Night is the default mood of this game, so it
// gets the most keys.
const PALETTE = [
  { h: 0.0, zenith: [0.012, 0.016, 0.045], horizon: [0.055, 0.045, 0.095], ground: [0.02, 0.02, 0.03], sun: [0.32, 0.38, 0.62], glow: [0.16, 0.06, 0.14], stars: 1.0, exposure: 1.35 },
  { h: 4.0, zenith: [0.02, 0.03, 0.075], horizon: [0.11, 0.07, 0.12], ground: [0.025, 0.025, 0.035], sun: [0.35, 0.4, 0.6], glow: [0.18, 0.07, 0.15], stars: 0.85, exposure: 1.3 },
  { h: 5.4, zenith: [0.07, 0.09, 0.19], horizon: [0.42, 0.24, 0.24], ground: [0.05, 0.045, 0.05], sun: [0.9, 0.5, 0.35], glow: [0.14, 0.07, 0.12], stars: 0.25, exposure: 1.15 },
  { h: 6.6, zenith: [0.22, 0.34, 0.62], horizon: [0.85, 0.55, 0.42], ground: [0.12, 0.11, 0.11], sun: [1.5, 1.0, 0.72], glow: [0.05, 0.04, 0.05], stars: 0.0, exposure: 1.0 },
  { h: 9.0, zenith: [0.28, 0.45, 0.85], horizon: [0.62, 0.72, 0.9], ground: [0.2, 0.2, 0.21], sun: [2.0, 1.9, 1.75], glow: [0.0, 0.0, 0.0], stars: 0.0, exposure: 0.85 },
  { h: 13.0, zenith: [0.26, 0.44, 0.9], horizon: [0.7, 0.78, 0.92], ground: [0.24, 0.24, 0.25], sun: [2.4, 2.3, 2.15], glow: [0.0, 0.0, 0.0], stars: 0.0, exposure: 0.78 },
  { h: 17.0, zenith: [0.22, 0.36, 0.76], horizon: [0.78, 0.66, 0.7], ground: [0.19, 0.18, 0.18], sun: [2.1, 1.7, 1.3], glow: [0.02, 0.01, 0.02], stars: 0.0, exposure: 0.9 },
  { h: 18.6, zenith: [0.13, 0.14, 0.4], horizon: [0.95, 0.42, 0.32], ground: [0.09, 0.08, 0.09], sun: [1.8, 0.75, 0.4], glow: [0.12, 0.05, 0.1], stars: 0.1, exposure: 1.05 },
  { h: 19.8, zenith: [0.045, 0.05, 0.16], horizon: [0.36, 0.16, 0.28], ground: [0.04, 0.035, 0.05], sun: [0.7, 0.4, 0.45], glow: [0.2, 0.07, 0.18], stars: 0.55, exposure: 1.25 },
  { h: 21.5, zenith: [0.016, 0.02, 0.06], horizon: [0.09, 0.055, 0.13], ground: [0.025, 0.022, 0.035], sun: [0.34, 0.4, 0.64], glow: [0.2, 0.08, 0.19], stars: 0.95, exposure: 1.35 },
];

function samplePalette(hour) {
  const h = ((hour % 24) + 24) % 24;
  let a = PALETTE[PALETTE.length - 1], b = PALETTE[0], t = 0;
  for (let i = 0; i < PALETTE.length; i++) {
    const cur = PALETTE[i];
    const next = PALETTE[(i + 1) % PALETTE.length];
    const start = cur.h;
    const end = next.h > cur.h ? next.h : next.h + 24;
    const hh = h >= start ? h : h + 24;
    if (hh >= start && hh <= end) {
      a = cur; b = next; t = (hh - start) / (end - start);
      break;
    }
  }
  const mix3 = (x, y) => [lerp(x[0], y[0], t), lerp(x[1], y[1], t), lerp(x[2], y[2], t)];
  return {
    zenith: mix3(a.zenith, b.zenith),
    horizon: mix3(a.horizon, b.horizon),
    ground: mix3(a.ground, b.ground),
    sun: mix3(a.sun, b.sun),
    glow: mix3(a.glow, b.glow),
    stars: lerp(a.stars, b.stars, t),
    exposure: lerp(a.exposure, b.exposure, t),
  };
}

export class Environment {
  constructor(opts = {}) {
    this.hour = opts.hour ?? 22.5;
    this.timeScale = opts.timeScale ?? 0;   // game hours per real second
    this.weatherName = opts.weather ?? 'clear';
    this.target = { ...WEATHER[this.weatherName] };
    this.current = { ...this.target };
    this.transition = 0;
    this.windPhase = 0;

    this.sunDir = new Float32Array([0, 0.6, 0.8]);
    this.sunColor = new Float32Array(3);
    this.skyZenith = new Float32Array(3);
    this.skyHorizon = new Float32Array(3);
    this.skyGround = new Float32Array(3);
    this.cityGlow = new Float32Array(3);
    this.ambientSky = new Float32Array(3);
    this.ambientGround = new Float32Array(3);
    this.fogColor = new Float32Array(3);
    this.wind = new Float32Array([1, 0, 0.3]);
    this.fogDensity = 0.004;
    this.starIntensity = 1;
    this.exposure = 1.3;
    this.wetness = 0;
    this.update(0);
  }

  setWeather(name, immediate = false) {
    if (!WEATHER[name]) return;
    this.weatherName = name;
    this.target = { ...WEATHER[name] };
    if (immediate) this.current = { ...this.target };
  }

  setHour(h) { this.hour = ((h % 24) + 24) % 24; }

  /** Grip multiplier handed to the physics through the surface query. */
  get gripMultiplier() { return 1 - this.current.gripLoss; }

  update(dt) {
    this.hour = (this.hour + this.timeScale * dt) % 24;
    for (const k of Object.keys(this.target)) {
      if (typeof this.target[k] !== 'number') continue;
      this.current[k] = approach(this.current[k] ?? this.target[k], this.target[k], 0.25, dt);
    }
    this.windPhase += dt * 0.3;

    const p = samplePalette(this.hour);
    const cloud = this.current.cloudCover;
    const overcast = 1 - cloud * 0.72;

    // Sun / moon direction: a simple arc, with the moon standing in after dusk.
    const dayT = (this.hour - 6) / 12;          // 0 at sunrise, 1 at sunset
    const isDay = this.hour > 5.4 && this.hour < 18.9;
    const arc = isDay ? dayT : ((this.hour < 5.4 ? this.hour + 24 : this.hour) - 18.9) / 10.5;
    const elev = Math.sin(clamp(arc, 0, 1) * Math.PI) * (isDay ? 1.0 : 0.62) + 0.04;
    const azim = lerp(-1.2, 2.0, clamp(arc, 0, 1)) + (isDay ? 0 : 1.4);
    this.sunDir[0] = Math.cos(azim) * Math.cos(elev * 1.2);
    this.sunDir[1] = Math.max(0.05, Math.sin(elev * 1.35));
    this.sunDir[2] = Math.sin(azim) * Math.cos(elev * 1.2);
    normalize(this.sunDir);

    const sunScale = overcast * (isDay ? 1 : 0.55);
    for (let i = 0; i < 3; i++) {
      this.sunColor[i] = p.sun[i] * sunScale;
      this.skyZenith[i] = p.zenith[i] * lerp(1, 0.55, cloud);
      this.skyHorizon[i] = p.horizon[i] * lerp(1, 0.7, cloud * 0.6);
      this.skyGround[i] = p.ground[i];
      this.cityGlow[i] = p.glow[i] * lerp(1, 1.6, cloud);
      // Ambient is the sky integrated crudely: zenith above, ground bounce below.
      this.ambientSky[i] = lerp(p.zenith[i], p.horizon[i], 0.45) * 0.9 + p.glow[i] * 0.5;
      this.ambientGround[i] = p.ground[i] * 0.7 + p.glow[i] * 0.25;
      this.fogColor[i] = lerp(p.horizon[i], p.zenith[i], 0.45) * 0.75 + p.glow[i] * 0.30;
    }

    this.starIntensity = p.stars * (1 - cloud * 0.9);
    this.exposure = p.exposure * lerp(1, 0.92, cloud);
    this.fogDensity = 0.0021 * this.current.fogScale;
    this.wetness = this.current.wetness;
    this.rainRate = this.current.rainRate;
    this.cloudCover = cloud;

    const ws = this.current.windSpeed;
    this.wind[0] = Math.cos(this.windPhase * 0.7) * ws;
    this.wind[2] = Math.sin(this.windPhase * 0.5) * ws;
  }

  /** Human-readable clock for the HUD. */
  clockString() {
    const h = Math.floor(this.hour);
    const m = Math.floor((this.hour - h) * 60);
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  }

  serialize() {
    return { hour: this.hour, weather: this.weatherName, timeScale: this.timeScale };
  }
}

function normalize(v) {
  const l = Math.hypot(v[0], v[1], v[2]) || 1;
  v[0] /= l; v[1] /= l; v[2] /= l;
}
