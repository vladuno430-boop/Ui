import com.arena3.core.Mat4;
import com.arena3.core.Vec3;
import com.arena3.game.PlayerState;
import com.arena3.game.WeaponDef;
import com.arena3.render.FighterPose;
import com.arena3.render.MeshBuilder;
import com.arena3.render.Models;
import com.arena3.render.ProcTex;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Contact sheet of the fighter model and every weapon, for checking the art. */
public class ModelPreview {

    static int W, H;
    static float[] depth;
    static int[] pixels;
    static Vec3 camEye = new Vec3(), camFwd = new Vec3(), camRight = new Vec3(), camUp = new Vec3();
    static float focal;
    static int[][] materials = new int[ProcTex.MAT_COUNT][];

    static {
        for (int i = 0; i < ProcTex.MAT_COUNT; i++) materials[i] = ProcTex.generateModel(i);
    }

    /** Samples a model material, wrapping like GL does. */
    static float sampleMaterial(int material, float u, float v) {
        int[] tex = materials[Math.max(0, Math.min(ProcTex.MAT_COUNT - 1, material))];
        int size = ProcTex.MODEL_SIZE;
        int x = Math.floorMod((int) (u * size), size);
        int y = Math.floorMod((int) (v * size), size);
        return ((tex[y * size + x] >> 16) & 0xFF) / 255f;
    }

