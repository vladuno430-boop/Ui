// Cameras. The chase view tracks the car's *velocity* rather than its heading,
// which is what keeps a big drift readable instead of swinging the world around.

import { vec3, mat4, clamp, lerp, approach, wrapPi, DEG, TAU } from '../core/math.js';
import { DRIVER_EYE } from '../render/car-model.js';

export const CAMERA_MODES = ['chase', 'chaseFar', 'cockpit', 'bumper', 'cinematic'];

export const CAMERA_LABELS = {
  chase: 'Chase',
  chaseFar: 'Wide chase',
  cockpit: 'Cockpit',
  bumper: 'Bonnet',
  cinematic: 'Cinematic',
};

export class Camera {
  constructor() {
    this.mode = 'chase';
    this.position = vec3.create(0, 3, -8);
    this.target = vec3.create(0, 1, 0);
    this.up = vec3.create(0, 1, 0);
    this.upVector = vec3.create(0, 1, 0);
    this.right = vec3.create(1, 0, 0);
    this.fov = 62 * DEG;
    this.baseFov = 62 * DEG;
    this.shake = 0;
    this.roll = 0;
    this._yaw = 0;
    this._smoothPos = vec3.create();
    this._smoothTarget = vec3.create();
    this._cineTimer = 0;
    this._cineIndex = 0;
    this._cinePos = vec3.create();
    this._initialised = false;
    this.orbitAngle = 0;
    this.headTilt = 0;
  }

  setMode(mode) {
    if (!CAMERA_MODES.includes(mode)) return;
    this.mode = mode;
    this._initialised = false;
    this._cineTimer = 0;
  }

  cycle(dir = 1) {
    const i = CAMERA_MODES.indexOf(this.mode);
    this.setMode(CAMERA_MODES[(i + dir + CAMERA_MODES.length) % CAMERA_MODES.length]);
    return this.mode;
  }

  addShake(amount) {
    this.shake = Math.min(1.2, this.shake + amount);
  }

  update(dt, vehicle, track, opts = {}) {
    const speed = vehicle.speed;
    const slip = vehicle.slipAngle;

    switch (this.mode) {
      case 'cockpit': this.updateCockpit(dt, vehicle); break;
      case 'bumper': this.updateBumper(dt, vehicle); break;
      case 'cinematic': this.updateCinematic(dt, vehicle, track); break;
      case 'chaseFar': this.updateChase(dt, vehicle, 8.6, 3.4, 0.5); break;
      default: this.updateChase(dt, vehicle, 6.1, 2.35, 1.0); break;
    }

    // Speed-sensitive FOV: the world opens up as the car gets going.
    const targetFov = this.baseFov + clamp(speed / 62, 0, 1) * 14 * DEG
      + Math.abs(slip) * (this.mode === 'cockpit' ? 2 : 7) * DEG;
    this.fov = approach(this.fov, targetFov, 4, dt);

    // Impact and kerb shake.
    this.shake = Math.max(0, this.shake - dt * 1.9);
    if (this.shake > 0.001) {
      const s = this.shake * this.shake * 0.42;
      const t = performance.now() * 0.001;
      this.position[0] += Math.sin(t * 47.3) * s;
      this.position[1] += Math.sin(t * 61.7) * s * 0.7;
      this.position[2] += Math.cos(t * 53.1) * s;
    }

    this.recomputeBasis();
  }

  recomputeBasis() {
    const dir = vec3.sub(vec3.create(), this.target, this.position);
    vec3.normalize(dir, dir);
    const worldUp = [Math.sin(this.roll), Math.cos(this.roll), 0];
    // Roll the up vector around the view direction.
    const r = vec3.cross(vec3.create(), dir, [0, 1, 0]);
    vec3.normalize(r, r);
    const u = vec3.cross(vec3.create(), r, dir);
    const cr = Math.cos(this.roll), sr = Math.sin(this.roll);
    this.right[0] = r[0] * cr + u[0] * sr;
    this.right[1] = r[1] * cr + u[1] * sr;
    this.right[2] = r[2] * cr + u[2] * sr;
    this.upVector[0] = u[0] * cr - r[0] * sr;
    this.upVector[1] = u[1] * cr - r[1] * sr;
    this.upVector[2] = u[2] * cr - r[2] * sr;
    vec3.copy(this.up, this.upVector);
  }

