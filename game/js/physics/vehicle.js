// Vehicle dynamics for the Toyota Mark II X71 (GX71 chassis).
//
// A four-wheel model in the car's body frame: planar rigid body (x, z, yaw)
// plus a vertical degree of freedom for jumps and crests. Load transfer is
// split into an instant geometric part (through the roll centres) and a lagged
// elastic part (through the springs), which is why setup changes — bars, ride
// height, damping — actually change how the car rotates.
//
// Axes: +Z forward, +X right, +Y up. Everything is SI.

import { clamp, sign, wrapPi, lerp, approach, DEG } from '../core/math.js';
import { Tire, COMPOUND, SURFACE } from './tire.js';

const G = 9.81;
const RPM_TO_RAD = Math.PI / 30;
const RAD_TO_RPM = 30 / Math.PI;

// Normalised torque curve of a turbocharged Toyota straight-six.
const TORQUE_CURVE = [
  [700, 0.30], [1200, 0.44], [1800, 0.58], [2400, 0.74], [3000, 0.87],
  [3600, 0.95], [4200, 1.00], [4800, 1.00], [5400, 0.96], [6000, 0.90],
  [6600, 0.81], [7200, 0.70], [7800, 0.56], [8200, 0.42],
];

function curveLookup(curve, rpm) {
  if (rpm <= curve[0][0]) return curve[0][1];
  const last = curve[curve.length - 1];
  if (rpm >= last[0]) return last[1];
  for (let i = 1; i < curve.length; i++) {
    if (rpm <= curve[i][0]) {
      const [r0, t0] = curve[i - 1];
      const [r1, t1] = curve[i];
      return lerp(t0, t1, (rpm - r0) / (r1 - r0));
    }
  }
  return last[1];
}

/** Baseline GX71 as it leaves the garage: stock-ish, 1G-GTE, street tires. */
export function baseSetup() {
  return {
    mass: 1320,               // kg, kerb minus interior
    yawInertia: 2050,         // kg m²
    wheelbase: 2.66,
    trackFront: 1.44,
    trackRear: 1.43,
    weightDistFront: 0.535,   // front axle share of static weight
    cgHeight: 0.51,
    frontalArea: 1.94,
    dragCoefficient: 0.36,
    liftFront: -0.02,         // negative = downforce
    liftRear: -0.03,

    // Engine
    peakTorque: 170,          // Nm naturally aspirated, before boost scaling
    redline: 7200,
    limiter: 7400,
    idleRpm: 850,
    engineInertia: 0.22,
    engineBrakeCoef: 0.028,
    boostTarget: 0.55,        // bar
    boostSpoolRpm: 3200,
    turboLag: 0.55,           // seconds to ~63% of target
    boostEfficiency: 0.82,
    antiLag: false,

    // Drivetrain
    gearRatios: [3.285, 1.894, 1.275, 1.0, 0.783],
    reverseRatio: 3.768,
    finalDrive: 4.1,
    drivelineEfficiency: 0.9,
    drivelineInertia: 0.09,
    clutchCapacity: 620,      // Nm
    clutchStiffness: 95,
    shiftTime: 0.22,
    autoShift: true,

    // Limited slip differential (clutch type)
    diffPreload: 55,          // Nm
    diffPowerRamp: 0.45,      // fraction of drive torque locked under power
    diffCoastRamp: 0.25,
    diffViscous: 6.5,

    // Suspension
    springFront: 42000,       // N/m
    springRear: 38000,
    damperFront: 3600,        // N s/m
    damperRear: 3300,
    barFront: 12000,
    barRear: 9000,
    rollCentreFront: 0.09,
    rollCentreRear: 0.13,
    antiDive: 0.35,
    antiSquat: 0.30,
    rideHeightFront: 0.115,
    rideHeightRear: 0.125,

    // Steering & alignment
    maxSteerAngle: 38 * DEG,
    steerSpeed: 5.2,          // rad/s at the road wheel
    ackermann: 0.6,
    camberFront: -2.5 * DEG,
    camberRear: -1.5 * DEG,
    toeFront: 0.1 * DEG,
    toeRear: 0.25 * DEG,
    casterTrail: 0.035,

    // Wheels & tires
    wheelRadius: 0.318,
    wheelInertia: 1.15,
    tireWidthFront: 0.215,
    tireWidthRear: 0.225,
    compoundFront: 'sport',
    compoundRear: 'sport',

    // Brakes
    brakeTorqueFront: 2300,
    brakeTorqueRear: 1500,
    handbrakeTorque: 2600,
    brakeBias: 0.66,
    absEnabled: false,

    // Assists (all off = raw)
    steeringAssist: 0,
    tractionControl: 0,
    stabilityControl: 0,
  };
}

