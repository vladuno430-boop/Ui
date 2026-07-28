package com.arena3.render;

import com.arena3.game.ItemDef;
import com.arena3.game.WeaponDef;

/**
 * The model library. Fighters are jointed so they can be posed, weapons have
 * their own silhouettes, and pickups are simple spinning shapes. All of it is
 * built from boxes at start-up rather than loaded from files.
 */
public final class Models {

    // ---- fighter parts, in the order the renderer poses them ----
    public static final int PART_LEG_L = 0;
    public static final int PART_LEG_R = 1;
    public static final int PART_HIPS = 2;
    public static final int PART_TORSO = 3;
    public static final int PART_ARM_L = 4;
    public static final int PART_ARM_R = 5;
    public static final int PART_HEAD = 6;
    public static final int PART_COUNT = 7;

    /** Accent colours, one per player slot. */
    public static final float[][] PLAYER_COLORS = {
            {0.90f, 0.62f, 0.18f},   // amber (the local player)
            {0.32f, 0.62f, 0.95f},   // blue
            {0.85f, 0.25f, 0.22f},   // red
            {0.35f, 0.80f, 0.45f},   // green
            {0.78f, 0.36f, 0.85f},   // violet
            {0.90f, 0.85f, 0.30f},   // yellow
            {0.25f, 0.82f, 0.82f},   // cyan
            {0.95f, 0.50f, 0.60f},   // pink
    };

    private Models() {
    }

    /**
     * Builds the seven parts of a fighter in its own space: X is forward, Z is
     * up, and the origin sits where the player's origin does (24 above the feet).
     */
    public static MeshBuilder.MeshData[] fighter(float r, float g, float b) {
        MeshBuilder.MeshData[] parts = new MeshBuilder.MeshData[PART_COUNT];
        MeshBuilder mb = new MeshBuilder();

        float dark = 0.22f, mid = 0.34f;

        // Legs are modelled around their hip joint at z = 0 so they can swing.
        parts[PART_LEG_L] = mb.reset()
                .box(-5f, 1f, -22f, 5f, 9f, 0f, dark, dark, dark * 1.2f)
                .box(-7f, 0.5f, -24f, 7f, 9.5f, -19f, mid * 0.8f, mid * 0.8f, mid * 0.9f)
                .build();
        parts[PART_LEG_R] = mb.reset()
                .box(-5f, -9f, -22f, 5f, -1f, 0f, dark, dark, dark * 1.2f)
                .box(-7f, -9.5f, -24f, 7f, -0.5f, -19f, mid * 0.8f, mid * 0.8f, mid * 0.9f)
                .build();

        parts[PART_HIPS] = mb.reset()
                .box(-6f, -10f, -2f, 6f, 10f, 6f, mid, mid, mid * 1.1f)
                .build();

        // Torso carries the accent colour and a back pack.
        parts[PART_TORSO] = mb.reset()
                .box(-7f, -11f, 0f, 7f, 11f, 18f, mid, mid, mid * 1.15f)
                .box(-8f, -13f, 14f, 8f, 13f, 22f, mid * 0.9f, mid * 0.9f, mid)
                .box(2f, -8f, 3f, 8f, 8f, 16f, r * 0.85f, g * 0.85f, b * 0.85f)      // chest plate
                .box(-11f, -7f, 2f, -7f, 7f, 17f, dark * 1.1f, dark * 1.1f, dark * 1.3f)
                .box(-12f, -4f, 6f, -10f, 4f, 13f, r * 0.6f, g * 0.6f, b * 0.6f)
                .build();

        // Arms hang from the shoulder at z = 20.
        parts[PART_ARM_L] = mb.reset()
                .box(-3.5f, 0f, -16f, 3.5f, 7f, 2f, mid * 0.9f, mid * 0.9f, mid)
                .box(-4f, -0.5f, -18f, 4f, 7.5f, -13f, r * 0.7f, g * 0.7f, b * 0.7f)
                .build();
        parts[PART_ARM_R] = mb.reset()
                .box(-3.5f, -7f, -16f, 3.5f, 0f, 2f, mid * 0.9f, mid * 0.9f, mid)
                .box(-4f, -7.5f, -18f, 4f, 0.5f, -13f, r * 0.7f, g * 0.7f, b * 0.7f)
                .build();

        // Head sits on the neck at z = 22, with a glowing visor.
        parts[PART_HEAD] = mb.reset()
                .box(-6f, -6f, 0f, 6f, 6f, 11f, mid * 1.05f, mid * 1.05f, mid * 1.2f)
                .box(4f, -5f, 3f, 7f, 5f, 8f, r * 1.6f, g * 1.6f, b * 1.6f)
                .box(-5f, -2.5f, 10f, 1f, 2.5f, 14f, dark, dark, dark)
                .build();

        return parts;
    }

