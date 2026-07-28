// Tire model.
//
// Pacejka-style Magic Formula with combined-slip normalisation, load
// sensitivity, a relaxation length so forces build over distance rather than
// instantly, and a simple thermal model. The thermal part matters for drifting:
// a cold tire is greasy, a tire held at big slip angles overheats and the rear
// steps out further for the same input.

import { clamp, sign } from '../core/math.js';

// Magic formula: y = D * sin(C * atan(B*x - E*(B*x - atan(B*x))))
export function magic(x, B, C, D, E) {
  const bx = B * x;
  return D * Math.sin(C * Math.atan(bx - E * (bx - Math.atan(bx))));
}

export const SURFACE = {
  asphalt: { grip: 1.00, roll: 0.014, name: 'asphalt' },
  concrete: { grip: 0.93, roll: 0.015, name: 'concrete' },
  paint: { grip: 0.86, roll: 0.013, name: 'paint' },
  kerb: { grip: 0.80, roll: 0.030, name: 'kerb' },
  dirt: { grip: 0.55, roll: 0.055, name: 'dirt' },
  grass: { grip: 0.42, roll: 0.090, name: 'grass' },
};

/**
 * Solve for the Magic Formula stiffness factor B that puts the curve's peak
 * exactly at `peak`.
 *
 * The formula peaks where C·atan(z) = π/2, i.e. z = tan(π/2C), with
 * z = Bx − E(Bx − atan(Bx)). Substituting u = Bx that is
 * (1−E)u + E·atan(u) = tan(π/2C), which is monotonic in u for E < 1, so a
 * bisection nails it. Deriving B this way means the compound tables below can
 * be written in the terms that actually matter — peak grip and the slip angle
 * it happens at — instead of opaque coefficients.
 */
export function stiffnessFor(C, E, peak) {
  const target = Math.tan(Math.PI / (2 * C));
  let lo = 1e-4, hi = 400;
  for (let i = 0; i < 64; i++) {
    const mid = (lo + hi) * 0.5;
    const f = (1 - E) * mid + E * Math.atan(mid);
    if (f < target) lo = mid; else hi = mid;
  }
  return ((lo + hi) * 0.5) / peak;
}

function compound(def) {
  return {
    ...def,
    Blat: stiffnessFor(def.Clat, def.Elat, def.peakSlipAngle),
    Blong: stiffnessFor(def.Clong, def.Elong, def.peakSlipRatio),
  };
}

// E controls how much grip is left past the peak: higher E, flatter shoulder,
// easier to hold a slide. That single number is most of what separates a
// cheap hard drift tire from a semi-slick.
export const COMPOUND = {
  street: compound({
    label: 'Street',
    muPeak: 1.00, Clat: 1.50, Elat: 0.40, Clong: 1.50, Elong: 0.40,
    peakSlipAngle: 0.175, peakSlipRatio: 0.115,   // 10.0°
    loadSens: 0.095, camberStiff: 0.85,
    optTemp: 65, tempWindow: 55, heatRate: 0.85, coolRate: 0.055,
    wearRate: 0.9, relaxation: 0.42,
  }),
  sport: compound({
    label: 'Sport',
    muPeak: 1.14, Clat: 1.55, Elat: 0.35, Clong: 1.52, Elong: 0.35,
    peakSlipAngle: 0.157, peakSlipRatio: 0.105,   // 9.0°
    loadSens: 0.088, camberStiff: 1.0,
    optTemp: 75, tempWindow: 50, heatRate: 1.0, coolRate: 0.05,
    wearRate: 1.25, relaxation: 0.36,
  }),
  semiSlick: compound({
    label: 'Semi-slick',
    muPeak: 1.30, Clat: 1.62, Elat: 0.30, Clong: 1.56, Elong: 0.30,
    peakSlipAngle: 0.140, peakSlipRatio: 0.095,   // 8.0°
    loadSens: 0.078, camberStiff: 1.15,
    optTemp: 88, tempWindow: 42, heatRate: 1.25, coolRate: 0.045,
    wearRate: 1.9, relaxation: 0.3,
  }),
  drift: compound({
    // Cheap hard-compound rears: lower peak, but the curve past the peak is
    // almost flat, which is exactly why they are easy to hold sideways.
    label: 'Drift spec',
    muPeak: 0.92, Clat: 1.32, Elat: 0.70, Clong: 1.40, Elong: 0.65,
    peakSlipAngle: 0.227, peakSlipRatio: 0.135,   // 13.0°
    loadSens: 0.07, camberStiff: 0.75,
    optTemp: 78, tempWindow: 60, heatRate: 0.7, coolRate: 0.06,
    wearRate: 0.55, relaxation: 0.48,
  }),
};

export class Tire {
  constructor(compound = COMPOUND.sport) {
    this.compound = compound;
    this.temp = 34;         // °C
    this.wear = 0;          // 0 fresh … 1 corded
    this.slipAngle = 0;     // relaxed (filtered) values used for force
    this.slipRatio = 0;
    this.rawSlipAngle = 0;
    this.rawSlipRatio = 0;
    this.fx = 0;
    this.fy = 0;
    this.load = 0;
    this.slipSpeed = 0;     // m/s of contact-patch scrub — drives smoke + noise
    this.width = 0.225;
  }

  setCompound(compound, width) {
    this.compound = compound;
    if (width) this.width = width;
  }

