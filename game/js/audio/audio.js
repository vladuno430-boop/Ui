// All audio is synthesised at runtime — no sample files ship with the game.
//
// The engine is an additive stack tied to firing frequency with a load-tracking
// filter, plus intake, turbo and exhaust layers. The soundtrack is a phonk
// sequencer: 808 cowbell melodies over a distorted sub, triplet hats and a
// Memphis-minor pad.

import { clamp, lerp, rng } from '../core/math.js';

function noiseBuffer(ctx, seconds = 2) {
  const len = Math.floor(ctx.sampleRate * seconds);
  const buf = ctx.createBuffer(1, len, ctx.sampleRate);
  const d = buf.getChannelData(0);
  let last = 0;
  for (let i = 0; i < len; i++) {
    const white = Math.random() * 2 - 1;
    last = (last + 0.02 * white) / 1.02;   // a touch of brown noise for weight
    d[i] = white * 0.7 + last * 3.5 * 0.3;
  }
  return buf;
}

function makeDistortion(ctx, amount = 40) {
  const shaper = ctx.createWaveShaper();
  const n = 1024;
  const curve = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const x = (i / (n - 1)) * 2 - 1;
    curve[i] = ((1 + amount) * x) / (1 + amount * Math.abs(x));
  }
  shaper.curve = curve;
  shaper.oversample = '2x';
  return shaper;
}

/* ---------------------------------------------------------------- engine -- */

export class CarAudio {
  constructor(ctx, master) {
    this.ctx = ctx;
    this.enabled = true;
    this.bus = ctx.createGain();
    this.bus.gain.value = 1;
    this.bus.connect(master);

    this.noise = noiseBuffer(ctx, 3);

    // --- engine core: harmonic stack over the firing frequency ---
    this.engineGain = ctx.createGain();
    this.engineGain.gain.value = 0;
    this.engineFilter = ctx.createBiquadFilter();
    this.engineFilter.type = 'lowpass';
    this.engineFilter.frequency.value = 900;
    this.engineFilter.Q.value = 1.2;
    this.engineShaper = makeDistortion(ctx, 18);
    this.engineGain.connect(this.engineShaper);
    this.engineShaper.connect(this.engineFilter);
    this.engineFilter.connect(this.bus);

    this.oscillators = [];
    // A straight six fires three times per revolution; the half-order and the
    // second order are what give it the smooth, slightly whistly character.
    const partials = [
      { mul: 0.5, gain: 0.30, type: 'sawtooth' },
      { mul: 1.0, gain: 1.00, type: 'sawtooth' },
      { mul: 2.0, gain: 0.42, type: 'square' },
      { mul: 3.0, gain: 0.20, type: 'sawtooth' },
      { mul: 4.5, gain: 0.10, type: 'square' },
    ];
    for (const p of partials) {
      const osc = ctx.createOscillator();
      osc.type = p.type;
      osc.frequency.value = 60;
      const g = ctx.createGain();
      g.gain.value = p.gain;
      osc.connect(g);
      g.connect(this.engineGain);
      osc.start();
      this.oscillators.push({ osc, gain: g, mul: p.mul, base: p.gain });
    }

    // --- mechanical rumble ---
    this.rumble = ctx.createBufferSource();
    this.rumble.buffer = this.noise;
    this.rumble.loop = true;
    this.rumbleFilter = ctx.createBiquadFilter();
    this.rumbleFilter.type = 'bandpass';
    this.rumbleFilter.frequency.value = 120;
    this.rumbleFilter.Q.value = 1.4;
    this.rumbleGain = ctx.createGain();
    this.rumbleGain.gain.value = 0;
    this.rumble.connect(this.rumbleFilter);
    this.rumbleFilter.connect(this.rumbleGain);
    this.rumbleGain.connect(this.bus);
    this.rumble.start();

    // --- turbo whistle ---
    this.turbo = ctx.createOscillator();
    this.turbo.type = 'sine';
    this.turbo.frequency.value = 2400;
    this.turboGain = ctx.createGain();
    this.turboGain.gain.value = 0;
    this.turboFilter = ctx.createBiquadFilter();
    this.turboFilter.type = 'bandpass';
    this.turboFilter.frequency.value = 3200;
    this.turboFilter.Q.value = 6;
    this.turbo.connect(this.turboGain);
    this.turboGain.connect(this.turboFilter);
    this.turboFilter.connect(this.bus);
    this.turbo.start();

    // --- tire scrub / squeal ---
    this.tireSource = ctx.createBufferSource();
    this.tireSource.buffer = this.noise;
    this.tireSource.loop = true;
    this.tireBand = ctx.createBiquadFilter();
    this.tireBand.type = 'bandpass';
    this.tireBand.frequency.value = 1150;
    this.tireBand.Q.value = 7.5;
    this.tireGain = ctx.createGain();
    this.tireGain.gain.value = 0;
    this.tireScrub = ctx.createBiquadFilter();
    this.tireScrub.type = 'bandpass';
    this.tireScrub.frequency.value = 320;
    this.tireScrub.Q.value = 2;
    this.scrubGain = ctx.createGain();
    this.scrubGain.gain.value = 0;
    this.tireSource.connect(this.tireBand);
    this.tireBand.connect(this.tireGain);
    this.tireGain.connect(this.bus);
    this.tireSource.connect(this.tireScrub);
    this.tireScrub.connect(this.scrubGain);
    this.scrubGain.connect(this.bus);
    this.tireSource.start();

    // --- wind ---
    this.windSource = ctx.createBufferSource();
    this.windSource.buffer = this.noise;
    this.windSource.loop = true;
    this.windFilter = ctx.createBiquadFilter();
    this.windFilter.type = 'lowpass';
    this.windFilter.frequency.value = 700;
    this.windGain = ctx.createGain();
    this.windGain.gain.value = 0;
    this.windSource.connect(this.windFilter);
    this.windFilter.connect(this.windGain);
    this.windGain.connect(this.bus);
    this.windSource.start();

    this.lastBackfire = 0;
    this.lastShift = 0;
  }

