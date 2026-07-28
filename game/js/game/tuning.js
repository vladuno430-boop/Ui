// Parts catalogue and the setup sheet.
//
// Parts are functions that mutate the physics setup, so a build is just an
// ordered fold over `baseSetup()`. The alignment/setup sliders are applied
// afterwards and can undo or exaggerate anything the parts did.

import { baseSetup } from '../physics/vehicle.js';
import { COMPOUND } from '../physics/tire.js';
import { clamp, lerp, DEG } from '../core/math.js';
import { BODY_KITS, RIM_STYLES, PAINT_FINISH, VINYLS } from '../render/car-model.js';

const part = (id, name, price, apply, extra = {}) => ({ id, name, price, apply, ...extra });

export const PARTS = {
  engine: [
    part('engine_stock', '1G-GTE — stock', 0, (s) => { }, { note: '2.0 twin-turbo six, as delivered', power: 185 }),
    part('engine_intake', 'Intake & exhaust', 4200, (s) => {
      s.peakTorque *= 1.06; s.boostEfficiency += 0.03; s.engineBrakeCoef *= 0.96;
    }, { note: 'Free-flow everything. Wakes the mid-range up.' }),
    part('engine_cams', 'Cams & valve springs', 9800, (s) => {
      s.peakTorque *= 1.09; s.redline += 400; s.limiter += 400; s.engineInertia *= 0.94;
    }, { note: 'Revs harder, idles worse.' }),
    part('engine_1jz', '1JZ-GTE swap', 26000, (s) => {
      s.peakTorque = 250; s.redline = 7200; s.limiter = 7500; s.mass += 28;
      s.engineInertia = 0.27; s.weightDistFront += 0.012;
    }, { note: 'The obvious answer. 2.5 litres of iron.' }),
    part('engine_2jz', '2JZ-GTE swap', 48000, (s) => {
      s.peakTorque = 300; s.redline = 7400; s.limiter = 7700; s.mass += 46;
      s.engineInertia = 0.31; s.weightDistFront += 0.02; s.boostEfficiency += 0.04;
    }, { note: 'Three litres. Everything downstream needs to be stronger.' }),
    part('engine_built', 'Built 2JZ — forged', 82000, (s) => {
      s.peakTorque = 345; s.redline = 8000; s.limiter = 8300; s.mass += 44;
      s.engineInertia = 0.29; s.weightDistFront += 0.02; s.boostEfficiency += 0.08;
    }, { note: 'Sleeved, forged, and completely unreasonable.' }),
  ],

  turbo: [
    part('turbo_stock', 'Stock CT12 twins', 0, (s) => { }, { note: '0.55 bar, spools instantly' }),
    part('turbo_boost', 'Boost controller', 1800, (s) => {
      s.boostTarget += 0.22; s.turboLag *= 0.98;
    }, { note: 'Free power, shorter engine life.' }),
    part('turbo_single', 'Single T67 conversion', 14500, (s) => {
      s.boostTarget = 1.15; s.turboLag = 0.85; s.boostSpoolRpm = 3900; s.boostEfficiency += 0.02;
    }, { note: 'Nothing, nothing, nothing — then everything.' }),
    part('turbo_big', 'GT35 billet', 27000, (s) => {
      s.boostTarget = 1.6; s.turboLag = 1.05; s.boostSpoolRpm = 4500; s.boostEfficiency += 0.05;
    }, { note: 'Enormous. Bring patience and a long third gear.' }),
    part('turbo_antilag', 'Anti-lag system', 12000, (s) => {
      s.antiLag = true; s.turboLag *= 0.55; s.boostSpoolRpm *= 0.86;
    }, { note: 'Keeps it on boost off throttle. Loud, hot, illegal.' }),
  ],

  suspension: [
    part('susp_stock', 'Stock struts', 0, (s) => { }),
    part('susp_lowering', 'Lowering springs', 2400, (s) => {
      s.springFront *= 1.25; s.springRear *= 1.22;
      s.rideHeightFront -= 0.03; s.rideHeightRear -= 0.03; s.cgHeight -= 0.025;
    }),
    part('susp_coilover', 'Adjustable coilovers', 8600, (s) => {
      s.springFront = 62000; s.springRear = 54000;
      s.damperFront = 5200; s.damperRear = 4600;
      s.rideHeightFront = 0.085; s.rideHeightRear = 0.095; s.cgHeight -= 0.04;
      s.rollCentreFront = 0.06; s.rollCentreRear = 0.10;
    }, { note: 'Height, preload and damping become yours to get wrong.' }),
    part('susp_pro', 'Circuit-spec dampers', 19500, (s) => {
      s.springFront = 78000; s.springRear = 66000;
      s.damperFront = 7000; s.damperRear = 6100;
      s.rideHeightFront = 0.075; s.rideHeightRear = 0.085; s.cgHeight -= 0.05;
      s.rollCentreFront = 0.05; s.rollCentreRear = 0.09;
      s.barFront = 18000; s.barRear = 13500;
    }),
    part('susp_angle', 'Angle kit & knuckles', 11200, (s) => {
      s.maxSteerAngle = 56 * DEG; s.ackermann = 0.15; s.steerSpeed = 6.4;
      s.casterTrail = 0.052;
    }, { note: '56° of lock. Catching it is suddenly possible.' }),
  ],

  differential: [
    part('diff_open', 'Open differential', 0, (s) => {
      s.diffPreload = 8; s.diffPowerRamp = 0.05; s.diffCoastRamp = 0.02; s.diffViscous = 1.0;
    }, { note: 'One wheel peel. Not a drift diff.' }),
    part('diff_viscous', 'Viscous LSD', 3200, (s) => {
      s.diffPreload = 30; s.diffPowerRamp = 0.25; s.diffCoastRamp = 0.15; s.diffViscous = 9;
    }),
    part('diff_15', '1.5-way clutch LSD', 7400, (s) => {
      s.diffPreload = 70; s.diffPowerRamp = 0.55; s.diffCoastRamp = 0.25; s.diffViscous = 7;
    }, { note: 'Locks under power, freer on the overrun.' }),
    part('diff_2way', '2-way clutch LSD', 9900, (s) => {
      s.diffPreload = 95; s.diffPowerRamp = 0.72; s.diffCoastRamp = 0.62; s.diffViscous = 8;
    }, { note: 'Predictable in and out. The standard answer.' }),
    part('diff_welded', 'Welded diff', 600, (s) => {
      s.diffPreload = 900; s.diffPowerRamp = 1.0; s.diffCoastRamp = 1.0; s.diffViscous = 40;
    }, { note: 'Cheap, brutal, hops on tight corners.' }),
  ],

  gearbox: [
    part('gear_stock', 'W58 5-speed', 0, (s) => { }),
    part('gear_r154', 'R154 5-speed', 6800, (s) => {
      s.gearRatios = [3.25, 1.955, 1.31, 1.0, 0.753];
      s.clutchCapacity = 780; s.shiftTime = 0.19;
    }),
    part('gear_close', 'Close-ratio dog box', 21000, (s) => {
      s.gearRatios = [2.72, 1.88, 1.42, 1.14, 0.95];
      s.clutchCapacity = 950; s.shiftTime = 0.11; s.drivelineEfficiency = 0.93;
    }, { note: 'Keeps it on boost between gears.' }),
    part('gear_twin', 'Twin-plate clutch', 5400, (s) => {
      s.clutchCapacity += 320; s.clutchStiffness += 60; s.drivelineInertia *= 0.82;
    }, { note: 'Grabby. Perfect for clutch kicks.' }),
  ],

  wheels: [
    part('wheel_stock', '15" steel — 195 section', 0, (s) => {
      s.wheelRadius = 0.315; s.tireWidthFront = 0.195; s.tireWidthRear = 0.195;
      s.wheelInertia = 1.3;
    }),
    part('wheel_16', '16×8 — 215/225', 3200, (s) => {
      s.wheelRadius = 0.318; s.tireWidthFront = 0.215; s.tireWidthRear = 0.225;
      s.wheelInertia = 1.15;
    }),
    part('wheel_17', '17×9.5 — 235/255', 6900, (s) => {
      s.wheelRadius = 0.324; s.tireWidthFront = 0.235; s.tireWidthRear = 0.255;
      s.wheelInertia = 1.24; s.trackFront += 0.05; s.trackRear += 0.06;
    }),
    part('wheel_18', '18×10.5 — 245/275', 11800, (s) => {
      s.wheelRadius = 0.331; s.tireWidthFront = 0.245; s.tireWidthRear = 0.275;
      s.wheelInertia = 1.42; s.trackFront += 0.08; s.trackRear += 0.10; s.mass += 12;
    }),
  ],

  tires: [
    part('tire_street', 'Street radials', 0, (s) => {
      s.compoundFront = 'street'; s.compoundRear = 'street';
    }),
    part('tire_sport', 'Sport compound', 1900, (s) => {
      s.compoundFront = 'sport'; s.compoundRear = 'sport';
    }),
    part('tire_semi', 'Semi-slicks', 5200, (s) => {
      s.compoundFront = 'semiSlick'; s.compoundRear = 'semiSlick';
    }, { note: 'Grippy front, expensive rear.' }),
    part('tire_drift', 'Grip front / drift rear', 3400, (s) => {
      s.compoundFront = 'semiSlick'; s.compoundRear = 'drift';
    }, { note: 'The setup everyone actually runs.' }),
  ],

  brakes: [
    part('brake_stock', 'Stock brakes', 0, (s) => { }),
    part('brake_pads', 'Performance pads & lines', 1400, (s) => {
      s.brakeTorqueFront *= 1.15; s.brakeTorqueRear *= 1.12;
    }),
    part('brake_big', '4-pot front kit', 6300, (s) => {
      s.brakeTorqueFront *= 1.45; s.brakeTorqueRear *= 1.1; s.mass += 6;
    }),
    part('brake_hydro', 'Hydraulic handbrake', 2600, (s) => {
      s.handbrakeTorque = 4200;
    }, { note: 'Locks the rear axle instantly. Essential.' }),
  ],

  weight: [
    part('weight_stock', 'Full interior', 0, (s) => { }),
    part('weight_strip', 'Stripped interior', 900, (s) => {
      s.mass -= 85; s.yawInertia -= 90;
    }),
    part('weight_cage', 'Cage & buckets', 5800, (s) => {
      s.mass -= 30; s.yawInertia -= 60;
      s.springFront *= 1.04; s.springRear *= 1.04;   // stiffer shell
    }),
    part('weight_carbon', 'Carbon panels & lexan', 16400, (s) => {
      s.mass -= 96; s.yawInertia -= 140; s.cgHeight -= 0.018;
    }),
  ],
};