  // Grip multiplier from temperature: a parabola around the optimum, clipped so
  // a stone-cold or destroyed tire still has *some* grip.
  tempFactor() {
    const c = this.compound;
    const d = (this.temp - c.optTemp) / c.tempWindow;
    return clamp(1 - 0.42 * d * d, 0.75, 1.02);
  }

  wearFactor() {
    return 1 - 0.28 * this.wear * this.wear;
  }

  /**
   * @param {number} load        vertical load N (>= 0)
   * @param {number} slipAngle   rad, positive = contact patch sliding to +lateral
   * @param {number} slipRatio   (wheelSpeed - roadSpeed)/roadSpeed
   * @param {number} camber      rad, positive = top of wheel leaning out
   * @param {number} surfaceGrip surface friction multiplier
   * @param {number} vLong       longitudinal contact velocity m/s
   * @param {number} dt
   * @param {number} refLoad     static reference load N for load sensitivity
   */
  solve(load, slipAngle, slipRatio, camber, surfaceGrip, vLong, dt, refLoad) {
    const c = this.compound;
    this.load = load;
    this.rawSlipAngle = slipAngle;
    this.rawSlipRatio = slipRatio;

    if (load <= 1) {
      this.fx = 0; this.fy = 0; this.slipSpeed = 0;
      this.slipAngle *= 0.85; this.slipRatio *= 0.85;
      this.cool(dt);
      return;
    }

    // Relaxation length: forces lag behind slip by a fixed *distance*, so the
    // lag shrinks with speed. Below walking pace this also damps the numerical
    // stiffness that otherwise makes standing-still cars jitter.
    const speed = Math.abs(vLong);
    const relax = clamp((speed * dt) / c.relaxation, 0, 1);
    const lowSpeedBlend = clamp(speed / 2.5, 0, 1);
    const k = Math.max(relax, 1 - lowSpeedBlend * 0.98);
    this.slipAngle += (slipAngle - this.slipAngle) * clamp(k, 0, 1);
    this.slipRatio += (slipRatio - this.slipRatio) * clamp(Math.max(relax, 0.2), 0, 1);

    // Load sensitivity: μ falls as load rises. This is what makes weight
    // transfer matter — the loaded outside tire cannot make up for the unloaded
    // inside one.
    const loadRatio = load / Math.max(1, refLoad);
    const muLoad = 1 - c.loadSens * (loadRatio - 1);
    const camberLoss = 1 - 0.35 * Math.abs(camber) * Math.abs(camber) * 4;
    const mu = c.muPeak * surfaceGrip * Math.max(0.35, muLoad)
      * this.tempFactor() * this.wearFactor() * Math.max(0.6, camberLoss);

    // Normalised combined slip. Both axes are scaled by their own peak so the
    // friction circle stays circular in normalised space.
    const sa = this.slipAngle;
    const sr = this.slipRatio;
    const nx = sr / c.peakSlipRatio;
    const ny = Math.tan(sa) / Math.tan(c.peakSlipAngle);
    const rho = Math.hypot(nx, ny);

    let fx = 0, fy = 0;
    if (rho > 1e-5) {
      // Evaluate each axis at the combined slip magnitude, then split the
      // resulting force along the slip direction (friction-ellipse method).
      const fxMag = magic(rho * c.peakSlipRatio, c.Blong, c.Clong, mu, c.Elong);
      const fyMag = magic(rho * c.peakSlipAngle, c.Blat, c.Clat, mu, c.Elat);
      const combined = (Math.abs(nx) * fxMag + Math.abs(ny) * fyMag) / rho;
      fx = (nx / rho) * combined * load;
      fy = -(ny / rho) * combined * load;
    }

    // Camber thrust — a leaning tire generates lateral force even at zero slip.
    fy += load * mu * c.camberStiff * 0.18 * camber;

    this.fx = fx;
    this.fy = fy;

    // Scrub speed of the contact patch, used for smoke, marks and squeal.
    const vLat = Math.tan(clamp(sa, -1.4, 1.4)) * Math.abs(vLong);
    const vSlipLong = sr * Math.max(1, Math.abs(vLong));
    this.slipSpeed = Math.hypot(vLat, vSlipLong);

    this.heat(dt, mu, load);
  }

  heat(dt, mu, load) {
    const c = this.compound;
    // Friction power per unit contact area → temperature rise.
    const power = this.slipSpeed * Math.hypot(this.fx, this.fy) * 1.5e-4;
    const area = this.width / 0.225;
    this.temp += (power * c.heatRate / area) * dt;
    this.cool(dt);
    this.wear = clamp(this.wear + power * c.wearRate * 8e-5 * dt * (1 + this.tempOver()), 0, 1);
  }

  tempOver() {
    return Math.max(0, (this.temp - this.compound.optTemp - this.compound.tempWindow) / 60);
  }

  cool(dt, ambient = 22) {
    this.temp += (ambient - this.temp) * this.compound.coolRate * dt;
  }

  reset(ambient = 34) {
    this.temp = ambient;
    this.wear = 0;
    this.slipAngle = this.slipRatio = 0;
    this.fx = this.fy = 0;
  }
}

// Load transfer helper: how much of the lateral transfer an axle takes,
// given front and rear roll stiffness (springs + anti-roll bars).
export function rollStiffnessSplit(frontRate, rearRate, frontBar, rearBar) {
  const f = frontRate + frontBar;
  const r = rearRate + rearBar;
  const total = f + r || 1;
  return { front: f / total, rear: r / total };
}

export { clamp, sign };
