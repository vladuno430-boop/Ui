package com.arena3.game;

/** Static definition of everything that can sit on the floor and be picked up. */
public final class ItemDef {

    // ---- categories ----
    public static final int CAT_HEALTH = 0;
    public static final int CAT_ARMOR = 1;
    public static final int CAT_WEAPON = 2;
    public static final int CAT_AMMO = 3;
    public static final int CAT_POWERUP = 4;

    // ---- powerup ids (index into PlayerState.powerupTime) ----
    public static final int PW_QUAD = 0;
    public static final int PW_HASTE = 1;
    public static final int PW_REGEN = 2;
    public static final int PW_INVIS = 3;
    public static final int PW_BATTLESUIT = 4;
    public static final int PW_COUNT = 5;

    // ---- item ids ----
    public static final int HEALTH_SMALL = 0;
    public static final int HEALTH = 1;
    public static final int HEALTH_LARGE = 2;
    public static final int HEALTH_MEGA = 3;
    public static final int ARMOR_SHARD = 4;
    public static final int ARMOR_YELLOW = 5;
    public static final int ARMOR_RED = 6;
    public static final int WEAPON_SHOTGUN = 7;
    public static final int WEAPON_GRENADE = 8;
    public static final int WEAPON_ROCKET = 9;
    public static final int WEAPON_LIGHTNING = 10;
    public static final int WEAPON_RAILGUN = 11;
    public static final int WEAPON_PLASMA = 12;
    public static final int WEAPON_VOID = 13;
    public static final int WEAPON_MACHINEGUN = 14;
    public static final int AMMO_BULLETS = 15;
    public static final int AMMO_SHELLS = 16;
    public static final int AMMO_GRENADES = 17;
    public static final int AMMO_ROCKETS = 18;
    public static final int AMMO_LIGHTNING = 19;
    public static final int AMMO_SLUGS = 20;
    public static final int AMMO_CELLS = 21;
    public static final int AMMO_VOID = 22;
    public static final int POWERUP_QUAD = 23;
    public static final int POWERUP_HASTE = 24;
    public static final int POWERUP_REGEN = 25;
    public static final int POWERUP_INVIS = 26;
    public static final int POWERUP_BATTLESUIT = 27;

    public final int id;
    public final String name;
    public final int category;
    /** Health/armor points, ammo rounds, or powerup seconds. */
    public final int amount;
    /** Cap this pickup may fill to, where it applies. */
    public final int cap;
    /** Seconds before it comes back after being taken. */
    public final float respawn;
    /** Weapon id for weapon and ammo pickups, else -1. */
    public final int weapon;
    /** Powerup slot for powerups, else -1. */
    public final int powerup;
    /** How much a bot wants this, before need-scaling. */
    public final float botWeight;