  updateChase(dt, vehicle, distance, height, agility) {
    const speed = vehicle.speed;
    const v = vehicle.worldVelocity();
    const moving = speed > 3;
    // Blend between where the car points and where it is travelling.
    const headingYaw = vehicle.yaw;
    const velYaw = moving ? Math.atan2(v[0], v[2]) : headingYaw;
    const blend = clamp((speed - 3) / 14, 0, 1) * 0.72;
    let desiredYaw = headingYaw + wrapPi(velYaw - headingYaw) * blend;

    // Lead the corner slightly by the yaw rate — the camera "looks in".
    desiredYaw += clamp(vehicle.yawRate * 0.22, -0.35, 0.35);

    if (!this._initialised) { this._yaw = desiredYaw; this._initialised = true; }
    const rate = lerp(2.6, 6.5, agility) * (1 + clamp(speed / 55, 0, 1));
    this._yaw += wrapPi(desiredYaw - this._yaw) * clamp(dt * rate, 0, 1);

    const dist = distance + clamp(speed / 60, 0, 1) * 1.6;
    const h = height + clamp(speed / 70, 0, 1) * 0.5 + Math.abs(vehicle.slipAngle) * 0.5;
    const px = vehicle.pos[0] - Math.sin(this._yaw) * dist;
    const pz = vehicle.pos[2] - Math.cos(this._yaw) * dist;
    const py = vehicle.pos[1] + h;

    const smooth = clamp(dt * lerp(6, 13, agility), 0, 1);
    this.position[0] = lerp(this.position[0], px, smooth);
    this.position[1] = lerp(this.position[1], py, clamp(dt * 6, 0, 1));
    this.position[2] = lerp(this.position[2], pz, smooth);

    // Aim slightly ahead of the car along its velocity.
    const lead = clamp(speed * 0.12, 0, 3.4);
    const tx = vehicle.pos[0] + Math.sin(this._yaw) * lead;
    const tz = vehicle.pos[2] + Math.cos(this._yaw) * lead;
    this.target[0] = lerp(this.target[0], tx, clamp(dt * 9, 0, 1));
    this.target[1] = lerp(this.target[1], vehicle.pos[1] + 1.05, clamp(dt * 7, 0, 1));
    this.target[2] = lerp(this.target[2], tz, clamp(dt * 9, 0, 1));

    this.roll = approach(this.roll, clamp(-vehicle.yawRate * 0.06 - vehicle.roll * 0.25, -0.09, 0.09), 6, dt);
  }

  updateCockpit(dt, vehicle) {
    const cy = Math.cos(vehicle.yaw), sy = Math.sin(vehicle.yaw);
    const eye = DRIVER_EYE;
    // Body attitude moves the driver's head with the car.
    const ox = eye[0], oy = eye[1] + vehicle.pitch * 0.4, oz = eye[2];
    this.position[0] = vehicle.pos[0] + ox * cy + oz * sy;
    this.position[1] = vehicle.pos[1] + oy;
    this.position[2] = vehicle.pos[2] - ox * sy + oz * cy;

    // The driver looks into the corner: eyes lead the slip angle.
    const look = clamp(-vehicle.slipAngle * 1.25 + vehicle.yawRate * 0.1, -0.95, 0.95);
    this.headTilt = approach(this.headTilt, look, 7, dt);
    const yaw = vehicle.yaw + this.headTilt;
    const dist = 8;
    this.target[0] = this.position[0] + Math.sin(yaw) * dist;
    this.target[1] = this.position[1] + 0.1 - vehicle.pitch * 2.2;
    this.target[2] = this.position[2] + Math.cos(yaw) * dist;
    this.roll = approach(this.roll, vehicle.roll * 0.8, 8, dt);
    this.baseFov = 68 * DEG;
  }

  updateBumper(dt, vehicle) {
    const cy = Math.cos(vehicle.yaw), sy = Math.sin(vehicle.yaw);
    const oz = 1.05, oy = 1.02;
    this.position[0] = vehicle.pos[0] + oz * sy;
    this.position[1] = vehicle.pos[1] + oy + vehicle.pitch * 0.3;
    this.position[2] = vehicle.pos[2] + oz * cy;
    const yaw = vehicle.yaw + clamp(-vehicle.slipAngle * 0.6, -0.5, 0.5);
    this.target[0] = this.position[0] + Math.sin(yaw) * 8;
    this.target[1] = this.position[1] - vehicle.pitch * 2.0;
    this.target[2] = this.position[2] + Math.cos(yaw) * 8;
    this.roll = approach(this.roll, vehicle.roll * 0.5, 8, dt);
    this.baseFov = 64 * DEG;
  }