const WHEEL_ORDER = ['FL', 'FR', 'RL', 'RR'];

class Wheel {
  constructor(name, x, z, front) {
    this.name = name;
    this.x = x;              // body-frame lateral offset (+ = right)
    this.z = z;              // body-frame longitudinal offset (+ = forward)
    this.front = front;
    this.driven = !front;    // RWD
    this.tire = new Tire(COMPOUND.sport);
    this.omega = 0;          // rad/s
    this.steer = 0;
    this.camber = 0;
    this.load = 0;
    this.staticLoad = 0;
    this.compression = 0;    // m, positive = compressed
    this.compressionVel = 0;
    this.contact = true;
    this.surface = SURFACE.asphalt;
    this.fx = 0;
    this.fy = 0;
    this.radius = 0.318;
    this.locked = false;
    this.spin = 0;           // visual rotation angle
    this.worldPos = [0, 0, 0];
  }
}

export class Vehicle {
  constructor(setup = baseSetup()) {
    this.setup = { ...setup };
    this.wheels = [];
    this.telemetry = {};
    this.buildWheels();
    this.applySetup(this.setup);
    this.reset(0, 0, 0);
  }

  buildWheels() {
    const s = this.setup;
    const a = s.wheelbase * (1 - s.weightDistFront);  // CG → front axle
    const b = s.wheelbase * s.weightDistFront;        // CG → rear axle
    this.wheels = [
      new Wheel('FL', -s.trackFront / 2, a, true),
      new Wheel('FR', s.trackFront / 2, a, true),
      new Wheel('RL', -s.trackRear / 2, -b, false),
      new Wheel('RR', s.trackRear / 2, -b, false),
    ];
    this.lengthFront = a;
    this.lengthRear = b;
  }

  applySetup(setup) {
    Object.assign(this.setup, setup);
    const s = this.setup;
    this.buildWheels();
    const totalWeight = s.mass * G;
    for (const w of this.wheels) {
      w.radius = s.wheelRadius;
      w.staticLoad = totalWeight * (w.front ? s.weightDistFront : 1 - s.weightDistFront) / 2;
      w.camber = w.front ? s.camberFront : s.camberRear;
      w.tire.setCompound(
        COMPOUND[w.front ? s.compoundFront : s.compoundRear] || COMPOUND.sport,
        w.front ? s.tireWidthFront : s.tireWidthRear,
      );
    }
    this.refLoad = totalWeight / 4;
    // Roll stiffness split decides how much of the lateral transfer each axle
    // takes. More rear stiffness → more rear transfer → looser rear end.
    const kf = s.springFront * s.trackFront * s.trackFront * 0.5 + s.barFront;
    const kr = s.springRear * s.trackRear * s.trackRear * 0.5 + s.barRear;
    this.rollSplitFront = kf / (kf + kr);
    // Elastic transfer lag: stiffer and better damped → quicker.
    const wnF = Math.sqrt(s.springFront / (s.mass * 0.25));
    this.transferLag = clamp(1.6 / wnF, 0.045, 0.32);
  }

  reset(x, z, yaw, y = 0) {
    this.pos = [x, y, z];
    this.yaw = yaw;
    this.pitch = 0;
    this.roll = 0;
    this.vx = 0;             // body-frame lateral velocity
    this.vz = 0;             // body-frame forward velocity
    this.vy = 0;             // world vertical
    this.yawRate = 0;
    this.axFiltered = 0;
    this.ayFiltered = 0;
    this.ax = 0;
    this.ay = 0;
    this.gear = 1;
    this.gearIndex = 1;      // 0 = neutral, -1 = reverse
    this.shiftTimer = 0;
    this.engineRpm = this.setup.idleRpm;
    this.engineOmega = this.setup.idleRpm * RPM_TO_RAD;
    this.boost = 0;
    this.clutch = 1;         // 1 = engaged
    this.clutchTorque = 0;
    this.airborne = false;
    this.airTime = 0;
    this.groundNormal = [0, 1, 0];
    this.bodyHeight = 0;
    this.wheelSpinRate = 0;
    this.odometer = 0;
    this.steerAngle = 0;
    this.lastShift = 0;
    this.engineLoad = 0;
    this.backfire = 0;
    this.impactImpulse = 0;
    for (const w of this.wheels) {
      w.omega = 0; w.steer = 0; w.compression = 0; w.compressionVel = 0;
      w.tire.reset();
    }
  }