  setVolume(v) { this.bus.gain.value = v; }

  /** @param {object} t telemetry from the vehicle */
  update(t, dt, env) {
    if (!this.enabled) return;
    const ctx = this.ctx;
    const now = ctx.currentTime;
    const smooth = 0.06;

    const rpm = clamp(t.rpm, 500, 8600);
    const fire = (rpm / 60) * 3;          // straight-six firing frequency
    const load = clamp(t.throttle * 0.75 + (t.boost || 0) * 0.5, 0, 1);

    for (const o of this.oscillators) {
      o.osc.frequency.setTargetAtTime(fire * o.mul, now, smooth * 0.5);
      // Under load the upper orders come forward; off throttle it goes hollow.
      const shape = o.mul >= 2 ? 0.45 + load * 0.9 : 1.0;
      o.gain.gain.setTargetAtTime(o.base * shape, now, smooth);
    }
    const engineLevel = 0.10 + load * 0.16 + clamp(rpm / 8000, 0, 1) * 0.06;
    this.engineGain.gain.setTargetAtTime(engineLevel, now, smooth);
    this.engineFilter.frequency.setTargetAtTime(
      420 + load * 2600 + clamp(rpm / 8000, 0, 1) * 2200, now, smooth);

    this.rumbleFilter.frequency.setTargetAtTime(70 + fire * 0.6, now, smooth);
    this.rumbleGain.gain.setTargetAtTime(0.05 + load * 0.09, now, smooth);

    const boost = t.boost || 0;
    this.turbo.frequency.setTargetAtTime(1800 + boost * 3200 + rpm * 0.32, now, 0.08);
    this.turboGain.gain.setTargetAtTime(clamp(boost, 0, 1.5) * 0.045 * t.throttle, now, 0.08);

    // Squeal rises with scrub speed and tightens as the tire gets hot.
    const slip = t.rearSlipSpeed || 0;
    const squeal = clamp((slip - 1.2) / 12, 0, 1);
    const front = t.wheels ? (t.wheels[0].tire.slipSpeed + t.wheels[1].tire.slipSpeed) * 0.5 : 0;
    const frontSqueal = clamp((front - 1.5) / 12, 0, 1);
    const total = Math.max(squeal, frontSqueal);
    const wet = env ? env.wetness : 0;
    this.tireGain.gain.setTargetAtTime(total * 0.16 * (1 - wet * 0.6), now, 0.05);
    this.tireBand.frequency.setTargetAtTime(880 + total * 900 + Math.sin(now * 7) * 60, now, 0.05);
    this.scrubGain.gain.setTargetAtTime(total * 0.1 + (wet > 0.3 ? total * 0.12 : 0), now, 0.05);

    const speed = t.speedKmh / 3.6;
    this.windGain.gain.setTargetAtTime(clamp(speed / 70, 0, 1) * 0.07, now, 0.12);
    this.windFilter.frequency.setTargetAtTime(320 + speed * 24, now, 0.12);
  }

