// Procedural mesh construction. The game ships no model files — every piece of
// geometry in the world is built here at load time.
//
// Vertex layout (stride 60 bytes):
//   0  position  vec3
//   12 normal    vec3
//   24 uv        vec2
//   32 color     vec3   albedo tint
//   44 params    vec4   x roughness, y metallic, z emissive, w paint mask

import { mat4, vec3, TAU, lerp } from './math.js';
import { Mesh } from './gl.js';

export const VERTEX_STRIDE = 60;
export const VERTEX_FLOATS = 15;

export const ATTRIB_LAYOUT = [
  { index: 0, size: 3, offset: 0 },
  { index: 1, size: 3, offset: 12 },
  { index: 2, size: 2, offset: 24 },
  { index: 3, size: 3, offset: 32 },
  { index: 4, size: 4, offset: 44 },
];

export const MAT = {
  paint: { roughness: 0.22, metallic: 0.55, emissive: 0, paint: 1 },
  mattePaint: { roughness: 0.7, metallic: 0.1, emissive: 0, paint: 1 },
  glass: { roughness: 0.05, metallic: 0.2, emissive: 0, paint: 0 },
  chrome: { roughness: 0.08, metallic: 1, emissive: 0, paint: 0 },
  rubber: { roughness: 0.92, metallic: 0, emissive: 0, paint: 0 },
  plastic: { roughness: 0.55, metallic: 0, emissive: 0, paint: 0 },
  metal: { roughness: 0.4, metallic: 0.8, emissive: 0, paint: 0 },
  asphalt: { roughness: 0.85, metallic: 0.02, emissive: 0, paint: 0 },
  concrete: { roughness: 0.9, metallic: 0, emissive: 0, paint: 0 },
  neon: { roughness: 0.3, metallic: 0, emissive: 3.5, paint: 0 },
  lamp: { roughness: 0.3, metallic: 0, emissive: 2.2, paint: 0 },
};

export class MeshBuilder {
  constructor() {
    this.verts = [];
    this.indices = [];
    this.stack = [mat4.create()];
    this.material = MAT.plastic;
    this.color = [0.8, 0.8, 0.8];
    this._normalMat = new Float32Array(9);
    this._tmp = vec3.create();
    this._dirtyNormal = true;
  }

  get vertexCount() { return this.verts.length / VERTEX_FLOATS; }
  get matrix() { return this.stack[this.stack.length - 1]; }

  push(m) {
    const top = mat4.create();
    mat4.multiply(top, this.matrix, m);
    this.stack.push(top);
    this._dirtyNormal = true;
    return this;
  }

  pushTransform(pos, yaw = 0, pitch = 0, roll = 0, scale = 1) {
    return this.push(mat4.compose(mat4.create(), pos, yaw, pitch, roll, scale));
  }

  pushTranslate(x, y, z) {
    return this.push(mat4.fromTranslation(mat4.create(), [x, y, z]));
  }

  pushScale(x, y, z) {
    return this.push(mat4.fromScaling(mat4.create(), [x, y, z]));
  }

  pop() {
    if (this.stack.length > 1) this.stack.pop();
    this._dirtyNormal = true;
    return this;
  }

  setMaterial(mat, color) {
    this.material = mat;
    if (color) this.color = color;
    return this;
  }

  setColor(color) { this.color = color; return this; }

  vertex(px, py, pz, nx, ny, nz, u, v) {
    const m = this.matrix;
    if (this._dirtyNormal) {
      mat4.normalFromMat4(this._normalMat, m);
      this._dirtyNormal = false;
    }
    const n = this._normalMat;
    const x = m[0] * px + m[4] * py + m[8] * pz + m[12];
    const y = m[1] * px + m[5] * py + m[9] * pz + m[13];
    const z = m[2] * px + m[6] * py + m[10] * pz + m[14];
    let tx = n[0] * nx + n[3] * ny + n[6] * nz;
    let ty = n[1] * nx + n[4] * ny + n[7] * nz;
    let tz = n[2] * nx + n[5] * ny + n[8] * nz;
    const l = Math.hypot(tx, ty, tz) || 1;
    const mt = this.material;
    const c = this.color;
    this.verts.push(
      x, y, z,
      tx / l, ty / l, tz / l,
      u, v,
      c[0], c[1], c[2],
      mt.roughness, mt.metallic, mt.emissive, mt.paint,
    );
    return this.vertexCount - 1;
  }