  get speed() { return Math.hypot(this.vx, this.vz); }
  get speedKmh() { return this.speed * 3.6; }

  /** Angle between where the car points and where it is going. */
  get slipAngle() {
    if (this.speed < 0.8) return 0;
    return Math.atan2(this.vx, Math.abs(this.vz));
  }

  get forward() { return [Math.sin(this.yaw), 0, Math.cos(this.yaw)]; }
  get right() { return [Math.cos(this.yaw), 0, -Math.sin(this.yaw)]; }

  worldVelocity() {
    const c = Math.cos(this.yaw), s = Math.sin(this.yaw);
    return [this.vz * s + this.vx * c, this.vy, this.vz * c - this.vx * s];
  }

  setWorldVelocity(vxW, vzW) {
    const c = Math.cos(this.yaw), s = Math.sin(this.yaw);
    this.vz = vxW * s + vzW * c;
    this.vx = vxW * c - vzW * s;
  }

  /* ------------------------------------------------------------ stepping -- */

  update(dt, input, world) {
    dt = clamp(dt, 0, 1 / 20);
    const substeps = dt > 1 / 90 ? 4 : 2;
    const h = dt / substeps;
    for (let i = 0; i < substeps; i++) this.step(h, input, world);
    this.updateVisuals(dt);
    this.updateTelemetry(input);
  }

