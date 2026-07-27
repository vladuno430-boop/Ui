package com.arena3.core;

/** Angle conversions and the small numeric helpers the engine leans on. */
public final class MathUtil {

    public static final float DEG2RAD = (float) (Math.PI / 180.0);
    public static final float RAD2DEG = (float) (180.0 / Math.PI);

    private MathUtil() {
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Smooth approach that behaves the same at any frame rate. */
    public static float approach(float cur, float target, float rate, float dt) {
        return lerp(cur, target, 1f - (float) Math.exp(-rate * dt));
    }

    /** Wraps to [-180, 180). */
    public static float normalizeAngle(float deg) {
        deg = deg % 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    /** Shortest signed difference from {@code from} to {@code to}, in degrees. */
    public static float angleDelta(float from, float to) {
        return normalizeAngle(to - from);
    }

    /** Steps an angle towards a target by at most {@code maxStep} degrees. */
    public static float angleTowards(float from, float to, float maxStep) {
        float d = angleDelta(from, to);
        if (d > maxStep) d = maxStep;
        if (d < -maxStep) d = -maxStep;
        return normalizeAngle(from + d);
    }

    /**
     * Builds the forward/right/up basis for yaw+pitch view angles. Any of the
     * outputs may be null. Yaw rotates around Z, pitch is positive looking down
     * (the Quake convention).
     */
    public static void angleVectors(float pitchDeg, float yawDeg, Vec3 forward, Vec3 right, Vec3 up) {
        float sy = (float) Math.sin(yawDeg * DEG2RAD), cy = (float) Math.cos(yawDeg * DEG2RAD);
        float sp = (float) Math.sin(pitchDeg * DEG2RAD), cp = (float) Math.cos(pitchDeg * DEG2RAD);
        if (forward != null) forward.set(cp * cy, cp * sy, -sp);
        if (right != null) right.set(sy, -cy, 0);
        if (up != null) up.set(sp * cy, sp * sy, cp);
    }

    /** Yaw in degrees pointing along the given direction. */
    public static float yawOf(Vec3 dir) {
        return (float) Math.atan2(dir.y, dir.x) * RAD2DEG;
    }

    /** Pitch in degrees pointing along the given direction (positive = down). */
    public static float pitchOf(Vec3 dir) {
        float flat = (float) Math.sqrt(dir.x * dir.x + dir.y * dir.y);
        return -(float) Math.atan2(dir.z, flat) * RAD2DEG;
    }

    /** Cheap smoothstep on [0,1]. */
    public static float smoothstep(float t) {
        t = clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