  tri(a, b, c) { this.indices.push(a, b, c); return this; }
  quad(a, b, c, d) { this.indices.push(a, b, c, a, c, d); return this; }

  // Flat-shaded convex polygon from world-space-ish local points.
  polygon(points, normal, uvScale = 0.25) {
    if (points.length < 3) return this;
    let n = normal;
    if (!n) n = faceNormal(points);
    const base = [];
    for (const p of points) {
      base.push(this.vertex(p[0], p[1], p[2], n[0], n[1], n[2], p[0] * uvScale, p[2] * uvScale));
    }
    for (let i = 1; i < base.length - 1; i++) this.tri(base[0], base[i], base[i + 1]);
    return this;
  }

  // Axis-aligned box centred on the current origin.
  box(sx, sy, sz, cx = 0, cy = 0, cz = 0, uvScale = 1) {
    const hx = sx / 2, hy = sy / 2, hz = sz / 2;
    const faces = [
      { n: [0, 0, 1], p: [[-hx, -hy, hz], [hx, -hy, hz], [hx, hy, hz], [-hx, hy, hz]], su: sx, sv: sy },
      { n: [0, 0, -1], p: [[hx, -hy, -hz], [-hx, -hy, -hz], [-hx, hy, -hz], [hx, hy, -hz]], su: sx, sv: sy },
      { n: [1, 0, 0], p: [[hx, -hy, hz], [hx, -hy, -hz], [hx, hy, -hz], [hx, hy, hz]], su: sz, sv: sy },
      { n: [-1, 0, 0], p: [[-hx, -hy, -hz], [-hx, -hy, hz], [-hx, hy, hz], [-hx, hy, -hz]], su: sz, sv: sy },
      { n: [0, 1, 0], p: [[-hx, hy, hz], [hx, hy, hz], [hx, hy, -hz], [-hx, hy, -hz]], su: sx, sv: sz },
      { n: [0, -1, 0], p: [[-hx, -hy, -hz], [hx, -hy, -hz], [hx, -hy, hz], [-hx, -hy, hz]], su: sx, sv: sz },
    ];
    for (const f of faces) {
      const uv = [[0, 0], [f.su * uvScale, 0], [f.su * uvScale, f.sv * uvScale], [0, f.sv * uvScale]];
      const idx = f.p.map((p, i) =>
        this.vertex(p[0] + cx, p[1] + cy, p[2] + cz, f.n[0], f.n[1], f.n[2], uv[i][0], uv[i][1]));
      this.quad(idx[0], idx[1], idx[2], idx[3]);
    }
    return this;
  }

  // Cylinder along +Y, base at y=0.
  cylinder(radiusBottom, radiusTop, height, segments = 16, caps = true, cx = 0, cy = 0, cz = 0) {
    const ring = (r, y) => {
      const out = [];
      for (let i = 0; i <= segments; i++) {
        const a = (i / segments) * TAU;
        out.push([cx + Math.cos(a) * r, cy + y, cz + Math.sin(a) * r, Math.cos(a), Math.sin(a), i / segments]);
      }
      return out;
    };
    const bottom = ring(radiusBottom, 0);
    const top = ring(radiusTop, height);
    const slope = (radiusBottom - radiusTop) / height;
    for (let i = 0; i < segments; i++) {
      const b0 = bottom[i], b1 = bottom[i + 1], t0 = top[i], t1 = top[i + 1];
      const i0 = this.vertex(b0[0], b0[1], b0[2], b0[3], slope, b0[4], b0[5], 0);
      const i1 = this.vertex(b1[0], b1[1], b1[2], b1[3], slope, b1[4], b1[5], 0);
      const i2 = this.vertex(t1[0], t1[1], t1[2], t1[3], slope, t1[4], t1[5], 1);
      const i3 = this.vertex(t0[0], t0[1], t0[2], t0[3], slope, t0[4], t0[5], 1);
      this.quad(i0, i1, i2, i3);
    }
    if (caps) {
      if (radiusTop > 0) {
        const c = this.vertex(cx, cy + height, cz, 0, 1, 0, 0.5, 0.5);
        const idx = [];
        for (let i = 0; i <= segments; i++) {
          const a = (i / segments) * TAU;
          idx.push(this.vertex(cx + Math.cos(a) * radiusTop, cy + height, cz + Math.sin(a) * radiusTop,
            0, 1, 0, 0.5 + Math.cos(a) * 0.5, 0.5 + Math.sin(a) * 0.5));
        }
        for (let i = 0; i < segments; i++) this.tri(c, idx[i], idx[i + 1]);
      }
      if (radiusBottom > 0) {
        const c = this.vertex(cx, cy, cz, 0, -1, 0, 0.5, 0.5);
        const idx = [];
        for (let i = 0; i <= segments; i++) {
          const a = (i / segments) * TAU;
          idx.push(this.vertex(cx + Math.cos(a) * radiusBottom, cy, cz + Math.sin(a) * radiusBottom,
            0, -1, 0, 0.5 + Math.cos(a) * 0.5, 0.5 + Math.sin(a) * 0.5));
        }
        for (let i = 0; i < segments; i++) this.tri(c, idx[i + 1], idx[i]);
      }
    }
    return this;
  }

