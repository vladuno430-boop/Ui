// GLSL ES 3.00 sources. The sky model is shared between the sky pass and the
// surface shader so that reflections agree with what is actually behind the car.

const COMMON = /* glsl */`
precision highp float;

const float PI = 3.14159265359;

float saturate(float x) { return clamp(x, 0.0, 1.0); }
vec3 saturate3(vec3 x) { return clamp(x, 0.0, 1.0); }

float hash21(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}

float noise2(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float a = hash21(i), b = hash21(i + vec2(1.0, 0.0));
  float c = hash21(i + vec2(0.0, 1.0)), d = hash21(i + vec2(1.0, 1.0));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
`;

const SKY = /* glsl */`
uniform vec3 u_skyZenith;
uniform vec3 u_skyHorizon;
uniform vec3 u_skyGround;
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform float u_starIntensity;
uniform float u_cloudCover;
uniform float u_time;
uniform vec3 u_cityGlow;

vec3 skyColor(vec3 dir) {
  float h = dir.y;
  float t = saturate(h * 1.6 + 0.08);
  vec3 col = mix(u_skyHorizon, u_skyZenith, pow(t, 0.75));
  if (h < 0.0) col = mix(u_skyHorizon, u_skyGround, saturate(-h * 3.0));

  // Light pollution piled up on the horizon — the reason a Tokyo night sky is
  // never actually black.
  float glow = pow(saturate(1.0 - abs(h) * 3.4), 3.0);
  col += u_cityGlow * glow;

  // Stars, faded out by cloud cover and by the sun.
  if (h > 0.0 && u_starIntensity > 0.001) {
    vec2 sp = dir.xz / max(0.15, dir.y) * 3.0;
    float s = hash21(floor(sp * 42.0));
    float star = smoothstep(0.9955, 1.0, s) * (0.5 + 0.5 * sin(u_time * 2.0 + s * 90.0));
    col += vec3(star) * u_starIntensity * saturate(h * 3.0) * (1.0 - u_cloudCover * 0.85);
  }

  // Moon / sun disc plus its halo.
  float sd = max(dot(dir, u_sunDir), 0.0);
  col += u_sunColor * pow(sd, 900.0) * 14.0;
  col += u_sunColor * pow(sd, 12.0) * 0.16;

  // Cloud band.
  if (u_cloudCover > 0.01 && h > -0.05) {
    vec2 cp = dir.xz / max(0.12, dir.y + 0.1) * 0.6 + vec2(u_time * 0.004, u_time * 0.002);
    float c = noise2(cp * 1.7) * 0.6 + noise2(cp * 4.1) * 0.3 + noise2(cp * 9.0) * 0.1;
    float mask = smoothstep(0.62 - u_cloudCover * 0.5, 0.95 - u_cloudCover * 0.4, c);
    vec3 cloudCol = mix(u_skyHorizon * 1.15, u_skyZenith * 0.6, 0.4) + u_cityGlow * 0.5;
    col = mix(col, cloudCol, mask * saturate(h * 4.0) * u_cloudCover);
  }
  return col;
}
`;

export const SKY_VERT = /* glsl */`#version 300 es
${COMMON}
layout(location = 0) in vec2 a_pos;
uniform mat4 u_invViewProj;
uniform vec3 u_cameraPos;
out vec3 v_dir;
void main() {
  vec4 far = u_invViewProj * vec4(a_pos, 1.0, 1.0);
  v_dir = normalize(far.xyz / far.w - u_cameraPos);
  gl_Position = vec4(a_pos, 1.0, 1.0);
}
`;

export const SKY_FRAG = /* glsl */`#version 300 es
${COMMON}
${SKY}
in vec3 v_dir;
out vec4 fragColor;
void main() {
  vec3 dir = normalize(v_dir);
  fragColor = vec4(skyColor(dir), 1.0);
}
`;

/* ------------------------------------------------------------- surfaces -- */

