// Track generation and the ground query the physics runs against.
//
// A track is a closed Catmull-Rom loop of control points. It gets resampled at
// a fixed arc-length step into segments, each carrying a tangent, a banked
// lateral vector, a surface normal and a width. The road ribbon, kerbs,
// shoulders and barriers are all extruded from those segments, and
// `sample(x, z)` walks a uniform grid to find the segment under any point.

import { vec3, catmullRom, clamp, lerp, TAU, rng } from '../core/math.js';
import { MeshBuilder, MAT } from '../core/geometry.js';
import { SURFACE } from '../physics/tire.js';
import { buildScenery } from './scenery.js';

const STEP = 3.0;          // metres between segments
const CELL = 24;           // spatial hash cell size

export class Track {
  constructor(def, ctx) {
    this.def = def;
    this.name = def.name;
    this.theme = def.theme;
    this.ctx = ctx;
    this.lights = [];
    this.chunks = [];
    this.random = rng(def.seed ?? 1);
    this.buildSpline();
    this.buildGrid();
    this.buildMeshes(ctx);
  }

  /* ------------------------------------------------------------- geometry */

  buildSpline() {
    const cps = this.def.points;
    const n = cps.length;
    const dense = [];
    const p = vec3.create();
    // Oversample the spline, then walk it at a constant arc length so segment
    // spacing does not depend on how far apart the control points were placed.
    const sub = 40;
    for (let i = 0; i < n; i++) {
      const p0 = cps[(i - 1 + n) % n], p1 = cps[i], p2 = cps[(i + 1) % n], p3 = cps[(i + 2) % n];
      for (let j = 0; j < sub; j++) {
        catmullRom(p0, p1, p2, p3, j / sub, p);
        dense.push([p[0], p[1], p[2], i + j / sub]);
      }
    }

    const segs = [];
    let acc = 0;
    let prev = dense[0];
    let carry = 0;
    const pushSeg = (pt, param) => {
      segs.push({ p: [pt[0], pt[1], pt[2]], param, s: acc });
    };
    pushSeg(dense[0], dense[0][3]);
    for (let i = 1; i <= dense.length; i++) {
      const cur = dense[i % dense.length];
      const d = Math.hypot(cur[0] - prev[0], cur[1] - prev[1], cur[2] - prev[2]);
      carry += d;
      acc += d;
      if (carry >= STEP) {
        carry = 0;
        pushSeg(cur, cur[3]);
      }
      prev = cur;
    }
    this.length = acc;
    this.segments = segs;

    // Per-segment frame.
    const count = segs.length;
    for (let i = 0; i < count; i++) {
      const a = segs[i].p;
      const b = segs[(i + 1) % count].p;
      const prevP = segs[(i - 1 + count) % count].p;
      const t = vec3.normalize(vec3.create(), [b[0] - prevP[0], b[1] - prevP[1], b[2] - prevP[2]]);
      const flatT = vec3.normalize(vec3.create(), [t[0], 0, t[2]]);
      const right = vec3.create(flatT[2], 0, -flatT[0]);
      const s = segs[i];
      s.tangent = t;
      s.right = right;
      s.grade = t[1];
      s.width = this.widthAt(s.s / this.length);
      s.bank = this.bankAt(i, count);
      // Banked lateral and the resulting surface normal.
      const cb = Math.cos(s.bank), sb = Math.sin(s.bank);
      s.lateral = vec3.create(right[0] * cb, sb, right[2] * cb);
      const nrm = vec3.cross(vec3.create(), s.lateral, t);
      if (nrm[1] < 0) vec3.scale(nrm, nrm, -1);
      s.normal = vec3.normalize(nrm, nrm);
      s.curvature = 0;
    }
    // Curvature drives banking cues, the AI-free clipping zones and prop placement.
    for (let i = 0; i < count; i++) {
      const a = segs[(i - 2 + count) % count], b = segs[i], c = segs[(i + 2) % count];
      const v1 = Math.atan2(b.p[0] - a.p[0], b.p[2] - a.p[2]);
      const v2 = Math.atan2(c.p[0] - b.p[0], c.p[2] - b.p[2]);
      let d = v2 - v1;
      while (d > Math.PI) d -= TAU;
      while (d < -Math.PI) d += TAU;
      segs[i].curvature = d / (STEP * 4);
    }

    this.buildClippingZones();
  }

