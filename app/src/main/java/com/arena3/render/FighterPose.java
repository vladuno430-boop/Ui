package com.arena3.render;

import com.arena3.core.Mat4;
import com.arena3.game.PlayerState;

/**
 * Works out where each part of a fighter goes for a given player state: legs
 * swing with the run cycle, the torso follows the view while the legs lag it,
 * the head pitches with the aim, and a corpse topples over.
 *
 * <p>Kept free of GL so the offline preview poses fighters exactly as the game
 * does.
 */
public final class FighterPose {

    /** Seven body parts plus the held weapon. */
    public static final int MATRIX_COUNT = Models.PART_COUNT + 1;
    public static final int WEAPON_MATRIX = Models.PART_COUNT;

    private FighterPose() {
    }

    public static Mat4[] allocate() {
        Mat4[] m = new Mat4[MATRIX_COUNT];
        for (int i = 0; i < m.length; i++) m[i] = new Mat4();
        return m;
    }

    /** Fills {@code out} with one model matrix per part. */
    public static void compute(PlayerState ps, Mat4[] out) {
        float legsYaw = ps.legsYaw;
        float torsoYaw = ps.yaw;
        float roll = 0f;
        float sink = 0f;
        if (!ps.alive) {
            // Fall over during the first third of a second, then settle.
            roll = Math.min(1f, ps.deathTime * 3.2f) * 88f;
            sink = -Math.min(14f, ps.deathTime * 30f);
            legsYaw = torsoYaw;
        }

        float swing = 0f;
        float speed = ps.velocity.length2d();
        if (ps.alive && ps.onGround && speed > 20f) {
            swing = (float) Math.sin(ps.bobCycle * 6.2831855f) * Math.min(38f, speed * 0.11f);
        } else if (ps.alive && !ps.onGround) {
            swing = 22f;
        }

        part(out[Models.PART_LEG_L], ps, sink, legsYaw, roll, 0f, swing, 0f);
        part(out[Models.PART_LEG_R], ps, sink, legsYaw, roll, 0f, -swing, 0f);
        part(out[Models.PART_HIPS], ps, sink, legsYaw, roll, 0f, 0f, 0f);
        part(out[Models.PART_TORSO], ps, sink, torsoYaw, roll, 4f, 0f, 0f);
        part(out[Models.PART_ARM_L], ps, sink, torsoYaw, roll, 22f, -swing * 0.6f, 12f);
        part(out[Models.PART_ARM_R], ps, sink, torsoYaw, roll, 22f, swing * 0.6f, -12f);
        part(out[Models.PART_HEAD], ps, sink, torsoYaw, roll, 26f, ps.alive ? ps.pitch * 0.5f : 0f, 0f);

        Mat4 weapon = out[WEAPON_MATRIX];
        weapon.identity();
        weapon.translate(ps.origin.x, ps.origin.y, ps.origin.z + sink);
        weapon.rotateZ(torsoYaw);
        if (roll != 0f) weapon.rotateX(roll);
        weapon.translate(6f, -13f, 12f);
        weapon.rotateY(ps.alive ? ps.pitch : 0f);
        weapon.scale(0.85f, 0.85f, 0.85f);
    }

    private static void part(Mat4 m, PlayerState ps, float sink, float yaw, float roll,
                             float attachZ, float pitch, float rollLocal) {
        m.identity();
        m.translate(ps.origin.x, ps.origin.y, ps.origin.z + sink);
        m.rotateZ(yaw);
        if (roll != 0f) m.rotateX(roll);
        m.translate(0f, 0f, attachZ);
        if (pitch != 0f) m.rotateY(pitch);
        if (rollLocal != 0f) m.rotateX(rollLocal);
    }
}
