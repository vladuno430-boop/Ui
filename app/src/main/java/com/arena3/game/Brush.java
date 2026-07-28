package com.arena3.game;

import com.arena3.core.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A convex volume bounded by planes — the same primitive Quake levels are carved
 * from. Collision uses the planes directly; the renderer turns each plane into a
 * polygon by clipping a large quad against all the others.
 *
 * <p>Planes are stored as (nx, ny, nz, dist) with the normal pointing out of the
 * volume, so a point is inside when {@code dot(n, p) - dist <= 0} for every plane.
 */
public final class Brush {

    /** 4 floats per plane: nx, ny, nz, dist. */
    public final float[] planes;
    /** Texture index per plane, parallel to {@link #planes}. */
    public final int[] texture;
    /** Surface flag bits per plane. */
    public final int[] surfaceFlags;
    /** Texture scale per plane, in world units per tile. */
    public final float[] texScale;

    public int contents;
    public final Vec3 mins = new Vec3();
    public final Vec3 maxs = new Vec3();
    /** Marks brushes generated as decoration so the renderer can sort them. */
    public boolean detail;

    public Brush(float[] planes, int contents) {
        this.planes = planes;
        this.contents = contents;
        int n = planes.length / 4;
        this.texture = new int[n];
        this.surfaceFlags = new int[n];
        this.texScale = new float[n];
        java.util.Arrays.fill(texScale, 128f);
        computeBounds();
    }

    public int planeCount() {
        return planes.length / 4;
    }

    public Brush setTexture(int tex) {
        java.util.Arrays.fill(texture, tex);
        return this;
    }

    public Brush setTexture(int face, int tex) {
        texture[face] = tex;
        return this;
    }

    public Brush setFlags(int face, int flags) {
        surfaceFlags[face] |= flags;
        return this;
    }

    public Brush setAllFlags(int flags) {
        for (int i = 0; i < surfaceFlags.length; i++) surfaceFlags[i] |= flags;
        return this;
    }

    public Brush setScale(float scale) {
        java.util.Arrays.fill(texScale, scale);
        return this;
    }