    /**
     * A weapon in view-model space: the barrel runs along +X, the origin is at
     * the grip. The same mesh is reused for the pickup on the floor.
     */
    public static MeshBuilder.MeshData weapon(int id) {
        MeshBuilder mb = new MeshBuilder();
        float body = 0.26f, metal = 0.42f;
        switch (id) {
            case WeaponDef.GAUNTLET:
                mb.box(-4f, -3f, -3f, 8f, 3f, 5f, body, body, body * 1.2f)
                        .box(6f, -2f, -1f, 16f, 2f, 3f, metal, metal, metal * 1.1f)
                        .box(14f, -3.5f, -2f, 20f, 3.5f, 4f, 0.30f, 0.75f, 1.00f);
                break;
            case WeaponDef.MACHINEGUN:
                mb.box(-6f, -2.5f, -2.5f, 10f, 2.5f, 4f, body, body, body * 1.15f)
                        .taperedBox(10f, 30f, 0f, 0.5f, 2.0f, 2.0f, 0.85f, metal, metal, metal)
                        .box(-2f, -3f, -8f, 4f, 3f, -2f, body * 0.8f, body * 0.8f, body)
                        .box(2f, -4f, 2f, 12f, 4f, 6f, metal * 0.8f, metal * 0.8f, metal * 0.9f);
                break;
            case WeaponDef.SHOTGUN:
                mb.box(-8f, -3f, -3f, 8f, 3f, 4f, 0.30f, 0.22f, 0.16f)
                        .taperedBox(6f, 34f, 0f, 1.5f, 3.2f, 3.2f, 0.95f, metal, metal, metal)
                        .box(0f, -3.5f, -7f, 8f, 3.5f, -2f, 0.34f, 0.26f, 0.18f)
                        .box(20f, -4f, -4f, 26f, 4f, 4f, metal * 0.7f, metal * 0.7f, metal * 0.8f);
                break;
            case WeaponDef.GRENADE:
                mb.box(-6f, -4f, -3f, 8f, 4f, 5f, 0.24f, 0.30f, 0.22f)
                        .taperedBox(8f, 26f, 0f, 1f, 4.5f, 4.5f, 1.0f, 0.30f, 0.36f, 0.26f)
                        .box(-2f, -5f, 5f, 10f, 5f, 9f, 0.20f, 0.26f, 0.18f)
                        .sphere(4f, 0f, 8f, 3.4f, 6, 8, 0.45f, 0.55f, 0.35f);
                break;
            case WeaponDef.ROCKET:
                mb.box(-8f, -5f, -4f, 6f, 5f, 5f, 0.30f, 0.26f, 0.24f)
                        .taperedBox(4f, 32f, 0f, 1f, 6f, 6f, 1.0f, 0.36f, 0.32f, 0.30f)
                        .box(24f, -7f, -6f, 30f, 7f, 7f, 0.42f, 0.20f, 0.16f)
                        .box(-4f, -3f, -10f, 4f, 3f, -3f, 0.24f, 0.22f, 0.22f)
                        .box(6f, -2f, 6f, 22f, 2f, 9f, 0.50f, 0.46f, 0.42f);
                break;
            case WeaponDef.LIGHTNING:
                mb.box(-6f, -3f, -3f, 8f, 3f, 5f, 0.22f, 0.24f, 0.34f)
                        .taperedBox(8f, 28f, 0f, 1f, 3f, 3f, 0.8f, 0.30f, 0.34f, 0.46f)
                        .sphere(28f, 0f, 1f, 4.5f, 6, 10, 0.40f, 0.72f, 1.30f)
                        .box(2f, -4.5f, 4f, 16f, 4.5f, 8f, 0.26f, 0.30f, 0.42f);
                break;
            case WeaponDef.RAILGUN:
                mb.box(-10f, -3f, -3f, 6f, 3f, 5f, 0.20f, 0.26f, 0.22f)
                        .taperedBox(4f, 40f, 0f, 1f, 3.4f, 3.4f, 0.9f, 0.26f, 0.34f, 0.28f)
                        .box(8f, -4.5f, 5f, 30f, 4.5f, 8f, 0.30f, 0.42f, 0.34f)
                        .box(12f, -1.5f, 8f, 26f, 1.5f, 10f, 0.20f, 0.90f, 0.50f)
                        .box(-6f, -3f, -9f, 2f, 3f, -2f, 0.20f, 0.24f, 0.22f);
                break;
            case WeaponDef.PLASMA:
                mb.box(-6f, -4f, -3f, 8f, 4f, 5f, 0.22f, 0.26f, 0.36f)
                        .taperedBox(8f, 26f, 0f, 1f, 4f, 4f, 0.7f, 0.28f, 0.32f, 0.44f)
                        .sphere(10f, 0f, 8f, 4.5f, 6, 10, 0.35f, 0.55f, 1.20f)
                        .box(20f, -5f, -4f, 26f, 5f, 5f, 0.30f, 0.40f, 0.60f);
                break;
            default:
                mb.box(-10f, -6f, -5f, 6f, 6f, 6f, 0.26f, 0.22f, 0.30f)
                        .taperedBox(4f, 30f, 0f, 0f, 7f, 7f, 0.8f, 0.30f, 0.26f, 0.36f)
                        .sphere(30f, 0f, 0f, 7f, 8, 12, 0.55f, 0.30f, 1.20f)
                        .box(-6f, -4f, 6f, 10f, 4f, 10f, 0.34f, 0.28f, 0.44f);
                break;
        }
        return mb.build();
    }