  step(h, input, world) {
    const s = this.setup;
    const throttleRaw = clamp(input.throttle, 0, 1);
    const brake = clamp(input.brake, 0, 1);
    const handbrake = clamp(input.handbrake, 0, 1);

    this.updateSteering(h, input);

    // ---- ground sampling ------------------------------------------------
    const sample = world.sample(this.pos[0], this.pos[2]);
    this.groundNormal = sample.normal;
    const groundY = sample.height;
    const targetY = groundY;
    const gap = this.pos[1] - targetY;
    if (gap > 0.06) {
      this.airborne = true;
      this.airTime += h;
    } else if (this.airborne && gap <= 0.06) {
      this.airborne = false;
      this.airTime = 0;
      this.vy = Math.max(this.vy, -1.5);
      this.impactImpulse = Math.max(this.impactImpulse, Math.abs(this.vy) * 0.4);
    }
    if (this.airborne) {
      this.vy -= G * h;
      this.pos[1] += this.vy * h;
      if (this.pos[1] <= targetY) { this.pos[1] = targetY; this.vy *= -0.15; this.airborne = false; }
    } else {
      this.pos[1] = lerp(this.pos[1], targetY, clamp(h * 18, 0, 1));
      this.vy = 0;
    }

    // Gravity along the road surface — this is what makes a downhill touge run
    // pick up speed off throttle.
    const n = this.groundNormal;
    const gWorldX = G * n[1] * n[0];
    const gWorldZ = G * n[1] * n[2];
    const cy = Math.cos(this.yaw), sy = Math.sin(this.yaw);
    const gLong = gWorldX * sy + gWorldZ * cy;
    const gLat = gWorldX * cy - gWorldZ * sy;
    const normalScale = Math.max(0.2, n[1]);

    // ---- vertical loads --------------------------------------------------
    this.computeLoads(h, normalScale, brake, throttleRaw);

    // ---- drivetrain ------------------------------------------------------
    const driveTorques = this.updateDrivetrain(h, throttleRaw, input, sample);

    // ---- per-wheel tire forces ------------------------------------------
    let sumFx = 0, sumFz = 0, sumMz = 0;
    const speedAbs = Math.abs(this.vz);

    for (let i = 0; i < 4; i++) {
      const w = this.wheels[i];
      const toe = (w.front ? s.toeFront : s.toeRear) * (w.x < 0 ? 1 : -1);
      const delta = w.steer + toe;
      const cd = Math.cos(delta), sd = Math.sin(delta);

      // Contact patch velocity in body frame (v + ω × r).
      const vpx = this.vx + this.yawRate * w.z;
      const vpz = this.vz - this.yawRate * w.x;
      const vLong = vpx * sd + vpz * cd;
      const vLat = vpx * cd - vpz * sd;

      const slipAngle = Math.atan2(vLat, Math.abs(vLong) + 0.9);
      const rollSpeed = w.omega * w.radius;
      const denom = Math.max(Math.abs(vLong), 1.4);
      let slipRatio = (rollSpeed - vLong) / denom;
      slipRatio = clamp(slipRatio, -3.5, 3.5);

      const surf = w.surface = sample.surface;
      w.tire.solve(
        w.load, slipAngle, slipRatio, w.camber, surf.grip,
        vLong, h, this.refLoad,
      );

      // Brake / drive torque on the wheel.
      let brakeTorque = 0;
      const bias = w.front ? s.brakeBias : 1 - s.brakeBias;
      brakeTorque += brake * (w.front ? s.brakeTorqueFront : s.brakeTorqueRear) * bias * 2;
      if (!w.front) brakeTorque += handbrake * s.handbrakeTorque;

      // Rolling resistance grows with load and surface.
      const rollRes = surf.roll * w.load * w.radius * Math.sign(w.omega || vLong);

      let torque = driveTorques[i] - rollRes;
      const tractionTorque = -w.tire.fx * w.radius;
      const inertia = s.wheelInertia + (w.driven ? s.drivelineInertia * 0.5 : 0);

      // Brake torque is applied implicitly so a locked wheel stays locked
      // instead of oscillating around zero.
      let omega = w.omega + (torque + tractionTorque) * h / inertia;
      const brakeDecel = brakeTorque * h / inertia;
      if (Math.abs(omega) <= brakeDecel) {
        omega = 0;
        w.locked = brakeTorque > 1 && Math.abs(vLong) > 1.2;
      } else {
        omega -= Math.sign(omega) * brakeDecel;
        w.locked = false;
      }
      if (s.absEnabled && brake > 0 && Math.abs(vLong) > 3) {
        const target = vLong / w.radius;
        if (Math.abs(omega) < Math.abs(target) * 0.82) omega = target * 0.86;
        w.locked = false;
      }
      w.omega = omega;

      // Rotate tire forces from wheel frame back into body frame.
      const fLong = w.tire.fx;
      const fLat = w.tire.fy;
      const fx = fLong * sd + fLat * cd;
      const fz = fLong * cd - fLat * sd;
      w.fx = fx;
      w.fy = fz;
      sumFx += fx;
      sumFz += fz;
      sumMz += w.z * fx - w.x * fz;
      // Pneumatic trail: self-aligning torque through the steering geometry.
      if (w.front) sumMz -= fLat * s.casterTrail * cd;
    }

    // ---- aero, gravity, integration -------------------------------------
    const rho = 1.204;
    const v2 = this.vz * this.vz + this.vx * this.vx;
    const drag = 0.5 * rho * s.dragCoefficient * s.frontalArea * v2;
    const dirLong = -sign(this.vz);
    const dirLat = -sign(this.vx);
    const speedSafe = Math.max(1e-3, Math.hypot(this.vx, this.vz));
    sumFz += drag * dirLong * Math.abs(this.vz) / speedSafe;
    sumFx += drag * dirLat * Math.abs(this.vx) / speedSafe * 1.6;   // sideways drag

    const m = s.mass;
    const axBody = sumFx / m + gLat;
    const azBody = sumFz / m + gLong;

    if (this.airborne) {
      // No tire forces in the air; keep momentum, damp yaw slightly.
      this.yawRate *= Math.exp(-0.4 * h);
    } else {
      const izz = s.yawInertia;
      let yawAccel = sumMz / izz;
      if (s.stabilityControl > 0) {
        const target = this.desiredYawRate();
        yawAccel += (target - this.yawRate) * s.stabilityControl * 3.2;
      }
      this.yawRate += yawAccel * h;
    }

    // Body-frame acceleration includes the Coriolis term from the rotating frame.
    this.vx += (axBody - this.yawRate * this.vz) * h;
    this.vz += (azBody + this.yawRate * this.vx) * h;

    this.ax = axBody;
    this.ay = azBody;
    // Lagged accelerations feed the elastic share of the load transfer.
    this.axFiltered = approach(this.axFiltered, axBody, 1 / this.transferLag, h);
    this.ayFiltered = approach(this.ayFiltered, azBody, 1 / this.transferLag, h);

    this.yaw = wrapPi(this.yaw + this.yawRate * h);
    const cyaw = Math.cos(this.yaw), syaw = Math.sin(this.yaw);
    const wx = this.vz * syaw + this.vx * cyaw;
    const wz = this.vz * cyaw - this.vx * syaw;
    this.pos[0] += wx * h;
    this.pos[2] += wz * h;
    this.odometer += Math.hypot(wx, wz) * h;

    // Kill jitter when parked.
    if (Math.abs(this.vz) < 0.12 && Math.abs(this.vx) < 0.12 && this.engineLoad < 0.05) {
      this.vz *= 0.7; this.vx *= 0.7; this.yawRate *= 0.6;
    }
  }

