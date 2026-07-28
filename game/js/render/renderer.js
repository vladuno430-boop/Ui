// Forward renderer: shadowed directional light, a bank of punctual neon
// lights, headlight cones, HDR buffer, bloom and an ACES tonemap.

import { Program, RenderTarget, fullscreenTriangle, Mesh } from '../core/gl.js';
import { mat4, vec3, clamp, lerp, DEG } from '../core/math.js';
import { ATTRIB_LAYOUT, VERTEX_STRIDE } from '../core/geometry.js';
import * as S from './shaders.js';
import { buildBody, buildGlass, buildHeadlights, buildTaillights, buildWheel } from './car-model.js';

const MAX_LIGHTS = 16;

export const QUALITY = {
  low: { scale: 0.62, shadow: 512, bloom: true, bloomIterations: 1, shadowDistance: 32, drawDistance: 260, marks: 900, chroma: 0 },
  medium: { scale: 0.82, shadow: 1024, bloom: true, bloomIterations: 2, shadowDistance: 42, drawDistance: 400, marks: 1800, chroma: 0.0015 },
  high: { scale: 1.0, shadow: 2048, bloom: true, bloomIterations: 3, shadowDistance: 55, drawDistance: 620, marks: 2600, chroma: 0.0022 },
};

export class Renderer {
  constructor(ctx, quality = 'medium') {
    this.ctx = ctx;
    const gl = ctx.gl;
    this.gl = gl;
    this.quality = QUALITY[quality] ? quality : 'medium';
    this.q = QUALITY[this.quality];

    this.programs = {
      surface: new Program(ctx, S.SURFACE_VERT, S.SURFACE_FRAG, 'surface'),
      sky: new Program(ctx, S.SKY_VERT, S.SKY_FRAG, 'sky'),
      shadow: new Program(ctx, S.SHADOW_VERT, S.SHADOW_FRAG, 'shadow'),
      particle: new Program(ctx, S.PARTICLE_VERT, S.PARTICLE_FRAG, 'particle'),
      mark: new Program(ctx, S.MARK_VERT, S.MARK_FRAG, 'mark'),
      rain: new Program(ctx, S.RAIN_VERT, S.RAIN_FRAG, 'rain'),
      bright: new Program(ctx, S.POST_VERT, S.BRIGHT_FRAG, 'bright'),
      blur: new Program(ctx, S.POST_VERT, S.BLUR_FRAG, 'blur'),
      composite: new Program(ctx, S.POST_VERT, S.COMPOSITE_FRAG, 'composite'),
    };

    this.fsTri = fullscreenTriangle(ctx);
    this.shadowMap = new RenderTarget(ctx, this.q.shadow, this.q.shadow, { depthOnly: true });
    this.scene = new RenderTarget(ctx, 1280, 720, { hdr: true });
    this.bloomA = new RenderTarget(ctx, 640, 360, { hdr: true, depth: false });
    this.bloomB = new RenderTarget(ctx, 640, 360, { hdr: true, depth: false });

    // Reusable matrices — no allocation in the render loop.
    this.viewProj = mat4.create();
    this.view = mat4.create();
    this.proj = mat4.create();
    this.invViewProj = mat4.create();
    this.model = mat4.create();
    this.normalMat = new Float32Array(9);
    this.lightViewProj = mat4.create();
    this.shadowBias = mat4.create();
    this.shadowMat = mat4.create();
    this.frustum = new Float32Array(24);

    this.lightPos = new Float32Array(MAX_LIGHTS * 4);
    this.lightColor = new Float32Array(MAX_LIGHTS * 3);
    this.spotPos = new Float32Array(6);
    this.spotDir = new Float32Array(6);
    this.spotColor = new Float32Array(6);
    this._lightScratch = [];

    this.car = null;
    this.carConfig = null;
    this.stats = { drawCalls: 0, triangles: 0 };
  }

  setQuality(name) {
    if (!QUALITY[name]) return;
    this.quality = name;
    this.q = QUALITY[name];
    if (this.shadowMap.width !== this.q.shadow) {
      this.shadowMap.dispose();
      this.shadowMap = new RenderTarget(this.ctx, this.q.shadow, this.q.shadow, { depthOnly: true });
    }
  }

