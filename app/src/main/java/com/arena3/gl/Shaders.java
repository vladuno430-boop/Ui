package com.arena3.gl;

/** GLSL ES 3.00 sources for the game's five draw passes. */
public final class Shaders {

    public static final int MAX_DYNAMIC_LIGHTS = 8;

    private Shaders() {
    }

    /** Shared tail: fog, then a cheap gamma curve on the way out. */
    private static final String FOG_AND_OUT =
            "vec3 applyFog(vec3 color, float dist) {\n"
                    + "  float f = clamp((dist - uFogRange.x) / max(1.0, uFogRange.y - uFogRange.x), 0.0, 1.0);\n"
                    + "  return mix(color, uFogColor, f);\n"
                    + "}\n"
                    + "vec4 present(vec3 color) {\n"
                    + "  return vec4(sqrt(clamp(color, 0.0, 1.0)), 1.0);\n"
                    + "}\n";


    /**
     * Shared lighting helpers: a PCF shadow lookup, a cheap analytic environment
     * for reflections, and the Fresnel term that decides how much of it shows.
     */
    private static final String LIGHTING_COMMON =
            "uniform sampler2D uShadowMap;\n"
                    + "uniform mat4 uLightViewProj;\n"
                    + "uniform vec3 uSunDir;\n"
                    + "uniform vec3 uSunColor;\n"
                    + "uniform float uShadowTexel;\n"
                    + "uniform float uShadowBias;\n"
                    + "uniform int uEnhanced;\n"
                    + "uniform vec3 uSkyLow;\n"
                    + "uniform vec3 uSkyHigh;\n"
                    + "uniform vec3 uGroundColor;\n"
                    // Percentage-closer filtering: four taps in a rotated grid is
                    // enough to take the staircase off a shadow edge on a phone.
                    + "float shadowFactor(vec3 world, float ndl) {\n"
                    + "  if (uEnhanced == 0) return 1.0;\n"
                    + "  vec4 lightPos = uLightViewProj * vec4(world, 1.0);\n"
                    + "  vec3 proj = lightPos.xyz / lightPos.w * 0.5 + 0.5;\n"
                    + "  if (proj.x < 0.0 || proj.x > 1.0 || proj.y < 0.0 || proj.y > 1.0\n"
                    + "      || proj.z > 1.0) return 1.0;\n"
                    + "  // Slope-scaled bias, or shallow surfaces self-shadow into stripes.\n"
                    + "  // uShadowBias carries one texel of depth, so the same numbers\n"
                    + "  // hold whatever the map size or the shadow resolution.\n"
                    + "  float bias = mix(3.0, 0.75, ndl) * uShadowBias;\n"
                    + "  float lit = 0.0;\n"
                    + "  for (int i = 0; i < 4; i++) {\n"
                    + "    vec2 offset = vec2(i == 0 ? -0.7 : (i == 1 ? 0.7 : (i == 2 ? -0.3 : 0.3)),\n"
                    + "                       i == 0 ? -0.3 : (i == 1 ? 0.3 : (i == 2 ? 0.7 : -0.7)));\n"
                    + "    float depth = texture(uShadowMap, proj.xy + offset * uShadowTexel).r;\n"
                    + "    lit += proj.z - bias <= depth ? 1.0 : 0.0;\n"
                    + "  }\n"
                    + "  return lit * 0.25;\n"
                    + "}\n"
                    // Reflections come from an analytic environment rather than a
                    // captured probe: sky above, level colour below, sun disc.
                    + "vec3 environment(vec3 dir) {\n"
                    + "  float up = clamp(dir.z * 0.5 + 0.5, 0.0, 1.0);\n"
                    + "  vec3 col = mix(uGroundColor, mix(uSkyLow, uSkyHigh, up), smoothstep(0.42, 0.58, up));\n"
                    + "  float sun = max(0.0, dot(dir, -uSunDir));\n"
                    + "  col += uSunColor * pow(sun, 48.0) * 3.0;\n"
                    + "  return col;\n"
                    + "}\n"
                    + "float fresnel(float cosTheta, float f0) {\n"
                    + "  return f0 + (1.0 - f0) * pow(1.0 - clamp(cosTheta, 0.0, 1.0), 5.0);\n"
                    + "}\n"
                    // Roughness per world material: polished floors mirror, concrete does not.
                    + "float materialGloss(int layer) {\n"
                    + "  if (layer == 3) return 0.72;\n"      // floor metal
                    + "  if (layer == 13) return 0.62;\n"     // dark metal
                    + "  if (layer == 0 || layer == 1) return 0.38;\n"  // tech and panel walls
                    + "  if (layer == 6) return 0.55;\n"      // brass trim
                    + "  if (layer == 4) return 0.30;\n"      // grating
                    + "  if (layer == 11 || layer == 12) return 0.65;\n" // pads
                    + "  return 0.12;\n"
                    + "}\n";