  backfire(strength = 1) {
    if (!this.enabled) return;
    const ctx = this.ctx;
    const now = ctx.currentTime;
    if (now - this.lastBackfire < 0.06) return;
    this.lastBackfire = now;
    const src = ctx.createBufferSource();
    src.buffer = this.noise;
    src.playbackRate.value = 0.8 + Math.random() * 0.5;
    const filt = ctx.createBiquadFilter();
    filt.type = 'bandpass';
    filt.frequency.value = 260 + Math.random() * 400;
    filt.Q.value = 1.1;
    const g = ctx.createGain();
    const peak = 0.35 * strength;
    g.gain.setValueAtTime(peak, now);
    g.gain.exponentialRampToValueAtTime(0.001, now + 0.14);
    src.connect(filt); filt.connect(g); g.connect(this.bus);
    src.start(now);
    src.stop(now + 0.2);
  }

  blowOff(strength = 1) {
    if (!this.enabled) return;
    const ctx = this.ctx;
    const now = ctx.currentTime;
    const src = ctx.createBufferSource();
    src.buffer = this.noise;
    const filt = ctx.createBiquadFilter();
    filt.type = 'bandpass';
    filt.frequency.setValueAtTime(4200, now);
    filt.frequency.exponentialRampToValueAtTime(900, now + 0.28);
    filt.Q.value = 4;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.001, now);
    g.gain.linearRampToValueAtTime(0.16 * strength, now + 0.02);
    g.gain.exponentialRampToValueAtTime(0.001, now + 0.3);
    src.connect(filt); filt.connect(g); g.connect(this.bus);
    src.start(now);
    src.stop(now + 0.35);
  }

  impact(strength = 1) {
    if (!this.enabled) return;
    const ctx = this.ctx;
    const now = ctx.currentTime;
    const osc = ctx.createOscillator();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(160, now);
    osc.frequency.exponentialRampToValueAtTime(45, now + 0.25);
    const g = ctx.createGain();
    g.gain.setValueAtTime(clamp(strength, 0, 1) * 0.5, now);
    g.gain.exponentialRampToValueAtTime(0.001, now + 0.4);
    osc.connect(g); g.connect(this.bus);
    osc.start(now); osc.stop(now + 0.45);

    const src = ctx.createBufferSource();
    src.buffer = this.noise;
    const filt = ctx.createBiquadFilter();
    filt.type = 'bandpass';
    filt.frequency.value = 1800;
    const ng = ctx.createGain();
    ng.gain.setValueAtTime(clamp(strength, 0, 1) * 0.3, now);
    ng.gain.exponentialRampToValueAtTime(0.001, now + 0.18);
    src.connect(filt); filt.connect(ng); ng.connect(this.bus);
    src.start(now); src.stop(now + 0.2);
  }

  shift() {
    if (!this.enabled) return;
    const ctx = this.ctx;
    const now = ctx.currentTime;
    const src = ctx.createBufferSource();
    src.buffer = this.noise;
    const filt = ctx.createBiquadFilter();
    filt.type = 'highpass';
    filt.frequency.value = 2600;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.08, now);
    g.gain.exponentialRampToValueAtTime(0.001, now + 0.07);
    src.connect(filt); filt.connect(g); g.connect(this.bus);
    src.start(now); src.stop(now + 0.09);
  }

  chime(freq = 880, duration = 0.12, gain = 0.12) {
    if (!this.enabled) return;
    const ctx = this.ctx;
    const now = ctx.currentTime;
    const osc = ctx.createOscillator();
    osc.type = 'triangle';
    osc.frequency.value = freq;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, now);
    g.gain.linearRampToValueAtTime(gain, now + 0.01);
    g.gain.exponentialRampToValueAtTime(0.0001, now + duration);
    osc.connect(g); g.connect(this.bus);
    osc.start(now); osc.stop(now + duration + 0.02);
  }
}