    private ItemDef(int id, String name, int category, int amount, int cap, float respawn,
                    int weapon, int powerup, float botWeight) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.amount = amount;
        this.cap = cap;
        this.respawn = respawn;
        this.weapon = weapon;
        this.powerup = powerup;
        this.botWeight = botWeight;
    }

    public static final ItemDef[] ALL = {
            new ItemDef(HEALTH_SMALL, "5 Health", CAT_HEALTH, 5, 200, 35f, -1, -1, 0.25f),
            new ItemDef(HEALTH, "25 Health", CAT_HEALTH, 25, 100, 35f, -1, -1, 0.60f),
            new ItemDef(HEALTH_LARGE, "50 Health", CAT_HEALTH, 50, 100, 35f, -1, -1, 0.85f),
            new ItemDef(HEALTH_MEGA, "Mega Health", CAT_HEALTH, 100, 200, 35f, -1, -1, 1.40f),
            new ItemDef(ARMOR_SHARD, "Armor Shard", CAT_ARMOR, 5, 200, 25f, -1, -1, 0.25f),
            new ItemDef(ARMOR_YELLOW, "Armor", CAT_ARMOR, 50, 100, 25f, -1, -1, 0.95f),
            new ItemDef(ARMOR_RED, "Heavy Armor", CAT_ARMOR, 100, 200, 25f, -1, -1, 1.35f),

            new ItemDef(WEAPON_SHOTGUN, "Shotgun", CAT_WEAPON, 0, 0, 5f, WeaponDef.SHOTGUN, -1, 0.70f),
            new ItemDef(WEAPON_GRENADE, "Grenade Launcher", CAT_WEAPON, 0, 0, 5f, WeaponDef.GRENADE, -1, 0.55f),
            new ItemDef(WEAPON_ROCKET, "Rocket Launcher", CAT_WEAPON, 0, 0, 5f, WeaponDef.ROCKET, -1, 1.20f),
            new ItemDef(WEAPON_LIGHTNING, "Lightning Gun", CAT_WEAPON, 0, 0, 5f, WeaponDef.LIGHTNING, -1, 0.90f),
            new ItemDef(WEAPON_RAILGUN, "Railgun", CAT_WEAPON, 0, 0, 5f, WeaponDef.RAILGUN, -1, 1.10f),
            new ItemDef(WEAPON_PLASMA, "Plasma Gun", CAT_WEAPON, 0, 0, 5f, WeaponDef.PLASMA, -1, 0.85f),
            new ItemDef(WEAPON_VOID, "Void Cannon", CAT_WEAPON, 0, 0, 5f, WeaponDef.VOIDCANNON, -1, 1.30f),
            new ItemDef(WEAPON_MACHINEGUN, "Machinegun", CAT_WEAPON, 0, 0, 5f, WeaponDef.MACHINEGUN, -1, 0.40f),

            new ItemDef(AMMO_BULLETS, "Bullets", CAT_AMMO, 50, 200, 40f, WeaponDef.MACHINEGUN, -1, 0.30f),
            new ItemDef(AMMO_SHELLS, "Shells", CAT_AMMO, 10, 200, 40f, WeaponDef.SHOTGUN, -1, 0.30f),
            new ItemDef(AMMO_GRENADES, "Grenades", CAT_AMMO, 5, 200, 40f, WeaponDef.GRENADE, -1, 0.30f),
            new ItemDef(AMMO_ROCKETS, "Rockets", CAT_AMMO, 5, 200, 40f, WeaponDef.ROCKET, -1, 0.45f),
            new ItemDef(AMMO_LIGHTNING, "Lightning", CAT_AMMO, 60, 200, 40f, WeaponDef.LIGHTNING, -1, 0.35f),
            new ItemDef(AMMO_SLUGS, "Slugs", CAT_AMMO, 10, 200, 40f, WeaponDef.RAILGUN, -1, 0.45f),
            new ItemDef(AMMO_CELLS, "Cells", CAT_AMMO, 30, 200, 40f, WeaponDef.PLASMA, -1, 0.35f),
            new ItemDef(AMMO_VOID, "Void Charge", CAT_AMMO, 15, 200, 40f, WeaponDef.VOIDCANNON, -1, 0.45f),

            new ItemDef(POWERUP_QUAD, "Quad Damage", CAT_POWERUP, 30, 0, 120f, -1, PW_QUAD, 2.20f),
            new ItemDef(POWERUP_HASTE, "Haste", CAT_POWERUP, 30, 0, 120f, -1, PW_HASTE, 1.40f),
            new ItemDef(POWERUP_REGEN, "Regeneration", CAT_POWERUP, 30, 0, 120f, -1, PW_REGEN, 1.50f),
            new ItemDef(POWERUP_INVIS, "Invisibility", CAT_POWERUP, 30, 0, 120f, -1, PW_INVIS, 1.30f),
            new ItemDef(POWERUP_BATTLESUIT, "Battle Suit", CAT_POWERUP, 30, 0, 120f, -1, PW_BATTLESUIT, 1.60f),
    };

    public static ItemDef get(int id) {
        return ALL[id];
    }

    /** The pickup that gives ammo for a weapon. */
    public static int ammoItemFor(int weapon) {
        switch (weapon) {
            case WeaponDef.MACHINEGUN: return AMMO_BULLETS;
            case WeaponDef.SHOTGUN: return AMMO_SHELLS;
            case WeaponDef.GRENADE: return AMMO_GRENADES;
            case WeaponDef.ROCKET: return AMMO_ROCKETS;
            case WeaponDef.LIGHTNING: return AMMO_LIGHTNING;
            case WeaponDef.RAILGUN: return AMMO_SLUGS;
            case WeaponDef.PLASMA: return AMMO_CELLS;
            case WeaponDef.VOIDCANNON: return AMMO_VOID;
            default: return -1;
        }
    }

    /** The floor pickup for a weapon. */
    public static int weaponItemFor(int weapon) {
        switch (weapon) {
            case WeaponDef.MACHINEGUN: return WEAPON_MACHINEGUN;
            case WeaponDef.SHOTGUN: return WEAPON_SHOTGUN;
            case WeaponDef.GRENADE: return WEAPON_GRENADE;
            case WeaponDef.ROCKET: return WEAPON_ROCKET;
            case WeaponDef.LIGHTNING: return WEAPON_LIGHTNING;
            case WeaponDef.RAILGUN: return WEAPON_RAILGUN;
            case WeaponDef.PLASMA: return WEAPON_PLASMA;
            case WeaponDef.VOIDCANNON: return WEAPON_VOID;
            default: return -1;
        }
    }
}
