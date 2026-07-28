// Themed environment props built along the track spline.
//
// Everything is generated from the track's seed, so a given track always looks
// the same. Props are grouped into chunks of consecutive segments and each
// chunk gets its own mesh and bounding sphere for frustum culling.

import { MeshBuilder, MAT } from '../core/geometry.js';
import { rng, lerp, clamp, TAU } from '../core/math.js';

const CHUNK_SEGMENTS = 22;

const NEON_COLORS = [
  [1.0, 0.15, 0.45], [0.15, 0.85, 1.0], [0.65, 0.25, 1.0],
  [1.0, 0.55, 0.1], [0.2, 1.0, 0.6], [1.0, 0.9, 0.25],
];

export function buildScenery(track, def) {
  const segs = track.segments;
  const chunks = [];
  const lights = [];
  const rand = rng((def.seed ?? 1) * 7919 + 13);

  for (let start = 0; start < segs.length; start += CHUNK_SEGMENTS) {
    const b = new MeshBuilder();
    const end = Math.min(start + CHUNK_SEGMENTS, segs.length);
    const ctxObj = { builder: b, track, def, rand, lights, from: start, to: end };
    switch (def.theme) {
      case 'mountain': mountainChunk(ctxObj); break;
      case 'industrial': industrialChunk(ctxObj); break;
      default: cityChunk(ctxObj); break;
    }
    if (b.vertexCount === 0) continue;
    const { min, max } = b.bounds();
    chunks.push({
      builder: b,
      center: [(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2],
      radius: Math.hypot(max[0] - min[0], max[1] - min[1], max[2] - min[2]) / 2 + 2,
    });
  }

  // Start / finish gantry.
  const gantry = new MeshBuilder();
  buildStartGantry(gantry, track, lights);
  const gb = gantry.bounds();
  chunks.push({
    builder: gantry,
    center: [(gb.min[0] + gb.max[0]) / 2, (gb.min[1] + gb.max[1]) / 2, (gb.min[2] + gb.max[2]) / 2],
    radius: Math.hypot(gb.max[0] - gb.min[0], gb.max[1] - gb.min[1], gb.max[2] - gb.min[2]) / 2 + 2,
  });

  return { chunks, lights };
}

/* ------------------------------------------------------------- utilities -- */

function place(track, i, offset, forward = 0) {
  const segs = track.segments;
  const s = segs[i % segs.length];
  const t = s.tangent;
  return [
    s.p[0] + s.lateral[0] * offset + t[0] * forward,
    s.p[1],
    s.p[2] + s.lateral[2] * offset + t[2] * forward,
  ];
}

function headingAt(track, i) {
  const s = track.segments[i % track.segments.length];
  return Math.atan2(s.tangent[0], s.tangent[2]);
}

function addLight(lights, pos, color, radius, intensity) {
  if (lights.length > 800) return;
  lights.push({ pos: [pos[0], pos[1], pos[2]], color, radius, intensity });
}

/* ------------------------------------------------------------------ city -- */

function cityChunk({ builder: b, track, def, rand, lights, from, to }) {
  const halfRoad = () => track.segments[from].width / 2;
  for (let i = from; i < to; i++) {
    const s = track.segments[i];
    const w = s.width / 2 + def.shoulder + def.wallDistance;
    const yaw = headingAt(track, i);

    // Kerb walls along the road edge.
    if (i % 2 === 0) {
      b.setMaterial(MAT.concrete, [0.16, 0.16, 0.18]);
      for (const side of [-1, 1]) {
        const p = place(track, i, side * (w + 0.2));
        b.pushTransform([p[0], p[1] + 0.18, p[2]], yaw, 0, 0);
        b.box(0.4, 0.36, 6.2);
        b.pop();
      }
    }

    // Buildings: a wall of them on each side, varied in depth and height.
    if (i % 3 === 0) {
      for (const side of [-1, 1]) {
        if (rand() < 0.12) continue;
        const depth = 9 + rand() * 16;
        const width = 7 + rand() * 12;
        const height = 8 + Math.pow(rand(), 1.6) * 46;
        const dist = w + 3 + rand() * 5 + depth / 2;
        const p = place(track, i, side * dist, (rand() - 0.5) * 6);
        buildBuilding(b, p, yaw, width, depth, height, rand, lights, side);
      }
    }

    // Street lamps.
    if (i % 5 === 2) {
      const side = rand() < 0.5 ? -1 : 1;
      const p = place(track, i, side * (w + 0.9));
      b.setMaterial(MAT.metal, [0.2, 0.21, 0.23]);
      b.pushTransform([p[0], p[1], p[2]], yaw, 0, 0);
      b.cylinder(0.075, 0.06, 6.4, 8, false);
      b.pushTransform([-side * 1.1, 6.3, 0], 0, 0, 0);
      b.box(2.4, 0.12, 0.16);
      b.pop();
      b.setMaterial(MAT.lamp, [1.0, 0.86, 0.62]);
      b.pushTransform([-side * 2.1, 6.16, 0], 0, 0, 0);
      b.box(0.7, 0.1, 0.28);
      b.pop();
      b.pop();
      addLight(lights, [p[0] - track.segments[i].lateral[0] * side * 2.1, p[1] + 6.1,
        p[2] - track.segments[i].lateral[2] * side * 2.1], [1.0, 0.82, 0.55], 22, 1.5);
    }

    // Vertical neon signage — the reason to come out at night.
    if (i % 4 === 1 && rand() < 0.8) {
      const side = rand() < 0.5 ? -1 : 1;
      const p = place(track, i, side * (w + 2.2));
      const color = NEON_COLORS[Math.floor(rand() * NEON_COLORS.length)];
      const h = 3 + rand() * 7;
      const y = 3.5 + rand() * 12;
      b.setMaterial(MAT.plastic, [0.05, 0.05, 0.06]);
      b.pushTransform([p[0], p[1] + y + h / 2, p[2]], yaw, 0, 0);
      b.box(0.9, h, 0.22);
      b.pop();
      b.setMaterial(MAT.neon, color);
      const blocks = Math.max(2, Math.floor(h / 1.1));
      for (let k = 0; k < blocks; k++) {
        b.pushTransform([p[0], p[1] + y + 0.55 + k * (h / blocks), p[2]], yaw, 0, 0);
        // Stacked glyph-like bars read as vertical signage at speed.
        b.box(0.62 - (k % 2) * 0.18, 0.34, 0.06, -side * 0.12, 0, 0);
        b.pop();
      }
      addLight(lights, [p[0], p[1] + y + h / 2, p[2]], color, 16 + h, 1.9);
    }

    // Horizontal neon tube under building eaves.
    if (i % 6 === 4 && rand() < 0.7) {
      const side = rand() < 0.5 ? -1 : 1;
      const p = place(track, i, side * (w + 1.6));
      const color = NEON_COLORS[Math.floor(rand() * NEON_COLORS.length)];
      b.setMaterial(MAT.neon, color);
      b.pushTransform([p[0], p[1] + 4.2 + rand() * 3, p[2]], yaw, 0, 0);
      b.box(0.12, 0.12, 7.5);
      b.pop();
      addLight(lights, [p[0], p[1] + 5, p[2]], color, 14, 1.1);
    }

    // Street furniture: vending machines glow, and they are everywhere in Japan.
    if (i % 7 === 3 && rand() < 0.6) {
      const side = rand() < 0.5 ? -1 : 1;
      const p = place(track, i, side * (w + 1.4));
      b.setMaterial(MAT.plastic, [0.12, 0.12, 0.14]);
      b.pushTransform([p[0], p[1] + 0.9, p[2]], yaw, 0, 0);
      b.box(1.0, 1.8, 0.7);
      b.pop();
      const color = rand() < 0.5 ? [0.95, 0.25, 0.2] : [0.25, 0.6, 1.0];
      b.setMaterial({ roughness: 0.3, metallic: 0, emissive: 2.0, paint: 0 }, color);
      b.pushTransform([p[0], p[1] + 1.15, p[2]], yaw, 0, 0);
      b.box(0.82, 1.0, 0.72);
      b.pop();
      addLight(lights, [p[0], p[1] + 1.3, p[2]], color, 7, 0.9);
    }

    // Traffic light at bigger corners.
    if (Math.abs(s.curvature) > 0.02 && i % 8 === 0) {
      const side = Math.sign(s.curvature) * -1;
      const p = place(track, i, side * (w + 1.0));
      b.setMaterial(MAT.metal, [0.16, 0.17, 0.19]);
      b.pushTransform([p[0], p[1], p[2]], yaw, 0, 0);
      b.cylinder(0.07, 0.06, 5.0, 6, false);
      b.pop();
      b.setMaterial({ roughness: 0.3, metallic: 0, emissive: 2.6, paint: 0 }, [0.2, 1.0, 0.45]);
      b.pushTransform([p[0], p[1] + 4.6, p[2]], yaw, 0, 0);
      b.box(0.22, 0.22, 0.1);
      b.pop();
      addLight(lights, [p[0], p[1] + 4.6, p[2]], [0.2, 1.0, 0.45], 8, 0.8);
    }
  }
}

function buildBuilding(b, p, yaw, width, depth, height, rand, lights, side) {
  const shade = 0.06 + rand() * 0.05;
  b.setMaterial(MAT.concrete, [shade, shade * 1.02, shade * 1.15]);
  b.pushTransform([p[0], p[1] + height / 2, p[2]], yaw + (rand() - 0.5) * 0.15, 0, 0);
  b.box(width, height, depth);

  // Window grid on the road-facing side. Lit windows are emissive and cheap —
  // no extra draw calls, just different vertex parameters.
  const cols = Math.max(2, Math.floor(width / 1.6));
  const rows = Math.max(2, Math.floor(height / 2.4));
  const wz = -side * (depth / 2 + 0.05);
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      const lit = rand();
      const x = -width / 2 + (c + 0.5) * (width / cols);
      const y = -height / 2 + (r + 0.7) * (height / rows);
      if (lit < 0.42) {
        const warm = rand();
        b.setMaterial({ roughness: 0.3, metallic: 0, emissive: 1.1 + rand() * 1.4, paint: 0 },
          warm < 0.6 ? [1.0, 0.82, 0.5] : warm < 0.85 ? [0.55, 0.8, 1.0] : [0.7, 0.5, 1.0]);
      } else {
        b.setMaterial({ roughness: 0.08, metallic: 0.6, emissive: 0, paint: 0 }, [0.03, 0.035, 0.05]);
      }
      b.pushTranslate(x, y, wz);
      b.box(width / cols * 0.62, height / rows * 0.5, 0.06);
      b.pop();
    }
  }

  // Rooftop details: a water tank, an aerial, a red beacon.
  b.setMaterial(MAT.metal, [0.13, 0.13, 0.15]);
  if (rand() < 0.6) {
    b.pushTranslate((rand() - 0.5) * width * 0.4, height / 2 + 0.9, (rand() - 0.5) * depth * 0.4);
    b.box(2.0, 1.8, 2.0);
    b.pop();
  }
  if (height > 26) {
    b.setMaterial({ roughness: 0.3, metallic: 0, emissive: 3.0, paint: 0 }, [1.0, 0.12, 0.1]);
    b.pushTranslate(0, height / 2 + 0.4, 0);
    b.box(0.4, 0.4, 0.4);
    b.pop();
  }
  b.pop();
}

