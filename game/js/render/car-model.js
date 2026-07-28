// Toyota Mark II X71 (GX71) — built from parametric cross sections.
//
// The X71 is a study in straight lines: a flat bonnet, an upright glasshouse, a
// razor-thin C-pillar and quad rectangular headlamps under a chrome brow. The
// body is lofted from sections down the length of the car, then the details
// that give it away — the lamp brow, the beltline trim, the flat boot lid — are
// laid on top.
//
// Car space: +Z forward, +X right, +Y up, origin on the ground under the CG.

import { MeshBuilder, MAT, roundedSection } from '../core/geometry.js';
import { TAU, lerp, clamp, DEG } from '../core/math.js';

export const BODY_KITS = {
  stock: { label: 'Stock', flare: 0, lip: 0, skirt: 0, wing: 0, canards: false, pipes: 0 },
  aero: { label: 'Street aero', flare: 0.03, lip: 0.06, skirt: 0.04, wing: 0.35, canards: false, pipes: 0 },
  wide: { label: 'Wide-body ZOKU', flare: 0.085, lip: 0.10, skirt: 0.06, wing: 0.5, canards: true, pipes: 0 },
  bosozoku: { label: 'Bosozoku', flare: 0.11, lip: 0.22, skirt: 0.08, wing: 0.9, canards: true, pipes: 4 },
  timeattack: { label: 'Time attack', flare: 0.07, lip: 0.12, skirt: 0.05, wing: 1.15, canards: true, pipes: 0 },
};

export const RIM_STYLES = {
  work: { label: 'Work Equip 03', spokes: 8, dish: 0.055, spokeWidth: 0.055, mesh: false, lipColor: [0.82, 0.82, 0.86] },
  ssr: { label: 'SSR MK-III', spokes: 8, dish: 0.045, spokeWidth: 0.042, mesh: true, lipColor: [0.75, 0.76, 0.8] },
  watanabe: { label: 'RS Watanabe', spokes: 8, dish: 0.065, spokeWidth: 0.07, mesh: false, lipColor: [0.55, 0.56, 0.6] },
  te37: { label: 'Volk TE37', spokes: 6, dish: 0.03, spokeWidth: 0.075, mesh: false, lipColor: [0.5, 0.36, 0.16] },
  mesh: { label: 'BBS mesh', spokes: 16, dish: 0.04, spokeWidth: 0.03, mesh: true, lipColor: [0.8, 0.78, 0.7] },
};

// Car paint is a dielectric under clear coat, so metallic stays near zero for
// everything except an actual chrome wrap — push it up and the colour washes
// out into a grey sky reflection.
export const PAINT_FINISH = {
  gloss: { label: 'Gloss', roughness: 0.14, metallic: 0.04 },
  metallic: { label: 'Metallic', roughness: 0.22, metallic: 0.22 },
  pearl: { label: 'Pearl', roughness: 0.18, metallic: 0.14 },
  matte: { label: 'Matte', roughness: 0.68, metallic: 0.02 },
  chrome: { label: 'Chrome wrap', roughness: 0.07, metallic: 1.0 },
};

export const VINYLS = {
  none: { label: 'None' },
  stripe: { label: 'Racing stripe' },
  zoku: { label: 'Zoku script' },
  camo: { label: 'Urban camo' },
  sponsor: { label: 'Sponsor block' },
  flames: { label: 'Kaen' },
};

const BODY_LENGTH_FRONT = 2.32;
const BODY_LENGTH_REAR = -2.36;
const HALF_WIDTH = 0.845;

// z, halfWidth, sillY, beltY — the lower body's silhouette.
const BODY_SECTIONS = [
  [2.32, 0.760, 0.34, 0.70],
  [2.24, 0.812, 0.27, 0.80],
  [2.10, 0.838, 0.235, 0.855],
  [1.86, 0.845, 0.225, 0.885],
  [1.55, 0.845, 0.220, 0.905],
  [1.33, 0.845, 0.220, 0.920],
  [1.02, 0.845, 0.218, 0.945],
  [0.62, 0.842, 0.216, 0.965],
  [0.10, 0.845, 0.215, 0.972],
  [-0.55, 0.845, 0.215, 0.972],
  [-1.10, 0.845, 0.218, 0.968],
  [-1.42, 0.842, 0.222, 0.960],
  [-1.80, 0.836, 0.235, 0.950],
  [-2.10, 0.820, 0.265, 0.920],
  [-2.26, 0.795, 0.30, 0.870],
  [-2.36, 0.745, 0.35, 0.790],
];

// z, halfWidth, roofY — the glasshouse.
const CABIN_SECTIONS = [
  [0.66, 0.790, 0.98],
  [0.42, 0.782, 1.12],
  [0.18, 0.772, 1.30],
  [-0.02, 0.762, 1.375],
  [-0.42, 0.758, 1.392],
  [-0.86, 0.756, 1.388],
  [-1.12, 0.748, 1.345],
  [-1.36, 0.735, 1.215],
  [-1.56, 0.722, 1.055],
  [-1.63, 0.712, 0.995],
];

