package com.arena3.game;

import com.arena3.core.Vec3;

/**
 * A one-frame notification from the simulation to the presentation layers.
 * Events are pooled: {@link GameWorld} hands out instances, the renderer and the
 * sound engine read them, and they are recycled at the end of the frame.
 */
public final class GameEvent {

    public static final int FIRE = 0;           // weapon fired: a=weapon, b=player
    public static final int IMPACT = 1;         // shot hit a surface: a=weapon
    public static final int IMPACT_FLESH = 2;   // shot hit a player: a=weapon, b=victim
    public static final int EXPLOSION = 3;      // a=weapon
    public static final int RAIL_TRAIL = 4;     // from -> to, b=player
    public static final int BEAM = 5;           // lightning, from -> to, b=player
    public static final int PICKUP = 6;         // a=item id, b=player
    public static final int JUMP = 7;           // b=player
    public static final int LAND = 8;           // b=player, f=impact speed
    public static final int PAIN = 9;           // b=player, a=damage
    public static final int DEATH = 10;         // b=player, a=gib(1)/normal(0)
    public static final int TELEPORT = 11;      // b=player
    public static final int JUMPPAD = 12;       // b=player
    public static final int RESPAWN = 13;       // b=player
    public static final int HIT_CONFIRM = 14;   // local player damaged someone: a=damage
    public static final int FRAG = 15;          // kill feed entry, text carries it
    public static final int ANNOUNCE = 16;      // centre-screen callout
    public static final int MATCH_END = 17;
    public static final int STEP = 18;          // footstep, b=player
    public static final int WEAPON_SWITCH = 19; // a=weapon, b=player
    public static final int NO_AMMO = 20;       // b=player

    public int type;
    public final Vec3 pos = new Vec3();
    public final Vec3 dir = new Vec3();
    public final Vec3 to = new Vec3();
    public int a;
    public int b;
    public float f;
    public String text;

    void reset(int type) {
        this.type = type;
        pos.zero();
        dir.set(0, 0, 1);
        to.zero();
        a = 0;
        b = -1;
        f = 0;
        text = null;
    }
}