/* -------------------------------------------------------------- mountain -- */

function mountainChunk({ builder: b, track, def, rand, lights, from, to }) {
  for (let i = from; i < to; i++) {
    const s = track.segments[i];
    const w = s.width / 2 + def.shoulder;
    const yaw = headingAt(track, i);
    const inner = Math.sign(s.curvature) || (i % 2 ? 1 : -1);

    // W-beam guardrail on the outside of every corner, with reflector posts.
    for (const side of [-1, 1]) {
      const isOutside = side !== inner;
      if (!isOutside && Math.abs(s.curvature) > 0.004) continue;
      const p = place(track, i, side * (w + def.wallDistance));
      b.setMaterial(MAT.metal, [0.42, 0.43, 0.46]);
      b.pushTransform([p[0], p[1] + 0.62, p[2]], yaw, 0, 0);
      b.box(0.09, 0.32, 3.1);
      b.pop();
      if (i % 2 === 0) {
        b.setMaterial(MAT.metal, [0.3, 0.31, 0.33]);
        b.pushTransform([p[0], p[1] + 0.3, p[2]], yaw, 0, 0);
        b.box(0.1, 0.62, 0.12);
        b.pop();
        b.setMaterial({ roughness: 0.2, metallic: 0.2, emissive: 1.6, paint: 0 },
          side > 0 ? [1.0, 0.75, 0.2] : [1.0, 1.0, 1.0]);
        b.pushTransform([p[0], p[1] + 0.78, p[2]], yaw, 0, 0);
        b.box(0.11, 0.09, 0.09);
        b.pop();
      }
    }

    // Cut slope on the inside, drop-off with trees on the outside.
    if (i % 2 === 0) {
      const cliffSide = inner;
      const p = place(track, i, cliffSide * (w + def.wallDistance + 3.5));
      b.setMaterial(MAT.concrete, [0.075, 0.08, 0.075]);
      const h = 4 + rand() * 9;
      b.pushTransform([p[0], p[1] + h / 2 - 1.2, p[2]], yaw + (rand() - 0.5) * 0.4, 0, (rand() - 0.5) * 0.12);
      b.box(7 + rand() * 4, h, 7, 0, 0, 0);
      b.pop();
    }

    const treeSide = -inner;
    if (rand() < 0.85) {
      for (let k = 0; k < 3; k++) {
        const dist = def.wallDistance + w + 2.5 + rand() * 22;
        const p = place(track, i, treeSide * dist, (rand() - 0.5) * 8);
        buildCedar(b, p, 5 + rand() * 9, rand);
      }
    }
    if (rand() < 0.35) {
      const p = place(track, i, inner * (w + def.wallDistance + 1.6), (rand() - 0.5) * 6);
      buildCedar(b, p, 4 + rand() * 6, rand);
    }

    // Sparse lighting — the touge is mostly moonlight and headlamps.
    if (i % 26 === 5) {
      const p = place(track, i, inner * (w + def.wallDistance + 0.8));
      b.setMaterial(MAT.metal, [0.2, 0.21, 0.23]);
      b.pushTransform([p[0], p[1], p[2]], yaw, 0, 0);
      b.cylinder(0.07, 0.055, 5.4, 6, false);
      b.pop();
      b.setMaterial(MAT.lamp, [0.85, 0.95, 1.0]);
      b.pushTransform([p[0], p[1] + 5.3, p[2]], yaw, 0, 0);
      b.box(0.5, 0.12, 0.24);
      b.pop();
      addLight(lights, [p[0], p[1] + 5.2, p[2]], [0.8, 0.9, 1.0], 20, 1.3);
    }

    // Corner marker chevrons.
    if (Math.abs(s.curvature) > 0.018 && i % 3 === 0) {
      const p = place(track, i, -inner * (w + def.wallDistance + 0.7));
      b.setMaterial({ roughness: 0.3, metallic: 0.1, emissive: 1.2, paint: 0 }, [1.0, 0.85, 0.1]);
      b.pushTransform([p[0], p[1] + 1.1, p[2]], yaw, 0, 0);
      b.box(0.06, 0.5, 0.55);
      b.pop();
    }

    // A vending machine at the summit pull-in, glowing in the dark.
    if (i % 40 === 12) {
      const p = place(track, i, inner * (w + def.wallDistance + 1.3));
      b.setMaterial({ roughness: 0.3, metallic: 0, emissive: 2.0, paint: 0 }, [0.95, 0.3, 0.2]);
      b.pushTransform([p[0], p[1] + 0.95, p[2]], yaw, 0, 0);
      b.box(1.0, 1.9, 0.7);
      b.pop();
      addLight(lights, [p[0], p[1] + 1.4, p[2]], [1.0, 0.4, 0.25], 9, 1.2);
    }
  }
}

