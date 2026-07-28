package com.arena3.render;

import com.arena3.core.Vec3;
import com.arena3.game.CollisionWorld;
import com.arena3.game.Contents;
import com.arena3.game.Trace;

import java.util.Random;

/**
 * Sparks, smoke, blood, explosion cores, rail spirals and lightning bolts.
 *
 * <p>Particles live in flat arrays and are turned into camera-facing quads each
 * frame. Two batches come out: additive for anything that glows, alpha-blended
 * for smoke and blood. Beams are kept separately because they are oriented along
 * their own axis rather than towards the camera.
 */
public final class ParticleSystem {

    public static final int MAX_PARTICLES = 3000;
    public static final int MAX_BEAMS = 96;
    /** px py pz | u v | r g b a */
    public static final int VERTEX_FLOATS = 9;

    // particle state
    private final float[] px = new float[MAX_PARTICLES];
    private final float[] py = new float[MAX_PARTICLES];
    private final float[] pz = new float[MAX_PARTICLES];
    private final float[] vx = new float[MAX_PARTICLES];
    private final float[] vy = new float[MAX_PARTICLES];
    private final float[] vz = new float[MAX_PARTICLES];
    private final float[] life = new float[MAX_PARTICLES];
    private final float[] maxLife = new float[MAX_PARTICLES];
    private final float[] size = new float[MAX_PARTICLES];
    private final float[] sizeGrow = new float[MAX_PARTICLES];
    private final float[] cr = new float[MAX_PARTICLES];
    private final float[] cg = new float[MAX_PARTICLES];
    private final float[] cb = new float[MAX_PARTICLES];
    private final float[] alpha = new float[MAX_PARTICLES];
    private final float[] gravity = new float[MAX_PARTICLES];
    private final float[] drag = new float[MAX_PARTICLES];
    private final boolean[] additive = new boolean[MAX_PARTICLES];
    private final boolean[] collides = new boolean[MAX_PARTICLES];
    private final boolean[] alive = new boolean[MAX_PARTICLES];
    private int cursor;
    private int liveCount;

    // beams: straight glowing segments with a fading life
    private final float[] beamData = new float[MAX_BEAMS * 12]; // ax ay az bx by bz r g b width life maxLife
    private int beamCount;

    private final Random rnd = new Random(12345);
    private final Trace trace = new Trace();
    private final Vec3 tmpA = new Vec3();
    private final Vec3 tmpB = new Vec3();

    public int liveParticles() {
        return liveCount;
    }

    public int beams() {
        return beamCount;
    }

    public void clear() {
        java.util.Arrays.fill(alive, false);
        liveCount = 0;
        beamCount = 0;
    }

    // ------------------------------------------------------------------ spawn