    /** Pickup model for an item id. Weapons reuse their weapon mesh. */
    public static MeshBuilder.MeshData item(int itemId) {
        ItemDef def = ItemDef.get(itemId);
        MeshBuilder mb = new MeshBuilder();
        switch (def.category) {
            case ItemDef.CAT_HEALTH: {
                float s = itemId == ItemDef.HEALTH_MEGA ? 1.5f : (itemId == ItemDef.HEALTH_SMALL ? 0.7f : 1f);
                float r = 0.85f, g = 0.20f, b = 0.22f;
                if (itemId == ItemDef.HEALTH_MEGA) {
                    r = 0.30f;
                    g = 0.80f;
                    b = 0.95f;
                }
                mb.boxCentered(0, 0, 0, 5.5f * s, 5.5f * s, 5.5f * s, 0.88f, 0.88f, 0.91f);
                mb.boxCentered(0, 0, 0, 13 * s, 4 * s, 4 * s, r, g, b);
                mb.boxCentered(0, 0, 0, 4 * s, 13 * s, 4 * s, r, g, b);
                mb.boxCentered(0, 0, 0, 4 * s, 4 * s, 13 * s, r, g, b);
                break;
            }
            case ItemDef.CAT_ARMOR: {
                float r, g, b;
                if (itemId == ItemDef.ARMOR_RED) {
                    r = 0.85f;
                    g = 0.20f;
                    b = 0.18f;
                } else if (itemId == ItemDef.ARMOR_YELLOW) {
                    r = 0.90f;
                    g = 0.72f;
                    b = 0.18f;
                } else {
                    r = 0.35f;
                    g = 0.75f;
                    b = 0.45f;
                }
                float s = itemId == ItemDef.ARMOR_SHARD ? 0.55f : 1f;
                // A chest plate: broad shoulders, a narrowing waist, a dark core.
                mb.box(-3.5f * s, -15 * s, 2 * s, 3.5f * s, 15 * s, 11 * s, r, g, b);
                mb.box(-3.5f * s, -11 * s, -6 * s, 3.5f * s, 11 * s, 2 * s, r * 1.08f, g * 1.08f, b * 1.08f);
                mb.box(-3.5f * s, -6 * s, -14 * s, 3.5f * s, 6 * s, -6 * s, r * 0.85f, g * 0.85f, b * 0.85f);
                mb.box(-4.5f * s, -5 * s, -1 * s, -3f * s, 5 * s, 8 * s, 0.10f, 0.11f, 0.13f);
                mb.box(-4.2f * s, -16 * s, 8 * s, 3.5f * s, -12 * s, 13 * s, r * 0.9f, g * 0.9f, b * 0.9f);
                mb.box(-4.2f * s, 12 * s, 8 * s, 3.5f * s, 16 * s, 13 * s, r * 0.9f, g * 0.9f, b * 0.9f);
                break;
            }
            case ItemDef.CAT_AMMO: {
                float r = 0.45f, g = 0.45f, b = 0.50f;
                mb.boxCentered(0, 0, 0, 11, 8, 7, r, g, b);
                mb.boxCentered(0, 0, 7, 12, 9, 1.5f, 0.75f, 0.62f, 0.20f);
                mb.boxCentered(0, 0, -7, 12, 9, 1.5f, 0.30f, 0.30f, 0.34f);
                break;
            }
            case ItemDef.CAT_POWERUP: {
                float r = 0.6f, g = 0.6f, b = 1.0f;
                switch (def.powerup) {
                    case ItemDef.PW_QUAD: r = 0.35f; g = 0.45f; b = 1.60f; break;
                    case ItemDef.PW_HASTE: r = 1.40f; g = 0.90f; b = 0.30f; break;
                    case ItemDef.PW_REGEN: r = 1.40f; g = 0.35f; b = 0.35f; break;
                    case ItemDef.PW_INVIS: r = 0.70f; g = 0.85f; b = 1.30f; break;
                    default: r = 1.20f; g = 0.70f; b = 0.25f; break;
                }
                mb.sphere(0, 0, 0, 13f, 8, 12, r, g, b);
                mb.boxCentered(0, 0, 0, 18, 2.5f, 2.5f, r * 0.7f, g * 0.7f, b * 0.7f);
                mb.boxCentered(0, 0, 0, 2.5f, 18, 2.5f, r * 0.7f, g * 0.7f, b * 0.7f);
                break;
            }
            default:
                mb.boxCentered(0, 0, 0, 10, 10, 10, 0.6f, 0.6f, 0.6f);
                break;
        }
        return mb.build();
    }

