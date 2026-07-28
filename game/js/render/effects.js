// Tire smoke, rubber laid on the road, and rain.
//
// All three are dynamic geometry written straight into GPU buffers each frame:
// smoke and rain as instanced billboards, marks as a ring buffer of quads that
// fade out on their own in the vertex shader.

import { Mesh } from '../core/gl.js';
import { clamp, lerp, rng, TAU } from '../core/math.js';

/* ----------------------------------------------------------------- smoke -- */

const MAX_SMOKE = 700;
const SMOKE_FLOATS = 10;   // posSize(4) colorAlpha(4) rotSpin(2)

export class SmokeSystem {
  constructor(ctx) {
    this.ctx = ctx;
    this.particles = [];
    this.instanceData = new Float32Array(MAX_SMOKE * SMOKE_FLOATS);
    this.random = rng(9187);
    const corners = new Float32Array([-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1]);
    this.mesh = new Mesh(ctx, {
      vertices: corners,
      layout: [{ index: 0, size: 2, offset: 0 }],
      stride: 8,
      instanceLayout: {
        data: this.instanceData,
        stride: SMOKE_FLOATS * 4,
        layout: [
          { index: 1, size: 4, offset: 0 },
          { index: 2, size: 4, offset: 16 },
          { index: 3, size: 2, offset: 32 },
        ],
      },
    });
    this.count = 0;
  }

  /**
   * @param {number[]} pos world position of the contact patch
   * @param {number} rate 0..1 how hard the tire is scrubbing
   * @param {number[]} dir world direction the smoke gets kicked in
   * @param {number[]} tint colour picked up from nearby lights
   */
  emit(pos, rate, dir, tint, dt, wetness = 0) {
    // Wet tarmac makes spray instead of smoke: shorter lived, brighter, faster.
    const spray = wetness > 0.35;
    const perSecond = rate * (spray ? 70 : 95);
    let n = perSecond * dt;
    let count = Math.floor(n);
    if (this.random() < n - count) count++;
    for (let i = 0; i < count; i++) {
      if (this.particles.length >= MAX_SMOKE) this.particles.shift();
      const r = this.random;
      const speed = spray ? 3.5 + rate * 5 : 1.2 + rate * 4.5;
      this.particles.push({
        x: pos[0] + (r() - 0.5) * 0.35,
        y: pos[1] + 0.04 + r() * 0.1,
        z: pos[2] + (r() - 0.5) * 0.35,
        vx: dir[0] * speed * (0.5 + r()) + (r() - 0.5) * 1.2,
        vy: (spray ? 1.4 : 0.7) * (0.4 + r()),
        vz: dir[2] * speed * (0.5 + r()) + (r() - 0.5) * 1.2,
        life: 0,
        maxLife: spray ? 0.5 + r() * 0.45 : 1.2 + r() * 1.6 + rate * 0.7,
        size: spray ? 0.18 + r() * 0.22 : 0.24 + r() * 0.34,
        grow: spray ? 1.2 : 1.5 + r() * 1.4,
        rot: r() * TAU,
        spin: (r() - 0.5) * 1.6,
        tint: tint ? [tint[0], tint[1], tint[2]] : [1, 1, 1],
        alpha: spray ? 0.26 : clamp(0.12 + rate * 0.4, 0.06, 0.44),
        spray,
      });
    }
  }

  update(dt, wind) {
    const list = this.particles;
    let w = 0;
    for (let i = 0; i < list.length; i++) {
      const p = list[i];
      p.life += dt;
      if (p.life >= p.maxLife) continue;
      const drag = p.spray ? 3.2 : 1.35;
      p.vx += (wind[0] - p.vx) * drag * dt * 0.35;
      p.vz += (wind[2] - p.vz) * drag * dt * 0.35;
      p.vy += (p.spray ? -6.5 : 0.35 - p.vy * 0.6) * dt;
      p.x += p.vx * dt;
      p.y += p.vy * dt;
      p.z += p.vz * dt;
      p.rot += p.spin * dt;
      list[w++] = p;
    }
    list.length = w;

    const data = this.instanceData;
    let k = 0;
    for (let i = 0; i < list.length; i++) {
      const p = list[i];
      const t = p.life / p.maxLife;
      const fade = t < 0.12 ? t / 0.12 : 1 - (t - 0.12) / 0.88;
      const size = p.size * (1 + t * p.grow);
      const o = k * SMOKE_FLOATS;
      data[o] = p.x; data[o + 1] = p.y; data[o + 2] = p.z; data[o + 3] = size;
      const bright = p.spray ? 1.35 : lerp(0.95, 0.55, t);
      data[o + 4] = p.tint[0] * bright;
      data[o + 5] = p.tint[1] * bright;
      data[o + 6] = p.tint[2] * bright;
      data[o + 7] = p.alpha * clamp(fade, 0, 1);
      data[o + 8] = p.rot;
      data[o + 9] = 0;
      k++;
    }
    this.count = k;
    if (k > 0) this.mesh.updateInstances(data.subarray(0, k * SMOKE_FLOATS), k);
    else this.mesh.instanceCount = 0;
  }