  // Loft: connect a list of closed rings (each ring is an array of [x,y,z] in
  // the same winding and with the same vertex count). This is how the car body
  // is built — cross sections down the length of the car.
  /**
   * @param {Array<Array<number[]>>} rings closed sections, same vertex count
   * @param {object} [opts]
   * @param {(p:number[]) => boolean} [opts.cut] returns true for points inside a
   *   hole; any quad touching one is dropped. This is how the wheel arches get
   *   opened up in an otherwise closed body shell.
   */
  loft(rings, { closeStart = true, closeEnd = true, uvScale = 0.3, cut = null } = {}) {
    const ringIdx = [];
    for (let r = 0; r < rings.length; r++) {
      const ring = rings[r];
      const prev = rings[Math.max(0, r - 1)];
      const next = rings[Math.min(rings.length - 1, r + 1)];
      const idx = [];
      const v = r / (rings.length - 1);
      // Ring centroid, used to point every normal outwards. The cross product
      // below is only correct up to a sign — which way it comes out depends on
      // the winding and on which way the sections were ordered — and getting it
      // wrong shades the whole surface as if it were lit from inside.
      let cx = 0, cy = 0, cz = 0;
      for (const p of ring) { cx += p[0]; cy += p[1]; cz += p[2]; }
      cx /= ring.length; cy /= ring.length; cz /= ring.length;

      for (let i = 0; i < ring.length; i++) {
        const p = ring[i];
        const a = ring[(i - 1 + ring.length) % ring.length];
        const b = ring[(i + 1) % ring.length];
        // Normal from the ring tangent crossed with the lengthwise tangent.
        const t1 = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
        const t2 = [next[i][0] - prev[i][0], next[i][1] - prev[i][1], next[i][2] - prev[i][2]];
        let n = [
          t1[1] * t2[2] - t1[2] * t2[1],
          t1[2] * t2[0] - t1[0] * t2[2],
          t1[0] * t2[1] - t1[1] * t2[0],
        ];
        const l = Math.hypot(n[0], n[1], n[2]);
        if (l < 1e-6) n = [p[0] - cx, p[1] - cy, p[2] - cz];
        else { n[0] /= l; n[1] /= l; n[2] /= l; }
        if (n[0] * (p[0] - cx) + n[1] * (p[1] - cy) + n[2] * (p[2] - cz) < 0) {
          n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2];
        }
        const ln = Math.hypot(n[0], n[1], n[2]) || 1;
        idx.push(this.vertex(p[0], p[1], p[2], n[0] / ln, n[1] / ln, n[2] / ln,
          (i / ring.length) * uvScale * 6, v * uvScale * 12));
      }
      ringIdx.push(idx);
    }
    // Counter-clockwise seen from outside, matching the renderer's front-face
    // winding — the other way round and the visible surface gets culled and you
    // see straight through the model to whatever is inside it.
    for (let r = 0; r < rings.length - 1; r++) {
      const a = ringIdx[r], b = ringIdx[r + 1];
      const ra = rings[r], rb = rings[r + 1];
      for (let i = 0; i < a.length; i++) {
        const j = (i + 1) % a.length;
        if (cut && (cut(ra[i]) || cut(ra[j]) || cut(rb[i]) || cut(rb[j]))) continue;
        this.quad(a[i], b[i], b[j], a[j]);
      }
    }
    if (closeStart) this.capRing(rings[0], true);
    if (closeEnd) this.capRing(rings[rings.length - 1], false);
    return this;
  }

  capRing(ring, flip) {
    const n = faceNormal(ring);
    if (flip) { n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2]; }
    let cx = 0, cy = 0, cz = 0;
    for (const p of ring) { cx += p[0]; cy += p[1]; cz += p[2]; }
    cx /= ring.length; cy /= ring.length; cz /= ring.length;
    const c = this.vertex(cx, cy, cz, n[0], n[1], n[2], 0.5, 0.5);
    const idx = ring.map((p) => this.vertex(p[0], p[1], p[2], n[0], n[1], n[2], 0.5, 0.5));
    for (let i = 0; i < idx.length; i++) {
      const j = (i + 1) % idx.length;
      if (flip) this.tri(c, idx[j], idx[i]);
      else this.tri(c, idx[i], idx[j]);
    }
    return this;
  }

  // Flat strip along a path, used for road surfaces, kerbs and guardrails.
  strip(left, right, { normal = [0, 1, 0], uvRepeat = 0.1, closed = false } = {}) {
    const li = [], ri = [];
    for (let i = 0; i < left.length; i++) {
      li.push(this.vertex(left[i][0], left[i][1], left[i][2], normal[0], normal[1], normal[2], 0, i * uvRepeat));
      ri.push(this.vertex(right[i][0], right[i][1], right[i][2], normal[0], normal[1], normal[2], 1, i * uvRepeat));
    }
    const n = left.length - (closed ? 0 : 1);
    for (let i = 0; i < n; i++) {
      const j = (i + 1) % left.length;
      this.quad(li[i], ri[i], ri[j], li[j]);
    }
    return this;
  }

  append(other) {
    const offset = this.vertexCount;
    for (const v of other.verts) this.verts.push(v);
    for (const i of other.indices) this.indices.push(i + offset);
    return this;
  }

  data() {
    return {
      vertices: new Float32Array(this.verts),
      indices: new Uint32Array(this.indices),
    };
  }

  build(ctx) {
    const { vertices, indices } = this.data();
    return new Mesh(ctx, {
      vertices, indices,
      layout: ATTRIB_LAYOUT,
      stride: VERTEX_STRIDE,
    });
  }

  bounds() {
    const min = [Infinity, Infinity, Infinity];
    const max = [-Infinity, -Infinity, -Infinity];
    for (let i = 0; i < this.verts.length; i += VERTEX_FLOATS) {
      for (let k = 0; k < 3; k++) {
        min[k] = Math.min(min[k], this.verts[i + k]);
        max[k] = Math.max(max[k], this.verts[i + k]);
      }
    }
    return { min, max };
  }
}