  widthAt(t) {
    const w = this.def.width;
    if (typeof w === 'number') return w;
    const i = clamp(Math.floor(t * w.length), 0, w.length - 1);
    const j = (i + 1) % w.length;
    const f = t * w.length - i;
    return lerp(w[i], w[j], f);
  }

  bankAt(i, count) {
    if (!this.def.banking) return 0;
    const a = this.segments[(i - 2 + count) % count].p;
    const b = this.segments[i].p;
    const c = this.segments[(i + 2) % count].p;
    const v1 = Math.atan2(b[0] - a[0], b[2] - a[2]);
    const v2 = Math.atan2(c[0] - b[0], c[2] - b[2]);
    let d = v2 - v1;
    while (d > Math.PI) d -= TAU;
    while (d < -Math.PI) d += TAU;
    return clamp(-d * this.def.banking, -0.09, 0.09);
  }

  buildClippingZones() {
    // A clipping zone sits on the inside of a corner: hold a big angle close to
    // it and the multiplier climbs.
    const zones = [];
    const segs = this.segments;
    let i = 4;
    while (i < segs.length - 4) {
      const k = Math.abs(segs[i].curvature);
      if (k > 0.012) {
        let j = i, peak = i;
        while (j < segs.length - 4 && Math.abs(segs[j].curvature) > 0.006) {
          if (Math.abs(segs[j].curvature) > Math.abs(segs[peak].curvature)) peak = j;
          j++;
        }
        if (j - i > 3) {
          const s = segs[peak];
          const side = Math.sign(s.curvature) || 1;
          const off = (s.width / 2) * 0.82 * side;
          zones.push({
            index: peak,
            x: s.p[0] + s.lateral[0] * off,
            z: s.p[2] + s.lateral[2] * off,
            radius: Math.max(4.5, s.width * 0.55),
            side,
            strength: clamp(Math.abs(s.curvature) * 26, 0.4, 1.6),
            from: i, to: j,
          });
        }
        i = j + 2;
      } else i++;
    }
    this.clippingZones = zones;
  }

  buildGrid() {
    const cells = new Map();
    const key = (cx, cz) => cx * 100003 + cz;
    const count = this.segments.length;
    for (let i = 0; i < count; i++) {
      const a = this.segments[i].p;
      const b = this.segments[(i + 1) % count].p;
      const reach = this.segments[i].width * 0.5 + 14;
      const minX = Math.min(a[0], b[0]) - reach, maxX = Math.max(a[0], b[0]) + reach;
      const minZ = Math.min(a[2], b[2]) - reach, maxZ = Math.max(a[2], b[2]) + reach;
      for (let cx = Math.floor(minX / CELL); cx <= Math.floor(maxX / CELL); cx++) {
        for (let cz = Math.floor(minZ / CELL); cz <= Math.floor(maxZ / CELL); cz++) {
          const k = key(cx, cz);
          let list = cells.get(k);
          if (!list) cells.set(k, (list = []));
          list.push(i);
        }
      }
    }
    this.cells = cells;
    this._key = key;
    this._lastSegment = 0;
  }

  segmentsNear(x, z) {
    return this.cells.get(this._key(Math.floor(x / CELL), Math.floor(z / CELL)));
  }

