// Drift scoring.
//
// Points accumulate while the car is sideways, scaled by angle, speed and how
// close it runs to the clipping zone on the inside of the corner. Linking
// corners without straightening builds the multiplier; spinning, stopping or
// hitting something drops the bank.

import { clamp, lerp, DEG } from '../core/math.js';

const MIN_ANGLE = 9 * DEG;
const IDEAL_LOW = 32 * DEG;
const IDEAL_HIGH = 62 * DEG;
const SPIN_ANGLE = 96 * DEG;
const MIN_SPEED = 26 / 3.6;      // m/s — below this a slide stops counting

export class DriftScorer {
  constructor(track) {
    this.track = track;
    this.reset();
  }

  reset() {
    this.total = 0;
    this.pending = 0;
    this.multiplier = 1;
    this.active = false;
    this.driftTime = 0;
    this.straightTime = 0;
    this.lastSign = 0;
    this.transitions = 0;
    this.best = 0;
    this.bestSingle = 0;
    this.events = [];
    this.zoneHits = new Set();
    this.zoneFlash = 0;
    this.failReason = null;
    this.failTimer = 0;
    this.angle = 0;
    this.rate = 0;
    this.proximity = 0;
    this.lastBank = 0;
    this.peakAngle = 0;
    this.peakSpeed = 0;
    this.wallHits = 0;
    this.distance = 0;
  }

  push(type, data) {
    this.events.push({ type, ...data });
    if (this.events.length > 32) this.events.shift();
  }

  /**
   * @param {number} dt
   * @param {Vehicle} vehicle
   * @param {object} surfaceInfo result of track.sample at the car
   * @param {number} impact wall impact strength this frame
   */
  update(dt, vehicle, surfaceInfo, impact) {
    const speed = vehicle.speed;
    const angle = Math.abs(vehicle.slipAngle);
    this.angle = vehicle.slipAngle;
    this.failTimer = Math.max(0, this.failTimer - dt);
    this.zoneFlash = Math.max(0, this.zoneFlash - dt);
    this.distance += speed * dt;

    if (impact > 0.06) {
      this.wallHits++;
      this.fail('CONTACT');
      return;
    }

    // Spinning out ends the run outright.
    if (angle > SPIN_ANGLE && speed > 6) {
      this.fail('SPUN');
      return;
    }

    const drifting = angle > MIN_ANGLE && speed > MIN_SPEED && !vehicle.airborne
      && surfaceInfo.onTrack;

    if (drifting) {
      this.straightTime = 0;
      if (!this.active) {
        this.active = true;
        this.driftTime = 0;
        this.lastSign = Math.sign(vehicle.slipAngle);
        this.push('start', {});
      }
      const sign = Math.sign(vehicle.slipAngle);
      if (sign !== 0 && sign !== this.lastSign && angle > 14 * DEG) {
        this.lastSign = sign;
        this.transitions++;
        this.multiplier = Math.min(9.9, this.multiplier + 0.55);
        this.push('transition', { multiplier: this.multiplier });
      }

      this.driftTime += dt;
      this.peakAngle = Math.max(this.peakAngle, angle);
      this.peakSpeed = Math.max(this.peakSpeed, speed);

      // Angle window: too little is not a drift, too much is a spin waiting to
      // happen, and the sweet spot in between pays the most.
      let angleFactor;
      if (angle < IDEAL_LOW) angleFactor = (angle - MIN_ANGLE) / (IDEAL_LOW - MIN_ANGLE);
      else if (angle <= IDEAL_HIGH) angleFactor = 1;
      else angleFactor = clamp(1 - (angle - IDEAL_HIGH) / (SPIN_ANGLE - IDEAL_HIGH), 0.15, 1);
      angleFactor = clamp(angleFactor, 0, 1);

      const speedFactor = clamp(speed / 27, 0.35, 2.1);
      this.proximity = this.zoneProximity(vehicle);
      const proximityFactor = 1 + this.proximity * 0.85;

      this.rate = 52 * angleFactor * speedFactor * proximityFactor;
      this.pending += this.rate * dt;

      // Multiplier creeps up the longer the slide is held.
      this.multiplier = Math.min(9.9, this.multiplier + dt * 0.16);
    } else {
      if (this.active) {
        this.straightTime += dt;
        const stopping = speed <= MIN_SPEED || !surfaceInfo.onTrack;
        if (this.straightTime > (stopping ? 0.25 : 0.85)) this.bank();
      }
      this.rate = 0;
    }
  }