export const PART_CATEGORIES = [
  { key: 'engine', label: 'Engine' },
  { key: 'turbo', label: 'Turbo' },
  { key: 'suspension', label: 'Suspension' },
  { key: 'differential', label: 'Differential' },
  { key: 'gearbox', label: 'Drivetrain' },
  { key: 'wheels', label: 'Wheels' },
  { key: 'tires', label: 'Tires' },
  { key: 'brakes', label: 'Brakes' },
  { key: 'weight', label: 'Weight' },
];

/* ----------------------------------------------------------- setup sheet -- */

export const SETUP_SLIDERS = [
  { key: 'camberFront', label: 'Front camber', min: -8, max: 0, step: 0.1, unit: '°', scale: DEG, hint: 'Negative camber buys front bite in the corner.' },
  { key: 'camberRear', label: 'Rear camber', min: -6, max: 0, step: 0.1, unit: '°', scale: DEG, hint: 'Too much and the rear loses drive traction.' },
  { key: 'toeFront', label: 'Front toe', min: -0.5, max: 0.5, step: 0.05, unit: '°', scale: DEG, hint: 'Toe-out sharpens turn-in.' },
  { key: 'toeRear', label: 'Rear toe', min: -0.2, max: 0.8, step: 0.05, unit: '°', scale: DEG, hint: 'Toe-in steadies the rear.' },
  { key: 'maxSteerAngle', label: 'Steering lock', min: 30, max: 65, step: 1, unit: '°', scale: DEG, hint: 'More lock, more angle you can hold.' },
  { key: 'rideHeightFront', label: 'Front ride height', min: 0.06, max: 0.16, step: 0.005, unit: 'm', scale: 1 },
  { key: 'rideHeightRear', label: 'Rear ride height', min: 0.06, max: 0.17, step: 0.005, unit: 'm', scale: 1 },
  { key: 'springFront', label: 'Front spring', min: 25000, max: 110000, step: 1000, unit: 'N/m', scale: 1 },
  { key: 'springRear', label: 'Rear spring', min: 22000, max: 100000, step: 1000, unit: 'N/m', scale: 1 },
  { key: 'damperFront', label: 'Front damping', min: 2000, max: 10000, step: 100, unit: 'Ns/m', scale: 1 },
  { key: 'damperRear', label: 'Rear damping', min: 1800, max: 9000, step: 100, unit: 'Ns/m', scale: 1 },
  { key: 'barFront', label: 'Front anti-roll bar', min: 0, max: 32000, step: 500, unit: 'N/m', scale: 1, hint: 'Stiffer front = more understeer on entry.' },
  { key: 'barRear', label: 'Rear anti-roll bar', min: 0, max: 26000, step: 500, unit: 'N/m', scale: 1, hint: 'Stiffer rear = looser rear.' },
  { key: 'diffPreload', label: 'Diff preload', min: 0, max: 260, step: 5, unit: 'Nm', scale: 1 },
  { key: 'diffPowerRamp', label: 'Diff power lock', min: 0, max: 1, step: 0.02, unit: '', scale: 1 },
  { key: 'diffCoastRamp', label: 'Diff coast lock', min: 0, max: 1, step: 0.02, unit: '', scale: 1 },
  { key: 'brakeBias', label: 'Brake bias (front)', min: 0.4, max: 0.85, step: 0.01, unit: '', scale: 1 },
  { key: 'finalDrive', label: 'Final drive', min: 3.2, max: 5.2, step: 0.05, unit: ':1', scale: 1 },
  { key: 'boostTarget', label: 'Boost', min: 0.2, max: 2.4, step: 0.05, unit: 'bar', scale: 1 },
  { key: 'tirePressure', label: 'Tire pressure', min: 1.4, max: 2.6, step: 0.05, unit: 'bar', scale: 1, virtual: true },
];