  updateSteering(h, input) {
    const s = this.setup;
    let target = clamp(input.steer, -1, 1);

    if (s.steeringAssist > 0 && this.speed > 4) {
      // Countersteer help for pad/touch players. Opposite lock has the *same*
      // sign as the body slip angle: a car sliding with its nose left of the
      // direction of travel (slip > 0) is caught by steering right.
      const desired = clamp((this.slipAngle * 1.15 + this.yawRate * 0.25) / s.maxSteerAngle, -1, 1);
      target = lerp(target, clamp(target + desired, -1, 1), s.steeringAssist);
    }

    // Speed-sensitive rate limiting — the wheel cannot teleport to full lock.
    const rate = s.steerSpeed * (1 + 0.4 * clamp(1 - this.speed / 40, 0, 1));
    const goal = target * s.maxSteerAngle;
    const maxStep = rate * h * s.maxSteerAngle / (38 * DEG);
    this.steerAngle += clamp(goal - this.steerAngle, -maxStep, maxStep);

    // Ackermann: the inside wheel turns more.
    const d = this.steerAngle;
    const wb = s.wheelbase;
    const tr = s.trackFront;
    for (const w of this.wheels) {
      if (!w.front) continue;
      if (Math.abs(d) < 1e-4) { w.steer = 0; continue; }
      const R = wb / Math.tan(Math.abs(d));
      const inner = Math.atan(wb / Math.max(0.6, R - tr / 2));
      const outer = Math.atan(wb / (R + tr / 2));
      const isInner = (d > 0 && w.x > 0) || (d < 0 && w.x < 0);
      const ack = isInner ? inner : outer;
      w.steer = Math.sign(d) * lerp(Math.abs(d), ack, s.ackermann);
      // Camber gain with steering angle (front geometry rolls onto the shoulder).
      w.camber = (w.front ? s.camberFront : s.camberRear) - Math.abs(w.steer) * 0.12 * Math.sign(w.steer || 1) * 0;
    }
  }

  desiredYawRate() {
    const s = this.setup;
    const v = Math.max(1, Math.abs(this.vz));
    const understeerGrad = 0.0025;
    return this.steerAngle * v / (s.wheelbase + understeerGrad * v * v);
  }