    /** Axis-aligned box brush. Faces are ordered -X +X -Y +Y -Z +Z. */
    public static Brush box(float x0, float y0, float z0, float x1, float y1, float z1, int contents) {
        float ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
        float ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        float az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);
        float[] p = {
                -1, 0, 0, -ax0,
                1, 0, 0, ax1,
                0, -1, 0, -ay0,
                0, 1, 0, ay1,
                0, 0, -1, -az0,
                0, 0, 1, az1,
        };
        return new Brush(p, contents);
    }

    /**
     * A box with one edge sliced off to make a ramp. {@code axis} is 0 for a slope
     * running along X and 1 for one running along Y; {@code rising} picks which end
     * of that axis is high. The sloped face is the last plane.
     */
    public static Brush ramp(float x0, float y0, float z0, float x1, float y1, float z1,
                             int axis, boolean rising, int contents) {
        float ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
        float ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        float az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

        // Slope plane through (low end, bottom) and (high end, top).
        float run = axis == 0 ? (ax1 - ax0) : (ay1 - ay0);
        float rise = az1 - az0;
        Vec3 n = new Vec3();
        if (axis == 0) {
            n.set(rising ? -rise : rise, 0, run);
        } else {
            n.set(0, rising ? -rise : rise, run);
        }
        n.normalize();
        // The plane must pass through the top of the high end.
        float px = rising ? ax1 : ax0;
        float py = rising ? ay1 : ay0;
        float dist = n.dot(axis == 0 ? px : ax0, axis == 0 ? ay0 : py, az1);

        float[] p = {
                -1, 0, 0, -ax0,
                1, 0, 0, ax1,
                0, -1, 0, -ay0,
                0, 1, 0, ay1,
                0, 0, -1, -az0,
                n.x, n.y, n.z, dist,
        };
        return new Brush(p, contents);
    }

    /**
     * Prism with an arbitrary set of vertical side planes derived from a convex
     * 2D footprint (counter-clockwise), capped at z0/z1. Used for columns and
     * angled walls.
     */
    public static Brush prism(float[] footprint, float z0, float z1, int contents) {
        int n = footprint.length / 2;
        List<float[]> pl = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float x0 = footprint[i * 2], y0 = footprint[i * 2 + 1];
            float x1 = footprint[((i + 1) % n) * 2], y1 = footprint[((i + 1) % n) * 2 + 1];
            float dx = x1 - x0, dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-4f) continue;
            // Outward normal of a CCW footprint is (dy, -dx).
            float nx = dy / len, ny = -dx / len;
            pl.add(new float[]{nx, ny, 0, nx * x0 + ny * y0});
        }
        pl.add(new float[]{0, 0, -1, -Math.min(z0, z1)});
        pl.add(new float[]{0, 0, 1, Math.max(z0, z1)});
        float[] planes = new float[pl.size() * 4];
        for (int i = 0; i < pl.size(); i++) {
            System.arraycopy(pl.get(i), 0, planes, i * 4, 4);
        }
        return new Brush(planes, contents);
    }

    /** Recomputes {@link #mins}/{@link #maxs} by intersecting plane triples. */
    public void computeBounds() {
        int n = planeCount();
        mins.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        maxs.set(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        Vec3 p = new Vec3();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    if (!intersect3(i, j, k, p)) continue;
                    if (!containsPoint(p, 0.05f)) continue;
                    mins.set(Math.min(mins.x, p.x), Math.min(mins.y, p.y), Math.min(mins.z, p.z));
                    maxs.set(Math.max(maxs.x, p.x), Math.max(maxs.y, p.y), Math.max(maxs.z, p.z));
                }
            }
        }
        if (mins.x > maxs.x) { // degenerate
            mins.zero();
            maxs.zero();
        }
    }

    /** Solves for the point common to three planes; false if they are near-parallel. */
    public boolean intersect3(int a, int b, int c, Vec3 out) {
        float a1 = planes[a * 4], b1 = planes[a * 4 + 1], c1 = planes[a * 4 + 2], d1 = planes[a * 4 + 3];
        float a2 = planes[b * 4], b2 = planes[b * 4 + 1], c2 = planes[b * 4 + 2], d2 = planes[b * 4 + 3];
        float a3 = planes[c * 4], b3 = planes[c * 4 + 1], c3 = planes[c * 4 + 2], d3 = planes[c * 4 + 3];
        float det = a1 * (b2 * c3 - b3 * c2) - b1 * (a2 * c3 - a3 * c2) + c1 * (a2 * b3 - a3 * b2);
        if (Math.abs(det) < 1e-6f) return false;
        float inv = 1f / det;
        float x = (d1 * (b2 * c3 - b3 * c2) - b1 * (d2 * c3 - d3 * c2) + c1 * (d2 * b3 - d3 * b2)) * inv;
        float y = (a1 * (d2 * c3 - d3 * c2) - d1 * (a2 * c3 - a3 * c2) + c1 * (a2 * d3 - a3 * d2)) * inv;
        float z = (a1 * (b2 * d3 - b3 * d2) - b1 * (a2 * d3 - a3 * d2) + d1 * (a2 * b3 - a3 * b2)) * inv;
        out.set(x, y, z);
        return true;
    }

    public boolean containsPoint(Vec3 p, float epsilon) {
        int n = planeCount();
        for (int i = 0; i < n; i++) {
            float d = planes[i * 4] * p.x + planes[i * 4 + 1] * p.y + planes[i * 4 + 2] * p.z - planes[i * 4 + 3];
            if (d > epsilon) return false;
        }
        return true;
    }

    /** True when the box overlaps this brush's bounding box. */
    public boolean boundsOverlap(Vec3 bmins, Vec3 bmaxs) {
        return !(bmaxs.x < mins.x || bmins.x > maxs.x
                || bmaxs.y < mins.y || bmins.y > maxs.y
                || bmaxs.z < mins.z || bmins.z > maxs.z);
    }
}