    private int alloc() {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            int idx = (cursor + i) % MAX_PARTICLES;
            if (!alive[idx]) {
                cursor = (idx + 1) % MAX_PARTICLES;
                alive[idx] = true;
                liveCount++;
                return idx;
            }
        }
        return -1;
    }

    private void spawn(float x, float y, float z, float dx, float dy, float dz,
                       float lifeSec, float sz, float grow, float r, float g, float b, float a,
                       float grav, float dragCoef, boolean add, boolean collide) {
        int i = alloc();
        if (i < 0) return;
        px[i] = x;
        py[i] = y;
        pz[i] = z;
        vx[i] = dx;
        vy[i] = dy;
        vz[i] = dz;
        life[i] = lifeSec;
        maxLife[i] = lifeSec;
        size[i] = sz;
        sizeGrow[i] = grow;
        cr[i] = r;
        cg[i] = g;
        cb[i] = b;
        alpha[i] = a;
        gravity[i] = grav;
        drag[i] = dragCoef;
        additive[i] = add;
        collides[i] = collide;
    }

    private float rand(float lo, float hi) {
        return lo + rnd.nextFloat() * (hi - lo);
    }

    /** Fireball plus smoke and sparks. */
    public void explosion(Vec3 at, float radius, float r, float g, float b) {
        float scale = radius / 120f;
        for (int i = 0; i < 26; i++) {
            float sp = rand(60f, 340f) * scale;
            randomDirection(tmpA);
            spawn(at.x, at.y, at.z, tmpA.x * sp, tmpA.y * sp, tmpA.z * sp,
                    rand(0.25f, 0.55f), rand(18f, 40f) * scale, 90f * scale,
                    r, g, b, 1f, -60f, 1.6f, true, false);
        }
        for (int i = 0; i < 16; i++) {
            randomDirection(tmpA);
            float sp = rand(30f, 150f) * scale;
            spawn(at.x, at.y, at.z, tmpA.x * sp, tmpA.y * sp, tmpA.z * sp + 40f,
                    rand(0.7f, 1.5f), rand(22f, 46f) * scale, 130f * scale,
                    0.14f, 0.13f, 0.13f, 0.55f, -20f, 1.1f, false, false);
        }
        for (int i = 0; i < 22; i++) {
            randomDirection(tmpA);
            float sp = rand(250f, 750f) * scale;
            spawn(at.x, at.y, at.z, tmpA.x * sp, tmpA.y * sp, tmpA.z * sp,
                    rand(0.3f, 0.9f), rand(2.5f, 5f), -1.5f,
                    1.6f, 0.85f, 0.35f, 1f, 700f, 0.4f, true, true);
        }
    }

    /** Sparks and a dust puff where a shot hits a wall. */
    public void bulletImpact(Vec3 at, Vec3 normal) {
        for (int i = 0; i < 7; i++) {
            randomHemisphere(tmpA, normal, 0.75f);
            float sp = rand(90f, 320f);
            spawn(at.x, at.y, at.z, tmpA.x * sp, tmpA.y * sp, tmpA.z * sp,
                    rand(0.15f, 0.4f), rand(1.6f, 3.2f), -2f,
                    1.5f, 1.1f, 0.55f, 1f, 600f, 0.5f, true, false);
        }
        for (int i = 0; i < 3; i++) {
            randomHemisphere(tmpA, normal, 0.5f);
            float sp = rand(20f, 70f);
            spawn(at.x, at.y, at.z, tmpA.x * sp, tmpA.y * sp, tmpA.z * sp,
                    rand(0.3f, 0.7f), rand(5f, 11f), 26f,
                    0.42f, 0.40f, 0.38f, 0.42f, -12f, 1.4f, false, false);
        }
    }

    /** Red spray when a shot lands on a player. */
    public void blood(Vec3 at, Vec3 dir, int amount) {
        int n = Math.min(18, 3 + amount / 6);
        for (int i = 0; i < n; i++) {
            randomHemisphere(tmpA, dir, 0.6f);
            float sp = rand(40f, 220f);
            spawn(at.x, at.y, at.z, tmpA.x * sp, tmpA.y * sp, tmpA.z * sp,
                    rand(0.4f, 0.9f), rand(3.5f, 8f), 4f,
                    0.55f, 0.06f, 0.07f, 0.95f, 620f, 0.7f, false, true);
        }
    }

    /** Smoke and fire left behind a rocket. */
    public void rocketTrail(Vec3 at, Vec3 back) {
        spawn(at.x, at.y, at.z, back.x * 12f + rand(-10, 10), back.y * 12f + rand(-10, 10),
                back.z * 12f + rand(-10, 10), rand(0.35f, 0.7f), rand(6f, 10f), 42f,
                0.30f, 0.27f, 0.26f, 0.42f, 12f, 1.5f, false, false);
        spawn(at.x, at.y, at.z, 0, 0, 0, 0.09f, 9f, -20f, 1.5f, 0.75f, 0.25f, 1f, 0f, 0f, true, false);
    }

    public void plasmaTrail(Vec3 at) {
        spawn(at.x, at.y, at.z, 0, 0, 0, 0.13f, 7f, -26f, 0.35f, 0.60f, 1.5f, 1f, 0f, 0f, true, false);
    }

    /** Spiralling ribbon of dots left by a railgun slug. */
    public void railTrail(Vec3 from, Vec3 to, float r, float g, float b) {
        tmpA.setSub(to, from);
        float length = tmpA.length();
        if (length < 1f) return;
        tmpA.scale(1f / length);
        // Build a basis perpendicular to the shot.
        tmpB.set(Math.abs(tmpA.z) > 0.9f ? 1f : 0f, 0f, Math.abs(tmpA.z) > 0.9f ? 0f : 1f);
        Vec3 side = new Vec3().setCross(tmpA, tmpB);
        side.normalize();
        Vec3 up = new Vec3().setCross(tmpA, side);

        int steps = (int) Math.min(220f, length / 12f);
        for (int i = 0; i < steps; i++) {
            float t = i / (float) steps;
            float d = t * length;
            float angle = t * length * 0.055f;
            float radius = 7f;
            float ox = (float) (Math.cos(angle) * radius);
            float oy = (float) (Math.sin(angle) * radius);
            float x = from.x + tmpA.x * d + side.x * ox + up.x * oy;
            float y = from.y + tmpA.y * d + side.y * ox + up.y * oy;
            float z = from.z + tmpA.z * d + side.z * ox + up.z * oy;
            spawn(x, y, z, side.x * 6f, side.y * 6f, side.z * 6f,
                    rand(0.5f, 0.9f), 4.5f, -3f, r, g, b, 1f, 0f, 0.4f, true, false);
        }
        beam(from, to, r * 0.8f, g * 0.8f, b * 0.8f, 2.2f, 0.32f);
    }

    /** Glowing segment: lightning bolts, rail cores, teleport columns. */
    public void beam(Vec3 from, Vec3 to, float r, float g, float b, float width, float lifeSec) {
        if (beamCount >= MAX_BEAMS) return;
        int o = beamCount * 12;
        beamData[o] = from.x;
        beamData[o + 1] = from.y;
        beamData[o + 2] = from.z;
        beamData[o + 3] = to.x;
        beamData[o + 4] = to.y;
        beamData[o + 5] = to.z;
        beamData[o + 6] = r;
        beamData[o + 7] = g;
        beamData[o + 8] = b;
        beamData[o + 9] = width;
        beamData[o + 10] = lifeSec;
        beamData[o + 11] = lifeSec;
        beamCount++;
    }

    /** Column of light where someone materialises. */
    public void teleportFlare(Vec3 at) {
        for (int i = 0; i < 40; i++) {
            float ang = rnd.nextFloat() * 6.2831855f;
            float rad = rand(4f, 22f);
            float x = at.x + (float) Math.cos(ang) * rad;
            float y = at.y + (float) Math.sin(ang) * rad;
            spawn(x, y, at.z - 24f + rnd.nextFloat() * 60f, 0, 0, rand(90f, 260f),
                    rand(0.4f, 0.8f), rand(4f, 9f), -6f, 0.55f, 0.35f, 1.5f, 1f, 0f, 0.6f, true, false);
        }
    }

    /** Puff of dust when someone lands hard. */
    public void landPuff(Vec3 at, float force) {
        int n = (int) Math.min(14f, 3f + force / 60f);
        for (int i = 0; i < n; i++) {
            float ang = rnd.nextFloat() * 6.2831855f;
            float sp = rand(40f, 130f);
            spawn(at.x, at.y, at.z - 22f, (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp, rand(5f, 40f),
                    rand(0.3f, 0.6f), rand(6f, 12f), 34f, 0.42f, 0.40f, 0.38f, 0.34f, -10f, 1.8f, false, false);
        }
    }

    /** Upward jet over a jump pad. */
    public void padJet(Vec3 at) {
        for (int i = 0; i < 3; i++) {
            spawn(at.x + rand(-24, 24), at.y + rand(-24, 24), at.z + 2f, 0, 0, rand(120f, 300f),
                    rand(0.4f, 0.8f), rand(4f, 8f), -4f, 0.25f, 1.0f, 0.75f, 0.9f, -40f, 0.6f, true, false);
        }
    }

    /** Muzzle flash sprite. */
    public void muzzleFlash(Vec3 at, float r, float g, float b) {
        spawn(at.x, at.y, at.z, 0, 0, 0, 0.05f, 14f, -40f, r, g, b, 1f, 0f, 0f, true, false);
    }

    public void sparkBurst(Vec3 at, float r, float g, float b, int count, float speed) {
        for (int i = 0; i < count; i++) {
            randomDirection(tmpA);
            float sp = rand(speed * 0.3f, speed);
            spawn(at.x, at.y, at.z, tmpA.x * sp, tmpA.y * sp, tmpA.z * sp,
                    rand(0.2f, 0.6f), rand(2f, 4f), -2f, r, g, b, 1f, 400f, 0.5f, true, false);
        }
    }

    private void randomDirection(Vec3 out) {
        float z = rand(-1f, 1f);
        float a = rnd.nextFloat() * 6.2831855f;
        float r = (float) Math.sqrt(Math.max(0f, 1f - z * z));
        out.set((float) Math.cos(a) * r, (float) Math.sin(a) * r, z);
    }

    private void randomHemisphere(Vec3 out, Vec3 axis, float spread) {
        randomDirection(out);
        if (out.dot(axis) < 0) out.negate();
        out.lerp(axis, 1f - spread);
        out.normalize();
    }

    // ----------------------------------------------------------------- update

    public void update(float dt, CollisionWorld world) {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            if (!alive[i]) continue;
            life[i] -= dt;
            if (life[i] <= 0f) {
                alive[i] = false;
                liveCount--;
                continue;
            }
            vz[i] -= gravity[i] * dt;
            if (drag[i] > 0f) {
                float k = Math.max(0f, 1f - drag[i] * dt);
                vx[i] *= k;
                vy[i] *= k;
                vz[i] *= k;
            }
            float nx = px[i] + vx[i] * dt;
            float ny = py[i] + vy[i] * dt;
            float nz = pz[i] + vz[i] * dt;

            if (collides[i] && world != null) {
                tmpA.set(px[i], py[i], pz[i]);
                tmpB.set(nx, ny, nz);
                world.traceRay(trace, tmpA, tmpB, Contents.MASK_SHOT);
                if (trace.fraction < 1f && !trace.startSolid) {
                    nx = trace.endPos.x + trace.normal.x * 0.5f;
                    ny = trace.endPos.y + trace.normal.y * 0.5f;
                    nz = trace.endPos.z + trace.normal.z * 0.5f;
                    float dot = vx[i] * trace.normal.x + vy[i] * trace.normal.y + vz[i] * trace.normal.z;
                    vx[i] = (vx[i] - 2f * dot * trace.normal.x) * 0.32f;
                    vy[i] = (vy[i] - 2f * dot * trace.normal.y) * 0.32f;
                    vz[i] = (vz[i] - 2f * dot * trace.normal.z) * 0.32f;
                }
            }
            px[i] = nx;
            py[i] = ny;
            pz[i] = nz;
            size[i] = Math.max(0.5f, size[i] + sizeGrow[i] * dt);
        }

        for (int i = beamCount - 1; i >= 0; i--) {
            int o = i * 12;
            beamData[o + 10] -= dt;
            if (beamData[o + 10] <= 0f) {
                // Swap with the last beam and shrink.
                int last = (beamCount - 1) * 12;
                if (o != last) System.arraycopy(beamData, last, beamData, o, 12);
                beamCount--;
            }
        }
    }

    // ------------------------------------------------------------- geometry

    /**
     * Writes camera-facing quads into {@code out}, four vertices per particle.
     * Returns the number of quads written.
     */
    public int buildQuads(float[] out, int maxQuads, Vec3 camRight, Vec3 camUp, boolean wantAdditive) {
        int quads = 0;
        for (int i = 0; i < MAX_PARTICLES && quads < maxQuads; i++) {
            if (!alive[i] || additive[i] != wantAdditive) continue;
            float t = life[i] / maxLife[i];
            float a = alpha[i] * Math.min(1f, t * 2.2f);
            float s = size[i];
            int o = quads * 4 * VERTEX_FLOATS;
            float rx = camRight.x * s, ry = camRight.y * s, rz = camRight.z * s;
            float ux = camUp.x * s, uy = camUp.y * s, uz = camUp.z * s;
            // Glowing particles keep their colour and fade via alpha.
            writeQuadVertex(out, o, px[i] - rx - ux, py[i] - ry - uy, pz[i] - rz - uz, 0f, 0f, i, a);
            writeQuadVertex(out, o + VERTEX_FLOATS, px[i] + rx - ux, py[i] + ry - uy, pz[i] + rz - uz, 1f, 0f, i, a);
            writeQuadVertex(out, o + VERTEX_FLOATS * 2, px[i] + rx + ux, py[i] + ry + uy, pz[i] + rz + uz, 1f, 1f, i, a);
            writeQuadVertex(out, o + VERTEX_FLOATS * 3, px[i] - rx + ux, py[i] - ry + uy, pz[i] - rz + uz, 0f, 1f, i, a);
            quads++;
        }
        return quads;
    }

    private void writeQuadVertex(float[] out, int o, float x, float y, float z, float u, float v,
                                 int i, float a) {
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = z;
        out[o + 3] = u;
        out[o + 4] = v;
        out[o + 5] = cr[i];
        out[o + 6] = cg[i];
        out[o + 7] = cb[i];
        out[o + 8] = a;
    }

    /** Writes beams as quads that face the camera along their own axis. */
    public int buildBeamQuads(float[] out, int maxQuads, Vec3 camPos) {
        int quads = 0;
        Vec3 axis = new Vec3(), toCam = new Vec3(), side = new Vec3();
        for (int i = 0; i < beamCount && quads < maxQuads; i++) {
            int o = i * 12;
            axis.set(beamData[o + 3] - beamData[o], beamData[o + 4] - beamData[o + 1],
                    beamData[o + 5] - beamData[o + 2]);
            if (axis.normalize() < 1f) continue;
            toCam.set(camPos.x - beamData[o], camPos.y - beamData[o + 1], camPos.z - beamData[o + 2]);
            side.setCross(axis, toCam);
            if (side.normalize() < 1e-4f) continue;

            float fade = beamData[o + 10] / Math.max(0.0001f, beamData[o + 11]);
            float w = beamData[o + 9] * (0.4f + 0.6f * fade);
            float r = beamData[o + 6], g = beamData[o + 7], b = beamData[o + 8];
            int q = quads * 4 * VERTEX_FLOATS;
            beamVertex(out, q, beamData[o] - side.x * w, beamData[o + 1] - side.y * w,
                    beamData[o + 2] - side.z * w, 0f, 0f, r, g, b, fade);
            beamVertex(out, q + VERTEX_FLOATS, beamData[o + 3] - side.x * w, beamData[o + 4] - side.y * w,
                    beamData[o + 5] - side.z * w, 1f, 0f, r, g, b, fade);
            beamVertex(out, q + VERTEX_FLOATS * 2, beamData[o + 3] + side.x * w, beamData[o + 4] + side.y * w,
                    beamData[o + 5] + side.z * w, 1f, 1f, r, g, b, fade);
            beamVertex(out, q + VERTEX_FLOATS * 3, beamData[o] + side.x * w, beamData[o + 1] + side.y * w,
                    beamData[o + 2] + side.z * w, 0f, 1f, r, g, b, fade);
            quads++;
        }
        return quads;
    }

    private static void beamVertex(float[] out, int o, float x, float y, float z, float u, float v,
                                   float r, float g, float b, float a) {
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = z;
        out[o + 3] = u;
        out[o + 4] = v;
        out[o + 5] = r;
        out[o + 6] = g;
        out[o + 7] = b;
        out[o + 8] = a;
    }
}