    // ------------------------------------------------------------------ world

    public static final String WORLD_VS =
            "#version 300 es\n"
                    + "layout(location = 0) in vec3 aPos;\n"
                    + "layout(location = 1) in vec3 aNormal;\n"
                    + "layout(location = 2) in vec2 aUV;\n"
                    + "layout(location = 3) in vec3 aLight;\n"
                    + "layout(location = 4) in float aOcclusion;\n"
                    + "layout(location = 5) in float aLayer;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "out vec3 vWorld;\n"
                    + "out vec3 vNormal;\n"
                    + "out vec2 vUV;\n"
                    + "out vec3 vLight;\n"
                    + "out float vOcclusion;\n"
                    + "flat out int vLayer;\n"
                    + "void main() {\n"
                    + "  vWorld = aPos;\n"
                    + "  vNormal = aNormal;\n"
                    + "  vUV = aUV;\n"
                    + "  vLight = aLight;\n"
                    + "  vOcclusion = aOcclusion;\n"
                    + "  vLayer = int(aLayer + 0.5);\n"
                    + "  gl_Position = uViewProj * vec4(aPos, 1.0);\n"
                    + "}\n";

    public static final String WORLD_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "precision mediump sampler2DArray;\n"
                    + "in vec3 vWorld;\n"
                    + "in vec3 vNormal;\n"
                    + "in vec2 vUV;\n"
                    + "in vec3 vLight;\n"
                    + "in float vOcclusion;\n"
                    + "flat in int vLayer;\n"
                    + "uniform sampler2DArray uTex;\n"
                    + "uniform vec3 uEye;\n"
                    + "uniform vec3 uFogColor;\n"
                    + "uniform vec2 uFogRange;\n"
                    + "uniform int uLightCount;\n"
                    + "uniform vec4 uLightPos[" + MAX_DYNAMIC_LIGHTS + "];\n"
                    + "uniform vec3 uLightColor[" + MAX_DYNAMIC_LIGHTS + "];\n"
                    + "out vec4 fragColor;\n"
                    + LIGHTING_COMMON
                    + FOG_AND_OUT
                    + "void main() {\n"
                    + "  vec3 albedo = texture(uTex, vec3(vUV, float(vLayer))).rgb;\n"
                    + "  vec3 n = normalize(vNormal);\n"
                    + "  vec3 viewDir = normalize(uEye - vWorld);\n"
                    + "  vec3 light = vLight;\n"
                    + "  // Sunlight is applied here, not baked, so it can be shadowed.\n"
                    + "  float ndl = max(0.0, dot(n, -uSunDir));\n"
                    + "  if (ndl > 0.0) {\n"
                    + "    light += uSunColor * ndl * vOcclusion * shadowFactor(vWorld, ndl);\n"
                    + "  }\n"
                    + "  for (int i = 0; i < uLightCount; i++) {\n"
                    + "    vec3 d = uLightPos[i].xyz - vWorld;\n"
                    + "    float dist = length(d);\n"
                    + "    float radius = uLightPos[i].w;\n"
                    + "    if (dist < radius) {\n"
                    + "      float atten = 1.0 - dist / radius;\n"
                    + "      atten *= atten;\n"
                    + "      float lam = max(0.0, dot(n, d / max(dist, 0.001)));\n"
                    + "      light += uLightColor[i] * (lam * 0.85 + 0.15) * atten;\n"
                    + "    }\n"
                    + "  }\n"
                    + "  vec3 color = albedo * light;\n"
                    + "  if (uEnhanced == 1) {\n"
                    + "    // Specular from the sun, plus a reflection of the environment\n"
                    + "    // weighted by Fresnel — grazing angles mirror, head-on does not.\n"
                    + "    float gloss = materialGloss(vLayer);\n"
                    + "    vec3 h = normalize(viewDir - uSunDir);\n"
                    + "    float spec = pow(max(0.0, dot(n, h)), mix(8.0, 220.0, gloss));\n"
                    + "    color += uSunColor * spec * gloss * 2.2 * shadowFactor(vWorld, ndl);\n"
                    + "    vec3 refl = environment(reflect(-viewDir, n));\n"
                    + "    float f = fresnel(dot(n, viewDir), 0.02 + gloss * 0.06);\n"
                    + "    color = mix(color, refl * (0.35 + albedo * 0.65), f * gloss * vOcclusion);\n"
                    + "  }\n"
                    + "  float dist = distance(vWorld, uEye);\n"
                    + "  fragColor = present(applyFog(color, dist));\n"
                    + "}\n";