  resize(width, height) {
    const s = this.q.scale;
    const w = Math.max(320, Math.round(width * s));
    const h = Math.max(240, Math.round(height * s));
    this.scene.resize(w, h);
    this.bloomA.resize(Math.max(80, w >> 1), Math.max(60, h >> 1));
    this.bloomB.resize(Math.max(80, w >> 1), Math.max(60, h >> 1));
    this.viewportW = width;
    this.viewportH = height;
  }

  /* ------------------------------------------------------------- car mesh -- */

  buildCar(config) {
    const key = JSON.stringify(config);
    if (this.carConfig === key) return;
    this.carConfig = key;
    if (this.car) {
      for (const m of Object.values(this.car)) if (m && m.dispose) m.dispose();
    }
    this.car = {
      body: buildBody(config).build(this.ctx),
      glass: buildGlass(config).build(this.ctx),
      head: buildHeadlights().build(this.ctx),
      tail: buildTaillights().build(this.ctx),
      wheelFront: buildWheel({ ...config, width: config.tireWidthFront, radius: config.wheelRadius }).build(this.ctx),
      wheelRear: buildWheel({ ...config, width: config.tireWidthRear, radius: config.wheelRadius }).build(this.ctx),
    };
    this.paintColor = config.paintColor || [0.75, 0.08, 0.12];
    this.paintFlake = config.finish === 'metallic' || config.finish === 'pearl' ? 0.35 : 0.0;
  }

  /* --------------------------------------------------------------- frame -- */