  computeLoads(h, normalScale, brake, throttle) {
    const s = this.setup;
    const m = s.mass;
    const total = m * G * normalScale;
    const wb = s.wheelbase;

    // Longitudinal transfer: instant anti-dive/anti-squat share plus a lagged
    // spring share.
    const axLong = this.ayFiltered;
    const geoShare = brake > 0.05 ? s.antiDive : throttle > 0.05 ? s.antiSquat : 0.25;
    const longTransfer = (m * (this.ay * geoShare + axLong * (1 - geoShare)) * s.cgHeight) / wb;

    // Lateral transfer: geometric (roll centre) + elastic (springs & bars).
    const rcF = s.rollCentreFront, rcR = s.rollCentreRear;
    const rcAvg = lerp(rcF, rcR, 0.5);
    const latGeoF = (m * this.ax * s.weightDistFront * rcF) / s.trackFront;
    const latGeoR = (m * this.ax * (1 - s.weightDistFront) * rcR) / s.trackRear;
    const elastic = m * this.axFiltered * (s.cgHeight - rcAvg);
    const latElasticF = (elastic * this.rollSplitFront) / s.trackFront;
    const latElasticR = (elastic * (1 - this.rollSplitFront)) / s.trackRear;
    const latF = latGeoF + latElasticF;
    const latR = latGeoR + latElasticR;

    // Aero
    const v2 = this.vz * this.vz;
    const q = 0.5 * 1.204 * v2 * s.frontalArea;
    const aeroF = -s.liftFront * q;
    const aeroR = -s.liftRear * q;

    for (const w of this.wheels) {
      const axleStatic = total * (w.front ? s.weightDistFront : 1 - s.weightDistFront) / 2;
      const longSign = w.front ? -1 : 1;
      const lat = w.front ? latF : latR;
      const latSign = w.x > 0 ? -1 : 1;   // right wheel loads up in a left turn
      const aero = (w.front ? aeroF : aeroR) / 2;
      let load = axleStatic + longSign * longTransfer / 2 + latSign * lat + aero;
      if (this.airborne) load = 0;
      // A wheel cannot pull the car down; lifting an inside wheel is allowed.
      w.load = Math.max(0, load);
      // Suspension travel for the visual body attitude.
      const rate = w.front ? s.springFront : s.springRear;
      const target = w.load / rate;
      const damp = (w.front ? s.damperFront : s.damperRear) / rate;
      const err = target - w.compression;
      w.compressionVel = approach(w.compressionVel, err / Math.max(h, 1e-3), 1 / clamp(damp, 0.02, 0.4), h);
      w.compression = clamp(w.compression + err * clamp(h * 14, 0, 1), 0, 0.16);
    }
  }