  updateCinematic(dt, vehicle, track) {
    this._cineTimer -= dt;
    const dist = vec3.dist(this.position, vehicle.pos);
    if (this._cineTimer <= 0 || dist > 95) {
      this.pickCinematicSpot(vehicle, track);
      this._cineTimer = 4.5 + Math.random() * 3.5;
    }
    // Slow dolly toward the action.
    const drift = 0.5 * dt;
    this._cinePos[0] += (vehicle.pos[0] - this._cinePos[0]) * 0.0;
    this.position[0] = lerp(this.position[0], this._cinePos[0], clamp(dt * 1.2, 0, 1));
    this.position[1] = lerp(this.position[1], this._cinePos[1], clamp(dt * 1.2, 0, 1));
    this.position[2] = lerp(this.position[2], this._cinePos[2], clamp(dt * 1.2, 0, 1));
    const v = vehicle.worldVelocity();
    this.target[0] = lerp(this.target[0], vehicle.pos[0] + v[0] * 0.25, clamp(dt * 4, 0, 1));
    this.target[1] = lerp(this.target[1], vehicle.pos[1] + 0.8, clamp(dt * 4, 0, 1));
    this.target[2] = lerp(this.target[2], vehicle.pos[2] + v[2] * 0.25, clamp(dt * 4, 0, 1));
    this.baseFov = lerp(34 * DEG, 52 * DEG, clamp(dist / 60, 0, 1));
    this.roll = approach(this.roll, 0.02, 3, dt);
  }

  pickCinematicSpot(vehicle, track) {
    const pick = Math.random();
    const v = vehicle.worldVelocity();
    const heading = Math.atan2(v[0], v[2]);
    if (track && pick < 0.45) {
      // Trackside: stand off to one side, ahead of the car.
      const info = track.sample(vehicle.pos[0], vehicle.pos[2]);
      const ahead = (info.segment + 8) % track.segments.length;
      const s = track.segments[ahead];
      const side = Math.random() < 0.5 ? -1 : 1;
      const off = (s.width / 2 + 3.5 + Math.random() * 5) * side;
      this._cinePos[0] = s.p[0] + s.lateral[0] * off;
      this._cinePos[1] = s.p[1] + 0.9 + Math.random() * 1.6;
      this._cinePos[2] = s.p[2] + s.lateral[2] * off;
    } else if (pick < 0.72) {
      // Low and close, wheel height.
      const a = heading + Math.PI + (Math.random() - 0.5) * 1.6;
      const d = 5 + Math.random() * 4;
      this._cinePos[0] = vehicle.pos[0] + Math.sin(a) * d;
      this._cinePos[1] = vehicle.pos[1] + 0.35 + Math.random() * 0.4;
      this._cinePos[2] = vehicle.pos[2] + Math.cos(a) * d;
    } else {
      // Helicopter.
      const a = Math.random() * TAU;
      const d = 12 + Math.random() * 14;
      this._cinePos[0] = vehicle.pos[0] + Math.sin(a) * d;
      this._cinePos[1] = vehicle.pos[1] + 9 + Math.random() * 8;
      this._cinePos[2] = vehicle.pos[2] + Math.cos(a) * d;
    }
  }

  /**
   * Slow turntable used in the garage. `lateral` slides the whole rig sideways
   * so the car sits clear of a menu panel instead of behind it.
   */
  orbit(dt, centre, radius = 7.2, height = 2.1, speed = 0.18, lateral = 0) {
    this.orbitAngle += dt * speed;
    const s = Math.sin(this.orbitAngle), c = Math.cos(this.orbitAngle);
    this.position[0] = centre[0] + s * radius;
    this.position[1] = centre[1] + height;
    this.position[2] = centre[2] + c * radius;
    vec3.set(this.target, centre[0], centre[1] + 0.65, centre[2]);
    if (lateral !== 0) {
      // Camera right, in the ground plane: perpendicular to the view direction.
      const rx = c, rz = -s;
      this.position[0] += rx * lateral;
      this.position[2] += rz * lateral;
      this.target[0] += rx * lateral;
      this.target[2] += rz * lateral;
    }
    this.roll = 0;
    this.fov = 38 * DEG;
    this.recomputeBasis();
  }
}
