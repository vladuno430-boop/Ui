package com.arena3.game;

import com.arena3.core.Vec3;

/** A pickup sitting in the world, with its respawn timer. */
public final class ItemEntity {

    public int itemId;
    public final Vec3 origin = new Vec3();
    /** Seconds until it comes back; 0 or less means it is on the floor now. */
    public float respawnIn;
    /** Phase offset so a row of items does not bob in lockstep. */
    public float bobPhase;
    /** Continuous spin, in degrees. */
    public float spin;

    public boolean available() {
        return respawnIn <= 0f;
    }

    public ItemDef def() {
        return ItemDef.get(itemId);
    }
}