export const SURFACE_VERT = /* glsl */`#version 300 es
${COMMON}
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_uv;
layout(location = 3) in vec3 a_color;
layout(location = 4) in vec4 a_params;

uniform mat4 u_viewProj;
uniform mat4 u_model;
uniform mat3 u_normalMat;
uniform mat4 u_shadowMat;

out vec3 v_world;
out vec3 v_normal;
out vec2 v_uv;
out vec3 v_color;
out vec4 v_params;
out vec4 v_shadowCoord;

void main() {
  vec4 world = u_model * vec4(a_position, 1.0);
  v_world = world.xyz;
  v_normal = normalize(u_normalMat * a_normal);
  v_uv = a_uv;
  v_color = a_color;
  v_params = a_params;
  v_shadowCoord = u_shadowMat * world;
  gl_Position = u_viewProj * world;
}
`;

export const SURFACE_FRAG = /* glsl */`#version 300 es
${COMMON}
${SKY}

in vec3 v_world;
in vec3 v_normal;
in vec2 v_uv;
in vec3 v_color;
in vec4 v_params;
in vec4 v_shadowCoord;

uniform vec3 u_cameraPos;
uniform vec3 u_ambientSky;
uniform vec3 u_ambientGround;
uniform vec3 u_fogColor;
uniform float u_fogDensity;
uniform float u_wetness;
uniform float u_emissiveBoost;
uniform vec3 u_paintColor;
uniform float u_paintFlake;
uniform float u_surfaceDetail;

#define MAX_LIGHTS 16
uniform int u_lightCount;
uniform vec4 u_lightPos[MAX_LIGHTS];    // xyz position, w radius
uniform vec3 u_lightColor[MAX_LIGHTS];  // rgb * intensity

uniform vec3 u_spotPos[2];
uniform vec3 u_spotDir[2];
uniform vec3 u_spotColor[2];
uniform float u_spotCos;

uniform sampler2D u_shadowMap;
uniform float u_shadowTexel;

out vec4 fragColor;

float distributionGGX(float ndh, float rough) {
  float a = rough * rough;
  float a2 = a * a;
  float d = ndh * ndh * (a2 - 1.0) + 1.0;
  return a2 / max(PI * d * d, 1e-5);
}

float geometrySchlick(float ndv, float ndl, float rough) {
  float k = (rough + 1.0) * (rough + 1.0) / 8.0;
  float gv = ndv / (ndv * (1.0 - k) + k);
  float gl = ndl / (ndl * (1.0 - k) + k);
  return gv * gl;
}

vec3 fresnel(float ct, vec3 f0) {
  return f0 + (1.0 - f0) * pow(1.0 - ct, 5.0);
}

float shadowFactor(vec3 n, vec3 l) {
  vec3 proj = v_shadowCoord.xyz / v_shadowCoord.w;
  if (proj.z > 1.0 || proj.x < 0.0 || proj.x > 1.0 || proj.y < 0.0 || proj.y > 1.0) return 1.0;
  float bias = max(0.0016 * (1.0 - dot(n, l)), 0.0006);
  float sum = 0.0;
  for (int x = -1; x <= 1; x++) {
    for (int y = -1; y <= 1; y++) {
      float d = texture(u_shadowMap, proj.xy + vec2(float(x), float(y)) * u_shadowTexel).r;
      sum += proj.z - bias > d ? 0.0 : 1.0;
    }
  }
  return sum / 9.0;
}

void main() {
  vec3 n = normalize(v_normal);
  vec3 v = normalize(u_cameraPos - v_world);
  float dist = length(u_cameraPos - v_world);

  float rough = v_params.x;
  float metal = v_params.y;
  float emissive = v_params.z;
  float paintMask = v_params.w;

  vec3 albedo = mix(v_color, u_paintColor, paintMask);

  // Surface break-up: coarse noise on road-like materials keeps big flat
  // planes from reading as plastic.
  if (u_surfaceDetail > 0.0) {
    float grain = noise2(v_world.xz * 3.1) * 0.5 + noise2(v_world.xz * 11.0) * 0.5;
    albedo *= 0.86 + grain * 0.28 * u_surfaceDetail;
    rough = clamp(rough + (grain - 0.5) * 0.18 * u_surfaceDetail, 0.03, 1.0);
  }

  // Metallic flake sparkle in the paint.
  if (paintMask > 0.5 && u_paintFlake > 0.0) {
    float flake = hash21(floor(v_world.xz * 260.0 + v_world.y * 260.0));
    albedo += vec3(smoothstep(0.985, 1.0, flake)) * u_paintFlake;
  }

  // Standing water flattens the surface and drops its diffuse response.
  float upFacing = saturate(n.y * 1.4 - 0.15);
  float wet = u_wetness * upFacing;
  rough = mix(rough, 0.055, wet * 0.85);
  albedo *= (1.0 - wet * 0.42);

  vec3 f0 = mix(vec3(0.04), albedo, metal);
  f0 = mix(f0, vec3(0.055), wet * 0.6);

  vec3 l = normalize(u_sunDir);
  float ndl = max(dot(n, l), 0.0);
  float ndv = max(dot(n, v), 1e-4);
  vec3 h = normalize(l + v);
  float ndh = max(dot(n, h), 0.0);

  float shadow = shadowFactor(n, l);
  vec3 direct = vec3(0.0);
  if (ndl > 0.0) {
    float d = distributionGGX(ndh, max(rough, 0.03));
    float g = geometrySchlick(ndv, ndl, rough);
    vec3 f = fresnel(max(dot(h, v), 0.0), f0);
    vec3 spec = d * g * f / max(4.0 * ndv * ndl, 1e-4);
    vec3 kd = (1.0 - f) * (1.0 - metal);
    direct = (kd * albedo / PI + spec) * u_sunColor * ndl * shadow;
  }

  // Hemispheric ambient plus an environment probe from the sky model, which is
  // what puts the neon back into the paintwork.
  vec3 refl = reflect(-v, n);
  vec3 env = skyColor(normalize(refl));
  float hemi = n.y * 0.5 + 0.5;
  vec3 ambient = mix(u_ambientGround, u_ambientSky, hemi) * albedo * (1.0 - metal * 0.7);
  vec3 fres = fresnel(ndv, f0);
  vec3 envSpec = env * fres * (1.0 - rough * 0.82) * (0.35 + wet * 0.9);

  // Punctual lights: neon, lamps, signs.
  vec3 punctual = vec3(0.0);
  for (int i = 0; i < MAX_LIGHTS; i++) {
    if (i >= u_lightCount) break;
    vec3 lp = u_lightPos[i].xyz - v_world;
    float ld = length(lp);
    float radius = u_lightPos[i].w;
    if (ld > radius) continue;
    vec3 ldir = lp / max(ld, 1e-4);
    float atten = pow(saturate(1.0 - ld / radius), 2.0);
    float lndl = max(dot(n, ldir), 0.0);
    vec3 lh = normalize(ldir + v);
    float lndh = max(dot(n, lh), 0.0);
    float d = distributionGGX(lndh, max(rough, 0.04));
    float g = geometrySchlick(ndv, max(lndl, 1e-4), rough);
    vec3 f = fresnel(max(dot(lh, v), 0.0), f0);
    vec3 spec = d * g * f / max(4.0 * ndv * max(lndl, 1e-4), 1e-4);
    punctual += (albedo * (1.0 - metal) / PI + spec) * u_lightColor[i] * lndl * atten;
  }

  // Headlight cones.
  for (int i = 0; i < 2; i++) {
    vec3 lp = u_spotPos[i] - v_world;
    float ld = length(lp);
    if (ld > 70.0) continue;
    vec3 ldir = lp / max(ld, 1e-4);
    float cosA = dot(-ldir, normalize(u_spotDir[i]));
    if (cosA < u_spotCos) continue;
    float cone = smoothstep(u_spotCos, mix(u_spotCos, 1.0, 0.35), cosA);
    float atten = cone / (1.0 + ld * ld * 0.006);
    float lndl = max(dot(n, ldir), 0.0);
    vec3 lh = normalize(ldir + v);
    float d = distributionGGX(max(dot(n, lh), 0.0), max(rough, 0.05));
    vec3 f = fresnel(max(dot(lh, v), 0.0), f0);
    float g = geometrySchlick(ndv, max(lndl, 1e-4), rough);
    vec3 spec = d * g * f / max(4.0 * ndv * max(lndl, 1e-4), 1e-4);
    punctual += (albedo * (1.0 - metal) / PI + spec) * u_spotColor[i] * lndl * atten * 16.0;
  }

  vec3 color = direct + ambient + envSpec + punctual;
  color += albedo * emissive * u_emissiveBoost;

  // Height-attenuated exponential fog.
  float fogAmount = 1.0 - exp(-dist * u_fogDensity * exp(-max(v_world.y, 0.0) * 0.02));
  vec3 fogged = mix(color, u_fogColor, saturate(fogAmount));

  fragColor = vec4(fogged, 1.0);
}
`;