    // -------------------------------------------------------------------- sky

    public static final String SKY_VS =
            "#version 300 es\n"
                    + "layout(location = 0) in vec3 aPos;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "out vec3 vWorld;\n"
                    + "void main() {\n"
                    + "  vWorld = aPos;\n"
                    + "  gl_Position = uViewProj * vec4(aPos, 1.0);\n"
                    + "}\n";

    public static final String SKY_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "in vec3 vWorld;\n"
                    + "uniform vec3 uEye;\n"
                    + "uniform int uStyle;\n"
                    + "uniform float uTime;\n"
                    + "out vec4 fragColor;\n"
                    + "float hash13(vec3 p) {\n"
                    + "  p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));\n"
                    + "  p *= 17.0;\n"
                    + "  return fract(p.x * p.y * p.z * (p.x + p.y + p.z));\n"
                    + "}\n"
                    + "float noise3(vec3 x) {\n"
                    + "  vec3 i = floor(x);\n"
                    + "  vec3 f = fract(x);\n"
                    + "  f = f * f * (3.0 - 2.0 * f);\n"
                    + "  float n000 = hash13(i);\n"
                    + "  float n100 = hash13(i + vec3(1,0,0));\n"
                    + "  float n010 = hash13(i + vec3(0,1,0));\n"
                    + "  float n110 = hash13(i + vec3(1,1,0));\n"
                    + "  float n001 = hash13(i + vec3(0,0,1));\n"
                    + "  float n101 = hash13(i + vec3(1,0,1));\n"
                    + "  float n011 = hash13(i + vec3(0,1,1));\n"
                    + "  float n111 = hash13(i + vec3(1,1,1));\n"
                    + "  return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),\n"
                    + "             mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);\n"
                    + "}\n"
                    + "float fbm(vec3 p) {\n"
                    + "  float v = 0.0, a = 0.5;\n"
                    + "  for (int i = 0; i < 4; i++) { v += noise3(p) * a; p *= 2.03; a *= 0.5; }\n"
                    + "  return v;\n"
                    + "}\n"
                    + "void main() {\n"
                    + "  vec3 dir = normalize(vWorld - uEye);\n"
                    + "  float up = clamp(dir.z * 0.5 + 0.5, 0.0, 1.0);\n"
                    + "  vec3 color;\n"
                    + "  if (uStyle == 1) {\n"
                    + "    vec3 low = vec3(0.30, 0.09, 0.05);\n"
                    + "    vec3 high = vec3(0.05, 0.03, 0.05);\n"
                    + "    color = mix(low, high, up);\n"
                    + "    float clouds = fbm(dir * 3.0 + vec3(uTime * 0.01, 0.0, 0.0));\n"
                    + "    color += vec3(0.34, 0.10, 0.04) * smoothstep(0.45, 0.85, clouds) * (1.0 - up);\n"
                    + "  } else if (uStyle == 2) {\n"
                    + "    color = vec3(0.012, 0.014, 0.03);\n"
                    + "    float neb = fbm(dir * 2.2);\n"
                    + "    color += vec3(0.10, 0.05, 0.22) * smoothstep(0.5, 0.95, neb);\n"
                    + "    color += vec3(0.02, 0.06, 0.10) * smoothstep(0.55, 1.0, fbm(dir * 1.3 + 11.0));\n"
                    + "  } else {\n"
                    + "    vec3 low = vec3(0.09, 0.11, 0.17);\n"
                    + "    vec3 high = vec3(0.02, 0.03, 0.07);\n"
                    + "    color = mix(low, high, up);\n"
                    + "    float clouds = fbm(dir * 2.4 + vec3(uTime * 0.006, 0.0, 0.0));\n"
                    + "    color += vec3(0.05, 0.06, 0.09) * smoothstep(0.5, 0.9, clouds);\n"
                    + "  }\n"
                    + "  // Stars: a sparse threshold on a high-frequency hash of the direction.\n"
                    + "  if (uStyle != 1) {\n"
                    + "    vec3 sp = dir * 260.0;\n"
                    + "    float star = hash13(floor(sp));\n"
                    + "    float bright = smoothstep(0.9965, 1.0, star);\n"
                    + "    float twinkle = 0.75 + 0.25 * sin(uTime * 2.5 + star * 90.0);\n"
                    + "    color += vec3(0.9, 0.93, 1.0) * bright * twinkle * (0.35 + 0.65 * up);\n"
                    + "  }\n"
                    + "  fragColor = vec4(sqrt(clamp(color, 0.0, 1.0)), 1.0);\n"
                    + "}\n";