/* ------------------------------------------------------------------ phonk -- */

// Memphis minor: root, b2, b3, 4, 5, b6, b7 — the scale the genre lives on.
const SCALE = [0, 1, 3, 5, 7, 8, 10];
const ROOTS = [46, 44, 49, 42];   // MIDI notes, dark register

const midiToHz = (n) => 440 * Math.pow(2, (n - 69) / 12);

export class PhonkPlayer {
  constructor(ctx, master) {
    this.ctx = ctx;
    this.bus = ctx.createGain();
    this.bus.gain.value = 0.55;
    this.compressor = ctx.createDynamicsCompressor();
    this.compressor.threshold.value = -14;
    this.compressor.ratio.value = 6;
    this.compressor.attack.value = 0.004;
    this.compressor.release.value = 0.16;
    this.bus.connect(this.compressor);
    this.compressor.connect(master);

    this.noise = noiseBuffer(ctx, 2);
    this.playing = false;
    this.bpm = 138;
    this.step = 0;
    this.nextNoteTime = 0;
    this.lookahead = 0.12;
    this.timer = null;
    this.trackIndex = 0;
    this.tracks = [];
    this.buildTracks();
    this.duck = 1;
  }

  buildTracks() {
    const names = [
      'NIGHT RUNNER', 'KANSAI DRIFT', 'TOUGE MEMORY',
      'CHROME TEARS', 'X71 SLIDE', 'YOKOHAMA BASS',
    ];
    for (let i = 0; i < names.length; i++) {
      const r = rng(1000 + i * 77);
      const root = ROOTS[i % ROOTS.length] + (r() < 0.4 ? -2 : 0);
      const melody = [];
      // 32-step cowbell line: sparse, syncopated, repeats every 16.
      for (let s = 0; s < 32; s++) {
        const beat = s % 16;
        const on = beat === 0 || beat === 3 || beat === 6 || beat === 8
          || beat === 11 || (r() < 0.18);
        melody.push(on ? SCALE[Math.floor(r() * SCALE.length)] + (r() < 0.25 ? 12 : 0) : null);
      }
      const bass = [];
      for (let s = 0; s < 32; s++) {
        const on = s % 8 === 0 || s % 8 === 6 || (r() < 0.1);
        bass.push(on ? SCALE[Math.floor(r() * 4)] : null);
      }
      this.tracks.push({
        name: names[i],
        bpm: 130 + Math.floor(r() * 22),
        root,
        melody,
        bass,
        hatDensity: 0.45 + r() * 0.4,
        rollChance: 0.12 + r() * 0.16,
        padChord: [0, 3, 7, 10],
      });
    }
  }

  get currentTrackName() { return this.tracks[this.trackIndex].name; }

  setVolume(v) { this.bus.gain.value = v; }