  updateDrivetrain(h, throttle, input, sample) {
    const s = this.setup;
    const torques = [0, 0, 0, 0];

    // --- shifting -------------------------------------------------------
    if (this.shiftTimer > 0) this.shiftTimer -= h;
    if (input.shiftUp) this.shift(1);
    if (input.shiftDown) this.shift(-1);
    if (s.autoShift && this.shiftTimer <= 0) this.autoShift(throttle, input);

    const shifting = this.shiftTimer > 0;
    const gearRatio = this.gearRatio();
    const totalRatio = gearRatio * s.finalDrive;

    // --- boost ----------------------------------------------------------
    const spool = clamp((this.engineRpm - s.boostSpoolRpm * 0.55) / (s.boostSpoolRpm * 0.9), 0, 1);
    const demand = s.antiLag ? Math.max(throttle, 0.55) : throttle;
    const boostTarget = s.boostTarget * spool * demand;
    const lagRate = 1 / Math.max(0.08, s.turboLag * (boostTarget > this.boost ? 1 : 0.35));
    this.boost = approach(this.boost, boostTarget, lagRate, h);
    if (throttle < 0.1 && this.boost > 0.08) {
      this.backfire = Math.min(1, this.backfire + this.boost * 0.6);  // blow-off + overrun pops
    }
    this.backfire = Math.max(0, this.backfire - h * 3);

    // --- engine ---------------------------------------------------------
    const rpm = this.engineRpm;
    const limiterCut = rpm > s.limiter ? 0 : rpm > s.redline ? clamp((s.limiter - rpm) / (s.limiter - s.redline), 0.15, 1) : 1;
    const boostMul = 1 + (this.boost / 1.013) * s.boostEfficiency;
    let engineTorque = curveLookup(TORQUE_CURVE, rpm) * s.peakTorque * boostMul * throttle * limiterCut;
    // Idle governor: enough authority to hold idle against internal friction.
    if (rpm < s.idleRpm * 1.35) {
      engineTorque += clamp((s.idleRpm * 1.06 - rpm) * 0.45, 0, 140);
    }
    engineTorque -= s.engineBrakeCoef * rpm * (1 - throttle * 0.85);
    this.engineLoad = clamp(throttle * limiterCut, 0, 1);

    // --- clutch ---------------------------------------------------------
    // The pedal (or the clutch button) can only ever *reduce* engagement; an
    // auto-clutch underneath handles pulling away and stops the engine being
    // dragged to a stall, which is what a driver's left foot is actually for.
    const clutchInput = clamp(1 - (input.clutch ?? 0), 0, 1);
    const driveOmega = (this.wheels[2].omega + this.wheels[3].omega) * 0.5;
    const transOmega = driveOmega * totalRatio;
    const transRpm = Math.abs(transOmega) * RAD_TO_RPM;
    const slip = this.engineOmega - transOmega;

    // Target revs to launch at — more throttle, more clutch slip and more revs.
    const launchRpm = s.idleRpm + throttle * 2200;
    const stationary = transRpm < s.idleRpm * 0.45;
    const launching = this.gearIndex !== 0 && transRpm < launchRpm * 0.95;

    let engagement = shifting ? 0 : clutchInput;
    // At a standstill off throttle the clutch comes out entirely.
    if (stationary && throttle < 0.06) engagement *= 0;

    const capacity = s.clutchCapacity * engagement;
    let clutchTorque;
    if (launching && throttle > 0.05) {
      // Slipping clutch: it transmits up to its capacity, but never more than
      // the engine can give while still climbing toward the launch revs.
      const headroom = engineTorque
        + (this.engineOmega - launchRpm * RPM_TO_RAD) * s.engineInertia * 3;
      clutchTorque = clamp(Math.min(capacity, Math.max(0, headroom)), 0, capacity);
    } else {
      clutchTorque = clamp(slip * s.clutchStiffness * engagement, -capacity, capacity);
    }
    this.clutchTorque = clutchTorque;
    this.clutch = engagement;
    this.clutchSlip = Math.abs(slip);

    this.engineOmega += (engineTorque - clutchTorque) * h / s.engineInertia;
    this.engineOmega = Math.max(s.idleRpm * 0.55 * RPM_TO_RAD, this.engineOmega);
    this.engineRpm = this.engineOmega * RAD_TO_RPM;

    // --- differential ---------------------------------------------------
    const axleTorque = clutchTorque * totalRatio * s.drivelineEfficiency;
    const wl = this.wheels[2], wr = this.wheels[3];
    const dOmega = wl.omega - wr.omega;
    const onPower = axleTorque > 0;
    const ramp = onPower ? s.diffPowerRamp : s.diffCoastRamp;
    const lockLimit = s.diffPreload + Math.abs(axleTorque) * ramp;
    // Clutch-pack lock plus a viscous term, capped by the ramp limit.
    let lockTorque = clamp(dOmega * s.diffViscous * 12, -lockLimit, lockLimit);
    if (Math.abs(dOmega) < 0.05) lockTorque = clamp(dOmega * 400, -s.diffPreload, s.diffPreload);

    const half = axleTorque * 0.5;
    // Handbrake disconnects drive from the rears so the axle can lock up.
    const hbCut = 1 - clamp(input.handbrake, 0, 1) * 0.95;
    torques[2] = (half - lockTorque) * hbCut;
    torques[3] = (half + lockTorque) * hbCut;

    this.wheelSpinRate = Math.abs(driveOmega * s.wheelRadius) - Math.abs(this.vz);
    return torques;
  }

  gearRatio() {
    const s = this.setup;
    if (this.gearIndex === 0) return 0;
    if (this.gearIndex < 0) return -s.reverseRatio;
    return s.gearRatios[this.gearIndex - 1] ?? s.gearRatios[s.gearRatios.length - 1];
  }

  shift(dir) {
    const s = this.setup;
    if (this.shiftTimer > 0) return;
    const next = this.gearIndex + dir;
    if (next > s.gearRatios.length || next < -1) return;
    if (next === 0 && dir > 0 && this.gearIndex < 0) { this.gearIndex = 1; }
    else this.gearIndex = next;
    this.shiftTimer = s.shiftTime;
    this.lastShift = performance.now();
  }

  autoShift(throttle, input) {
    const s = this.setup;
    if (this.gearIndex < 0) {
      if (this.vz > 0.5) this.gearIndex = 1;
      return;
    }
    if (this.gearIndex === 0) { this.gearIndex = 1; return; }
    // Shift on *road* speed, not engine speed: during wheelspin the engine is
    // near the limiter in every gear and an rpm-based box hunts wildly.
    const roadOmega = Math.abs(this.vz) / Math.max(0.05, s.wheelRadius);
    const rpmIn = (g) => roadOmega * (s.gearRatios[g - 1] ?? 1) * s.finalDrive * RAD_TO_RPM;
    const up = s.redline * 0.95;
    const down = s.redline * 0.42;
    const cur = rpmIn(this.gearIndex);
    if (cur > up && this.gearIndex < s.gearRatios.length && throttle > 0.15) this.shift(1);
    else if (this.gearIndex > 1 && cur < down && rpmIn(this.gearIndex - 1) < up * 0.92) this.shift(-1);
    else if (this.vz < 0.4 && input.brake > 0.5 && this.gearIndex === 1 && Math.abs(this.vz) < 1.2) this.gearIndex = -1;
  }

