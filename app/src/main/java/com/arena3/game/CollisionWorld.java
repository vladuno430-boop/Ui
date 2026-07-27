package com.arena3.game;

import com.arena3.core.Vec3;

import java.util.List;

/**
 * Sweeps axis-aligned boxes through a soup of convex brushes.
 *
 * <p>The test is the classic Quake one: every brush plane is pushed outwards by
 * the box's extent along that plane's normal, which turns the box sweep into a
 * ray sweep against the expanded volume. Because the expanded halfspaces only
 * approximate the true Minkowski sum around corners, box brushes carry their
 * axial planes anyway and non-axial brushes get their bounding-box planes added
 * as bevels when they are registered.
 *
 * <p>A flat uniform grid over XY narrows each sweep to a handful of brushes.
 */
public final class CollisionWorld {

    /** Enter/leave fraction bias, 1/32 of a unit as in Quake. */
    private static final float EPSILON = 0.03125f;
    /** Slack used when deciding a sweep stays clear of a plane. */
    private static final float SURFACE_CLIP_EPSILON = 0.125f;
    /** How far effects are pushed off a surface to avoid z-fighting. */
    private static final float SURFACE_CLIP_OFFSET = 0.125f;

    private Brush[] brushes = new Brush[0];

    // --- broadphase grid ---
    private static final float CELL = 512f;
    private int gridW, gridH;
    private float originX, originY;
    private int[][] cells;          // cell -> brush indices
    private int[] visitStamp;       // per-brush "seen during this query" marker
    private int stamp;

    private final Vec3 worldMins = new Vec3();
    private final Vec3 worldMaxs = new Vec3();

    // scratch
    private final Vec3 tStart = new Vec3();
    private final Vec3 tEnd = new Vec3();
    private final Vec3 half = new Vec3();
    private final Vec3 offset = new Vec3();
    private final Vec3 sweepMins = new Vec3();
    private final Vec3 sweepMaxs = new Vec3();