  start() {
    if (this.playing) return;
    this.playing = true;
    this.step = 0;
    this.nextNoteTime = this.ctx.currentTime + 0.08;
    this.bpm = this.tracks[this.trackIndex].bpm;
    this.timer = setInterval(() => this.schedule(), 25);
  }

  stop() {
    this.playing = false;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  next() {
    this.trackIndex = (this.trackIndex + 1) % this.tracks.length;
    this.bpm = this.tracks[this.trackIndex].bpm;
    this.step = 0;
    return this.currentTrackName;
  }

  select(i) {
    this.trackIndex = ((i % this.tracks.length) + this.tracks.length) % this.tracks.length;
    this.bpm = this.tracks[this.trackIndex].bpm;
    return this.currentTrackName;
  }

  schedule() {
    if (!this.playing) return;
    const secondsPerStep = 60 / this.bpm / 4;   // sixteenths
    while (this.nextNoteTime < this.ctx.currentTime + this.lookahead) {
      this.playStep(this.step, this.nextNoteTime, secondsPerStep);
      this.nextNoteTime += secondsPerStep;
      this.step = (this.step + 1) % 32;
    }
  }

  playStep(step, time, dur) {
    const t = this.tracks[this.trackIndex];
    const beat = step % 16;

    if (beat === 0 || beat === 6 || beat === 10) this.kick(time);
    if (beat === 4 || beat === 12) this.snare(time);

    // Hats, with the occasional triplet roll.
    if (Math.random() < t.hatDensity) this.hat(time, 0.035);
    if (step % 4 === 3 && Math.random() < t.rollChance) {
      for (let i = 1; i < 4; i++) this.hat(time + (dur * i) / 4, 0.02);
    }

    const m = t.melody[step];
    if (m !== null && m !== undefined) this.cowbell(midiToHz(t.root + 24 + m), time);

    const b = t.bass[step];
    if (b !== null && b !== undefined) this.sub(midiToHz(t.root - 12 + b), time, dur * 3.2);

    if (step % 16 === 0) this.pad(t, time, dur * 14);
  }

  cowbell(freq, time) {
    // Two detuned squares through a bandpass — the classic 808 cowbell recipe.
    const ctx = this.ctx;
    const g = ctx.createGain();
    const band = ctx.createBiquadFilter();
    band.type = 'bandpass';
    band.frequency.value = freq * 1.6;
    band.Q.value = 3.2;
    const shaper = makeDistortion(ctx, 26);
    g.connect(shaper); shaper.connect(band); band.connect(this.bus);
    g.gain.setValueAtTime(0.0001, time);
    g.gain.linearRampToValueAtTime(0.22 * this.duck, time + 0.004);
    g.gain.exponentialRampToValueAtTime(0.0001, time + 0.34);
    for (const mul of [1.0, 1.4983]) {
      const o = ctx.createOscillator();
      o.type = 'square';
      o.frequency.value = freq * mul;
      o.connect(g);
      o.start(time);
      o.stop(time + 0.36);
    }
  }

  sub(freq, time, len) {
    const ctx = this.ctx;
    const o = ctx.createOscillator();
    o.type = 'sine';
    o.frequency.setValueAtTime(freq * 1.6, time);
    o.frequency.exponentialRampToValueAtTime(freq, time + 0.06);
    const shaper = makeDistortion(ctx, 8);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, time);
    g.gain.linearRampToValueAtTime(0.42 * this.duck, time + 0.01);
    g.gain.exponentialRampToValueAtTime(0.0001, time + len);
    o.connect(shaper); shaper.connect(g); g.connect(this.bus);
    o.start(time); o.stop(time + len + 0.05);
  }

