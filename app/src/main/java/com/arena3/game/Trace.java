package com.arena3.game;

import com.arena3.core.Vec3;

/** Result of a box or ray sweep through the world. Reused to avoid allocation. */
public final class Trace {

    /** Fraction of the sweep completed, 1 when nothing was hit. */
    public float fraction;
    /** Point the sweep stopped at. */
    public final Vec3 endPos = new Vec3();
    /** Surface normal of whatever stopped the sweep. */
    public final Vec3 normal = new Vec3();
    /** True when the sweep started already inside solid. */
    public boolean startSolid;
    /** True when the sweep was inside solid for its entire length. */
    public boolean allSolid;
    /** Contents of the brush that was hit. */
    public int contents;
    /** Surface flags of the face that was hit. */
    public int surfaceFlags;
    /** Brush that stopped the sweep, or null. */
    public Brush brush;
    /** Entity index that stopped the sweep, or -1 for world geometry. */
    public int entity;

    public void reset(Vec3 end) {
        fraction = 1f;
        endPos.set(end);
        normal.zero();
        startSolid = false;
        allSolid = false;
        contents = 0;
        surfaceFlags = 0;
        brush = null;
        entity = -1;
    }

    public boolean hit() {
        return fraction < 1f || startSolid;
    }

    public void copyFrom(Trace o) {
        fraction = o.fraction;
        endPos.set(o.endPos);
        normal.set(o.normal);
        startSolid = o.startSolid;
        allSolid = o.allSolid;
        contents = o.contents;
        surfaceFlags = o.surfaceFlags;
        brush = o.brush;
        entity = o.entity;
    }

    /** Something a mover can trace against: world brushes, players, or both. */
    public interface Provider {
        void trace(Trace out, Vec3 start, Vec3 end, Vec3 mins, Vec3 maxs, int mask, int skipEntity);
    }
}