/* ---------------------------------------------------------------- shadow -- */

export const SHADOW_VERT = /* glsl */`#version 300 es
layout(location = 0) in vec3 a_position;
uniform mat4 u_lightViewProj;
uniform mat4 u_model;
void main() {
  gl_Position = u_lightViewProj * u_model * vec4(a_position, 1.0);
}
`;

export const SHADOW_FRAG = /* glsl */`#version 300 es
precision highp float;
void main() {}
`;

/* ------------------------------------------------------------- particles -- */

export const PARTICLE_VERT = /* glsl */`#version 300 es
${COMMON}
layout(location = 0) in vec2 a_corner;
layout(location = 1) in vec4 a_posSize;   // xyz world, w size
layout(location = 2) in vec4 a_colorLife; // rgb tint, a alpha
layout(location = 3) in vec2 a_rotSpin;   // rotation, unused

uniform mat4 u_viewProj;
uniform vec3 u_cameraRight;
uniform vec3 u_cameraUp;

out vec2 v_uv;
out vec4 v_color;

void main() {
  float c = cos(a_rotSpin.x), s = sin(a_rotSpin.x);
  vec2 corner = vec2(a_corner.x * c - a_corner.y * s, a_corner.x * s + a_corner.y * c);
  vec3 world = a_posSize.xyz
    + u_cameraRight * corner.x * a_posSize.w
    + u_cameraUp * corner.y * a_posSize.w;
  v_uv = a_corner * 0.5 + 0.5;
  v_color = a_colorLife;
  gl_Position = u_viewProj * vec4(world, 1.0);
}
`;