    /** Rocket, grenade or energy ball in flight. */
    public static MeshBuilder.MeshData projectile(int weapon) {
        MeshBuilder mb = new MeshBuilder();
        switch (weapon) {
            case WeaponDef.ROCKET:
                mb.taperedBox(-9f, 6f, 0f, 0f, 3f, 3f, 1.2f, 0.55f, 0.52f, 0.50f)
                        .taperedBox(6f, 12f, 0f, 0f, 3.6f, 3.6f, 0.15f, 0.70f, 0.30f, 0.20f)
                        .box(-10f, -5f, -1f, -5f, 5f, 1f, 0.40f, 0.38f, 0.38f)
                        .box(-10f, -1f, -5f, -5f, 1f, 5f, 0.40f, 0.38f, 0.38f);
                break;
            case WeaponDef.GRENADE:
                mb.sphere(0, 0, 0, 5f, 5, 7, 0.30f, 0.40f, 0.26f)
                        .boxCentered(0, 0, 5.5f, 1.6f, 1.6f, 2f, 0.60f, 0.30f, 0.20f);
                break;
            case WeaponDef.PLASMA:
                mb.sphere(0, 0, 0, 5.5f, 5, 8, 0.45f, 0.75f, 1.70f);
                break;
            default:
                mb.sphere(0, 0, 0, 9f, 6, 10, 0.70f, 0.40f, 1.70f)
                        .boxCentered(0, 0, 0, 13f, 2f, 2f, 0.90f, 0.60f, 1.80f);
                break;
        }
        return mb.build();
    }

    /** Chunk thrown out when someone is gibbed. */
    public static MeshBuilder.MeshData gib(int variant) {
        MeshBuilder mb = new MeshBuilder();
        float r = 0.42f, g = 0.10f, b = 0.10f;
        switch (variant % 3) {
            case 0: mb.boxCentered(0, 0, 0, 5, 4, 3, r, g, b); break;
            case 1: mb.boxCentered(0, 0, 0, 3, 6, 4, r * 0.8f, g, b * 1.2f); break;
            default: mb.sphere(0, 0, 0, 4.5f, 4, 6, r * 1.1f, g * 1.2f, b); break;
        }
        return mb.build();
    }
}