    // ------------------------------------------------------------------ model

    public static final String MODEL_VS =
            "#version 300 es\n"
                    + "layout(location = 0) in vec3 aPos;\n"
                    + "layout(location = 1) in vec3 aNormal;\n"
                    + "layout(location = 2) in vec2 aUV;\n"
                    + "layout(location = 3) in vec3 aColor;\n"
                    + "layout(location = 4) in float aMaterial;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "uniform mat4 uModel;\n"
                    + "out vec3 vWorld;\n"
                    + "out vec3 vNormal;\n"
                    + "out vec2 vUV;\n"
                    + "out vec3 vColor;\n"
                    + "flat out int vMaterial;\n"
                    + "void main() {\n"
                    + "  vec4 world = uModel * vec4(aPos, 1.0);\n"
                    + "  vWorld = world.xyz;\n"
                    + "  vNormal = mat3(uModel) * aNormal;\n"
                    + "  vUV = aUV;\n"
                    + "  vColor = aColor;\n"
                    + "  vMaterial = int(aMaterial + 0.5);\n"
                    + "  gl_Position = uViewProj * world;\n"
                    + "}\n";

    public static final String MODEL_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "precision mediump sampler2DArray;\n"
                    + "in vec3 vWorld;\n"
                    + "in vec3 vNormal;\n"
                    + "in vec2 vUV;\n"
                    + "in vec3 vColor;\n"
                    + "flat in int vMaterial;\n"
                    + "uniform sampler2DArray uTex;\n"
                    + "uniform vec3 uEye;\n"
                    + "uniform vec3 uFogColor;\n"
                    + "uniform vec2 uFogRange;\n"
                    + "uniform vec3 uAmbient;\n"
                    + "uniform vec3 uKeyDir;\n"
                    + "uniform vec3 uKeyColor;\n"
                    + "uniform vec3 uTint;\n"
                    + "uniform float uEmissive;\n"
                    + "uniform float uAlpha;\n"
                    + "uniform int uLightCount;\n"
                    + "uniform vec4 uLightPos[" + MAX_DYNAMIC_LIGHTS + "];\n"
                    + "uniform vec3 uLightColor[" + MAX_DYNAMIC_LIGHTS + "];\n"
                    + "uniform float uGloss;\n"
                    + "out vec4 fragColor;\n"
                    + LIGHTING_COMMON
                    + FOG_AND_OUT
                    + "void main() {\n"
                    + "  vec3 n = normalize(vNormal);\n"
                    + "  vec3 viewDir = normalize(uEye - vWorld);\n"
                    + "  float key = max(0.0, dot(n, -uKeyDir));\n"
                    + "  key *= shadowFactor(vWorld, key);\n"
                    + "  // A dim opposing fill keeps the unlit side from going flat black.\n"
                    + "  float fill = max(0.0, dot(n, uKeyDir)) * 0.35;\n"
                    + "  vec3 light = uAmbient + uKeyColor * key + uAmbient * fill;\n"
                    + "  for (int i = 0; i < uLightCount; i++) {\n"
                    + "    vec3 d = uLightPos[i].xyz - vWorld;\n"
                    + "    float dist = length(d);\n"
                    + "    float radius = uLightPos[i].w;\n"
                    + "    if (dist < radius) {\n"
                    + "      float atten = 1.0 - dist / radius;\n"
                    + "      atten *= atten;\n"
                    + "      float lam = max(0.0, dot(n, d / max(dist, 0.001)));\n"
                    + "      light += uLightColor[i] * (lam * 0.8 + 0.2) * atten;\n"
                    + "    }\n"
                    + "  }\n"
                    + "  // The material supplies the detail, the vertex colour the palette.\n"
                    + "  float detail = texture(uTex, vec3(vUV, float(vMaterial))).r;\n"
                    + "  vec3 base = vColor * uTint * (detail * 1.35);\n"
                    + "  vec3 color = mix(base * light, base, uEmissive);\n"
                    + "  if (uEnhanced == 1 && uEmissive < 0.5) {\n"
                    + "    vec3 h = normalize(viewDir - uKeyDir);\n"
                    + "    float spec = pow(max(0.0, dot(n, h)), mix(10.0, 180.0, uGloss));\n"
                    + "    color += uKeyColor * spec * uGloss * 1.8;\n"
                    + "    vec3 refl = environment(reflect(-viewDir, n));\n"
                    + "    float f = fresnel(dot(n, viewDir), 0.03 + uGloss * 0.05);\n"
                    + "    color = mix(color, refl * (0.3 + base * 0.7), f * uGloss * 0.85);\n"
                    + "  }\n"
                    + "  float dist = distance(vWorld, uEye);\n"
                    + "  vec4 outColor = present(applyFog(color, dist));\n"
                    + "  outColor.a = uAlpha;\n"
                    + "  fragColor = outColor;\n"
                    + "}\n";

