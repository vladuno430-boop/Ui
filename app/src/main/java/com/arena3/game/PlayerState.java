package com.arena3.game;

import com.arena3.core.Vec3;

/** Everything the simulation knows about one fighter, human or bot. */
public final class PlayerState {

    public int index;
    public String name = "";
    public boolean isBot;
    /** 0..4, only meaningful for bots. */
    public int skill = 2;
    /** Index into the renderer's palette of player colours. */
    public int colorIndex;

    // ---- movement ----
    public final Vec3 origin = new Vec3();
    public final Vec3 velocity = new Vec3();
    public float yaw;
    public float pitch;
    public boolean onGround;
    public boolean ducked;
    public boolean jumpHeld;
    public final Vec3 groundNormal = new Vec3(0, 0, 1);
    /** Set for one frame when a jump pad or teleporter moved the player. */
    public boolean launched;
    public boolean teleported;
    /** True from the moment a jump pad fires until the next landing. */
    public boolean padFlight;
    /** Seconds of accumulated air time, used for fall damage and animation. */
    public float airTime;
    /** Downward speed at the moment of the last landing. */
    public float landImpact;
    /** Counts up while moving on the ground; drives the view bob. */
    public float bobCycle;
    public float bobFraction;
    /** Buffered jump press so a tap just before landing still hops. */
    public float jumpBuffer;

    // ---- combat ----
    public boolean alive;
    public int health;
    public int armor;
    public int maxHealth = 100;
    /** Bit per weapon id. */
    public int weaponMask;
    public final int[] ammo = new int[WeaponDef.COUNT];
    public int weapon = WeaponDef.MACHINEGUN;
    public int pendingWeapon = -1;
    /** Seconds until this player may fire again. */
    public float weaponCooldown;
    /** Seconds left in the raise/lower animation. */
    public float weaponSwitchTime;
    public boolean firing;
    /** Time remaining on each powerup slot. */
    public final float[] powerupTime = new float[ItemDef.PW_COUNT];
    /** Spawn protection countdown. */
    public float invulnerable;

    public int frags;
    public int deaths;
    /** Kills without dying, for the multi-kill and streak callouts. */
    public int streak;
    /** Time of the previous kill, for "excellent" style multi-kills. */
    public float lastKillTime = -100f;
    public int multiKill;

    public float respawnTime;
    public int lastAttacker = -1;
    public float lastDamageTime = -100f;
    /** Direction the last hit came from, in world space, for the HUD indicator. */
    public final Vec3 lastDamageDir = new Vec3();
    public float lastPickupTime = -100f;
    public int lastPickupItem = -1;
    /** Timer used by the renderer to flash the model when hit. */
    public float painFlash;
    /** Continuous damage output for hit-sound feedback. */
    public float hitFeedback;

    // ---- animation / presentation ----
    public float legsYaw;
    public float deathTime;
    public int deathStyle;
    public float lastFireTime = -100f;
    /** Muzzle flash countdown. */
    public float muzzleFlash;

    public boolean hasWeapon(int w) {
        return (weaponMask & (1 << w)) != 0;
    }

    public void giveWeapon(int w) {
        weaponMask |= 1 << w;
    }

    public boolean hasPowerup(int p) {
        return powerupTime[p] > 0f;
    }

    public float damageScale() {
        return hasPowerup(ItemDef.PW_QUAD) ? 3f : 1f;
    }

    public float speedScale() {
        return hasPowerup(ItemDef.PW_HASTE) ? 1.3f : 1f;
    }

    public float fireRateScale() {
        return hasPowerup(ItemDef.PW_HASTE) ? 0.65f : 1f;
    }

    /** Eye position, accounting for crouching. */
    public void eyePosition(Vec3 out) {
        out.set(origin.x, origin.y, origin.z + viewHeight());
    }

    public float viewHeight() {
        return ducked ? 12f : 26f;
    }

    public Vec3 mins(Vec3 out) {
        return out.set(-15, -15, -24);
    }

    public Vec3 maxs(Vec3 out) {
        return out.set(15, 15, ducked ? 16 : 32);
    }

    /** Total effective hit points including armor absorption. */
    public int effectiveHealth() {
        return health + Math.min(armor, health * 2);
    }

    public void resetForSpawn() {
        velocity.zero();
        onGround = false;
        ducked = false;
        jumpHeld = false;
        launched = false;
        teleported = false;
        airTime = 0;
        bobCycle = 0;
        alive = true;
        health = maxHealth;
        armor = 0;
        weaponMask = 0;
        giveWeapon(WeaponDef.GAUNTLET);
        giveWeapon(WeaponDef.MACHINEGUN);
        java.util.Arrays.fill(ammo, 0);
        ammo[WeaponDef.MACHINEGUN] = 100;
        weapon = WeaponDef.MACHINEGUN;
        pendingWeapon = -1;
        weaponCooldown = 0;
        weaponSwitchTime = 0;
        firing = false;
        java.util.Arrays.fill(powerupTime, 0f);
        invulnerable = 1.5f;
        painFlash = 0;
        deathTime = 0;
        streak = 0;
        multiKill = 0;
    }
}
