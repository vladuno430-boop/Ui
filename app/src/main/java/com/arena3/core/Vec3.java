package com.arena3.core;

/**
 * Mutable 3-component vector. The simulation runs in Quake units with Z up:
 * one unit is roughly 3/4 of an inch, gravity is 800 u/s.
 *
 * <p>Instances are mutated in place in the hot paths (movement, tracing) to keep
 * the per-frame allocation count at zero; the {@code xxx()} family returns
 * {@code this} so calls chain.
 */
public final class Vec3 {
    public float x, y, z;

    public Vec3() {
    }

    public Vec3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3(Vec3 o) {
        set(o);
    }

    public Vec3 set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public Vec3 set(Vec3 o) {
        return set(o.x, o.y, o.z);
    }

    public Vec3 zero() {
        return set(0, 0, 0);
    }

    public Vec3 add(Vec3 o) {
        x += o.x;
        y += o.y;
        z += o.z;
        return this;
    }

    public Vec3 add(float ax, float ay, float az) {
        x += ax;
        y += ay;
        z += az;
        return this;
    }

    /** this += o * s */
    public Vec3 addScaled(Vec3 o, float s) {
        x += o.x * s;
        y += o.y * s;
        z += o.z * s;
        return this;
    }

    public Vec3 sub(Vec3 o) {
        x -= o.x;
        y -= o.y;
        z -= o.z;
        return this;
    }

    /** this = a - b */
    public Vec3 setSub(Vec3 a, Vec3 b) {
        return set(a.x - b.x, a.y - b.y, a.z - b.z);
    }

    /** this = a + b * s */
    public Vec3 setMa(Vec3 a, Vec3 b, float s) {
        return set(a.x + b.x * s, a.y + b.y * s, a.z + b.z * s);
    }

    public Vec3 scale(float s) {
        x *= s;
        y *= s;
        z *= s;
        return this;
    }

    public Vec3 negate() {
        return set(-x, -y, -z);
    }

    public float dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public float dot(float ox, float oy, float oz) {
        return x * ox + y * oy + z * oz;
    }

    /** this = a x b */
    public Vec3 setCross(Vec3 a, Vec3 b) {
        return set(a.y * b.z - a.z * b.y,
                a.z * b.x - a.x * b.z,
                a.x * b.y - a.y * b.x);
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public float lengthSq() {
        return x * x + y * y + z * z;
    }

    /** Length ignoring Z — the metric Quake movement code cares about. */
    public float length2d() {
        return (float) Math.sqrt(x * x + y * y);
    }

    public float distanceTo(Vec3 o) {
        float dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public float distanceSqTo(Vec3 o) {
        float dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public float distance2dTo(Vec3 o) {
        float dx = x - o.x, dy = y - o.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Normalises in place and returns the previous length. */
    public float normalize() {
        float len = length();
        if (len > 1e-6f) {
            float inv = 1f / len;
            x *= inv;
            y *= inv;
            z *= inv;
        } else {
            set(0, 0, 0);
        }
        return len;
    }

    public Vec3 lerp(Vec3 to, float t) {
        x += (to.x - x) * t;
        y += (to.y - y) * t;
        z += (to.z - z) * t;
        return this;
    }

    /** Removes the component of this vector pointing into {@code normal}. */
    public Vec3 clipVelocity(Vec3 normal, float overbounce) {
        float backoff = dot(normal);
        if (backoff < 0) {
            backoff *= overbounce;
        } else {
            backoff /= overbounce;
        }
        x -= normal.x * backoff;
        y -= normal.y * backoff;
        z -= normal.z * backoff;
        return this;
    }

    public boolean isFinite() {
        return !(Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)
                || Float.isInfinite(x) || Float.isInfinite(y) || Float.isInfinite(z));
    }

    @Override
    public String toString() {
        return String.format("(%.1f %.1f %.1f)", x, y, z);
    }
}