    // --------------------------------------------------------------- particle

    public static final String PARTICLE_VS =
            "#version 300 es\n"
                    + "layout(location = 0) in vec3 aPos;\n"
                    + "layout(location = 1) in vec2 aUV;\n"
                    + "layout(location = 2) in vec4 aColor;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "out vec2 vUV;\n"
                    + "out vec4 vColor;\n"
                    + "out vec3 vWorld;\n"
                    + "void main() {\n"
                    + "  vUV = aUV;\n"
                    + "  vColor = aColor;\n"
                    + "  vWorld = aPos;\n"
                    + "  gl_Position = uViewProj * vec4(aPos, 1.0);\n"
                    + "}\n";

    public static final String PARTICLE_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "in vec2 vUV;\n"
                    + "in vec4 vColor;\n"
                    + "in vec3 vWorld;\n"
                    + "uniform sampler2D uTex;\n"
                    + "uniform vec3 uEye;\n"
                    + "uniform vec3 uFogColor;\n"
                    + "uniform vec2 uFogRange;\n"
                    + "uniform float uFogAmount;\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "  float mask = texture(uTex, vUV).a;\n"
                    + "  if (mask < 0.01) discard;\n"
                    + "  vec3 color = vColor.rgb;\n"
                    + "  float dist = distance(vWorld, uEye);\n"
                    + "  float f = clamp((dist - uFogRange.x) / max(1.0, uFogRange.y - uFogRange.x), 0.0, 1.0)\n"
                    + "            * uFogAmount;\n"
                    + "  color = mix(color, uFogColor, f);\n"
                    + "  fragColor = vec4(sqrt(clamp(color, 0.0, 1.0)), vColor.a * mask);\n"
                    + "}\n";

    // ------------------------------------------------------------------ depth

    /**
     * Depth-only pass that fills the shadow map. It declares nothing but the
     * position, so the same program works for world geometry and models even
     * though their vertex layouts differ.
     */
    public static final String DEPTH_VS =
            "#version 300 es\n"
                    + "layout(location = 0) in vec3 aPos;\n"
                    + "uniform mat4 uViewProj;\n"
                    + "uniform mat4 uModel;\n"
                    + "void main() {\n"
                    + "  gl_Position = uViewProj * (uModel * vec4(aPos, 1.0));\n"
                    + "}\n";

    public static final String DEPTH_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "  fragColor = vec4(1.0);\n"
                    + "}\n";

    // -------------------------------------------------------------------- hud

    public static final String HUD_VS =
            "#version 300 es\n"
                    + "layout(location = 0) in vec2 aPos;\n"
                    + "layout(location = 1) in vec2 aUV;\n"
                    + "layout(location = 2) in vec4 aColor;\n"
                    + "uniform mat4 uProj;\n"
                    + "out vec2 vUV;\n"
                    + "out vec4 vColor;\n"
                    + "void main() {\n"
                    + "  vUV = aUV;\n"
                    + "  vColor = aColor;\n"
                    + "  gl_Position = uProj * vec4(aPos, 0.0, 1.0);\n"
                    + "}\n";

    public static final String HUD_FS =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "in vec2 vUV;\n"
                    + "in vec4 vColor;\n"
                    + "uniform sampler2D uTex;\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "  vec4 t = texture(uTex, vUV);\n"
                    + "  fragColor = vec4(vColor.rgb, vColor.a * t.a);\n"
                    + "}\n";
}