  clear() { this.particles.length = 0; this.count = 0; this.mesh.instanceCount = 0; }

  draw() {
    if (this.count === 0) return;
    this.mesh.draw(6);
  }
}

/* ------------------------------------------------------------ tire marks -- */

const MARK_QUADS = 2600;
const MARK_FLOATS = 7;     // pos(3) uv(2) meta(2)
const MARK_VERTS_PER_QUAD = 6;

export class TireMarks {
  constructor(ctx, life = 26) {
    this.ctx = ctx;
    this.life = life;
    this.capacity = MARK_QUADS;
    this.cursor = 0;
    this.used = 0;
    this.scratch = new Float32Array(MARK_VERTS_PER_QUAD * MARK_FLOATS);
    const data = new Float32Array(this.capacity * MARK_VERTS_PER_QUAD * MARK_FLOATS);
    this.mesh = new Mesh(ctx, {
      vertices: data,
      layout: [
        { index: 0, size: 3, offset: 0 },
        { index: 1, size: 2, offset: 12 },
        { index: 2, size: 2, offset: 20 },
      ],
      stride: MARK_FLOATS * 4,
      dynamic: true,
    });
    this.mesh.count = 0;
    this.lastEdges = new Map();
  }

  /**
   * Lay one segment of rubber for a wheel. Keeps the previous edge so segments
   * join up instead of leaving gaps at speed.
   */
  addSegment(id, pos, right, width, intensity, time) {
    const hx = right[0] * width * 0.5;
    const hz = right[2] * width * 0.5;
    const l = [pos[0] - hx, pos[1] + 0.012, pos[2] - hz];
    const r = [pos[0] + hx, pos[1] + 0.012, pos[2] + hz];
    const prev = this.lastEdges.get(id);
    this.lastEdges.set(id, { l, r, time });
    if (!prev || time - prev.time > 0.25) return;
    const d = Math.hypot(l[0] - prev.l[0], l[2] - prev.l[2]);
    if (d < 0.06) return;

    const s = this.scratch;
    const write = (i, p, u, v) => {
      const o = i * MARK_FLOATS;
      s[o] = p[0]; s[o + 1] = p[1]; s[o + 2] = p[2];
      s[o + 3] = u; s[o + 4] = v;
      s[o + 5] = time; s[o + 6] = intensity;
    };
    write(0, prev.l, 0, 0);
    write(1, prev.r, 1, 0);
    write(2, r, 1, 1);
    write(3, prev.l, 0, 0);
    write(4, r, 1, 1);
    write(5, l, 0, 1);

    const byteOffset = this.cursor * MARK_VERTS_PER_QUAD * MARK_FLOATS * 4;
    this.mesh.updateVertexRange(s, byteOffset);
    this.cursor = (this.cursor + 1) % this.capacity;
    this.used = Math.min(this.used + 1, this.capacity);
    this.mesh.count = this.used * MARK_VERTS_PER_QUAD;
  }

  clear() {
    this.cursor = 0;
    this.used = 0;
    this.mesh.count = 0;
    this.lastEdges.clear();
  }

  draw() {
    if (this.used === 0) return;
    this.mesh.draw(this.used * MARK_VERTS_PER_QUAD);
  }
}

/* ------------------------------------------------------------------ rain -- */

const RAIN_DROPS = 2200;

export class RainSystem {
  constructor(ctx) {
    this.ctx = ctx;
    const r = rng(4242);
    const seeds = new Float32Array(RAIN_DROPS * 4);
    for (let i = 0; i < RAIN_DROPS; i++) {
      seeds[i * 4] = (r() - 0.5) * 24;
      seeds[i * 4 + 1] = r() * 24;
      seeds[i * 4 + 2] = (r() - 0.5) * 24;
      seeds[i * 4 + 3] = 9 + r() * 7;
    }
    const corners = new Float32Array([-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1]);
    this.mesh = new Mesh(ctx, {
      vertices: corners,
      layout: [{ index: 0, size: 2, offset: 0 }],
      stride: 8,
      instanceLayout: {
        data: seeds,
        stride: 16,
        layout: [{ index: 1, size: 4, offset: 0 }],
      },
    });
    this.mesh.instanceCount = 0;
  }

  setIntensity(rate) {
    this.mesh.instanceCount = Math.floor(RAIN_DROPS * clamp(rate, 0, 1));
  }

  draw() {
    if (this.mesh.instanceCount === 0) return;
    this.mesh.draw(6);
  }
}