  zoneProximity(vehicle) {
    const zones = this.track?.clippingZones;
    if (!zones || !zones.length) return 0;
    let best = 0;
    for (let i = 0; i < zones.length; i++) {
      const z = zones[i];
      const dx = vehicle.pos[0] - z.x;
      const dz = vehicle.pos[2] - z.z;
      const d = Math.hypot(dx, dz);
      if (d > z.radius * 2.2) continue;
      const p = clamp(1 - d / (z.radius * 2.2), 0, 1) * z.strength;
      if (p > best) best = p;
      if (d < z.radius && !this.zoneHits.has(i)) {
        this.zoneHits.add(i);
        this.pending += 220 * z.strength;
        this.zoneFlash = 1;
        this.push('zone', { index: i });
      }
    }
    return clamp(best, 0, 1.4);
  }

  bank() {
    if (this.pending > 1) {
      const gained = Math.round(this.pending * this.multiplier);
      this.total += gained;
      this.lastBank = gained;
      this.bestSingle = Math.max(this.bestSingle, gained);
      this.push('bank', { amount: gained, multiplier: this.multiplier });
    }
    this.pending = 0;
    this.multiplier = 1;
    this.active = false;
    this.driftTime = 0;
    this.transitions = 0;
    this.zoneHits.clear();
  }

  fail(reason) {
    if (this.pending > 1 || this.active) {
      this.push('fail', { reason, lost: Math.round(this.pending * this.multiplier) });
    }
    this.failReason = reason;
    this.failTimer = 1.6;
    this.pending = 0;
    this.multiplier = 1;
    this.active = false;
    this.driftTime = 0;
    this.transitions = 0;
    this.zoneHits.clear();
  }

  /** Score as displayed: banked plus what is currently at risk. */
  get displayTotal() {
    return this.total + Math.round(this.pending * this.multiplier);
  }

  get grade() {
    const s = this.total;
    if (s > 260000) return 'SS';
    if (s > 160000) return 'S';
    if (s > 95000) return 'A';
    if (s > 52000) return 'B';
    if (s > 24000) return 'C';
    return 'D';
  }
}

/* ------------------------------------------------------------ lap timing -- */

export class LapTimer {
  constructor(track) {
    this.track = track;
    this.reset();
  }

  reset() {
    this.lap = 0;
    this.lapTime = 0;
    this.lastLap = null;
    this.bestLap = null;
    this.laps = [];
    this.lastProgress = 0;
    this.started = false;
    this.totalTime = 0;
  }

  update(dt, progress) {
    this.totalTime += dt;
    if (!this.started) {
      this.started = true;
      this.lastProgress = progress;
      return false;
    }
    this.lapTime += dt;
    const crossed = progress < 0.25 && this.lastProgress > 0.75;
    this.lastProgress = progress;
    if (crossed && this.lapTime > 8) {
      this.lap++;
      this.lastLap = this.lapTime;
      this.laps.push(this.lapTime);
      if (this.bestLap === null || this.lapTime < this.bestLap) this.bestLap = this.lapTime;
      this.lapTime = 0;
      return true;
    }
    return false;
  }
}

export function formatTime(seconds) {
  if (seconds === null || seconds === undefined) return '--:--.---';
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  const ms = Math.floor((seconds % 1) * 1000);
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}.${String(ms).padStart(3, '0')}`;
}

export function formatScore(n) {
  return Math.round(n).toLocaleString('en-US');
}