  /**
   * Ground query used by the vehicle: height, surface normal, friction and how
   * far off the racing line the point is.
   */
  sample(x, z) {
    const list = this.segmentsNear(x, z);
    let best = -1, bestDist = Infinity, bestT = 0, bestOffset = 0;
    const count = this.segments.length;
    const candidates = list && list.length ? list : [this._lastSegment];
    for (const i of candidates) {
      const a = this.segments[i].p;
      const b = this.segments[(i + 1) % count].p;
      const dx = b[0] - a[0], dz = b[2] - a[2];
      const len2 = dx * dx + dz * dz || 1;
      let t = ((x - a[0]) * dx + (z - a[2]) * dz) / len2;
      t = clamp(t, 0, 1);
      const px = a[0] + dx * t, pz = a[2] + dz * t;
      const d = (x - px) * (x - px) + (z - pz) * (z - pz);
      if (d < bestDist) {
        bestDist = d; best = i; bestT = t;
        const s = this.segments[i];
        bestOffset = (x - px) * s.lateral[0] + (z - pz) * s.lateral[2];
      }
    }
    if (best < 0) {
      return { height: 0, normal: [0, 1, 0], surface: SURFACE.grass, offset: 99, onTrack: false, segment: 0, progress: 0 };
    }
    this._lastSegment = best;
    const s = this.segments[best];
    const s2 = this.segments[(best + 1) % count];
    const baseY = lerp(s.p[1], s2.p[1], bestT);
    const halfWidth = s.width / 2;
    const abs = Math.abs(bestOffset);

    let surface, height = baseY + bestOffset * Math.tan(s.bank);
    let normal = s.normal;
    if (abs <= halfWidth) {
      surface = SURFACE.asphalt;
    } else if (abs <= halfWidth + 0.5) {
      surface = SURFACE.kerb;
      height += 0.035 * (abs - halfWidth) / 0.5;
      normal = [0, 1, 0];
    } else if (abs <= halfWidth + this.def.shoulder) {
      surface = this.def.shoulderSurface === 'dirt' ? SURFACE.dirt : SURFACE.concrete;
      height += 0.04 - (abs - halfWidth - 0.5) * 0.02;
      normal = [0, 1, 0];
    } else {
      surface = this.def.offTrackSurface === 'grass' ? SURFACE.grass : SURFACE.dirt;
      height += 0.04 - this.def.shoulder * 0.02;
      normal = [0, 1, 0];
    }

    return {
      height,
      normal,
      surface,
      offset: bestOffset,
      onTrack: abs <= halfWidth + 0.5,
      segment: best,
      halfWidth,
      progress: (s.s + bestT * STEP) / this.length,
      curvature: s.curvature,
      forward: s.tangent,
    };
  }

  /** Barrier collision. Returns the impact strength, 0 when clear. */
  collide(vehicle) {
    const p = vehicle.pos;
    const info = this.sample(p[0], p[2]);
    const limit = info.halfWidth + this.def.wallDistance;
    const abs = Math.abs(info.offset);
    if (abs < limit) return 0;
    const seg = this.segments[info.segment];
    const side = Math.sign(info.offset);
    const normal = [-seg.lateral[0] * side, 0, -seg.lateral[2] * side];
    return vehicle.collide(normal, (abs - limit) * 1.02, 0.22);
  }

  startPose(offsetMetres = 0) {
    const idx = clamp(Math.round(offsetMetres / STEP), 0, this.segments.length - 1);
    const s = this.segments[idx];
    return {
      x: s.p[0], y: s.p[1], z: s.p[2],
      yaw: Math.atan2(s.tangent[0], s.tangent[2]),
    };
  }

  /* --------------------------------------------------------------- meshes */

