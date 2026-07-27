package com.arena3.game;

/**
 * Static per-weapon tuning. Numbers follow the arena-shooter conventions the
 * movement and bot code are balanced around: damage in hit points, ranges and
 * speeds in world units per second, delays in seconds.
 */
public final class WeaponDef {

    // ---- weapon ids ----
    public static final int GAUNTLET = 0;
    public static final int MACHINEGUN = 1;
    public static final int SHOTGUN = 2;
    public static final int GRENADE = 3;
    public static final int ROCKET = 4;
    public static final int LIGHTNING = 5;
    public static final int RAILGUN = 6;
    public static final int PLASMA = 7;
    public static final int VOIDCANNON = 8;
    public static final int COUNT = 9;

    // ---- fire styles ----
    public static final int STYLE_MELEE = 0;
    public static final int STYLE_HITSCAN = 1;
    public static final int STYLE_SHOTGUN = 2;
    public static final int STYLE_PROJECTILE = 3;
    public static final int STYLE_BEAM = 4;

    public final int id;
    public final String name;
    public final String shortName;
    public final int style;
    /** Seconds between shots. */
    public final float fireDelay;
    public final int damage;
    /** Splash damage at the centre of the blast, 0 for none. */
    public final int splashDamage;
    public final float splashRadius;
    /** Pellets fired per trigger pull (shotgun style). */
    public final int pellets;
    /** Cone half-spread in world units at 1000 units of range. */
    public final float spread;
    /** Projectile speed, 0 for instant weapons. */
    public final float projectileSpeed;
    /** Maximum useful range; hitscan traces stop here. */
    public final float range;
    /** Ammo consumed per shot. */
    public final int ammoPerShot;
    /** Ammo granted by the matching pickup box. */
    public final int ammoPerBox;
    /** Ammo granted when the weapon itself is picked up. */
    public final int ammoOnPickup;
    /** How hard the hit pushes its victim. */
    public final float knockback;
    /** Self-knockback multiplier — this is what makes rocket jumping work. */
    public final float selfKnockback;
    /** Bot preference: higher wins when several weapons are in range. */
    public final float botPreference;
    /** Ideal engagement distance for bots. */
    public final float botIdealRange;

    private WeaponDef(int id, String name, String shortName, int style, float fireDelay, int damage,
                      int splashDamage, float splashRadius, int pellets, float spread,
                      float projectileSpeed, float range, int ammoPerShot, int ammoPerBox,
                      int ammoOnPickup, float knockback, float selfKnockback,
                      float botPreference, float botIdealRange) {
        this.id = id;
        this.name = name;
        this.shortName = shortName;
        this.style = style;
        this.fireDelay = fireDelay;
        this.damage = damage;
        this.splashDamage = splashDamage;
        this.splashRadius = splashRadius;
        this.pellets = pellets;
        this.spread = spread;
        this.projectileSpeed = projectileSpeed;
        this.range = range;
        this.ammoPerShot = ammoPerShot;
        this.ammoPerBox = ammoPerBox;
        this.ammoOnPickup = ammoOnPickup;
        this.knockback = knockback;
        this.selfKnockback = selfKnockback;
        this.botPreference = botPreference;
        this.botIdealRange = botIdealRange;
    }

    public boolean isProjectile() {
        return style == STYLE_PROJECTILE;
    }

    /** True for projectiles that arc rather than fly straight. */
    public boolean isLobbed() {
        return id == GRENADE;
    }

    public boolean usesAmmo() {
        return ammoPerShot > 0;
    }

    public static final WeaponDef[] ALL = {
            new WeaponDef(GAUNTLET, "Gauntlet", "GNT", STYLE_MELEE,
                    0.40f, 50, 0, 0, 1, 0, 0, 48f, 0, 0, 0, 40f, 0f, 0.15f, 40f),
            new WeaponDef(MACHINEGUN, "Machinegun", "MG", STYLE_HITSCAN,
                    0.10f, 7, 0, 0, 1, 200f, 0, 8192f, 1, 50, 0, 12f, 0f, 0.45f, 700f),
            new WeaponDef(SHOTGUN, "Shotgun", "SG", STYLE_SHOTGUN,
                    1.00f, 10, 0, 0, 11, 700f, 0, 8192f, 1, 10, 10, 14f, 0f, 0.70f, 260f),
            new WeaponDef(GRENADE, "Grenade Launcher", "GL", STYLE_PROJECTILE,
                    0.80f, 100, 100, 150f, 1, 0, 700f, 8192f, 1, 5, 10, 100f, 0.6f, 0.55f, 420f),
            new WeaponDef(ROCKET, "Rocket Launcher", "RL", STYLE_PROJECTILE,
                    0.80f, 100, 100, 120f, 1, 0, 900f, 8192f, 1, 5, 10, 120f, 1.0f, 1.00f, 500f),
            new WeaponDef(LIGHTNING, "Lightning Gun", "LG", STYLE_BEAM,
                    0.05f, 8, 0, 0, 1, 0, 0, 768f, 1, 60, 100, 8f, 0f, 0.85f, 380f),
            new WeaponDef(RAILGUN, "Railgun", "RG", STYLE_HITSCAN,
                    1.50f, 100, 0, 0, 1, 0, 0, 8192f, 1, 10, 10, 100f, 0f, 0.90f, 1400f),
            new WeaponDef(PLASMA, "Plasma Gun", "PG", STYLE_PROJECTILE,
                    0.10f, 20, 15, 20f, 1, 0, 2000f, 8192f, 1, 30, 50, 45f, 0.5f, 0.75f, 500f),
            new WeaponDef(VOIDCANNON, "Void Cannon", "VC", STYLE_PROJECTILE,
                    0.20f, 100, 100, 120f, 1, 0, 2000f, 8192f, 1, 15, 20, 130f, 0.4f, 1.10f, 600f),
    };

    public static WeaponDef get(int id) {
        return ALL[id];
    }
}