  /* ------------------------------------------------------------- outputs -- */

  updateVisuals(dt) {
    const s = this.setup;
    // Body attitude from suspension travel — read the corners, not the accels,
    // so it stays consistent with the load model.
    const fl = this.wheels[0].compression, fr = this.wheels[1].compression;
    const rl = this.wheels[2].compression, rr = this.wheels[3].compression;
    const targetPitch = clamp(((fl + fr) - (rl + rr)) * 1.1, -0.09, 0.09);
    const targetRoll = clamp(((fr + rr) - (fl + rl)) * 1.35, -0.12, 0.12);
    this.pitch = approach(this.pitch, targetPitch, 9, dt);
    this.roll = approach(this.roll, targetRoll, 9, dt);
    this.bodyHeight = (s.rideHeightFront + s.rideHeightRear) * 0.5;

    const cy = Math.cos(this.yaw), sy = Math.sin(this.yaw);
    for (const w of this.wheels) {
      w.spin = (w.spin + w.omega * dt) % (Math.PI * 2);
      w.worldPos[0] = this.pos[0] + w.x * cy + w.z * sy;
      w.worldPos[1] = this.pos[1] + w.radius;
      w.worldPos[2] = this.pos[2] - w.x * sy + w.z * cy;
    }
    this.impactImpulse = Math.max(0, this.impactImpulse - dt * 2);
  }

  updateTelemetry(input) {
    const t = this.telemetry;
    t.speedKmh = this.speedKmh;
    t.rpm = this.engineRpm;
    t.gear = this.gearIndex;
    t.boost = this.boost;
    t.slipAngle = this.slipAngle;
    t.slipDeg = this.slipAngle / DEG;
    t.yawRate = this.yawRate;
    t.throttle = input.throttle;
    t.brake = input.brake;
    t.handbrake = input.handbrake;
    t.steer = this.steerAngle / this.setup.maxSteerAngle;
    t.airborne = this.airborne;
    t.wheels = this.wheels;
    t.gForceLat = this.ax / G;
    t.gForceLong = this.ay / G;
    t.rearSlipSpeed = (this.wheels[2].tire.slipSpeed + this.wheels[3].tire.slipSpeed) * 0.5;
    t.tireTemp = this.wheels.map((w) => w.tire.temp);
    t.tireWear = this.wheels.map((w) => w.tire.wear);
    t.power = (this.clutchTorque * this.engineOmega) / 1000;
  }

  /** Wall / prop collision response. `normal` points away from the surface. */
  collide(normal, penetration, restitution = 0.25) {
    const v = this.worldVelocity();
    const vn = v[0] * normal[0] + v[2] * normal[2];
    if (vn < 0) {
      const j = -(1 + restitution) * vn;
      this.setWorldVelocity(v[0] + normal[0] * j, v[2] + normal[2] * j);
      // Scrubbing along a barrier bleeds speed and kicks the car straight.
      this.vz *= 0.9;
      this.vx *= 0.75;
      this.yawRate *= 0.55;
      this.impactImpulse = Math.max(this.impactImpulse, Math.min(1, -vn / 12));
    }
    this.pos[0] += normal[0] * penetration;
    this.pos[2] += normal[2] * penetration;
    return this.impactImpulse;
  }

  /** Snapshot for ghosts and replays. */
  snapshot() {
    return {
      x: this.pos[0], y: this.pos[1], z: this.pos[2],
      yaw: this.yaw, pitch: this.pitch, roll: this.roll,
      s: this.wheels[0].steer, r: this.engineRpm,
      w: this.wheels.map((w) => w.tire.slipSpeed > 6 ? 1 : 0).reduce((a, b, i) => a | (b << i), 0),
    };
  }
}

export { COMPOUND, SURFACE };