export const ASSIST_SLIDERS = [
  { key: 'steeringAssist', label: 'Countersteer assist', min: 0, max: 1, step: 0.05 },
  { key: 'tractionControl', label: 'Traction control', min: 0, max: 1, step: 0.05 },
  { key: 'stabilityControl', label: 'Stability control', min: 0, max: 1, step: 0.05 },
];

export function defaultBuild() {
  return {
    parts: {
      engine: 'engine_stock', turbo: 'turbo_stock', suspension: 'susp_stock',
      differential: 'diff_viscous', gearbox: 'gear_stock', wheels: 'wheel_stock',
      tires: 'tire_street', brakes: 'brake_stock', weight: 'weight_stock',
    },
    tune: {},
    assists: { steeringAssist: 0.25, tractionControl: 0, stabilityControl: 0 },
    style: {
      paintColor: [0.72, 0.06, 0.10],
      finish: 'gloss',
      kit: 'stock',
      rim: 'work',
      rimColor: [0.72, 0.73, 0.78],
      vinyl: 'none',
      vinylColor: [0.95, 0.85, 0.2],
      tint: 0.7,
      caliperColor: [0.72, 0.12, 0.1],
      cage: false,
      cageColor: [0.85, 0.85, 0.88],
      wingCarbon: false,
    },
    autoShift: true,
  };
}