function buildCedar(b, p, height, rand) {
  b.setMaterial(MAT.plastic, [0.09, 0.07, 0.055]);
  b.pushTransform([p[0], p[1], p[2]], rand() * TAU, 0, 0);
  b.cylinder(0.16, 0.1, height * 0.45, 6, false);
  b.setMaterial(MAT.plastic, [0.035, 0.075, 0.045]);
  const tiers = 3;
  for (let t = 0; t < tiers; t++) {
    const y = height * (0.3 + t * 0.22);
    const r = height * 0.20 * (1 - t * 0.28);
    b.cylinder(r, r * 0.15, height * 0.34, 7, false, 0, y, 0);
  }
  b.pop();
}

/* ------------------------------------------------------------ industrial -- */

function industrialChunk({ builder: b, track, def, rand, lights, from, to }) {
  const CONTAINER = [
    [0.75, 0.18, 0.16], [0.16, 0.42, 0.62], [0.72, 0.55, 0.15],
    [0.5, 0.5, 0.55], [0.2, 0.5, 0.35], [0.65, 0.25, 0.2],
  ];
  for (let i = from; i < to; i++) {
    const s = track.segments[i];
    const w = s.width / 2 + def.shoulder + def.wallDistance;
    const yaw = headingAt(track, i);

    // Concrete jersey barriers line the course.
    if (i % 2 === 0) {
      b.setMaterial(MAT.concrete, [0.19, 0.19, 0.2]);
      for (const side of [-1, 1]) {
        const p = place(track, i, side * w);
        b.pushTransform([p[0], p[1] + 0.42, p[2]], yaw, 0, 0);
        b.box(0.5, 0.84, 5.9);
        b.pop();
        if (i % 4 === 0) {
          b.setMaterial({ roughness: 0.4, metallic: 0.1, emissive: 0.9, paint: 0 }, [1.0, 0.6, 0.05]);
          b.pushTransform([p[0], p[1] + 0.72, p[2]], yaw, 0, 0);
          b.box(0.52, 0.16, 1.2);
          b.pop();
          b.setMaterial(MAT.concrete, [0.19, 0.19, 0.2]);
        }
      }
    }

    // Stacked shipping containers.
    if (i % 3 === 1 && rand() < 0.85) {
      const side = rand() < 0.5 ? -1 : 1;
      const base = place(track, i, side * (w + 4 + rand() * 8), (rand() - 0.5) * 5);
      const stack = 1 + Math.floor(rand() * 3);
      for (let k = 0; k < stack; k++) {
        const c = CONTAINER[Math.floor(rand() * CONTAINER.length)];
        b.setMaterial({ roughness: 0.75, metallic: 0.25, emissive: 0, paint: 0 },
          [c[0] * 0.5, c[1] * 0.5, c[2] * 0.5]);
        b.pushTransform([base[0], base[1] + 1.3 + k * 2.62, base[2]],
          yaw + (rand() - 0.5) * 0.25, 0, 0);
        b.box(2.44, 2.59, 6.06);
        // Corrugation.
        for (let r = 0; r < 8; r++) {
          b.pushTranslate(0, 0, -2.7 + r * 0.72);
          b.box(2.5, 2.4, 0.08);
          b.pop();
        }
        b.pop();
      }
    }

    // Warehouses with roller doors.
    if (i % 7 === 2 && rand() < 0.7) {
      const side = rand() < 0.5 ? -1 : 1;
      const p = place(track, i, side * (w + 12 + rand() * 8));
      const h = 7 + rand() * 5;
      b.setMaterial(MAT.concrete, [0.11, 0.115, 0.125]);
      b.pushTransform([p[0], p[1] + h / 2, p[2]], yaw, 0, 0);
      b.box(18 + rand() * 10, h, 14);
      b.setMaterial(MAT.metal, [0.16, 0.17, 0.18]);
      b.pushTranslate(0, -h / 2 + 2.2, -side * 7.1);
      b.box(5.0, 4.2, 0.2);
      b.pop();
      b.setMaterial({ roughness: 0.3, metallic: 0, emissive: 1.8, paint: 0 }, [0.8, 0.92, 1.0]);
      b.pushTranslate(0, h / 2 - 0.6, -side * 7.05);
      b.box(12, 0.6, 0.15);
      b.pop();
      b.pop();
      addLight(lights, [p[0], p[1] + h - 0.6, p[2]], [0.7, 0.85, 1.0], 20, 1.1);
    }

    // Floodlight masts.
    if (i % 9 === 4) {
      const side = rand() < 0.5 ? -1 : 1;
      const p = place(track, i, side * (w + 2.2));
      b.setMaterial(MAT.metal, [0.22, 0.23, 0.25]);
      b.pushTransform([p[0], p[1], p[2]], yaw, 0, 0);
      b.cylinder(0.11, 0.08, 9.5, 6, false);
      b.pop();
      b.setMaterial({ roughness: 0.25, metallic: 0.1, emissive: 3.2, paint: 0 }, [0.95, 0.97, 1.0]);
      for (const dx of [-0.5, 0.5]) {
        b.pushTransform([p[0], p[1] + 9.3, p[2]], yaw, 0, 0);
        b.box(0.8, 0.5, 0.25, dx, 0, -side * 0.4);
        b.pop();
      }
      addLight(lights, [p[0], p[1] + 9.2, p[2]], [0.85, 0.9, 1.0], 34, 2.4);
    }

    // Gantry crane straddling the course.
    if (i % 31 === 8) {
      const p = place(track, i, 0);
      b.setMaterial(MAT.metal, [0.3, 0.28, 0.22]);
      for (const side of [-1, 1]) {
        const q = place(track, i, side * (w + 3));
        b.pushTransform([q[0], q[1] + 8, q[2]], yaw, 0, 0);
        b.box(1.0, 16, 1.0);
        b.pop();
      }
      b.pushTransform([p[0], p[1] + 16.4, p[2]], yaw, 0, 0);
      b.box(2 * (w + 4), 1.2, 2.2);
      b.pop();
      b.setMaterial({ roughness: 0.3, metallic: 0, emissive: 2.4, paint: 0 }, [1.0, 0.3, 0.15]);
      b.pushTransform([p[0], p[1] + 17.2, p[2]], yaw, 0, 0);
      b.box(0.4, 0.4, 0.4);
      b.pop();
      addLight(lights, [p[0], p[1] + 16, p[2]], [1.0, 0.45, 0.2], 26, 1.4);
    }

    // Pipework and puddles for texture near the ground.
    if (i % 5 === 3 && rand() < 0.5) {
      const side = rand() < 0.5 ? -1 : 1;
      const p = place(track, i, side * (w + 1.4));
      b.setMaterial(MAT.metal, [0.25, 0.24, 0.22]);
      b.pushTransform([p[0], p[1] + 1.1, p[2]], yaw, 0, Math.PI / 2);
      b.cylinder(0.22, 0.22, 6.0, 8, false);
      b.pop();
    }
  }
}