  render(scene) {
    const gl = this.gl;
    const { camera, env, track, vehicle, smoke, marks, rain, ghosts, time } = scene;
    this.ctx.beginFrame();

    mat4.perspective(this.proj, camera.fov, this.viewportW / this.viewportH, 0.12, this.q.drawDistance * 2.4);
    mat4.lookAt(this.view, camera.position, camera.target, camera.up);
    mat4.multiply(this.viewProj, this.proj, this.view);
    mat4.invert(this.invViewProj, this.viewProj);
    extractFrustum(this.frustum, this.viewProj);

    this.collectLights(track, camera.position, vehicle, scene.extraLights);
    this.shadowPass(scene);

    // ---- main pass ------------------------------------------------------
    this.scene.bind();
    gl.clearColor(0, 0, 0, 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.enable(gl.DEPTH_TEST);
    gl.depthFunc(gl.LESS);
    gl.enable(gl.CULL_FACE);
    gl.cullFace(gl.BACK);
    gl.frontFace(gl.CCW);
    gl.disable(gl.BLEND);

    this.drawSky(env, camera, time);
    this.drawWorld(scene);
    this.drawCar(scene);
    this.drawMarks(scene);
    this.drawSmoke(scene);
    this.drawGlass(scene);
    if (env.rainRate > 0.01) this.drawRain(scene);

    // ---- post -----------------------------------------------------------
    this.postProcess(scene);

    this.stats.drawCalls = this.ctx.drawCalls;
    this.stats.triangles = Math.round(this.ctx.triangles);
  }

  collectLights(track, cameraPos, vehicle, extraLights) {
    const list = this._lightScratch;
    list.length = 0;
    const lights = track ? track.lights : [];
    // Extra lights (the garage rim lights) always win a slot.
    if (extraLights) for (const l of extraLights) list.push({ l, score: Infinity });
    // Rank by "how much this light can matter here": brightness over distance.
    for (let i = 0; i < lights.length; i++) {
      const l = lights[i];
      const dx = l.pos[0] - cameraPos[0];
      const dy = l.pos[1] - cameraPos[1];
      const dz = l.pos[2] - cameraPos[2];
      const d2 = dx * dx + dy * dy + dz * dz;
      if (d2 > 190 * 190) continue;
      list.push({ l, score: l.intensity * l.radius / (d2 + 1) });
    }
    list.sort((a, b) => b.score - a.score);
    const n = Math.min(MAX_LIGHTS, list.length);
    for (let i = 0; i < n; i++) {
      const l = list[i].l;
      this.lightPos[i * 4] = l.pos[0];
      this.lightPos[i * 4 + 1] = l.pos[1];
      this.lightPos[i * 4 + 2] = l.pos[2];
      this.lightPos[i * 4 + 3] = l.radius;
      this.lightColor[i * 3] = l.color[0] * l.intensity;
      this.lightColor[i * 3 + 1] = l.color[1] * l.intensity;
      this.lightColor[i * 3 + 2] = l.color[2] * l.intensity;
    }
    this.lightCount = n;

    // Headlights, as two forward cones from the lamp positions.
    if (vehicle) {
      const cy = Math.cos(vehicle.yaw), sy = Math.sin(vehicle.yaw);
      const fwd = [sy, -0.09, cy];
      for (let i = 0; i < 2; i++) {
        const side = i === 0 ? -0.55 : 0.55;
        this.spotPos[i * 3] = vehicle.pos[0] + cy * side + sy * 2.3;
        this.spotPos[i * 3 + 1] = vehicle.pos[1] + 0.75;
        this.spotPos[i * 3 + 2] = vehicle.pos[2] - sy * side + cy * 2.3;
        this.spotDir[i * 3] = fwd[0];
        this.spotDir[i * 3 + 1] = fwd[1];
        this.spotDir[i * 3 + 2] = fwd[2];
        const on = vehicle.headlights !== false ? 1 : 0;
        this.spotColor[i * 3] = 1.0 * on;
        this.spotColor[i * 3 + 1] = 0.96 * on;
        this.spotColor[i * 3 + 2] = 0.86 * on;
      }
    }
  }

  shadowPass(scene) {
    const gl = this.gl;
    const { env, track, vehicle } = scene;
    const centre = vehicle ? vehicle.pos : [0, 0, 0];
    const d = this.q.shadowDistance;
    const eye = [
      centre[0] + env.sunDir[0] * d * 1.4,
      centre[1] + Math.max(12, env.sunDir[1] * d * 1.4),
      centre[2] + env.sunDir[2] * d * 1.4,
    ];
    mat4.lookAt(this.view, eye, centre, [0, 1, 0]);
    mat4.ortho(this.proj, -d, d, -d, d, 1, d * 3.4);
    mat4.multiply(this.lightViewProj, this.proj, this.view);

    // Bias matrix: clip space (-1..1) → texture space (0..1).
    const b = this.shadowBias;
    mat4.identity(b);
    b[0] = 0.5; b[5] = 0.5; b[10] = 0.5;
    b[12] = 0.5; b[13] = 0.5; b[14] = 0.5;
    mat4.multiply(this.shadowMat, b, this.lightViewProj);

    this.shadowMap.bind();
    gl.clear(gl.DEPTH_BUFFER_BIT);
    gl.enable(gl.DEPTH_TEST);
    gl.enable(gl.CULL_FACE);
    gl.cullFace(gl.FRONT);

    const p = this.ctx.useProgram(this.programs.shadow);
    p.set('u_lightViewProj', this.lightViewProj);
    mat4.identity(this.model);
    p.set('u_model', this.model);
    if (track) {
      track.roadMesh.draw();
      for (const c of track.chunks) {
        const dx = c.center[0] - centre[0], dz = c.center[2] - centre[2];
        if (dx * dx + dz * dz > (d + c.radius) * (d + c.radius)) continue;
        c.mesh.draw();
      }
    }
    if (this.car && vehicle) {
      this.carModelMatrix(vehicle, this.model);
      p.set('u_model', this.model);
      this.car.body.draw();
      this.drawWheels(vehicle, p, true);
    }
    gl.cullFace(gl.BACK);
  }

  drawSky(env, camera, time) {
    const gl = this.gl;
    gl.disable(gl.DEPTH_TEST);
    gl.depthMask(false);
    const p = this.ctx.useProgram(this.programs.sky);
    p.set('u_invViewProj', this.invViewProj);
    p.set('u_cameraPos', camera.position);
    this.setSkyUniforms(p, env, time);
    this.fsTri.draw(3);
    gl.depthMask(true);
    gl.enable(gl.DEPTH_TEST);
  }

  setSkyUniforms(p, env, time) {
    p.set('u_skyZenith', env.skyZenith);
    p.set('u_skyHorizon', env.skyHorizon);
    p.set('u_skyGround', env.skyGround);
    p.set('u_sunDir', env.sunDir);
    p.set('u_sunColor', env.sunColor);
    p.set('u_cityGlow', env.cityGlow);
    p.set('u_starIntensity', env.starIntensity);
    p.set('u_cloudCover', env.cloudCover);
    p.set('u_time', time);
  }

  bindSurfaceProgram(scene) {
    const { env, camera, time } = scene;
    const p = this.ctx.useProgram(this.programs.surface);
    p.set('u_viewProj', this.viewProj);
    p.set('u_cameraPos', camera.position);
    p.set('u_ambientSky', env.ambientSky);
    p.set('u_ambientGround', env.ambientGround);
    p.set('u_fogColor', env.fogColor);
    p.set('u_fogDensity', env.fogDensity);
    p.set('u_wetness', env.wetness);
    p.set('u_shadowMat', this.shadowMat);
    p.setInt('u_lightCount', this.lightCount);
    p.set('u_lightPos', this.lightPos);
    p.set('u_lightColor', this.lightColor);
    p.set('u_spotPos', this.spotPos);
    p.set('u_spotDir', this.spotDir);
    p.set('u_spotColor', this.spotColor);
    p.set('u_spotCos', Math.cos(26 * DEG));
    p.setTexture('u_shadowMap', this.shadowMap.depth, 0);
    p.set('u_shadowTexel', 1 / this.shadowMap.width);
    this.setSkyUniforms(p, env, time);
    return p;
  }

  drawWorld(scene) {
    const { track, camera } = scene;
    if (!track) return;
    const p = this.bindSurfaceProgram(scene);
    mat4.identity(this.model);
    mat4.normalFromMat4(this.normalMat, this.model);
    p.set('u_model', this.model);
    p.set('u_normalMat', this.normalMat);
    p.set('u_paintColor', [1, 1, 1]);
    p.set('u_paintFlake', 0);
    p.set('u_emissiveBoost', 1.0);
    p.set('u_surfaceDetail', 1.0);
    track.roadMesh.draw();

    const far = this.q.drawDistance;
    for (const c of track.chunks) {
      const dx = c.center[0] - camera.position[0];
      const dy = c.center[1] - camera.position[1];
      const dz = c.center[2] - camera.position[2];
      if (dx * dx + dy * dy + dz * dz > (far + c.radius) * (far + c.radius)) continue;
      if (!sphereInFrustum(this.frustum, c.center, c.radius)) continue;
      c.mesh.draw();
    }
  }

  carModelMatrix(vehicle, out) {
    return mat4.compose(out, vehicle.pos, vehicle.yaw, vehicle.pitch, vehicle.roll, 1);
  }

  drawCar(scene) {
    const { vehicle } = scene;
    if (!this.car || !vehicle) return;
    const p = this.bindSurfaceProgram(scene);
    p.set('u_surfaceDetail', 0.0);
    p.set('u_paintColor', this.paintColor);
    p.set('u_paintFlake', this.paintFlake);
    p.set('u_emissiveBoost', 1.0);

    this.carModelMatrix(vehicle, this.model);
    mat4.normalFromMat4(this.normalMat, this.model);
    p.set('u_model', this.model);
    p.set('u_normalMat', this.normalMat);
    this.car.body.draw();

    // Lamps get their own emissive multiplier so they can flare under braking.
    p.set('u_emissiveBoost', vehicle.headlights === false ? 0.15 : 2.4);
    this.car.head.draw();
    const brake = scene.brakeLevel ?? 0;
    p.set('u_emissiveBoost', 0.55 + brake * 4.2);
    this.car.tail.draw();
    p.set('u_emissiveBoost', 1.0);

    this.drawWheels(vehicle, p, false);

    // Ghost cars: same body, drawn translucent.
    if (scene.ghosts && scene.ghosts.length) {
      const gl = this.gl;
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
      gl.depthMask(false);
      for (const g of scene.ghosts) {
        if (!g.visible) continue;
        p.set('u_paintColor', g.color || [0.2, 0.8, 1.0]);
        p.set('u_emissiveBoost', 1.6);
        mat4.compose(this.model, [g.x, g.y, g.z], g.yaw, g.pitch || 0, g.roll || 0, 1);
        mat4.normalFromMat4(this.normalMat, this.model);
        p.set('u_model', this.model);
        p.set('u_normalMat', this.normalMat);
        this.car.body.draw();
      }
      gl.depthMask(true);
      gl.disable(gl.BLEND);
      p.set('u_paintColor', this.paintColor);
      p.set('u_emissiveBoost', 1.0);
    }
  }

  drawWheels(vehicle, program, shadowPass) {
    const gl = this.gl;
    const m = this.model;
    for (const w of vehicle.wheels) {
      const mirror = w.x < 0;
      wheelMatrix(m, vehicle, w, mirror);
      program.set('u_model', m);
      if (!shadowPass) {
        mat4.normalFromMat4(this.normalMat, m);
        program.set('u_normalMat', this.normalMat);
        gl.frontFace(mirror ? gl.CW : gl.CCW);
      }
      (w.front ? this.car.wheelFront : this.car.wheelRear).draw();
    }
    if (!shadowPass) gl.frontFace(gl.CCW);
  }

  drawGlass(scene) {
    const gl = this.gl;
    const { vehicle } = scene;
    if (!this.car || !vehicle) return;
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.depthMask(false);
    gl.disable(gl.CULL_FACE);
    const p = this.bindSurfaceProgram(scene);
    p.set('u_surfaceDetail', 0.0);
    p.set('u_paintColor', this.paintColor);
    p.set('u_emissiveBoost', 1.0);
    this.carModelMatrix(vehicle, this.model);
    mat4.normalFromMat4(this.normalMat, this.model);
    p.set('u_model', this.model);
    p.set('u_normalMat', this.normalMat);
    this.car.glass.draw();
    gl.enable(gl.CULL_FACE);
    gl.depthMask(true);
    gl.disable(gl.BLEND);
  }

  drawMarks(scene) {
    const { marks, time } = scene;
    if (!marks || marks.used === 0) return;
    const gl = this.gl;
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.depthMask(false);
    gl.disable(gl.CULL_FACE);
    const p = this.ctx.useProgram(this.programs.mark);
    p.set('u_viewProj', this.viewProj);
    p.set('u_time', time);
    p.set('u_life', marks.life);
    p.set('u_markColor', [0.028, 0.026, 0.028]);
    marks.draw();
    gl.enable(gl.CULL_FACE);
    gl.depthMask(true);
    gl.disable(gl.BLEND);
  }

  drawSmoke(scene) {
    const { smoke, camera, time } = scene;
    if (!smoke || smoke.count === 0) return;
    const gl = this.gl;
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.depthMask(false);
    const p = this.ctx.useProgram(this.programs.particle);
    p.set('u_viewProj', this.viewProj);
    p.set('u_cameraRight', camera.right);
    p.set('u_cameraUp', camera.upVector);
    p.set('u_time', time);
    smoke.draw();
    gl.depthMask(true);
    gl.disable(gl.BLEND);
  }

  drawRain(scene) {
    const { rain, camera, env, time } = scene;
    if (!rain) return;
    const gl = this.gl;
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.depthMask(false);
    const p = this.ctx.useProgram(this.programs.rain);
    p.set('u_viewProj', this.viewProj);
    p.set('u_cameraPos', camera.position);
    p.set('u_cameraRight', camera.right);
    p.set('u_time', time);
    p.set('u_length', 0.28 + env.rainRate * 0.3);
    p.set('u_wind', env.wind);
    p.set('u_rainColor', [0.68, 0.75, 0.9]);
    rain.draw();
    gl.depthMask(true);
    gl.disable(gl.BLEND);
  }

  postProcess(scene) {
    const gl = this.gl;
    const { env } = scene;
    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.CULL_FACE);
    gl.disable(gl.BLEND);

    let bloomTex = this.bloomB.color;
    if (this.q.bloom) {
      this.bloomA.bind();
      let p = this.ctx.useProgram(this.programs.bright);
      p.setTexture('u_source', this.scene.color, 0);
      p.set('u_threshold', 0.85);
      this.fsTri.draw(3);

      p = this.ctx.useProgram(this.programs.blur);
      for (let i = 0; i < this.q.bloomIterations; i++) {
        this.bloomB.bind();
        p.setTexture('u_source', this.bloomA.color, 0);
        p.set('u_direction', [(1.4 + i) / this.bloomA.width, 0]);
        this.fsTri.draw(3);
        this.bloomA.bind();
        p.setTexture('u_source', this.bloomB.color, 0);
        p.set('u_direction', [0, (1.4 + i) / this.bloomA.height]);
        this.fsTri.draw(3);
      }
      bloomTex = this.bloomA.color;
    }

    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, this.ctx.canvas.width, this.ctx.canvas.height);
    const p = this.ctx.useProgram(this.programs.composite);
    p.setTexture('u_scene', this.scene.color, 0);
    p.setTexture('u_bloom', bloomTex, 1);
    p.set('u_bloomStrength', this.q.bloom ? (scene.bloomStrength ?? 0.75) : 0);
    p.set('u_exposure', env.exposure * (scene.exposureBias ?? 1));
    p.set('u_vignette', scene.vignette ?? 0.55);
    p.set('u_chroma', this.q.chroma * (scene.chromaBias ?? 1));
    p.set('u_grain', scene.grain ?? 0.022);
    p.set('u_time', scene.time);
    p.set('u_speedBlur', scene.speedBlur ?? 0);
    this.fsTri.draw(3);
  }
}