function sectionRing(z, hw, bottom, top, n = 20, squash = 1) {
  return roundedSection(hw, bottom, top, 0.10, z, n, squash);
}

/* ------------------------------------------------------------------ body -- */

export function buildBody(config = {}) {
  const kit = BODY_KITS[config.kit] || BODY_KITS.stock;
  const finish = PAINT_FINISH[config.finish] || PAINT_FINISH.gloss;
  const paint = { roughness: finish.roughness, metallic: finish.metallic, emissive: 0, paint: 1 };
  const trim = MAT.chrome;
  const b = new MeshBuilder();

  const rideDrop = clamp(config.rideDrop ?? 0, 0, 0.09);
  b.pushTranslate(0, -rideDrop, 0);

  /* lower body ---------------------------------------------------------- */
  // The shell is one closed loft with the two wheel openings cut out of it, so
  // the wheels sit in real arches rather than behind a slab-sided flank.
  b.setMaterial(paint, [1, 1, 1]);
  const rings = BODY_SECTIONS.map(([z, hw, sill, belt]) =>
    sectionRing(z, hw, sill, belt, 28, 0.94));
  b.loft(rings, { closeStart: true, closeEnd: true, cut: archCut });

  /* glasshouse: the shell itself is glass (built separately). What goes on
     the body mesh is the metal around it — roof skin, pillars, headers. The
     X71's signature is how thin those pillars are. */
  b.setMaterial(paint, [1, 1, 1]);
  b.pushTranslate(0, 1.386, -0.45); b.box(1.46, 0.035, 1.30); b.pop();       // roof skin
  b.pushTransform([0, 1.352, 0.13], 0, -0.30, 0); b.box(1.44, 0.05, 0.16); b.pop();   // header
  b.pushTransform([0, 1.336, -1.14], 0, 0.34, 0); b.box(1.42, 0.05, 0.16); b.pop();   // rear header
  for (const sx of [-1, 1]) {
    strut(b, sx * 0.714, 0.985, 0.63, sx * 0.652, 1.35, 0.14, 0.075, 0.085);  // A-pillar
    strut(b, sx * 0.768, 0.985, -0.10, sx * 0.762, 1.375, -0.13, 0.055, 0.075); // B-pillar
    strut(b, sx * 0.752, 0.985, -1.30, sx * 0.716, 1.34, -1.02, 0.05, 0.075);   // C-pillar
    // Quarter panel below the rear side glass.
    b.pushTransform([sx * 0.756, 0.985, -1.44], 0, 0, 0); b.box(0.06, 0.06, 0.42); b.pop();
  }

  /* bonnet: a flat panel with the faint centre crease of the era --------- */
  b.setMaterial(paint, [1, 1, 1]);
  for (const [z0, z1] of [[0.70, 1.10], [1.10, 1.55], [1.55, 1.94], [1.94, 2.16]]) {
    const y0 = bodyTopAt(z0) + 0.004;
    const y1 = bodyTopAt(z1) + 0.004;
    const w0 = bodyWidthAt(z0) - 0.02;
    const w1 = bodyWidthAt(z1) - 0.02;
    const i0 = b.vertex(-w0, y0, z0, 0, 1, 0, 0, 0);
    const i1 = b.vertex(w0, y0, z0, 0, 1, 0, 1, 0);
    const i2 = b.vertex(w1, y1, z1, 0, 1, 0, 1, 1);
    const i3 = b.vertex(-w1, y1, z1, 0, 1, 0, 0, 1);
    b.quad(i0, i1, i2, i3);
  }

  /* beltline and window trim -------------------------------------------- */
  b.setMaterial(trim, [0.86, 0.87, 0.9]);
  for (const sx of [-1, 1]) {
    b.pushTranslate(sx * 0.849, 0.952, -0.42);
    b.box(0.012, 0.028, 2.24);
    b.pop();
  }

  /* wheel arches and optional over-fenders -------------------------------- */
  for (const arch of ARCHES) {
    // Inner arch liner closes the hole left in the shell.
    b.setMaterial(MAT.plastic, [0.035, 0.035, 0.04]);
    for (const sx of [-1, 1]) archLiner(b, sx, arch);
    b.setMaterial(paint, [1, 1, 1]);
    for (const sx of [-1, 1]) archLip(b, sx, arch, kit.flare);
  }

  /* sills / side skirts --------------------------------------------------- */
  b.setMaterial(MAT.plastic, [0.06, 0.06, 0.07]);
  for (const sx of [-1, 1]) {
    b.pushTranslate(sx * (0.80 + kit.flare * 0.5), 0.185 - kit.skirt * 0.5, 0);
    b.box(0.10 + kit.skirt, 0.10 + kit.skirt, 2.30);
    b.pop();
  }

  /* front end: bumper, grille, lamp brow ---------------------------------- */
  b.setMaterial(MAT.plastic, [0.08, 0.08, 0.09]);
  b.pushTranslate(0, 0.46, 2.30); b.box(1.62, 0.30, 0.16); b.pop();
  if (kit.lip > 0.001) {
    b.pushTransform([0, 0.30 - kit.lip * 0.6, 2.34 + kit.lip * 0.5], 0, -0.12, 0);
    b.box(1.70 + kit.flare, 0.06, 0.34 + kit.lip);
    b.pop();
  }
  // Radiator grille — a slatted rectangle between the lamps.
  b.setMaterial(MAT.plastic, [0.03, 0.03, 0.035]);
  b.pushTranslate(0, 0.70, 2.315); b.box(1.06, 0.20, 0.06); b.pop();
  b.setMaterial(trim, [0.7, 0.71, 0.75]);
  for (let i = 0; i < 5; i++) {
    b.pushTranslate(0, 0.62 + i * 0.042, 2.345);
    b.box(1.02, 0.012, 0.02);
    b.pop();
  }
  // Chrome brow over the quad lamps.
  b.pushTranslate(0, 0.815, 2.30); b.box(1.66, 0.03, 0.12); b.pop();

  /* rear end -------------------------------------------------------------- */
  b.setMaterial(MAT.plastic, [0.08, 0.08, 0.09]);
  b.pushTranslate(0, 0.46, -2.34); b.box(1.58, 0.28, 0.14); b.pop();
  b.setMaterial(trim, [0.7, 0.71, 0.75]);
  b.pushTranslate(0, 0.80, -2.33); b.box(1.60, 0.025, 0.10); b.pop();

  /* mirrors, handles, aerial ---------------------------------------------- */
  b.setMaterial(paint, [1, 1, 1]);
  for (const sx of [-1, 1]) {
    b.pushTransform([sx * 0.87, 0.99, 0.50], 0, 0, sx * 0.2);
    b.box(0.05, 0.035, 0.09);
    b.pop();
    b.pushTransform([sx * 0.94, 1.03, 0.47], sx * 0.18, 0, 0);
    b.box(0.055, 0.10, 0.16);
    b.pop();
  }
  b.setMaterial(trim, [0.78, 0.79, 0.82]);
  for (const sx of [-1, 1]) {
    for (const z of [0.28, -0.62]) {
      b.pushTranslate(sx * 0.856, 0.90, z);
      b.box(0.014, 0.03, 0.14);
      b.pop();
    }
  }

  /* exhaust ---------------------------------------------------------------- */
  b.setMaterial(MAT.metal, [0.55, 0.56, 0.6]);
  if (kit.pipes > 0) {
    // Bosozoku takeyari — the pipes climb over the rear quarter.
    for (let i = 0; i < kit.pipes; i++) {
      const sx = i % 2 === 0 ? -1 : 1;
      const k = Math.floor(i / 2);
      b.pushTransform([sx * (0.55 + k * 0.16), 0.55 + k * 0.22, -2.0], 0, 0, 0);
      b.cylinder(0.045, 0.05, 1.5 + k * 0.4, 10, true, 0, 0, 0);
      b.pop();
    }
  } else {
    for (const sx of [-1, 1]) {
      b.pushTransform([sx * 0.34, 0.30, -2.30], 0, Math.PI / 2, 0);
      b.cylinder(0.052, 0.058, 0.22, 12, true);
      b.pop();
    }
  }

  /* wing -------------------------------------------------------------------- */
  if (kit.wing > 0.01) {
    b.setMaterial(config.wingCarbon ? MAT.plastic : paint, config.wingCarbon ? [0.08, 0.08, 0.09] : [1, 1, 1]);
    const h = 0.20 + kit.wing * 0.34;
    for (const sx of [-1, 1]) {
      b.pushTranslate(sx * 0.60, 0.99 + h * 0.5, -2.02);
      b.box(0.035, h, 0.24);
      b.pop();
    }
    b.pushTransform([0, 0.99 + h, -2.04], 0, 0.12, 0);
    b.box(1.52, 0.035, 0.30);
    b.pop();
    if (kit.wing > 0.8) {
      b.pushTransform([0, 0.99 + h + 0.10, -2.16], 0, 0.3, 0);
      b.box(1.48, 0.02, 0.12);
      b.pop();
    }
  }
  if (kit.canards) {
    b.setMaterial(MAT.plastic, [0.07, 0.07, 0.08]);
    for (const sx of [-1, 1]) {
      for (let i = 0; i < 2; i++) {
        b.pushTransform([sx * (0.78 + kit.flare), 0.40 + i * 0.11, 2.24], sx * 0.25, 0, sx * -0.35);
        b.box(0.24, 0.012, 0.16);
        b.pop();
      }
    }
  }

  /* vinyls ------------------------------------------------------------------ */
  applyVinyl(b, config.vinyl, config.vinylColor || [0.9, 0.1, 0.25], kit);

  /* interior ---------------------------------------------------------------- */
  buildInterior(b, config);

  b.pop();
  return b;
}