  kick(time) {
    const ctx = this.ctx;
    const o = ctx.createOscillator();
    o.type = 'sine';
    o.frequency.setValueAtTime(120, time);
    o.frequency.exponentialRampToValueAtTime(42, time + 0.1);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.5 * this.duck, time);
    g.gain.exponentialRampToValueAtTime(0.0001, time + 0.3);
    o.connect(g); g.connect(this.bus);
    o.start(time); o.stop(time + 0.32);
  }

  snare(time) {
    const ctx = this.ctx;
    const src = ctx.createBufferSource();
    src.buffer = this.noise;
    const f = ctx.createBiquadFilter();
    f.type = 'bandpass';
    f.frequency.value = 1900;
    f.Q.value = 0.9;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.28 * this.duck, time);
    g.gain.exponentialRampToValueAtTime(0.0001, time + 0.16);
    src.connect(f); f.connect(g); g.connect(this.bus);
    src.start(time); src.stop(time + 0.18);
  }

  hat(time, level) {
    const ctx = this.ctx;
    const src = ctx.createBufferSource();
    src.buffer = this.noise;
    src.playbackRate.value = 1.6;
    const f = ctx.createBiquadFilter();
    f.type = 'highpass';
    f.frequency.value = 7200;
    const g = ctx.createGain();
    g.gain.setValueAtTime(level * this.duck, time);
    g.gain.exponentialRampToValueAtTime(0.0001, time + 0.05);
    src.connect(f); f.connect(g); g.connect(this.bus);
    src.start(time); src.stop(time + 0.06);
  }

  pad(track, time, len) {
    const ctx = this.ctx;
    const filter = ctx.createBiquadFilter();
    filter.type = 'lowpass';
    filter.frequency.setValueAtTime(500, time);
    filter.frequency.linearRampToValueAtTime(1600, time + len * 0.5);
    filter.Q.value = 3;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, time);
    g.gain.linearRampToValueAtTime(0.055 * this.duck, time + len * 0.25);
    g.gain.exponentialRampToValueAtTime(0.0001, time + len);
    filter.connect(g); g.connect(this.bus);
    for (const semi of track.padChord) {
      const o = ctx.createOscillator();
      o.type = 'sawtooth';
      o.frequency.value = midiToHz(track.root + semi);
      o.detune.value = (Math.random() - 0.5) * 14;
      o.connect(filter);
      o.start(time); o.stop(time + len + 0.1);
    }
  }
}

/* ------------------------------------------------------------------- hub -- */

export class AudioHub {
  constructor() {
    this.ctx = null;
    this.master = null;
    this.car = null;
    this.music = null;
    this.ready = false;
    this.masterVolume = 0.85;
    this.sfxVolume = 0.9;
    this.musicVolume = 0.5;
  }

  async init() {
    if (this.ready) return true;
    const Ctor = window.AudioContext || window.webkitAudioContext;
    if (!Ctor) return false;
    this.ctx = new Ctor({ latencyHint: 'interactive' });
    this.master = this.ctx.createGain();
    this.master.gain.value = this.masterVolume;
    const limiter = this.ctx.createDynamicsCompressor();
    limiter.threshold.value = -6;
    limiter.ratio.value = 12;
    limiter.attack.value = 0.002;
    limiter.release.value = 0.12;
    this.master.connect(limiter);
    limiter.connect(this.ctx.destination);
    this.car = new CarAudio(this.ctx, this.master);
    this.music = new PhonkPlayer(this.ctx, this.master);
    this.car.setVolume(this.sfxVolume);
    this.music.setVolume(this.musicVolume);
    this.ready = true;
    return true;
  }

  async resume() {
    if (!this.ready) await this.init();
    if (this.ctx && this.ctx.state === 'suspended') await this.ctx.resume();
  }

  setMaster(v) {
    this.masterVolume = clamp(v, 0, 1);
    if (this.master) this.master.gain.value = this.masterVolume;
  }

  setSfx(v) {
    this.sfxVolume = clamp(v, 0, 1);
    if (this.car) this.car.setVolume(this.sfxVolume);
  }

  setMusic(v) {
    this.musicVolume = clamp(v, 0, 1);
    if (this.music) this.music.setVolume(this.musicVolume);
  }

  suspend() {
    if (this.ctx && this.ctx.state === 'running') this.ctx.suspend();
  }
}