export function findPart(category, id) {
  const list = PARTS[category] || [];
  return list.find((p) => p.id === id) || list[0];
}

/** Fold the installed parts and the setup sheet into a physics setup. */
export function buildSetup(build) {
  const s = baseSetup();
  for (const cat of PART_CATEGORIES) {
    const p = findPart(cat.key, build.parts?.[cat.key]);
    if (p && p.apply) p.apply(s);
  }
  if (build.style?.kit && BODY_KITS[build.style.kit]) {
    const kit = BODY_KITS[build.style.kit];
    // Aero adds downforce and a little drag; wide bodies widen the track.
    s.liftRear -= kit.wing * 0.055;
    s.liftFront -= kit.lip * 0.16;
    s.dragCoefficient += kit.wing * 0.028 + kit.flare * 0.12;
    s.trackFront += kit.flare * 1.1;
    s.trackRear += kit.flare * 1.2;
  }
  if (build.style?.cage) { s.yawInertia -= 20; s.mass += 34; }

  const tune = build.tune || {};
  for (const slider of SETUP_SLIDERS) {
    if (slider.virtual) continue;
    const v = tune[slider.key];
    if (v === undefined || v === null) continue;
    s[slider.key] = v * (slider.scale ?? 1);
  }
  // Tire pressure trades peak grip against how quickly the tire heats.
  if (tune.tirePressure !== undefined) {
    const p = clamp(tune.tirePressure, 1.4, 2.6);
    const off = Math.abs(p - 2.0);
    s.tirePressureFactor = 1 - off * 0.06;
  }
  const assists = build.assists || {};
  s.steeringAssist = assists.steeringAssist ?? 0;
  s.tractionControl = assists.tractionControl ?? 0;
  s.stabilityControl = assists.stabilityControl ?? 0;
  s.autoShift = build.autoShift !== false;
  return s;
}

/** Defaults for the setup sheet, derived from whatever parts are fitted. */
export function setupDefaults(build) {
  const clone = { ...build, tune: {} };
  const s = buildSetup(clone);
  const out = {};
  for (const slider of SETUP_SLIDERS) {
    if (slider.virtual) { out[slider.key] = 2.0; continue; }
    out[slider.key] = s[slider.key] / (slider.scale ?? 1);
  }
  return out;
}