    public void build(List<Brush> list) {
        brushes = list.toArray(new Brush[0]);
        visitStamp = new int[brushes.length];
        stamp = 0;

        worldMins.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        worldMaxs.set(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (Brush b : brushes) {
            worldMins.set(Math.min(worldMins.x, b.mins.x), Math.min(worldMins.y, b.mins.y),
                    Math.min(worldMins.z, b.mins.z));
            worldMaxs.set(Math.max(worldMaxs.x, b.maxs.x), Math.max(worldMaxs.y, b.maxs.y),
                    Math.max(worldMaxs.z, b.maxs.z));
        }
        if (brushes.length == 0) {
            worldMins.zero();
            worldMaxs.zero();
        }

        originX = worldMins.x - CELL;
        originY = worldMins.y - CELL;
        gridW = Math.max(1, (int) Math.ceil((worldMaxs.x - originX + CELL) / CELL));
        gridH = Math.max(1, (int) Math.ceil((worldMaxs.y - originY + CELL) / CELL));

        int[] counts = new int[gridW * gridH];
        for (Brush b : brushes) {
            forEachCell(b.mins, b.maxs, c -> counts[c]++);
        }
        cells = new int[gridW * gridH][];
        for (int i = 0; i < cells.length; i++) cells[i] = new int[counts[i]];
        int[] fill = new int[gridW * gridH];
        for (int i = 0; i < brushes.length; i++) {
            final int bi = i;
            forEachCell(brushes[i].mins, brushes[i].maxs, c -> cells[c][fill[c]++] = bi);
        }
    }

    private interface CellVisitor {
        void visit(int cell);
    }

    private void forEachCell(Vec3 mins, Vec3 maxs, CellVisitor v) {
        int x0 = clampCell((int) Math.floor((mins.x - originX) / CELL), gridW);
        int x1 = clampCell((int) Math.floor((maxs.x - originX) / CELL), gridW);
        int y0 = clampCell((int) Math.floor((mins.y - originY) / CELL), gridH);
        int y1 = clampCell((int) Math.floor((maxs.y - originY) / CELL), gridH);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                v.visit(y * gridW + x);
            }
        }
    }

    private static int clampCell(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    public Brush[] brushes() {
        return brushes;
    }

    public Vec3 worldMins() {
        return worldMins;
    }

    public Vec3 worldMaxs() {
        return worldMaxs;
    }

    /**
     * Sweeps the box {@code [mins,maxs]} from {@code start} to {@code end},
     * writing the first blocking hit into {@code out}.
     */
    public void traceBox(Trace out, Vec3 start, Vec3 end, Vec3 mins, Vec3 maxs, int mask) {
        out.reset(end);

        // Re-centre the box so the plane offsets can use symmetric extents.
        offset.set((mins.x + maxs.x) * 0.5f, (mins.y + maxs.y) * 0.5f, (mins.z + maxs.z) * 0.5f);
        half.set((maxs.x - mins.x) * 0.5f, (maxs.y - mins.y) * 0.5f, (maxs.z - mins.z) * 0.5f);
        tStart.set(start.x + offset.x, start.y + offset.y, start.z + offset.z);
        tEnd.set(end.x + offset.x, end.y + offset.y, end.z + offset.z);

        sweepMins.set(Math.min(tStart.x, tEnd.x) - half.x - 1, Math.min(tStart.y, tEnd.y) - half.y - 1,
                Math.min(tStart.z, tEnd.z) - half.z - 1);
        sweepMaxs.set(Math.max(tStart.x, tEnd.x) + half.x + 1, Math.max(tStart.y, tEnd.y) + half.y + 1,
                Math.max(tStart.z, tEnd.z) + half.z + 1);

        stamp++;
        int x0 = clampCell((int) Math.floor((sweepMins.x - originX) / CELL), gridW);
        int x1 = clampCell((int) Math.floor((sweepMaxs.x - originX) / CELL), gridW);
        int y0 = clampCell((int) Math.floor((sweepMins.y - originY) / CELL), gridH);
        int y1 = clampCell((int) Math.floor((sweepMaxs.y - originY) / CELL), gridH);

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int[] cell = cells[y * gridW + x];
                for (int idx : cell) {
                    if (visitStamp[idx] == stamp) continue;
                    visitStamp[idx] = stamp;
                    Brush b = brushes[idx];
                    if ((b.contents & mask) == 0) continue;
                    if (b.maxs.z < sweepMins.z || b.mins.z > sweepMaxs.z) continue;
                    if (b.maxs.x < sweepMins.x || b.mins.x > sweepMaxs.x) continue;
                    if (b.maxs.y < sweepMins.y || b.mins.y > sweepMaxs.y) continue;
                    traceThroughBrush(out, b);
                    if (out.allSolid) {
                        out.fraction = 0f;
                        out.endPos.set(start);
                        return;
                    }
                }
            }
        }

        if (out.fraction < 1f) {
            out.endPos.setMa(start, tmpDir(start, end), out.fraction);
        }
    }

    private final Vec3 dirScratch = new Vec3();

    private Vec3 tmpDir(Vec3 start, Vec3 end) {
        return dirScratch.setSub(end, start);
    }

    private void traceThroughBrush(Trace out, Brush b) {
        float enterFrac = -1f;
        float leaveFrac = 1f;
        int clipPlane = -1;
        boolean getOut = false;
        boolean startOut = false;

        final float[] p = b.planes;
        final int n = b.planeCount();
        for (int i = 0; i < n; i++) {
            int o = i * 4;
            float nx = p[o], ny = p[o + 1], nz = p[o + 2];
            // Push the plane out by the box extent along its normal.
            float dist = p[o + 3] + Math.abs(nx) * half.x + Math.abs(ny) * half.y + Math.abs(nz) * half.z;

            float d1 = nx * tStart.x + ny * tStart.y + nz * tStart.z - dist;
            float d2 = nx * tEnd.x + ny * tEnd.y + nz * tEnd.z - dist;

            if (d2 > 0) getOut = true;
            if (d1 > 0) startOut = true;

            // Completely in front of this plane: the sweep misses the brush.
            if (d1 > 0 && (d2 >= SURFACE_CLIP_EPSILON || d2 >= d1)) return;
            // Completely behind: this plane cannot clip the sweep.
            if (d1 <= 0 && d2 <= 0) continue;

            if (d1 > d2) {
                float f = (d1 - EPSILON) / (d1 - d2);
                if (f < 0) f = 0;
                if (f > enterFrac) {
                    enterFrac = f;
                    clipPlane = i;
                }
            } else {
                float f = (d1 + EPSILON) / (d1 - d2);
                if (f > 1) f = 1;
                if (f < leaveFrac) leaveFrac = f;
            }
        }

        if (!startOut) {
            out.startSolid = true;
            out.contents |= b.contents;
            if (!getOut) {
                out.allSolid = true;
                out.brush = b;
            }
            return;
        }

        if (enterFrac < leaveFrac && enterFrac > -1 && enterFrac < out.fraction) {
            if (enterFrac < 0) enterFrac = 0;
            out.fraction = enterFrac;
            int o = clipPlane * 4;
            out.normal.set(b.planes[o], b.planes[o + 1], b.planes[o + 2]);
            out.contents = b.contents;
            out.surfaceFlags = b.surfaceFlags[clipPlane];
            out.brush = b;
            out.entity = -1;
        }
    }

    /** Convenience zero-extent sweep, for bullets and line-of-sight checks. */
    private final Vec3 zero = new Vec3();

    public void traceRay(Trace out, Vec3 start, Vec3 end, int mask) {
        traceBox(out, start, end, zero, zero, mask);
    }

    /** True when nothing in {@code mask} sits between the two points. */
    public boolean isVisible(Vec3 a, Vec3 b, Trace scratch) {
        traceRay(scratch, a, b, Contents.MASK_SHOT);
        return scratch.fraction >= 1f && !scratch.startSolid;
    }

    /** Bitwise OR of the contents of every brush containing the point. */
    public int pointContents(Vec3 p) {
        int result = 0;
        int cx = clampCell((int) Math.floor((p.x - originX) / CELL), gridW);
        int cy = clampCell((int) Math.floor((p.y - originY) / CELL), gridH);
        for (int idx : cells[cy * gridW + cx]) {
            Brush b = brushes[idx];
            if (p.x < b.mins.x || p.x > b.maxs.x || p.y < b.mins.y || p.y > b.maxs.y
                    || p.z < b.mins.z || p.z > b.maxs.z) {
                continue;
            }
            if (b.containsPoint(p, 0f)) result |= b.contents;
        }
        return result;
    }

    /** Contents found anywhere inside the given box. */
    public int boxContents(Vec3 mins, Vec3 maxs) {
        int result = 0;
        int x0 = clampCell((int) Math.floor((mins.x - originX) / CELL), gridW);
        int x1 = clampCell((int) Math.floor((maxs.x - originX) / CELL), gridW);
        int y0 = clampCell((int) Math.floor((mins.y - originY) / CELL), gridH);
        int y1 = clampCell((int) Math.floor((maxs.y - originY) / CELL), gridH);
        stamp++;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int idx : cells[y * gridW + x]) {
                    if (visitStamp[idx] == stamp) continue;
                    visitStamp[idx] = stamp;
                    Brush b = brushes[idx];
                    if (b.boundsOverlap(mins, maxs)) result |= b.contents;
                }
            }
        }
        return result;
    }

    /**
     * Offsets a surface hit point slightly out of the surface so decals and
     * effects do not z-fight.
     */
    public static void nudgeOut(Vec3 point, Vec3 normal) {
        point.addScaled(normal, SURFACE_CLIP_OFFSET);
    }
}