    public static void main(String[] args) throws Exception {
        int cellW = 240, cellH = 300;
        int cols = 5, rows = 3;
        W = cellW;
        H = cellH;

        BufferedImage sheet = new BufferedImage(cols * cellW, rows * cellH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0x0E1116));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));

        String[] labels = new String[cols * rows];
        BufferedImage[] cells = new BufferedImage[cols * rows];

        // Fighter: idle, running, and dead.
        cells[0] = renderFighter(0f, false, 0f);
        labels[0] = "fighter (idle)";
        cells[1] = renderFighter(280f, false, 0.35f);
        labels[1] = "fighter (running)";
        cells[2] = renderFighter(0f, true, 0f);
        labels[2] = "fighter (dead)";

        int slot = 3;
        for (int wpn = 0; wpn < WeaponDef.COUNT; wpn++) {
            cells[slot] = renderWeapon(wpn);
            labels[slot] = WeaponDef.get(wpn).name;
            slot++;
        }
        cells[slot] = renderItem(com.arena3.game.ItemDef.ARMOR_RED);
        labels[slot++] = "heavy armor";
        cells[slot] = renderItem(com.arena3.game.ItemDef.HEALTH_MEGA);
        labels[slot++] = "mega health";
        cells[slot] = renderItem(com.arena3.game.ItemDef.POWERUP_QUAD);
        labels[slot++] = "quad damage";

        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == null) continue;
            int cx = (i % cols) * cellW, cy = (i / cols) * cellH;
            g.drawImage(cells[i], cx, cy, null);
            g.setColor(new Color(0xD8DEE9));
            g.drawString(labels[i], cx + 8, cy + cellH - 8);
            g.setColor(new Color(0x22262E));
            g.drawRect(cx, cy, cellW - 1, cellH - 1);
        }
        g.dispose();
        File out = new File(args.length > 0 ? args[0] : "models.png");
        ImageIO.write(sheet, "png", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }

    /** Orbits the camera to {@code yaw} at {@code dist}, aimed at (0, 0, targetZ). */
    static void beginCell(float dist, float height, float yaw, float targetZ) {
        pixels = new int[W * H];
        depth = new float[W * H];
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
        java.util.Arrays.fill(pixels, 0x0E1116);
        double a = Math.toRadians(yaw);
        camEye.set((float) Math.cos(a) * dist, (float) Math.sin(a) * dist, height);
        // Look back at the origin, using the same basis convention as the game.
        float pitch = (float) Math.toDegrees(Math.atan2(height - targetZ, dist));
        com.arena3.core.MathUtil.angleVectors(pitch, yaw + 180f, camFwd, camRight, camUp);
        focal = (W * 0.5f) / (float) Math.tan(Math.toRadians(32));
    }

    static BufferedImage finishCell() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, W, H, pixels, 0, W);
        return img;
    }

    static BufferedImage renderFighter(float speed, boolean dead, float bobCycle) {
        beginCell(105f, 26f, 35f, 4f);
        PlayerState ps = new PlayerState();
        ps.origin.set(0, 0, 0);
        ps.alive = !dead;
        ps.onGround = true;
        ps.velocity.set(speed, 0, 0);
        ps.bobCycle = bobCycle;
        ps.yaw = 0f;
        ps.legsYaw = -18f;
        ps.pitch = -8f;
        ps.deathTime = dead ? 1.5f : 0f;
        ps.weapon = WeaponDef.ROCKET;

        Mat4[] pose = FighterPose.allocate();
        FighterPose.compute(ps, pose);
        MeshBuilder.MeshData[] parts = Models.fighter(0.90f, 0.62f, 0.18f);
        for (int i = 0; i < Models.PART_COUNT; i++) draw(parts[i], pose[i]);
        if (!dead) draw(Models.weapon(ps.weapon), pose[FighterPose.WEAPON_MATRIX]);
        return finishCell();
    }

    static BufferedImage renderWeapon(int id) {
        beginCell(46f, 12f, 122f, 2f);
        Mat4 m = new Mat4();
        m.identity();
        m.translate(-8f, 0f, 0f);
        draw(Models.weapon(id), m);
        return finishCell();
    }

    static BufferedImage renderItem(int itemId) {
        beginCell(46f, 14f, 40f, 0f);
        Mat4 m = new Mat4();
        m.identity();
        m.rotateZ(30f);
        draw(Models.item(itemId), m);
        return finishCell();
    }

    static final int MF = MeshBuilder.VERTEX_FLOATS;
    static final Vec3 key = new Vec3(-0.45f, -0.35f, -0.82f);

    static void draw(MeshBuilder.MeshData data, Mat4 transform) {
        float[] sx = new float[3], sy = new float[3], sz = new float[3];
        float[] cr = new float[3], cg = new float[3], cb = new float[3];
        float[] tu = new float[3], tv = new float[3];
        int mat = 0;
        Vec3 p = new Vec3(), out = new Vec3(), n = new Vec3(), nOut = new Vec3();
        key.normalize();

        for (int i = 0; i < data.indexCount; i += 3) {
            boolean skip = false;
            for (int k = 0; k < 3; k++) {
                int o = data.indices[i + k] * MF;
                p.set(data.verts[o], data.verts[o + 1], data.verts[o + 2]);
                transform.transformPoint(p, out);
                n.set(data.verts[o + 3], data.verts[o + 4], data.verts[o + 5]);
                nOut.set(transform.m[0] * n.x + transform.m[4] * n.y + transform.m[8] * n.z,
                        transform.m[1] * n.x + transform.m[5] * n.y + transform.m[9] * n.z,
                        transform.m[2] * n.x + transform.m[6] * n.y + transform.m[10] * n.z);
                nOut.normalize();

                float dx = out.x - camEye.x, dy = out.y - camEye.y, dz = out.z - camEye.z;
                float cz = dx * camFwd.x + dy * camFwd.y + dz * camFwd.z;
                if (cz < 1f) {
                    skip = true;
                    break;
                }
                sx[k] = W * 0.5f + (dx * camRight.x + dy * camRight.y + dz * camRight.z) * focal / cz;
                sy[k] = H * 0.5f - (dx * camUp.x + dy * camUp.y + dz * camUp.z) * focal / cz;
                sz[k] = cz;
                tu[k] = data.verts[o + 6];
                tv[k] = data.verts[o + 7];
                mat = (int) data.verts[o + 11];
                float lam = Math.max(0f, -(nOut.x * key.x + nOut.y * key.y + nOut.z * key.z));
                float light = 0.30f + lam * 0.85f;
                cr[k] = data.verts[o + 8] * light;
                cg[k] = data.verts[o + 9] * light;
                cb[k] = data.verts[o + 10] * light;
            }
            if (skip) continue;
            raster(sx, sy, sz, cr, cg, cb, tu, tv, mat);
        }
    }

    static void raster(float[] sx, float[] sy, float[] sz, float[] cr, float[] cg, float[] cb,
                       float[] tu, float[] tv, int material) {
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
                float r = (w0 * cr[0] / sz[0] + w1 * cr[1] / sz[1] + w2 * cr[2] / sz[2]) * z;
                float g = (w0 * cg[0] / sz[0] + w1 * cg[1] / sz[1] + w2 * cg[2] / sz[2]) * z;
                float b = (w0 * cb[0] / sz[0] + w1 * cb[1] / sz[1] + w2 * cb[2] / sz[2]) * z;
                float u = (w0 * tu[0] / sz[0] + w1 * tu[1] / sz[1] + w2 * tu[2] / sz[2]) * z;
                float v = (w0 * tv[0] / sz[0] + w1 * tv[1] / sz[1] + w2 * tv[2] / sz[2]) * z;
                float detail = sampleMaterial(material, u, v) * 1.35f;
                pixels[o] = (c255(r * detail) << 16) | (c255(g * detail) << 8) | c255(b * detail);
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
