import com.arena3.core.MathUtil;
import com.arena3.core.Vec3;
import com.arena3.game.*;
import com.arena3.core.Mat4;
import com.arena3.render.FighterPose;
import com.arena3.render.MeshBuilder;
import com.arena3.render.Models;
import com.arena3.render.ProcTex;
import com.arena3.render.WorldGeometry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Software rasteriser for the world geometry. It exists so the level art,
 * texturing and lighting can be inspected without a device — it renders exactly
 * the vertex data the GL renderer uploads, through a shading path that mirrors
 * the shaders, including the sun's shadow map.
 *
 *   java -cp out Preview &lt;mapIndex&gt; &lt;out.png&gt; [x y z yaw pitch]
 *
 * System properties: {@code -Dwarmup=N} simulation frames before the shot,
 * {@code -Dshadows=false} to disable the shadow/reflection pass, and
 * {@code -Dshadowonly=true} to paint the raw shadow term instead of the scene.
 */
public class Preview {

    static int W = 960, H = 540;
    static float[] depth;
    static int[] pixels;
    static int[][] textures = new int[Tex.COUNT][];

    static SoftShadowMap shadows;
    static boolean enhanced = !"false".equals(System.getProperty("shadows"));
    /** Debug view: paint the shadow term straight to the screen. */
    static boolean shadowOnly = "true".equals(System.getProperty("shadowonly"));

    static MapDef map;
    static Vec3 camEye = new Vec3();
    static Vec3 camFwd = new Vec3(), camRight = new Vec3(), camUp = new Vec3();
    static float camFocal;
    static final Vec3 skyLow = new Vec3(), skyHigh = new Vec3(), groundColor = new Vec3();