export function visualConfig(build) {
  const style = build.style || {};
  const s = buildSetup(build);
  return {
    kit: style.kit || 'stock',
    finish: style.finish || 'gloss',
    paintColor: style.paintColor || [0.7, 0.1, 0.12],
    rim: style.rim || 'work',
    rimColor: style.rimColor || [0.72, 0.73, 0.78],
    vinyl: style.vinyl || 'none',
    vinylColor: style.vinylColor || [0.9, 0.2, 0.3],
    tint: style.tint ?? 0.7,
    caliperColor: style.caliperColor || [0.7, 0.12, 0.1],
    cage: !!style.cage,
    cageColor: style.cageColor || [0.85, 0.85, 0.88],
    wingCarbon: !!style.wingCarbon,
    rideDrop: clamp(0.125 - (s.rideHeightFront + s.rideHeightRear) / 2, 0, 0.09),
    wheelRadius: s.wheelRadius,
    tireWidthFront: s.tireWidthFront,
    tireWidthRear: s.tireWidthRear,
    profile: clamp(0.78 - (s.wheelRadius - 0.315) * 2.2, 0.55, 0.8),
  };
}

/* ---------------------------------------------------------------- stats -- */

const TORQUE_CURVE = [
  [700, 0.30], [1200, 0.44], [1800, 0.58], [2400, 0.74], [3000, 0.87],
  [3600, 0.95], [4200, 1.00], [4800, 1.00], [5400, 0.96], [6000, 0.90],
  [6600, 0.81], [7200, 0.70], [7800, 0.56], [8200, 0.42],
];

export function computeStats(build) {
  const s = buildSetup(build);
  let peakPower = 0, peakPowerRpm = 0, peakTorque = 0, peakTorqueRpm = 0;
  for (let rpm = 1000; rpm <= s.limiter; rpm += 100) {
    let base = 0;
    for (let i = 1; i < TORQUE_CURVE.length; i++) {
      if (rpm <= TORQUE_CURVE[i][0]) {
        const [r0, t0] = TORQUE_CURVE[i - 1];
        const [r1, t1] = TORQUE_CURVE[i];
        base = lerp(t0, t1, (rpm - r0) / (r1 - r0));
        break;
      }
    }
    const spool = clamp((rpm - s.boostSpoolRpm * 0.55) / (s.boostSpoolRpm * 0.9), 0, 1);
    const boost = s.boostTarget * spool;
    const torque = base * s.peakTorque * (1 + (boost / 1.013) * s.boostEfficiency);
    const hp = (torque * rpm) / 7127;
    if (torque > peakTorque) { peakTorque = torque; peakTorqueRpm = rpm; }
    if (hp > peakPower) { peakPower = hp; peakPowerRpm = rpm; }
  }
  const compound = COMPOUND[s.compoundRear] || COMPOUND.sport;
  return {
    power: Math.round(peakPower),
    powerRpm: peakPowerRpm,
    torque: Math.round(peakTorque),
    torqueRpm: peakTorqueRpm,
    mass: Math.round(s.mass),
    powerToWeight: +(peakPower / (s.mass / 1000)).toFixed(0),
    balance: Math.round(s.weightDistFront * 100),
    lock: Math.round(s.maxSteerAngle / DEG),
    boost: +s.boostTarget.toFixed(2),
    grip: +(compound.muPeak).toFixed(2),
    diffLock: Math.round(s.diffPowerRamp * 100),
    topSpeed: Math.round(estimateTopSpeed(s, peakPower)),
    setup: s,
  };
}

function estimateTopSpeed(s, hp) {
  // Balance drag power against crank power minus driveline losses.
  const kw = hp * 0.7355 * s.drivelineEfficiency;
  let v = 20;
  for (let i = 0; i < 60; i++) {
    const drag = 0.5 * 1.204 * s.dragCoefficient * s.frontalArea * v * v * v / 1000;
    const roll = 0.014 * s.mass * 9.81 * v / 1000;
    const err = kw - drag - roll;
    v += err * 0.03;
    if (v < 5) v = 5;
  }
  // Cap at what the gearing actually allows.
  const topGear = s.gearRatios[s.gearRatios.length - 1] * s.finalDrive;
  const geared = (s.limiter * Math.PI / 30) / topGear * s.wheelRadius;
  return Math.min(v, geared) * 3.6;
}

export function totalSpent(build) {
  let sum = 0;
  for (const cat of PART_CATEGORIES) {
    const p = findPart(cat.key, build.parts?.[cat.key]);
    if (p) sum += p.price;
  }
  return sum;
}

export { BODY_KITS, RIM_STYLES, PAINT_FINISH, VINYLS };