/* ------------------------------------------------------------- utilities -- */

const tmpA = mat4.create();
const tmpB = mat4.create();

function wheelMatrix(out, vehicle, wheel, mirror) {
  // T(hub) · Ry(car yaw + steer) · Rz(camber) · Rx(spin) · S(mirror)
  const yaw = vehicle.yaw + wheel.steer;
  const camber = wheel.camber * (wheel.x < 0 ? -1 : 1);
  const cy = Math.cos(vehicle.yaw), sy = Math.sin(vehicle.yaw);
  // Suspension travel raises the hub relative to the body.
  const drop = 0.06 - wheel.compression;
  const hubX = vehicle.pos[0] + wheel.x * cy + wheel.z * sy;
  const hubZ = vehicle.pos[2] - wheel.x * sy + wheel.z * cy;
  const hubY = vehicle.pos[1] + wheel.radius + drop * 0.3;

  mat4.identity(out);
  out[12] = hubX; out[13] = hubY; out[14] = hubZ;
  rotY(tmpA, yaw);
  mat4.multiply(out, out, tmpA);
  rotZ(tmpA, camber);
  mat4.multiply(out, out, tmpA);
  rotX(tmpA, wheel.spin);
  mat4.multiply(out, out, tmpA);
  if (mirror) {
    mat4.identity(tmpB);
    tmpB[0] = -1;
    mat4.multiply(out, out, tmpB);
  }
  return out;
}