    public static void main(String[] args) throws Exception {
        int mapIndex = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        String out = args.length > 1 ? args[1] : "preview.png";

        map = Maps.build(mapIndex);
        CollisionWorld cw = new CollisionWorld();
        cw.build(map.brushes);

        long t0 = System.nanoTime();
        WorldGeometry geo = new WorldGeometry();
        geo.build(map, cw);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("map %d: %d verts, %d tris, baked in %d ms%n",
                mapIndex, geo.vertexCount, geo.triangleCount(), ms);

        for (int i = 0; i < Tex.COUNT; i++) textures[i] = ProcTex.generate(i);
        environmentColors(map);

        Vec3 eye = new Vec3();
        float yaw, pitch;
        if (args.length >= 7) {
            eye.set(Float.parseFloat(args[2]), Float.parseFloat(args[3]), Float.parseFloat(args[4]));
            yaw = Float.parseFloat(args[5]);
            pitch = Float.parseFloat(args[6]);
        } else {
            MapDef.Spawn s = map.spawns.get(0);
            eye.set(s.pos.x, s.pos.y, s.pos.z + 26f);
            yaw = s.yaw;
            pitch = 0f;
        }

        // Let the match run so bots spread out, then draw them in place.
        GameConfig cfg = new GameConfig();
        cfg.mapIndex = mapIndex;
        cfg.botCount = 5;
        cfg.skill = 3;
        cfg.seed = 77;
        GameWorld sim = new GameWorld(map, cfg);
        sim.state = GameWorld.STATE_LIVE;
        int warmup = Integer.parseInt(System.getProperty("warmup", "240"));
        for (int i = 0; i < warmup; i++) {
            sim.inputs[0].clear();
            sim.update(1f / 60f);
        }

        if (enhanced) {
            long s0 = System.nanoTime();
            buildShadowMap(geo, sim);
            System.out.printf("shadow map: %dx%d, %d ms, %.1f%% covered%n",
                    SoftShadowMap.SIZE, SoftShadowMap.SIZE,
                    (System.nanoTime() - s0) / 1_000_000, shadows.coverage() * 100f);
        }

        render(geo, eye, yaw, pitch);
        drawEntities(sim);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, W, H, pixels, 0, W);
        ImageIO.write(img, "png", new File(out));
        System.out.println("wrote " + out);
    }

    /** The analytic environment the reflections sample, matching GameRenderer. */
    static void environmentColors(MapDef map) {
        switch (map.skyStyle) {
            case 1:
                skyLow.set(0.30f, 0.09f, 0.05f);
                skyHigh.set(0.05f, 0.03f, 0.05f);
                break;
            case 2:
                skyLow.set(0.03f, 0.03f, 0.07f);
                skyHigh.set(0.01f, 0.01f, 0.03f);
                break;
            default:
                skyLow.set(0.09f, 0.11f, 0.17f);
                skyHigh.set(0.02f, 0.03f, 0.07f);
                break;
        }
        groundColor.set(map.ambient.x * 1.6f, map.ambient.y * 1.6f, map.ambient.z * 1.6f);
    }

    // ----------------------------------------------------------- shadow map

    /**
     * Rasterises the level and the fighters into a depth buffer seen from the
     * sun, the software twin of the GL depth pass.
     */
    static void buildShadowMap(WorldGeometry geo, GameWorld sim) {
        shadows = new SoftShadowMap();
        shadows.build(geo, map.sunDir);

        Mat4[] pose = FighterPose.allocate();
        MeshBuilder.MeshData[] parts = Models.fighter(0.9f, 0.62f, 0.18f);
        for (PlayerState ps : sim.players) {
            if (!ps.alive) continue;
            FighterPose.compute(ps, pose);
            for (int i = 0; i < Models.PART_COUNT; i++) {
                shadows.addMesh(parts[i].verts, parts[i].indices, parts[i].indexCount,
                        MeshBuilder.VERTEX_FLOATS, pose[i]);
            }
        }
    }

    static float shadowFactor(float wx, float wy, float wz, float ndl) {
        return shadows == null ? 1f : shadows.factor(wx, wy, wz, ndl);
    }

    // ------------------------------------------------------------ reflections

    /** Analytic environment: sky above, level colour below, sun disc. */
    static void environment(float dx, float dy, float dz, Vec3 out) {
        float up = clamp(dz * 0.5f + 0.5f);
        float t = smoothstep(0.42f, 0.58f, up);
        float sr = skyLow.x + (skyHigh.x - skyLow.x) * up;
        float sg = skyLow.y + (skyHigh.y - skyLow.y) * up;
        float sb = skyLow.z + (skyHigh.z - skyLow.z) * up;
        float r = groundColor.x + (sr - groundColor.x) * t;
        float g = groundColor.y + (sg - groundColor.y) * t;
        float b = groundColor.z + (sb - groundColor.z) * t;
        float sun = Math.max(0f, -(dx * map.sunDir.x + dy * map.sunDir.y + dz * map.sunDir.z));
        float disc = (float) Math.pow(sun, 48.0) * 3f;
        out.set(r + map.sunColor.x * disc, g + map.sunColor.y * disc, b + map.sunColor.z * disc);
    }

    static float fresnel(float cosTheta, float f0) {
        float c = 1f - clamp(cosTheta);
        return f0 + (1f - f0) * c * c * c * c * c;
    }

    /** Roughness per world material, mirroring {@code materialGloss} in the shader. */
    static float materialGloss(int layer) {
        if (layer == 3) return 0.72f;
        if (layer == 13) return 0.62f;
        if (layer == 0 || layer == 1) return 0.38f;
        if (layer == 6) return 0.55f;
        if (layer == 4) return 0.30f;
        if (layer == 11 || layer == 12) return 0.65f;
        return 0.12f;
    }

    // ---------------------------------------------------------------- world

    static void render(WorldGeometry geo, Vec3 eye, float yaw, float pitch) {
        pixels = new int[W * H];
        depth = new float[W * H];
        Arrays.fill(depth, Float.MAX_VALUE);
        Arrays.fill(pixels, skyColor(map));

        Vec3 fwd = new Vec3(), right = new Vec3(), up = new Vec3();
        MathUtil.angleVectors(pitch, yaw, fwd, right, up);
        camEye.set(eye);
        camFwd.set(fwd);
        camRight.set(right);
        camUp.set(up);

        float fov = 90f;
        camFocal = (W * 0.5f) / (float) Math.tan(Math.toRadians(fov * 0.5));

        drawTris(geo.verts, geo.indices, geo.indexCount, false);
        drawTris(geo.skyVerts, geo.skyIndices, geo.skyIndexCount, true);
    }

    static int skyColor(MapDef map) {
        switch (map.skyStyle) {
            case 1: return 0x2A1712;
            case 2: return 0x05060C;
            default: return 0x1A2230;
        }
    }

    static final int VF = WorldGeometry.VERTEX_FLOATS;

    static void drawTris(float[] v, int[] idx, int count, boolean isSky) {
        // Per-vertex attributes carried through to the rasteriser.
        float[] sx = new float[3], sy = new float[3], sz = new float[3];
        float[] uu = new float[3], vv = new float[3];
        float[] lr = new float[3], lg = new float[3], lb = new float[3];
        float[] wx = new float[3], wy = new float[3], wz = new float[3];
        float[] nx = new float[3], ny = new float[3], nz = new float[3];
        float[] ao = new float[3];

        for (int i = 0; i < count; i += 3) {
            boolean behind = false;
            int layer = 0;
            for (int k = 0; k < 3; k++) {
                int o = idx[i + k] * VF;
                float dx = v[o] - camEye.x, dy = v[o + 1] - camEye.y, dz = v[o + 2] - camEye.z;
                float cz = dx * camFwd.x + dy * camFwd.y + dz * camFwd.z;
                if (cz < 4f) { behind = true; break; }
                float cx = dx * camRight.x + dy * camRight.y + dz * camRight.z;
                float cy = dx * camUp.x + dy * camUp.y + dz * camUp.z;
                sx[k] = W * 0.5f + cx * camFocal / cz;
                sy[k] = H * 0.5f - cy * camFocal / cz;
                sz[k] = cz;
                wx[k] = v[o];
                wy[k] = v[o + 1];
                wz[k] = v[o + 2];
                nx[k] = v[o + 3];
                ny[k] = v[o + 4];
                nz[k] = v[o + 5];
                uu[k] = v[o + 6];
                vv[k] = v[o + 7];
                lr[k] = v[o + 8];
                lg[k] = v[o + 9];
                lb[k] = v[o + 10];
                ao[k] = v[o + 11];
                layer = (int) v[o + 12];
            }
            if (behind) continue;
            raster(sx, sy, sz, uu, vv, lr, lg, lb, wx, wy, wz, nx, ny, nz, ao, layer, isSky);
        }
    }

    static void raster(float[] sx, float[] sy, float[] sz, float[] uu, float[] vv,
                       float[] lr, float[] lg, float[] lb,
                       float[] wx, float[] wy, float[] wz,
                       float[] nx, float[] ny, float[] nz, float[] ao,
                       int layer, boolean isSky) {
        int minX = (int) Math.max(0, Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2]))));
        int maxX = (int) Math.min(W - 1, Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2]))));
        int maxY = (int) Math.min(H - 1, Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))));
        if (minX > maxX || minY > maxY) return;

        float area = edge(sx[0], sy[0], sx[1], sy[1], sx[2], sy[2]);
        if (Math.abs(area) < 1e-6f) return;
        float inv = 1f / area;

        int[] tex = textures[layer];
        float gloss = materialGloss(layer);
        Vec3 refl = new Vec3();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float w0 = edge(sx[1], sy[1], sx[2], sy[2], px, py) * inv;
                float w1 = edge(sx[2], sy[2], sx[0], sy[0], px, py) * inv;
                float w2 = edge(sx[0], sy[0], sx[1], sy[1], px, py) * inv;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;

                // Perspective-correct interpolation.
                float iz = w0 / sz[0] + w1 / sz[1] + w2 / sz[2];
                float z = 1f / iz;
                int o = y * W + x;
                if (z >= depth[o]) continue;
                depth[o] = z;

                if (isSky) {
                    pixels[o] = skyColor(map);
                    continue;
                }

                float u = lerp3(uu, w0, w1, w2, sz, z);
                float vtex = lerp3(vv, w0, w1, w2, sz, z);
                float r = lerp3(lr, w0, w1, w2, sz, z);
                float g = lerp3(lg, w0, w1, w2, sz, z);
                float b = lerp3(lb, w0, w1, w2, sz, z);
                float pwx = lerp3(wx, w0, w1, w2, sz, z);
                float pwy = lerp3(wy, w0, w1, w2, sz, z);
                float pwz = lerp3(wz, w0, w1, w2, sz, z);
                float pnx = lerp3(nx, w0, w1, w2, sz, z);
                float pny = lerp3(ny, w0, w1, w2, sz, z);
                float pnz = lerp3(nz, w0, w1, w2, sz, z);
                float occlusion = lerp3(ao, w0, w1, w2, sz, z);
                float nlen = (float) Math.sqrt(pnx * pnx + pny * pny + pnz * pnz);
                if (nlen > 1e-6f) { pnx /= nlen; pny /= nlen; pnz /= nlen; }

                // Sunlight is applied here, not baked, so it can be shadowed.
                float ndl = Math.max(0f, -(pnx * map.sunDir.x + pny * map.sunDir.y
                        + pnz * map.sunDir.z));
                float shadow = 1f;
                if (ndl > 0f) {
                    shadow = shadowFactor(pwx, pwy, pwz, ndl);
                    r += map.sunColor.x * ndl * occlusion * shadow;
                    g += map.sunColor.y * ndl * occlusion * shadow;
                    b += map.sunColor.z * ndl * occlusion * shadow;
                }
                if (shadowOnly) {
                    pixels[o] = debugShadow(ndl, shadow);
                    continue;
                }

                int tx = Math.floorMod((int) (u * ProcTex.SIZE), ProcTex.SIZE);
                int ty = Math.floorMod((int) (vtex * ProcTex.SIZE), ProcTex.SIZE);
                int t = tex[ty * ProcTex.SIZE + tx];
                float ar = ((t >> 16) & 0xFF) / 255f;
                float ag = ((t >> 8) & 0xFF) / 255f;
                float ab = (t & 0xFF) / 255f;
                float cr = ar * r, cg = ag * g, cb = ab * b;

                if (enhanced) {
                    float vx = camEye.x - pwx, vy = camEye.y - pwy, vz = camEye.z - pwz;
                    float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                    if (vl > 1e-6f) { vx /= vl; vy /= vl; vz /= vl; }
                    float hx = vx - map.sunDir.x, hy = vy - map.sunDir.y, hz = vz - map.sunDir.z;
                    float hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                    if (hl > 1e-6f) { hx /= hl; hy /= hl; hz /= hl; }
                    float spec = (float) Math.pow(Math.max(0f, pnx * hx + pny * hy + pnz * hz),
                            8f + (220f - 8f) * gloss);
                    float s = spec * gloss * 2.2f * shadow;
                    cr += map.sunColor.x * s;
                    cg += map.sunColor.y * s;
                    cb += map.sunColor.z * s;

                    float ndv = pnx * vx + pny * vy + pnz * vz;
                    float rx = 2f * ndv * pnx - vx, ry = 2f * ndv * pny - vy, rz = 2f * ndv * pnz - vz;
                    environment(rx, ry, rz, refl);
                    float f = fresnel(ndv, 0.02f + gloss * 0.06f) * gloss * occlusion;
                    cr += (refl.x * (0.35f + ar * 0.65f) - cr) * f;
                    cg += (refl.y * (0.35f + ag * 0.65f) - cg) * f;
                    cb += (refl.z * (0.35f + ab * 0.65f) - cb) * f;
                }

                pixels[o] = fogged(cr, cg, cb, z);
            }
        }
    }

    // --------------------------------------------------------------- models

    /** Draws fighters, pickups and projectiles with the same meshes the game uses. */
    static void drawEntities(GameWorld sim) {
        Mat4[] pose = FighterPose.allocate();
        MeshBuilder.MeshData[] parts = Models.fighter(0.9f, 0.62f, 0.18f);
        MeshBuilder.MeshData[] weapons = new MeshBuilder.MeshData[WeaponDef.COUNT];
        for (int i = 0; i < WeaponDef.COUNT; i++) weapons[i] = Models.weapon(i);

        for (PlayerState ps : sim.players) {
            if (ps.index == 0) continue;                 // the camera stands in for player 0
            FighterPose.compute(ps, pose);
            for (int i = 0; i < Models.PART_COUNT; i++) {
                drawModel(parts[i], pose[i]);
            }
            if (ps.alive) drawModel(weapons[ps.weapon], pose[FighterPose.WEAPON_MATRIX]);
        }

        Mat4 m = new Mat4();
        for (ItemEntity item : sim.items) {
            if (!item.available()) continue;
            ItemDef def = item.def();
            m.identity();
            m.translate(item.origin.x, item.origin.y, item.origin.z + 6f);
            m.rotateZ(item.spin);
            if (def.category == ItemDef.CAT_WEAPON) {
                m.rotateY(-14f);
                m.scale(0.8f, 0.8f, 0.8f);
                drawModel(weapons[def.weapon], m);
            } else {
                drawModel(Models.item(def.id), m);
            }
        }

        for (Projectile p : sim.projectiles) {
            if (!p.active) continue;
            m.setTranslation(p.origin.x, p.origin.y, p.origin.z);
            drawModel(Models.projectile(p.weapon), m);
        }
    }

    static final int MF = MeshBuilder.VERTEX_FLOATS;
    /** Matches the entity pass in GameRenderer. */
    static final float MODEL_GLOSS = 0.45f;

    static void drawModel(MeshBuilder.MeshData data, Mat4 transform) {
        float[] sx = new float[3], sy = new float[3], sz = new float[3];
        float[] uu = new float[3], vv = new float[3];
        float[] cr = new float[3], cg = new float[3], cb = new float[3];
        float[] wx = new float[3], wy = new float[3], wz = new float[3];
        float[] nx = new float[3], ny = new float[3], nz = new float[3];
        Vec3 p = new Vec3(), out = new Vec3(), n = new Vec3(), nOut = new Vec3();
        int material = 0;

        for (int i = 0; i < data.indexCount; i += 3) {
            boolean behind = false;
            for (int k = 0; k < 3; k++) {
                int o = data.indices[i + k] * MF;
                p.set(data.verts[o], data.verts[o + 1], data.verts[o + 2]);
                transform.transformPoint(p, out);
                n.set(data.verts[o + 3], data.verts[o + 4], data.verts[o + 5]);
                // Rotate the normal without translating it.
                nOut.set(transform.m[0] * n.x + transform.m[4] * n.y + transform.m[8] * n.z,
                        transform.m[1] * n.x + transform.m[5] * n.y + transform.m[9] * n.z,
                        transform.m[2] * n.x + transform.m[6] * n.y + transform.m[10] * n.z);
                nOut.normalize();

                float dx = out.x - camEye.x, dy = out.y - camEye.y, dz = out.z - camEye.z;
                float cz = dx * camFwd.x + dy * camFwd.y + dz * camFwd.z;
                if (cz < 4f) { behind = true; break; }
                float cx = dx * camRight.x + dy * camRight.y + dz * camRight.z;
                float cy = dx * camUp.x + dy * camUp.y + dz * camUp.z;
                sx[k] = W * 0.5f + cx * camFocal / cz;
                sy[k] = H * 0.5f - cy * camFocal / cz;
                sz[k] = cz;
                wx[k] = out.x;
                wy[k] = out.y;
                wz[k] = out.z;
                nx[k] = nOut.x;
                ny[k] = nOut.y;
                nz[k] = nOut.z;
                uu[k] = data.verts[o + 6];
                vv[k] = data.verts[o + 7];
                cr[k] = data.verts[o + 8];
                cg[k] = data.verts[o + 9];
                cb[k] = data.verts[o + 10];
                material = (int) data.verts[o + 11];
            }
            if (behind) continue;
            rasterModel(sx, sy, sz, cr, cg, cb, uu, vv, wx, wy, wz, nx, ny, nz, material);
        }
    }

    static int[][] modelMaterials = new int[ProcTex.MAT_COUNT][];

    static {
        for (int i = 0; i < ProcTex.MAT_COUNT; i++) modelMaterials[i] = ProcTex.generateModel(i);
    }

    static float sampleModelMaterial(int material, float u, float v) {
        int size = ProcTex.MODEL_SIZE;
        int[] tex = modelMaterials[Math.max(0, Math.min(modelMaterials.length - 1, material))];
        int x = Math.floorMod((int) (u * size), size);
        int y = Math.floorMod((int) (v * size), size);
        return ((tex[y * size + x] >> 16) & 0xFF) / 255f;
    }

    /** Model shading, mirroring MODEL_FS: ambient plus a shadowed key light. */
    static void rasterModel(float[] sx, float[] sy, float[] sz,
                            float[] vr, float[] vg, float[] vb,
                            float[] tu, float[] tv,
                            float[] wx, float[] wy, float[] wz,
                            float[] nx, float[] ny, float[] nz, int material) {
        int minX = (int) Math.max(0, Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2]))));
        int maxX = (int) Math.min(W - 1, Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2]))));
        int maxY = (int) Math.min(H - 1, Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))));
        if (minX > maxX || minY > maxY) return;
        float area = edge(sx[0], sy[0], sx[1], sy[1], sx[2], sy[2]);
        if (Math.abs(area) < 1e-6f) return;
        float inv = 1f / area;

        float ambR = map.ambient.x * 2.4f + 0.10f;
        float ambG = map.ambient.y * 2.4f + 0.10f;
        float ambB = map.ambient.z * 2.4f + 0.10f;
        final float keyR = 0.55f, keyG = 0.55f, keyB = 0.60f;
        Vec3 refl = new Vec3();

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float w0 = edge(sx[1], sy[1], sx[2], sy[2], px, py) * inv;
                float w1 = edge(sx[2], sy[2], sx[0], sy[0], px, py) * inv;
                float w2 = edge(sx[0], sy[0], sx[1], sy[1], px, py) * inv;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float iz = w0 / sz[0] + w1 / sz[1] + w2 / sz[2];
                float z = 1f / iz;
                int o = y * W + x;
                if (z >= depth[o]) continue;
                depth[o] = z;

                float pwx = lerp3(wx, w0, w1, w2, sz, z);
                float pwy = lerp3(wy, w0, w1, w2, sz, z);
                float pwz = lerp3(wz, w0, w1, w2, sz, z);
                float pnx = lerp3(nx, w0, w1, w2, sz, z);
                float pny = lerp3(ny, w0, w1, w2, sz, z);
                float pnz = lerp3(nz, w0, w1, w2, sz, z);
                float nlen = (float) Math.sqrt(pnx * pnx + pny * pny + pnz * pnz);
                if (nlen > 1e-6f) { pnx /= nlen; pny /= nlen; pnz /= nlen; }

                float key = Math.max(0f, -(pnx * map.sunDir.x + pny * map.sunDir.y
                        + pnz * map.sunDir.z));
                float shadow = shadowFactor(pwx, pwy, pwz, key);
                if (shadowOnly) {
                    pixels[o] = debugShadow(key, shadow);
                    continue;
                }
                key *= shadow;
                // A dim opposing fill keeps the unlit side from going flat black.
                float fill = Math.max(0f, pnx * map.sunDir.x + pny * map.sunDir.y
                        + pnz * map.sunDir.z) * 0.35f;
                float lr = ambR + keyR * key + ambR * fill;
                float lg = ambG + keyG * key + ambG * fill;
                float lb = ambB + keyB * key + ambB * fill;

                float mu = lerp3(tu, w0, w1, w2, sz, z);
                float mv = lerp3(tv, w0, w1, w2, sz, z);
                float detail = sampleModelMaterial(material, mu, mv) * 1.35f;
                float br = lerp3(vr, w0, w1, w2, sz, z) * detail;
                float bg = lerp3(vg, w0, w1, w2, sz, z) * detail;
                float bb = lerp3(vb, w0, w1, w2, sz, z) * detail;
                float cr = br * lr, cg = bg * lg, cb = bb * lb;

                if (enhanced) {
                    float vx = camEye.x - pwx, vy = camEye.y - pwy, vz = camEye.z - pwz;
                    float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                    if (vl > 1e-6f) { vx /= vl; vy /= vl; vz /= vl; }
                    float hx = vx - map.sunDir.x, hy = vy - map.sunDir.y, hz = vz - map.sunDir.z;
                    float hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                    if (hl > 1e-6f) { hx /= hl; hy /= hl; hz /= hl; }
                    float spec = (float) Math.pow(Math.max(0f, pnx * hx + pny * hy + pnz * hz),
                            10f + (180f - 10f) * MODEL_GLOSS) * MODEL_GLOSS * 1.8f;
                    cr += keyR * spec;
                    cg += keyG * spec;
                    cb += keyB * spec;

                    float ndv = pnx * vx + pny * vy + pnz * vz;
                    float rx = 2f * ndv * pnx - vx, ry = 2f * ndv * pny - vy, rz = 2f * ndv * pnz - vz;
                    environment(rx, ry, rz, refl);
                    float f = fresnel(ndv, 0.03f + MODEL_GLOSS * 0.05f) * MODEL_GLOSS * 0.85f;
                    cr += (refl.x * (0.3f + br * 0.7f) - cr) * f;
                    cg += (refl.y * (0.3f + bg * 0.7f) - cg) * f;
                    cb += (refl.z * (0.3f + bb * 0.7f) - cb) * f;
                }

                pixels[o] = fogged(cr, cg, cb, z);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Debug palette for {@code -Dshadowonly}: blue where the surface faces away
     * from the sun, black where the shadow map occludes it, white where it is lit.
     */
    static int debugShadow(float ndl, float shadow) {
        if (ndl <= 0f) return 0x162230;
        int v = Math.max(0, Math.min(255, (int) (24f + 231f * shadow)));
        return (v << 16) | (v << 8) | v;
    }

    /** Perspective-correct interpolation of one attribute. */
    static float lerp3(float[] a, float w0, float w1, float w2, float[] sz, float z) {
        return (w0 * a[0] / sz[0] + w1 * a[1] / sz[1] + w2 * a[2] / sz[2]) * z;
    }

    static int fogged(float r, float g, float b, float dist) {
        float f = clamp((dist - map.fogNear) / (map.fogFar - map.fogNear));
        r = r * (1 - f) + map.fogColor.x * f;
        g = g * (1 - f) + map.fogColor.y * f;
        b = b * (1 - f) + map.fogColor.z * f;
        return (c255(r) << 16) | (c255(g) << 8) | c255(b);
    }

    static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    static float smoothstep(float lo, float hi, float v) {
        float t = clamp((v - lo) / (hi - lo));
        return t * t * (3f - 2f * t);
    }

    static int c255(float v) {
        int i = (int) (Math.sqrt(clamp(v)) * 255f);
        return Math.max(0, Math.min(255, i));
    }

    static float edge(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }
}