function faceNormal(points) {
  // Newell's method — robust for non-planar polygons.
  let nx = 0, ny = 0, nz = 0;
  for (let i = 0; i < points.length; i++) {
    const c = points[i], n = points[(i + 1) % points.length];
    nx += (c[1] - n[1]) * (c[2] + n[2]);
    ny += (c[2] - n[2]) * (c[0] + n[0]);
    nz += (c[0] - n[0]) * (c[1] + n[1]);
  }
  const l = Math.hypot(nx, ny, nz) || 1;
  return [nx / l, ny / l, nz / l];
}

/* --------------------------------------------------------------- shapes --- */

// Rounded rectangle cross section in the XY plane at a given Z. `n` points.
export function roundedSection(halfWidth, bottom, top, radius, z, n = 18, topSquash = 1) {
  const pts = [];
  const h = (top - bottom) / 2;
  const cy = (top + bottom) / 2;
  const r = Math.min(radius, Math.min(halfWidth, h) * 0.95);
  for (let i = 0; i < n; i++) {
    const a = (i / n) * TAU;
    // Superellipse gives boxy 80s/90s corners rather than a soft oval.
    const p = 2 + 4 * (1 - r / Math.min(halfWidth, h));
    const ca = Math.cos(a), sa = Math.sin(a);
    const x = Math.sign(ca) * Math.pow(Math.abs(ca), 2 / p) * halfWidth;
    let y = Math.sign(sa) * Math.pow(Math.abs(sa), 2 / p) * h;
    if (y > 0) y *= topSquash;
    pts.push([x, cy + y, z]);
  }
  return pts;
}

export function ringLerp(a, b, t) {
  return a.map((p, i) => [lerp(p[0], b[i][0], t), lerp(p[1], b[i][1], t), lerp(p[2], b[i][2], t)]);
}