export const PARTICLE_FRAG = /* glsl */`#version 300 es
${COMMON}
in vec2 v_uv;
in vec4 v_color;
uniform float u_time;
out vec4 fragColor;
void main() {
  vec2 d = v_uv - 0.5;
  float r = length(d) * 2.0;
  if (r > 1.0) discard;
  // Soft, slightly noisy puff rather than a clean gaussian.
  float a = pow(1.0 - r, 1.7);
  float n = noise2(v_uv * 5.0 + v_color.r * 12.0) * 0.35 + 0.75;
  fragColor = vec4(v_color.rgb, a * v_color.a * n);
}
`;

/* ------------------------------------------------------------ tire marks -- */

export const MARK_VERT = /* glsl */`#version 300 es
precision highp float;
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in vec2 a_meta;   // x birth time, y intensity
uniform mat4 u_viewProj;
uniform float u_time;
uniform float u_life;
out vec2 v_uv;
out float v_alpha;
void main() {
  v_uv = a_uv;
  float age = (u_time - a_meta.x) / max(u_life, 0.001);
  v_alpha = a_meta.y * clamp(1.0 - age, 0.0, 1.0);
  gl_Position = u_viewProj * vec4(a_position, 1.0);
}
`;

export const MARK_FRAG = /* glsl */`#version 300 es
${COMMON}
in vec2 v_uv;
in float v_alpha;
uniform vec3 u_markColor;
out vec4 fragColor;
void main() {
  float edge = smoothstep(0.0, 0.22, v_uv.x) * smoothstep(1.0, 0.78, v_uv.x);
  float grain = 0.72 + 0.28 * noise2(vec2(v_uv.x * 6.0, v_uv.y * 40.0));
  fragColor = vec4(u_markColor, v_alpha * edge * grain);
}
`;

/* ------------------------------------------------------------------ rain -- */

export const RAIN_VERT = /* glsl */`#version 300 es
${COMMON}
layout(location = 0) in vec2 a_corner;
layout(location = 1) in vec4 a_seed;   // xyz cell origin, w speed
uniform mat4 u_viewProj;
uniform vec3 u_cameraPos;
uniform vec3 u_cameraRight;
uniform float u_time;
uniform float u_length;
uniform vec3 u_wind;
out float v_fade;
void main() {
  vec3 base = a_seed.xyz;
  float fall = mod(u_time * a_seed.w + base.y * 31.0, 24.0);
  vec3 p = vec3(base.x, 24.0 - fall, base.z) + u_wind * fall * 0.12;
  p += floor(u_cameraPos / 24.0) * 24.0;
  vec3 world = p
    + u_cameraRight * a_corner.x * 0.02
    + vec3(0.0, a_corner.y * u_length, 0.0);
  v_fade = saturate(1.0 - length(p - u_cameraPos) / 26.0);
  gl_Position = u_viewProj * vec4(world, 1.0);
}
`;