function bodyTopAt(z) {
  for (let i = 1; i < BODY_SECTIONS.length; i++) {
    const [z1, , , t1] = BODY_SECTIONS[i];
    const [z0, , , t0] = BODY_SECTIONS[i - 1];
    if (z <= z0 && z >= z1) return lerp(t0, t1, (z0 - z) / (z0 - z1));
  }
  return 0.95;
}

function bodyWidthAt(z) {
  for (let i = 1; i < BODY_SECTIONS.length; i++) {
    const [z1, w1] = BODY_SECTIONS[i];
    const [z0, w0] = BODY_SECTIONS[i - 1];
    if (z <= z0 && z >= z1) return lerp(w0, w1, (z0 - z) / (z0 - z1));
  }
  return HALF_WIDTH;
}

/** An oriented box between two points — pillars, braces, arch supports. */
function strut(b, x0, y0, z0, x1, y1, z1, width, thickness) {
  const dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
  const len = Math.hypot(dx, dy, dz) || 0.01;
  const yaw = Math.atan2(dx, dz);
  const pitch = Math.atan2(dy, Math.hypot(dx, dz));
  b.pushTransform([(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2], yaw, 0, 0);
  b.push(rotXMat(Math.PI / 2 - pitch));
  b.box(width, len, thickness);
  b.pop();
  b.pop();
}

// The two wheel openings: centre z, half-length, height above the sill line.
const ARCHES = [
  { z: 1.33, rz: 0.48, ry: 0.40, base: 0.315 },
  { z: -1.33, rz: 0.50, ry: 0.42, base: 0.315 },
];
const ARCH_INNER_X = 0.50;   // the shell stays closed inboard of this

/** True for body points that fall inside a wheel opening. */
function archCut(p) {
  if (Math.abs(p[0]) < ARCH_INNER_X) return false;
  for (const a of ARCHES) {
    const dz = (p[2] - a.z) / a.rz;
    if (Math.abs(dz) >= 1) continue;
    const top = a.base + a.ry * Math.sqrt(1 - dz * dz);
    if (p[1] < top) return true;
  }
  return false;
}

/** Dark liner behind the opening so you do not see through the car. */
function archLiner(b, sx, arch) {
  const seg = 16;
  const inner = sx * (ARCH_INNER_X - 0.02);
  const outer = sx * (HALF_WIDTH - 0.01);
  const point = (a, x) => [
    x,
    arch.base + Math.sin(a) * arch.ry * 0.99,
    arch.z + Math.cos(a) * arch.rz * 0.99,
  ];
  for (let i = 0; i < seg; i++) {
    const a0 = Math.PI * (i / seg);
    const a1 = Math.PI * ((i + 1) / seg);
    const i0 = point(a0, inner), i1 = point(a1, inner);
    const o0 = point(a0, outer), o1 = point(a1, outer);
    // Faces inward/downward toward the wheel.
    b.polygon(sx > 0 ? [i0, i1, o1, o0] : [o0, o1, i1, i0], null, 0.5);
  }
}

/**
 * The lip around a wheel opening: a ribbon of quads swept over the arch, so it
 * reads as a flange rather than a row of blocks. `flare` pushes it outboard,
 * which is what turns a stock arch into an over-fender.
 */
function archLip(b, sx, arch, flare) {
  const seg = 16;
  const width = 0.03 + flare;
  const point = (a, out, dy) => [
    sx * (HALF_WIDTH + 0.002 + out),
    arch.base + Math.sin(a) * arch.ry + dy,
    arch.z + Math.cos(a) * arch.rz,
  ];
  for (let i = 0; i < seg; i++) {
    const a0 = Math.PI * (i / seg);
    const a1 = Math.PI * ((i + 1) / seg);
    const i0 = point(a0, 0, 0), i1 = point(a1, 0, 0);
    // The outer edge rolls slightly down and out, like a rolled arch.
    const o0 = point(a0, width, -0.014 * Math.sin(a0));
    const o1 = point(a1, width, -0.014 * Math.sin(a1));
    b.polygon(sx > 0 ? [i0, o0, o1, i1] : [i1, o1, o0, i0], null, 0.5);
    const u0 = [o0[0], o0[1] - 0.03, o0[2]];
    const u1 = [o1[0], o1[1] - 0.03, o1[2]];
    b.polygon(sx > 0 ? [o0, u0, u1, o1] : [o1, u1, u0, o0], null, 0.5);
  }
}

function rotXMat(a) {
  const c = Math.cos(a), s = Math.sin(a);
  return new Float32Array([1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1]);
}

/* ---------------------------------------------------------------- vinyls -- */

function applyVinyl(b, kind, color, kit) {
  if (!kind || kind === 'none') return;
  const flat = { roughness: 0.35, metallic: 0.1, emissive: 0, paint: 0 };
  b.setMaterial(flat, color);
  const x = HALF_WIDTH + 0.012;

  const panel = (sx, y, z, h, len, tilt = 0) => {
    b.pushTransform([sx * x, y, z], 0, 0, tilt);
    b.box(0.006, h, len);
    b.pop();
  };

  switch (kind) {
    case 'stripe':
      for (const sx of [-1, 1]) {
        panel(sx, 0.68, -0.1, 0.09, 3.6);
        panel(sx, 0.56, -0.1, 0.04, 3.6);
      }
      b.pushTranslate(0, 1.399, -0.4); b.box(0.22, 0.006, 3.0); b.pop();
      break;
    case 'zoku':
      for (const sx of [-1, 1]) {
        // Blocky brush-script suggestion built from angled bars.
        const strokes = [[0.62, 0.6, 0.5, 0.02], [0.86, 0.5, 0.36, -0.5], [0.30, 0.55, 0.42, 0.45],
          [0.10, 0.42, 0.30, 0.0], [-0.20, 0.6, 0.46, -0.35]];
        for (const [z, y, len, tilt] of strokes) panel(sx, y, z, 0.05, len, tilt);
      }
      break;
    case 'camo': {
      let seed = 7;
      const rand = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
      for (const sx of [-1, 1]) {
        for (let i = 0; i < 22; i++) {
          const z = lerp(-2.0, 2.0, rand());
          const y = lerp(0.32, 0.92, rand());
          b.setMaterial(flat, i % 3 === 0 ? color : [color[0] * 0.4, color[1] * 0.45, color[2] * 0.5]);
          panel(sx, y, z, 0.10 + rand() * 0.16, 0.20 + rand() * 0.45, (rand() - 0.5) * 0.6);
        }
      }
      break;
    }
    case 'sponsor':
      for (const sx of [-1, 1]) {
        for (let i = 0; i < 5; i++) {
          const z = 1.2 - i * 0.62;
          b.setMaterial(flat, i % 2 ? [0.95, 0.95, 0.95] : color);
          panel(sx, 0.62, z, 0.24, 0.44);
        }
      }
      break;
    case 'flames':
      for (const sx of [-1, 1]) {
        for (let i = 0; i < 7; i++) {
          const t = i / 6;
          b.setMaterial(flat, [lerp(1, color[0], t), lerp(0.55, color[1], t), lerp(0.05, color[2], t)]);
          panel(sx, 0.42 + Math.sin(i * 1.7) * 0.12, 1.6 - i * 0.52, 0.06 + t * 0.16, 0.5, Math.sin(i) * 0.4);
        }
      }
      break;
  }
}

/* -------------------------------------------------------------- interior -- */

function buildInterior(b, config) {
  const dark = { roughness: 0.85, metallic: 0.02, emissive: 0, paint: 0 };
  b.setMaterial(dark, [0.055, 0.055, 0.065]);

  // Floor and firewall keep the cabin from looking hollow through the glass.
  // Everything here has to stay under the beltline (~0.95) or it pokes out
  // through the bonnet.
  b.pushTranslate(0, 0.345, -0.40); b.box(1.52, 0.03, 2.3); b.pop();
  b.pushTranslate(0, 0.65, 0.58); b.box(1.50, 0.56, 0.06); b.pop();

  // Dashboard with a hooded binnacle.
  b.pushTranslate(0, 0.845, 0.42); b.box(1.48, 0.20, 0.30); b.pop();
  b.pushTransform([0, 0.945, 0.32], 0, -0.30, 0); b.box(0.60, 0.03, 0.26); b.pop();

  // Instruments — emissive so they glow at night in the cockpit view.
  b.setMaterial({ roughness: 0.4, metallic: 0, emissive: 1.4, paint: 0 }, [0.95, 0.35, 0.12]);
  for (const dx of [-0.15, 0.15]) {
    b.pushTransform([dx + 0.34, 0.912, 0.30], 0, -0.30, 0);
    b.cylinder(0.07, 0.07, 0.006, 16, true, 0, 0, 0);
    b.pop();
  }
  b.setMaterial(dark, [0.05, 0.05, 0.06]);

  // Centre console and shifter.
  b.pushTranslate(0, 0.55, 0.05); b.box(0.30, 0.30, 0.90); b.pop();
  b.setMaterial({ roughness: 0.3, metallic: 0.6, emissive: 0, paint: 0 }, [0.7, 0.7, 0.75]);
  b.pushTranslate(0.0, 0.78, 0.12); b.cylinder(0.014, 0.012, 0.20, 8, true); b.pop();
  b.pushTranslate(0.0, 0.98, 0.12); b.cylinder(0.032, 0.03, 0.05, 10, true); b.pop();
  // Hydraulic handbrake lever, vertical, right of the console.
  b.pushTransform([0.22, 0.72, -0.05], 0, -0.25, 0);
  b.cylinder(0.016, 0.014, 0.42, 8, true);
  b.pop();

  // Steering wheel (right-hand drive, as it left Toyota City).
  const wheelX = 0.34;
  b.setMaterial({ roughness: 0.6, metallic: 0.1, emissive: 0, paint: 0 }, [0.08, 0.08, 0.09]);
  b.pushTransform([wheelX, 1.00, 0.20], 0, -0.30 + Math.PI / 2, Math.PI / 2);
  torus(b, 0.175, 0.017, 22, 8);
  for (let i = 0; i < 3; i++) {
    const a = i * (TAU / 3) + 0.4;
    b.pushTransform([Math.cos(a) * 0.09, 0, Math.sin(a) * 0.09], -a, 0, 0);
    b.box(0.02, 0.012, 0.18);
    b.pop();
  }
  b.pop();
  b.pushTransform([wheelX, 1.00, 0.20], 0, -0.30, 0);
  b.setMaterial({ roughness: 0.5, metallic: 0.3, emissive: 0, paint: 0 }, [0.15, 0.15, 0.17]);
  b.cylinder(0.03, 0.03, 0.22, 10, true, 0, 0, 0.0);
  b.pop();

  // Bucket seats.
  for (const sx of [1, -1]) {
    b.setMaterial({ roughness: 0.8, metallic: 0.02, emissive: 0, paint: 0 },
      sx > 0 ? [0.14, 0.05, 0.06] : [0.07, 0.07, 0.08]);
    b.pushTranslate(sx * 0.34, 0.46, -0.18);
    b.box(0.48, 0.10, 0.50);
    b.pop();
    b.pushTransform([sx * 0.34, 0.76, -0.45], 0, 0.22, 0);
    b.box(0.46, 0.62, 0.12);
    b.pop();
    for (const bx of [-1, 1]) {
      b.pushTransform([sx * 0.34 + bx * 0.21, 0.76, -0.42], 0, 0.22, 0);
      b.box(0.06, 0.58, 0.16);
      b.pop();
    }
  }

  // Rear bench.
  b.setMaterial({ roughness: 0.8, metallic: 0.02, emissive: 0, paint: 0 }, [0.08, 0.08, 0.09]);
  b.pushTranslate(0, 0.48, -1.05); b.box(1.36, 0.12, 0.50); b.pop();
  b.pushTransform([0, 0.74, -1.30], 0, 0.25, 0); b.box(1.36, 0.52, 0.12); b.pop();

  if (config.cage !== false) {
    b.setMaterial({ roughness: 0.35, metallic: 0.7, emissive: 0, paint: 0 },
      config.cageColor || [0.85, 0.85, 0.88]);
    const tube = (x0, y0, z0, x1, y1, z1, r = 0.024) => {
      const dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
      const len = Math.hypot(dx, dy, dz);
      const yaw = Math.atan2(dx, dz);
      const pitch = Math.atan2(dy, Math.hypot(dx, dz));
      b.pushTransform([x0, y0, z0], yaw, 0, 0);
      b.pushTransform([0, 0, 0], 0, 0, 0);
      b.push(rotX(Math.PI / 2 - pitch));
      b.cylinder(r, r, len, 8, true);
      b.pop(); b.pop(); b.pop();
    };
    // Main hoop, A-pillar bars, harness bar, door bars.
    tube(-0.66, 0.36, -0.62, -0.66, 1.30, -0.62);
    tube(0.66, 0.36, -0.62, 0.66, 1.30, -0.62);
    tube(-0.66, 1.28, -0.62, 0.66, 1.28, -0.62);
    tube(-0.66, 1.28, -0.62, -0.72, 1.30, 0.55);
    tube(0.66, 1.28, -0.62, 0.72, 1.30, 0.55);
    tube(-0.72, 1.30, 0.55, 0.72, 1.30, 0.55);
    tube(-0.66, 1.28, -0.62, -0.70, 0.52, -1.70);
    tube(0.66, 1.28, -0.62, 0.70, 0.52, -1.70);
    tube(-0.66, 0.92, -0.62, 0.66, 0.92, -0.62, 0.02);
    for (const sx of [-1, 1]) tube(sx * 0.70, 0.55, 0.42, sx * 0.70, 0.80, -0.55, 0.02);
  }
}

function rotX(a) {
  const c = Math.cos(a), s = Math.sin(a);
  return new Float32Array([1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1]);
}

function torus(b, radius, tube, seg, tubeSeg) {
  const idx = [];
  for (let i = 0; i <= seg; i++) {
    const u = (i / seg) * TAU;
    const row = [];
    for (let j = 0; j <= tubeSeg; j++) {
      const v = (j / tubeSeg) * TAU;
      const cx = Math.cos(u) * radius, cz = Math.sin(u) * radius;
      const nx = Math.cos(u) * Math.cos(v), ny = Math.sin(v), nz = Math.sin(u) * Math.cos(v);
      row.push(b.vertex(cx + nx * tube, ny * tube, cz + nz * tube, nx, ny, nz, i / seg, j / tubeSeg));
    }
    idx.push(row);
  }
  for (let i = 0; i < seg; i++) {
    for (let j = 0; j < tubeSeg; j++) {
      b.quad(idx[i][j], idx[i + 1][j], idx[i + 1][j + 1], idx[i][j + 1]);
    }
  }
}

/* ----------------------------------------------------------------- glass -- */

/**
 * The glasshouse volume. It is lofted as one closed shell and drawn in the
 * transparent pass, so from outside it reads as dark tinted glass wrapped by
 * the body-coloured pillars, and from the driver's seat you can see out of it.
 */
export function buildGlass(config = {}) {
  const b = new MeshBuilder();
  const tint = clamp(config.tint ?? 0.75, 0, 0.95);
  const glass = { roughness: 0.045, metallic: 0.35, emissive: 0, paint: 0 };
  b.setMaterial(glass, [lerp(0.26, 0.015, tint), lerp(0.29, 0.02, tint), lerp(0.35, 0.03, tint)]);
  const rideDrop = clamp(config.rideDrop ?? 0, 0, 0.09);
  b.pushTranslate(0, -rideDrop, 0);
  const cabin = CABIN_SECTIONS.map(([z, hw, top]) => sectionRing(z, hw, 0.925, top, 20, 0.9));
  b.loft(cabin, { closeStart: true, closeEnd: true });
  b.pop();
  return b;
}

/* ---------------------------------------------------------------- lights -- */

export function buildHeadlights() {
  const b = new MeshBuilder();
  const lens = { roughness: 0.12, metallic: 0.1, emissive: 1.0, paint: 0 };
  b.setMaterial(lens, [1.0, 0.96, 0.86]);
  // Quad rectangular sealed beams, the X71's signature.
  for (const sx of [-1, 1]) {
    for (const dx of [0.19, 0.40]) {
      b.pushTranslate(sx * (0.35 + dx * 0.5), 0.75, 2.33);
      b.box(0.20, 0.115, 0.02);
      b.pop();
    }
  }
  // Amber corner markers.
  b.setMaterial({ roughness: 0.2, metallic: 0.1, emissive: 0.8, paint: 0 }, [1.0, 0.55, 0.12]);
  for (const sx of [-1, 1]) {
    b.pushTranslate(sx * 0.74, 0.74, 2.315);
    b.box(0.10, 0.10, 0.03);
    b.pop();
  }
  return b;
}

export function buildTaillights() {
  const b = new MeshBuilder();
  const lens = { roughness: 0.15, metallic: 0.05, emissive: 1.0, paint: 0 };
  b.setMaterial(lens, [1.0, 0.09, 0.08]);
  // Full-width lamp panel split into segments by chrome ribs.
  for (let i = 0; i < 6; i++) {
    const x = -0.66 + i * 0.264;
    b.pushTranslate(x, 0.70, -2.345);
    b.box(0.22, 0.20, 0.02);
    b.pop();
  }
  b.setMaterial({ roughness: 0.2, metallic: 0.1, emissive: 0.9, paint: 0 }, [1.0, 0.85, 0.6]);
  for (const sx of [-1, 1]) {
    b.pushTranslate(sx * 0.30, 0.60, -2.35);
    b.box(0.18, 0.07, 0.02);
    b.pop();
  }
  return b;
}

/* ----------------------------------------------------------------- wheel -- */

/**
 * One wheel, centred on the origin, axis along X. Built once and drawn four
 * times with a per-wheel transform.
 */
export function buildWheel(config = {}) {
  const style = RIM_STYLES[config.rim] || RIM_STYLES.work;
  const b = new MeshBuilder();
  const radius = config.radius ?? 0.318;
  const width = config.width ?? 0.225;
  const rimRadius = radius * (config.profile ?? 0.66);
  const rimColor = config.rimColor || [0.72, 0.73, 0.78];

  // Tire carcass: a torus-ish barrel with a squared shoulder.
  b.setMaterial(MAT.rubber, [0.045, 0.045, 0.05]);
  const seg = 26;
  const shoulder = 0.028;
  const profile = [
    [-width / 2, rimRadius],
    [-width / 2 - 0.005, radius - shoulder * 2],
    [-width / 2 + shoulder, radius],
    [width / 2 - shoulder, radius],
    [width / 2 + 0.005, radius - shoulder * 2],
    [width / 2, rimRadius],
  ];
  revolve(b, profile, seg);

  // Tread blocks give the tire a silhouette instead of a smooth ring.
  b.setMaterial(MAT.rubber, [0.06, 0.06, 0.065]);
  for (let i = 0; i < seg; i++) {
    const a = (i / seg) * TAU;
    if (i % 2) continue;
    b.pushTransform([0, Math.cos(a) * radius, Math.sin(a) * radius], 0, 0, 0);
    b.push(rotAxisX(a));
    b.box(width * 0.86, 0.008, radius * 0.16);
    b.pop(); b.pop();
  }

  // Rim barrel and lip.
  b.setMaterial({ roughness: 0.22, metallic: 0.85, emissive: 0, paint: 0 }, rimColor);
  const lipProfile = [
    [-width / 2 + 0.005, rimRadius],
    [-width / 2 + 0.02, rimRadius - 0.012],
    [width / 2 - 0.02 - style.dish, rimRadius - 0.012],
    [width / 2 - style.dish, rimRadius],
  ];
  revolve(b, lipProfile, seg);
  b.setMaterial({ roughness: 0.12, metallic: 0.95, emissive: 0, paint: 0 }, style.lipColor);
  b.pushTransform([width / 2 - style.dish, 0, 0], 0, 0, 0);
  b.push(rotAxisZ(Math.PI / 2));
  b.cylinder(rimRadius, rimRadius - 0.006, style.dish, seg, false, 0, 0, 0);
  b.pop(); b.pop();

  // Face: spokes plus an optional mesh infill.
  b.setMaterial({ roughness: 0.25, metallic: 0.8, emissive: 0, paint: 0 }, rimColor);
  const faceX = width / 2 - style.dish - 0.005;
  const spokes = style.spokes;
  for (let i = 0; i < spokes; i++) {
    const a = (i / spokes) * TAU;
    b.pushTransform([faceX, 0, 0], 0, 0, 0);
    b.push(rotAxisX(a));
    b.box(0.03, rimRadius * 0.92, style.spokeWidth, 0, rimRadius * 0.46, 0);
    b.pop(); b.pop();
  }
  if (style.mesh) {
    for (let i = 0; i < spokes; i++) {
      const a = ((i + 0.5) / spokes) * TAU;
      b.pushTransform([faceX - 0.008, 0, 0], 0, 0, 0);
      b.push(rotAxisX(a));
      b.box(0.018, rimRadius * 0.8, style.spokeWidth * 0.6, 0, rimRadius * 0.42, 0);
      b.pop(); b.pop();
    }
  }
  // Centre cap and lug nuts.
  b.pushTransform([faceX, 0, 0], 0, 0, 0);
  b.push(rotAxisZ(Math.PI / 2));
  b.cylinder(0.055, 0.05, 0.02, 12, true, 0, 0, 0);
  b.pop(); b.pop();
  b.setMaterial({ roughness: 0.3, metallic: 0.9, emissive: 0, paint: 0 }, [0.6, 0.6, 0.65]);
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * TAU + 0.4;
    b.pushTransform([faceX + 0.004, Math.cos(a) * 0.075, Math.sin(a) * 0.075], 0, 0, 0);
    b.push(rotAxisZ(Math.PI / 2));
    b.cylinder(0.012, 0.011, 0.016, 6, true, 0, 0, 0);
    b.pop(); b.pop();
  }

  // Brake disc and caliper, visible through the spokes.
  b.setMaterial({ roughness: 0.45, metallic: 0.7, emissive: 0, paint: 0 }, [0.3, 0.3, 0.33]);
  b.push(rotAxisZ(Math.PI / 2));
  b.cylinder(rimRadius * 0.74, rimRadius * 0.74, 0.022, 20, true, 0, -0.011, 0);
  b.pop();
  b.setMaterial({ roughness: 0.4, metallic: 0.5, emissive: 0, paint: 0 },
    config.caliperColor || [0.75, 0.12, 0.1]);
  b.pushTransform([-0.02, rimRadius * 0.6, 0], 0, 0, 0);
  b.box(0.055, 0.11, 0.05);
  b.pop();

  return b;
}

function revolve(b, profile, seg) {
  const rings = [];
  for (const [x, r] of profile) {
    const ring = [];
    for (let i = 0; i < seg; i++) {
      const a = (i / seg) * TAU;
      ring.push([x, Math.cos(a) * r, Math.sin(a) * r]);
    }
    rings.push(ring);
  }
  b.loft(rings, { closeStart: false, closeEnd: false });
}

function rotAxisX(a) {
  const c = Math.cos(a), s = Math.sin(a);
  return new Float32Array([1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1]);
}

function rotAxisZ(a) {
  const c = Math.cos(a), s = Math.sin(a);
  return new Float32Array([c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
}

/** Where the driver's eyes sit, for the cockpit camera. */
export const DRIVER_EYE = [0.34, 1.16, 0.16];