/* ------------------------------------------------------------- start line -- */

function buildStartGantry(b, track, lights) {
  const s = track.segments[0];
  const yaw = Math.atan2(s.tangent[0], s.tangent[2]);
  const w = s.width / 2;

  b.setMaterial({ roughness: 0.6, metallic: 0.1, emissive: 0.15, paint: 0 }, [0.92, 0.92, 0.92]);
  // Chequered strip across the road.
  const cells = Math.max(6, Math.round(s.width / 0.6));
  for (let i = 0; i < cells; i++) {
    const off = -w + (i + 0.5) * (s.width / cells);
    b.setMaterial({ roughness: 0.6, metallic: 0.1, emissive: 0.1, paint: 0 },
      i % 2 ? [0.92, 0.92, 0.92] : [0.06, 0.06, 0.07]);
    b.pushTransform([s.p[0] + s.lateral[0] * off, s.p[1] + 0.03, s.p[2] + s.lateral[2] * off], yaw, 0, 0);
    b.box(s.width / cells, 0.02, 1.4);
    b.pop();
  }

  // Scaffold gantry with a neon banner.
  b.setMaterial(MAT.metal, [0.24, 0.25, 0.27]);
  for (const side of [-1, 1]) {
    b.pushTransform([s.p[0] + s.lateral[0] * side * (w + 1.2), s.p[1] + 3.2,
      s.p[2] + s.lateral[2] * side * (w + 1.2)], yaw, 0, 0);
    b.box(0.35, 6.4, 0.35);
    b.pop();
  }
  b.pushTransform([s.p[0], s.p[1] + 6.3, s.p[2]], yaw, 0, 0);
  b.box(s.width + 2.8, 0.5, 0.5);
  b.pop();
  b.setMaterial(MAT.neon, [1.0, 0.2, 0.5]);
  b.pushTransform([s.p[0], s.p[1] + 5.7, s.p[2]], yaw, 0, 0);
  b.box(s.width + 1.2, 0.55, 0.12);
  b.pop();
  addLight(lights, [s.p[0], s.p[1] + 5.7, s.p[2]], [1.0, 0.25, 0.55], 24, 2.2);
}