export const RAIN_FRAG = /* glsl */`#version 300 es
precision highp float;
in float v_fade;
uniform vec3 u_rainColor;
out vec4 fragColor;
void main() {
  fragColor = vec4(u_rainColor, v_fade * 0.42);
}
`;

/* ------------------------------------------------------------------ post -- */

export const POST_VERT = /* glsl */`#version 300 es
layout(location = 0) in vec2 a_pos;
out vec2 v_uv;
void main() {
  v_uv = a_pos * 0.5 + 0.5;
  gl_Position = vec4(a_pos, 0.0, 1.0);
}
`;

export const BRIGHT_FRAG = /* glsl */`#version 300 es
${COMMON}
in vec2 v_uv;
uniform sampler2D u_source;
uniform float u_threshold;
out vec4 fragColor;
void main() {
  vec3 c = texture(u_source, v_uv).rgb;
  float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
  float k = max(0.0, lum - u_threshold) / max(lum, 1e-4);
  fragColor = vec4(c * k, 1.0);
}
`;

export const BLUR_FRAG = /* glsl */`#version 300 es
${COMMON}
in vec2 v_uv;
uniform sampler2D u_source;
uniform vec2 u_direction;
out vec4 fragColor;
void main() {
  // 9-tap gaussian using linear-sampling offsets.
  vec3 sum = texture(u_source, v_uv).rgb * 0.2270270270;
  vec2 o1 = u_direction * 1.3846153846;
  vec2 o2 = u_direction * 3.2307692308;
  sum += (texture(u_source, v_uv + o1).rgb + texture(u_source, v_uv - o1).rgb) * 0.3162162162;
  sum += (texture(u_source, v_uv + o2).rgb + texture(u_source, v_uv - o2).rgb) * 0.0702702703;
  fragColor = vec4(sum, 1.0);
}
`;

export const COMPOSITE_FRAG = /* glsl */`#version 300 es
${COMMON}
in vec2 v_uv;
uniform sampler2D u_scene;
uniform sampler2D u_bloom;
uniform float u_bloomStrength;
uniform float u_exposure;
uniform float u_vignette;
uniform float u_chroma;
uniform float u_grain;
uniform float u_time;
uniform float u_speedBlur;
out vec4 fragColor;

vec3 aces(vec3 x) {
  const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
  return saturate3((x * (a * x + b)) / (x * (c * x + d) + e));
}

void main() {
  vec2 uv = v_uv;
  vec2 centred = uv - 0.5;

  // Radial smear that ramps in with speed.
  vec3 scene = vec3(0.0);
  if (u_speedBlur > 0.001) {
    float total = 0.0;
    for (int i = 0; i < 6; i++) {
      float t = float(i) / 5.0;
      float scale = 1.0 - t * u_speedBlur * 0.045 * dot(centred, centred) * 4.0;
      float w = 1.0 - t * 0.6;
      scene += texture(u_scene, centred * scale + 0.5).rgb * w;
      total += w;
    }
    scene /= total;
  } else {
    scene = texture(u_scene, uv).rgb;
  }

  // Chromatic aberration grows toward the edges.
  if (u_chroma > 0.0001) {
    float amt = u_chroma * (0.4 + dot(centred, centred) * 3.0);
    scene.r = texture(u_scene, uv + centred * amt).r;
    scene.b = texture(u_scene, uv - centred * amt).b;
  }

  vec3 bloom = texture(u_bloom, uv).rgb;
  vec3 color = scene + bloom * u_bloomStrength;
  color *= u_exposure;
  color = aces(color);

  float vig = 1.0 - u_vignette * dot(centred, centred) * 1.9;
  color *= saturate(vig);

  float g = hash21(uv * 900.0 + fract(u_time) * 37.0) - 0.5;
  color += g * u_grain;

  // Gamma out.
  fragColor = vec4(pow(saturate3(color), vec3(1.0 / 2.2)), 1.0);
}
`;