function rotX(o, a) {
  const c = Math.cos(a), s = Math.sin(a);
  mat4.identity(o);
  o[5] = c; o[6] = s; o[9] = -s; o[10] = c;
  return o;
}
function rotY(o, a) {
  const c = Math.cos(a), s = Math.sin(a);
  mat4.identity(o);
  o[0] = c; o[2] = -s; o[8] = s; o[10] = c;
  return o;
}
function rotZ(o, a) {
  const c = Math.cos(a), s = Math.sin(a);
  mat4.identity(o);
  o[0] = c; o[1] = s; o[4] = -s; o[5] = c;
  return o;
}

function extractFrustum(out, m) {
  // Gribb/Hartmann plane extraction from the view-projection matrix.
  for (let i = 0; i < 6; i++) {
    const sign = i % 2 === 0 ? 1 : -1;
    const row = Math.floor(i / 2);
    for (let k = 0; k < 4; k++) {
      out[i * 4 + k] = m[k * 4 + 3] + sign * m[k * 4 + row];
    }
    const len = Math.hypot(out[i * 4], out[i * 4 + 1], out[i * 4 + 2]) || 1;
    out[i * 4] /= len; out[i * 4 + 1] /= len; out[i * 4 + 2] /= len; out[i * 4 + 3] /= len;
  }
}

function sphereInFrustum(planes, c, r) {
  for (let i = 0; i < 6; i++) {
    const d = planes[i * 4] * c[0] + planes[i * 4 + 1] * c[1] + planes[i * 4 + 2] * c[2] + planes[i * 4 + 3];
    if (d < -r) return false;
  }
  return true;
}