  buildMeshes(ctx) {
    const road = new MeshBuilder();
    const segs = this.segments;
    const count = segs.length;

    const edge = (i, k) => {
      const s = segs[i];
      return [
        s.p[0] + s.lateral[0] * k,
        s.p[1] + s.lateral[1] * k + 0.01,
        s.p[2] + s.lateral[2] * k,
      ];
    };

    // Asphalt.
    road.setMaterial(MAT.asphalt, this.def.roadColor || [0.085, 0.086, 0.095]);
    for (let i = 0; i < count; i++) {
      const j = (i + 1) % count;
      const w0 = segs[i].width / 2, w1 = segs[j].width / 2;
      const a = edge(i, -w0), b = edge(i, w0), c = edge(j, w1), d = edge(j, -w1);
      const n = segs[i].normal;
      const i0 = road.vertex(a[0], a[1], a[2], n[0], n[1], n[2], 0, i * 0.4);
      const i1 = road.vertex(b[0], b[1], b[2], n[0], n[1], n[2], 1, i * 0.4);
      const i2 = road.vertex(c[0], c[1], c[2], n[0], n[1], n[2], 1, (i + 1) * 0.4);
      const i3 = road.vertex(d[0], d[1], d[2], n[0], n[1], n[2], 0, (i + 1) * 0.4);
      road.quad(i0, i1, i2, i3);
    }

    // Centre line and edge markings.
    road.setMaterial({ roughness: 0.7, metallic: 0, emissive: 0.05, paint: 0 }, [0.85, 0.85, 0.82]);
    for (let i = 0; i < count; i++) {
      if (this.def.centreLine !== false && i % 3 === 0) {
        const j = (i + 1) % count;
        const a = edge(i, -0.09), b = edge(i, 0.09), c = edge(j, 0.09), d = edge(j, -0.09);
        const i0 = road.vertex(a[0], a[1] + 0.005, a[2], 0, 1, 0, 0, 0);
        const i1 = road.vertex(b[0], b[1] + 0.005, b[2], 0, 1, 0, 1, 0);
        const i2 = road.vertex(c[0], c[1] + 0.005, c[2], 0, 1, 0, 1, 1);
        const i3 = road.vertex(d[0], d[1] + 0.005, d[2], 0, 1, 0, 0, 1);
        road.quad(i0, i1, i2, i3);
      }
      for (const side of [-1, 1]) {
        const j = (i + 1) % count;
        const w0 = segs[i].width / 2 - 0.22, w1 = segs[j].width / 2 - 0.22;
        const a = edge(i, side * w0), b = edge(i, side * (w0 + 0.12));
        const c = edge(j, side * (w1 + 0.12)), d = edge(j, side * w1);
        const i0 = road.vertex(a[0], a[1] + 0.005, a[2], 0, 1, 0, 0, 0);
        const i1 = road.vertex(b[0], b[1] + 0.005, b[2], 0, 1, 0, 1, 0);
        const i2 = road.vertex(c[0], c[1] + 0.005, c[2], 0, 1, 0, 1, 1);
        const i3 = road.vertex(d[0], d[1] + 0.005, d[2], 0, 1, 0, 0, 1);
        road.quad(i0, i1, i2, i3);
      }
    }

    // Kerbs at corners.
    for (let i = 0; i < count; i++) {
      if (Math.abs(segs[i].curvature) < 0.008) continue;
      const j = (i + 1) % count;
      const side = Math.sign(segs[i].curvature) || 1;
      const w0 = segs[i].width / 2, w1 = segs[j].width / 2;
      road.setMaterial({ roughness: 0.6, metallic: 0, emissive: 0.02, paint: 0 },
        (i % 4 < 2) ? [0.75, 0.13, 0.14] : [0.86, 0.86, 0.84]);
      const a = edge(i, side * w0), b = edge(i, side * (w0 + 0.5));
      const c = edge(j, side * (w1 + 0.5)), d = edge(j, side * w1);
      const i0 = road.vertex(a[0], a[1] + 0.01, a[2], 0, 1, 0, 0, 0);
      const i1 = road.vertex(b[0], b[1] + 0.05, b[2], 0, 1, 0, 1, 0);
      const i2 = road.vertex(c[0], c[1] + 0.05, c[2], 0, 1, 0, 1, 1);
      const i3 = road.vertex(d[0], d[1] + 0.01, d[2], 0, 1, 0, 0, 1);
      road.quad(i0, i1, i2, i3);
    }

    // Shoulders / verge.
    road.setMaterial(MAT.concrete, this.def.shoulderColor || [0.10, 0.105, 0.11]);
    for (let i = 0; i < count; i++) {
      const j = (i + 1) % count;
      for (const side of [-1, 1]) {
        const w0 = segs[i].width / 2, w1 = segs[j].width / 2;
        const sh = this.def.shoulder + this.def.wallDistance;
        const a = edge(i, side * w0), b = edge(i, side * (w0 + sh));
        const c = edge(j, side * (w1 + sh)), d = edge(j, side * w1);
        const i0 = road.vertex(a[0], a[1], a[2], 0, 1, 0, 0, i * 0.2);
        const i1 = road.vertex(b[0], b[1] - 0.02, b[2], 0, 1, 0, 1, i * 0.2);
        const i2 = road.vertex(c[0], c[1] - 0.02, c[2], 0, 1, 0, 1, (i + 1) * 0.2);
        const i3 = road.vertex(d[0], d[1], d[2], 0, 1, 0, 0, (i + 1) * 0.2);
        if (side > 0) road.quad(i0, i1, i2, i3);
        else road.quad(i3, i2, i1, i0);
      }
    }

    this.roadMesh = road.build(ctx);

    // Everything else — buildings, guardrails, lamps, signs — comes from the
    // theme, chunked so it can be frustum culled.
    const scenery = buildScenery(this, this.def);
    this.chunks = scenery.chunks.map((c) => ({
      mesh: c.builder.build(ctx),
      center: c.center,
      radius: c.radius,
    }));
    this.lights = scenery.lights;
    this.startLine = scenery.startLine || this.startPose(0);
  }

  dispose() {
    this.roadMesh.dispose();
    for (const c of this.chunks) c.mesh.dispose();
  }
}

/** Distance along the loop, handling wrap-around, in metres. */
export function progressDelta(a, b, length) {
  let d = (b - a) * length;
  if (d > length / 2) d -= length;
  if (d < -length / 2) d += length;
  return d;
}
