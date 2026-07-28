import com.arena3.core.MathUtil;
import com.arena3.core.Vec3;
import com.arena3.game.*;
import com.arena3.core.Mat4;
import com.arena3.render.FighterPose;
import com.arena3.render.MeshBuilder;
import com.arena3.render.Models;
import com.arena3.render.ProcTex;
import com.arena3.render.WorldGeometry;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Software rasteriser for the world geometry. It exists so the level art,
 * texturing and baked lighting can be inspected without a device — it renders
 * exactly the vertex data the GL renderer uploads.
 *
 *   java -cp out Preview <mapIndex> <out.png> [x y z yaw pitch]
 */
public class Preview {

    static int W = 960, H = 540;
    static float[] depth;
    static int[] pixels;
    static int[][] textures = new int[Tex.COUNT][];

    public static void main(String[] args) throws Exception {
        int mapIndex = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        String out = args.length > 1 ? args[1] : "preview.png";

        MapDef map = Maps.build(mapIndex);
        CollisionWorld cw = new CollisionWorld();
        cw.build(map.brushes);

        long t0 = System.nanoTime();
        WorldGeometry geo = new WorldGeometry();
        geo.build(map, cw);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("map %d: %d verts, %d tris, baked in %d ms%n",
                mapIndex, geo.vertexCount, geo.triangleCount(), ms);

        for (int i = 0; i < Tex.COUNT; i++) textures[i] = ProcTex.generate(i);

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

        render(geo, map, eye, yaw, pitch);
        drawEntities(sim, map, eye, yaw, pitch);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, W, H, pixels, 0, W);
        ImageIO.write(img, "png", new File(out));
        System.out.println("wrote " + out);
    }

    static Vec3 camFwd = new Vec3(), camRight = new Vec3(), camUp = new Vec3();
    static float camFocal;

    /** Draws fighters, pickups and projectiles with the same meshes the game uses. */
    static void drawEntities(GameWorld sim, MapDef map, Vec3 eye, float yaw, float pitch) {
        Mat4[] pose = FighterPose.allocate();
        MeshBuilder.MeshData[] parts = Models.fighter(0.9f, 0.62f, 0.18f);
        MeshBuilder.MeshData[] weapons = new MeshBuilder.MeshData[WeaponDef.COUNT];
        for (int i = 0; i < WeaponDef.COUNT; i++) weapons[i] = Models.weapon(i);

        for (PlayerState ps : sim.players) {
            if (ps.index == 0) continue;                 // the camera stands in for player 0
            FighterPose.compute(ps, pose);
            for (int i = 0; i < Models.PART_COUNT; i++) {
                drawModel(parts[i], pose[i], map);
            }
            if (ps.alive) drawModel(weapons[ps.weapon], pose[FighterPose.WEAPON_MATRIX], map);
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
                drawModel(weapons[def.weapon], m, map);
            } else {
                drawModel(Models.item(def.id), m, map);
            }
        }

        for (Projectile p : sim.projectiles) {
            if (!p.active) continue;
            m.setTranslation(p.origin.x, p.origin.y, p.origin.z);
            drawModel(Models.projectile(p.weapon), m, map);
        }
    }

    static final int MF = MeshBuilder.VERTEX_FLOATS;

    static void drawModel(MeshBuilder.MeshData data, Mat4 transform, MapDef map) {
        float[] sx = new float[3], sy = new float[3], sz = new float[3];
        float[] uu = new float[3], vv = new float[3];
        float[] lr = new float[3], lg = new float[3], lb = new float[3];
        Vec3 p = new Vec3(), out = new Vec3(), n = new Vec3(), nOut = new Vec3();

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
                uu[k] = 0f;
                vv[k] = 0f;
                float key = Math.max(0f, -(nOut.x * map.sunDir.x + nOut.y * map.sunDir.y
                        + nOut.z * map.sunDir.z));
                float lightAmount = map.ambient.x * 2.4f + 0.12f + key * 0.55f;
                lr[k] = data.verts[o + 6] * lightAmount;
                lg[k] = data.verts[o + 7] * lightAmount;
                lb[k] = data.verts[o + 8] * lightAmount;
            }
            if (behind) continue;
            rasterModel(sx, sy, sz, lr, lg, lb, map);
        }
    }

    /** Same rasteriser as the world, but with flat vertex colours. */
    static void rasterModel(float[] sx, float[] sy, float[] sz,
                            float[] lr, float[] lg, float[] lb, MapDef map) {
        int minX = (int) Math.max(0, Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2]))));
        int maxX = (int) Math.min(W - 1, Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2]))));
        int maxY = (int) Math.min(H - 1, Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))));
        if (minX > maxX || minY > maxY) return;
        float area = edge(sx[0], sy[0], sx[1], sy[1], sx[2], sy[2]);
        if (Math.abs(area) < 1e-6f) return;
        float inv = 1f / area;
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
                float r = (w0 * lr[0] / sz[0] + w1 * lr[1] / sz[1] + w2 * lr[2] / sz[2]) * z;
                float g = (w0 * lg[0] / sz[0] + w1 * lg[1] / sz[1] + w2 * lg[2] / sz[2]) * z;
                float b = (w0 * lb[0] / sz[0] + w1 * lb[1] / sz[1] + w2 * lb[2] / sz[2]) * z;
                float f = Math.max(0f, Math.min(1f, (z - map.fogNear) / (map.fogFar - map.fogNear)));
                r = r * (1 - f) + map.fogColor.x * f;
                g = g * (1 - f) + map.fogColor.y * f;
                b = b * (1 - f) + map.fogColor.z * f;
                pixels[o] = (c255(r) << 16) | (c255(g) << 8) | c255(b);
            }
        }
    }

    static Vec3 camEye = new Vec3();

    static void render(WorldGeometry geo, MapDef map, Vec3 eye, float yaw, float pitch) {
        pixels = new int[W * H];
        depth = new float[W * H];
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
        int sky = skyColor(map);
        java.util.Arrays.fill(pixels, sky);

        Vec3 fwd = new Vec3(), right = new Vec3(), up = new Vec3();
        MathUtil.angleVectors(pitch, yaw, fwd, right, up);
        camEye.set(eye);
        camFwd.set(fwd);
        camRight.set(right);
        camUp.set(up);

        float fov = 90f;
        float focal = (W * 0.5f) / (float) Math.tan(Math.toRadians(fov * 0.5));
        camFocal = focal;

        drawTris(geo.verts, geo.indices, geo.indexCount, eye, fwd, right, up, focal, map, false);
        drawTris(geo.skyVerts, geo.skyIndices, geo.skyIndexCount, eye, fwd, right, up, focal, map, true);
    }

    static int skyColor(MapDef map) {
        switch (map.skyStyle) {
            case 1: return 0x2A1712;
            case 2: return 0x05060C;
            default: return 0x1A2230;
        }
    }

    static final int VF = WorldGeometry.VERTEX_FLOATS;

    static void drawTris(float[] v, int[] idx, int count, Vec3 eye, Vec3 fwd, Vec3 right, Vec3 up,
                         float focal, MapDef map, boolean isSky) {
        float[] sx = new float[3], sy = new float[3], sz = new float[3];
        float[] uu = new float[3], vv = new float[3];
        float[] lr = new float[3], lg = new float[3], lb = new float[3];
        for (int i = 0; i < count; i += 3) {
            boolean behind = false;
            int layer = 0;
            for (int k = 0; k < 3; k++) {
                int o = idx[i + k] * VF;
                float dx = v[o] - eye.x, dy = v[o + 1] - eye.y, dz = v[o + 2] - eye.z;
                float cz = dx * fwd.x + dy * fwd.y + dz * fwd.z;
                if (cz < 4f) { behind = true; break; }
                float cx = dx * right.x + dy * right.y + dz * right.z;
                float cy = dx * up.x + dy * up.y + dz * up.z;
                sx[k] = W * 0.5f + cx * focal / cz;
                sy[k] = H * 0.5f - cy * focal / cz;
                sz[k] = cz;
                uu[k] = v[o + 6];
                vv[k] = v[o + 7];
                lr[k] = v[o + 8];
                lg[k] = v[o + 9];
                lb[k] = v[o + 10];
                layer = (int) v[o + 11];
            }
            if (behind) continue;
            raster(sx, sy, sz, uu, vv, lr, lg, lb, layer, map, isSky);
        }
    }

    static void raster(float[] sx, float[] sy, float[] sz, float[] uu, float[] vv,
                       float[] lr, float[] lg, float[] lb, int layer, MapDef map, boolean isSky) {
        int minX = (int) Math.max(0, Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2]))));
        int maxX = (int) Math.min(W - 1, Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2]))));
        int maxY = (int) Math.min(H - 1, Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))));
        if (minX > maxX || minY > maxY) return;

        float area = edge(sx[0], sy[0], sx[1], sy[1], sx[2], sy[2]);
        if (Math.abs(area) < 1e-6f) return;
        float inv = 1f / area;

        int[] tex = textures[layer];
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

                float u = (w0 * uu[0] / sz[0] + w1 * uu[1] / sz[1] + w2 * uu[2] / sz[2]) * z;
                float vtex = (w0 * vv[0] / sz[0] + w1 * vv[1] / sz[1] + w2 * vv[2] / sz[2]) * z;
                float r = (w0 * lr[0] / sz[0] + w1 * lr[1] / sz[1] + w2 * lr[2] / sz[2]) * z;
                float g = (w0 * lg[0] / sz[0] + w1 * lg[1] / sz[1] + w2 * lg[2] / sz[2]) * z;
                float b = (w0 * lb[0] / sz[0] + w1 * lb[1] / sz[1] + w2 * lb[2] / sz[2]) * z;

                int color;
                if (isSky) {
                    color = skyColor(map);
                } else {
                    int tx = Math.floorMod((int) (u * ProcTex.SIZE), ProcTex.SIZE);
                    int ty = Math.floorMod((int) (vtex * ProcTex.SIZE), ProcTex.SIZE);
                    int t = tex[ty * ProcTex.SIZE + tx];
                    float tr = ((t >> 16) & 0xFF) / 255f * r;
                    float tg = ((t >> 8) & 0xFF) / 255f * g;
                    float tb = (t & 0xFF) / 255f * b;

                    // Distance fog, matching the shader.
                    float f = Math.max(0f, Math.min(1f, (z - map.fogNear) / (map.fogFar - map.fogNear)));
                    tr = tr * (1 - f) + map.fogColor.x * f;
                    tg = tg * (1 - f) + map.fogColor.y * f;
                    tb = tb * (1 - f) + map.fogColor.z * f;
                    color = (c255(tr) << 16) | (c255(tg) << 8) | c255(tb);
                }
                pixels[o] = color;
            }
        }
    }

    static int c255(float v) {
        int i = (int) (Math.sqrt(Math.max(0f, Math.min(1f, v))) * 255f);
        return Math.max(0, Math.min(255, i));
    }

    static float edge(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }
}
