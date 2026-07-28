package com.arena3.render;

import com.arena3.game.ItemDef;
import com.arena3.game.WeaponDef;

/**
 * The model library. Fighters are jointed so they can be posed, weapons have
 * their own silhouettes, and pickups are simple spinning shapes. All of it is
 * built from textured boxes at start-up rather than loaded from files.
 *
 * <p>Every piece picks a material from {@link ProcTex} — plated armour over a
 * woven undersuit, machined gunmetal, ribbed grips, glowing visors — so the
 * surfaces carry detail instead of being flat colour.
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

    // Base tints. The texture supplies the detail, these supply the palette.
    private static final float STEEL = 0.44f;
    private static final float DARK = 0.25f;
    private static final float SUIT = 0.19f;

    private Models() {
    }

    /**
     * Builds the seven parts of a fighter in its own space: X is forward, Z is
     * up, and the origin sits where the player's origin does (24 above the feet).
     */
    public static MeshBuilder.MeshData[] fighter(float r, float g, float b) {
        MeshBuilder.MeshData[] parts = new MeshBuilder.MeshData[PART_COUNT];
        MeshBuilder mb = new MeshBuilder();

        // ---- legs: woven thigh, plated shin, heavy boot ----
        for (int side = 0; side < 2; side++) {
            float y0 = side == 0 ? 1f : -9f;
            float y1 = side == 0 ? 9f : -1f;
            mb.reset();
            mb.material(ProcTex.MAT_MESH, 14f)
                    .box(-4f, y0 + 0.5f, -12f, 4f, y1 - 0.5f, 0f, SUIT, SUIT, SUIT * 1.15f);
            mb.material(ProcTex.MAT_ARMOR, 16f)
                    .box(-5f, y0, -22f, 5f, y1, -10f, STEEL, STEEL, STEEL * 1.06f)
                    // Knee cap.
                    .box(2f, y0 + 0.5f, -13f, 6f, y1 - 0.5f, -7f, STEEL * 1.05f, STEEL * 1.05f, STEEL)
                    // Thigh guard in the player's colour.
                    .box(-5.5f, y0 - 0.5f, -6f, 1f, y1 + 0.5f, -1f, r * 0.7f, g * 0.7f, b * 0.7f);
            mb.material(ProcTex.MAT_DARK_METAL, 12f)
                    .box(-7f, y0 - 0.5f, -24f, 7f, y1 + 0.5f, -19f, DARK, DARK, DARK * 1.12f);
            parts[side == 0 ? PART_LEG_L : PART_LEG_R] = mb.build();
        }

        // ---- hips ----
        parts[PART_HIPS] = mb.reset()
                .material(ProcTex.MAT_ARMOR, 18f)
                .box(-6f, -10f, -2f, 6f, 10f, 6f, STEEL, STEEL, STEEL * 1.05f)
                .material(ProcTex.MAT_DARK_METAL, 10f)
                .box(-6.5f, -10.5f, 2f, 6.5f, 10.5f, 5f, DARK, DARK, DARK * 1.1f)
                .material(ProcTex.MAT_GLOW, 8f)
                .box(4f, -2.5f, 2.5f, 6.8f, 2.5f, 4.5f, r * 1.5f, g * 1.5f, b * 1.5f)
                .build();

        // ---- torso: plated chest over a woven core, with a back pack ----
        parts[PART_TORSO] = mb.reset()
                .material(ProcTex.MAT_MESH, 14f)
                .box(-6f, -10f, 0f, 6f, 10f, 18f, SUIT, SUIT, SUIT * 1.2f)
                .material(ProcTex.MAT_ARMOR, 20f)
                // Chest and shoulder yoke.
                .box(-1f, -11f, 4f, 8f, 11f, 17f, STEEL, STEEL, STEEL * 1.06f)
                .box(-8f, -13.5f, 14f, 8f, 13.5f, 22f, STEEL * 0.95f, STEEL * 0.95f, STEEL)
                // Shoulder caps.
                .box(-6f, -14.5f, 12f, 5f, -10f, 21f, STEEL * 1.02f, STEEL * 1.02f, STEEL * 1.08f)
                .box(-6f, 10f, 12f, 5f, 14.5f, 21f, STEEL * 1.02f, STEEL * 1.02f, STEEL * 1.08f)
                .material(ProcTex.MAT_SHELL, 22f)
                // The accent panel that identifies the player.
                .box(6.5f, -7.5f, 6f, 9f, 7.5f, 15f, r, g, b)
                .material(ProcTex.MAT_DARK_METAL, 14f)
                // Back pack.
                .box(-12f, -8f, 2f, -6f, 8f, 17f, DARK, DARK, DARK * 1.15f)
                .material(ProcTex.MAT_GLOW, 9f)
                .box(-12.8f, -5f, 6f, -11.4f, 5f, 13f, r * 1.6f, g * 1.6f, b * 1.6f)
                .build();

        // ---- arms: woven upper, plated forearm, dark glove ----
        for (int side = 0; side < 2; side++) {
            float y0 = side == 0 ? 0f : -7f;
            float y1 = side == 0 ? 7f : 0f;
            mb.reset();
            mb.material(ProcTex.MAT_MESH, 12f)
                    .box(-3f, y0 + 0.4f, -9f, 3f, y1 - 0.4f, 2f, SUIT, SUIT, SUIT * 1.15f);
            mb.material(ProcTex.MAT_ARMOR, 14f)
                    .box(-3.5f, y0, -16f, 3.5f, y1, -8f, STEEL, STEEL, STEEL * 1.05f)
                    // Shoulder plate at the top of the arm.
                    .box(-4.2f, y0 - 0.6f, -2f, 4.2f, y1 + 0.6f, 2.5f, STEEL * 1.05f, STEEL * 1.05f, STEEL)
                    .material(ProcTex.MAT_SHELL, 12f)
                    .box(-4f, y0 - 0.4f, -13f, 4f, y1 + 0.4f, -10f, r * 0.85f, g * 0.85f, b * 0.85f);
            mb.material(ProcTex.MAT_GRIP, 8f)
                    .box(-4f, y0 - 0.5f, -19f, 4f, y1 + 0.5f, -15f, DARK * 0.9f, DARK * 0.9f, DARK);
            parts[side == 0 ? PART_ARM_L : PART_ARM_R] = mb.build();
        }

        // ---- head: helmet, visor, crest ----
        parts[PART_HEAD] = mb.reset()
                .material(ProcTex.MAT_ARMOR, 13f)
                .box(-6f, -6f, 0f, 6f, 6f, 11f, STEEL * 1.02f, STEEL * 1.02f, STEEL * 1.1f)
                // Jaw guard.
                .box(1f, -5f, 0f, 6.5f, 5f, 4f, STEEL * 0.92f, STEEL * 0.92f, STEEL)
                .material(ProcTex.MAT_GLOW, 7f)
                // Visor band, the most readable identifier at a distance.
                .box(5.2f, -5.2f, 4f, 7.4f, 5.2f, 8.5f, r * 1.9f, g * 1.9f, b * 1.9f)
                .material(ProcTex.MAT_DARK_METAL, 9f)
                // Crest along the top.
                .box(-5f, -2.2f, 10.5f, 2f, 2.2f, 14f, DARK, DARK, DARK * 1.2f)
                .box(-7f, -4f, 3f, -5.5f, 4f, 9f, DARK * 1.1f, DARK * 1.1f, DARK * 1.25f)
                .build();

        return parts;
    }

    /**
     * A weapon in view-model space: the barrel runs along +X, the origin is at
     * the grip. The same mesh is reused for the pickup on the floor.
     */
    public static MeshBuilder.MeshData weapon(int id) {
        MeshBuilder mb = new MeshBuilder();
        float steel = 0.46f, dark = 0.27f, grip = 0.23f;
        switch (id) {
            case WeaponDef.GAUNTLET:
                mb.material(ProcTex.MAT_DARK_METAL, 12f)
                        .box(-4f, -3f, -3f, 8f, 3f, 5f, dark, dark, dark * 1.15f)
                        .material(ProcTex.MAT_GUNMETAL, 14f)
                        .box(6f, -2f, -1f, 16f, 2f, 3f, steel, steel, steel)
                        .material(ProcTex.MAT_ENERGY, 10f)
                        .box(14f, -3.5f, -2f, 20f, 3.5f, 4f, 0.35f, 0.85f, 1.35f)
                        .material(ProcTex.MAT_GRIP, 7f)
                        .box(-3f, -2.5f, -8f, 3f, 2.5f, -2f, grip, grip, grip);
                break;
            case WeaponDef.MACHINEGUN:
                mb.material(ProcTex.MAT_GUNMETAL, 16f)
                        .box(-6f, -2.5f, -2.5f, 10f, 2.5f, 4f, steel * 0.8f, steel * 0.8f, steel * 0.85f)
                        .taperedBox(10f, 30f, 0f, 0.5f, 2.0f, 2.0f, 0.85f, steel, steel, steel)
                        .box(2f, -4f, 2f, 12f, 4f, 6f, steel * 0.75f, steel * 0.75f, steel * 0.8f)
                        .material(ProcTex.MAT_DARK_METAL, 10f)
                        .box(-7f, -3.2f, -3.2f, 2f, 3.2f, 1f, dark, dark, dark * 1.1f)
                        .material(ProcTex.MAT_GRIP, 7f)
                        .box(-2f, -3f, -9f, 4f, 3f, -2f, grip, grip, grip);
                break;
            case WeaponDef.SHOTGUN:
                mb.material(ProcTex.MAT_GRIP, 9f)
                        .box(-9f, -3f, -3f, 4f, 3f, 4f, 0.30f, 0.20f, 0.13f)
                        .box(0f, -3.5f, -8f, 8f, 3.5f, -2f, 0.32f, 0.21f, 0.14f)
                        .material(ProcTex.MAT_GUNMETAL, 16f)
                        .taperedBox(4f, 34f, 0f, 1.5f, 3.2f, 3.2f, 0.95f, steel, steel, steel)
                        .box(20f, -4.2f, -4.2f, 27f, 4.2f, 4.2f, steel * 0.8f, steel * 0.8f, steel * 0.85f)
                        .material(ProcTex.MAT_DARK_METAL, 10f)
                        .box(6f, -4f, -5f, 18f, 4f, -1f, dark, dark, dark);
                break;
            case WeaponDef.GRENADE:
                mb.material(ProcTex.MAT_DARK_METAL, 13f)
                        .box(-6f, -4f, -3f, 8f, 4f, 5f, 0.23f, 0.29f, 0.20f)
                        .material(ProcTex.MAT_GUNMETAL, 15f)
                        .taperedBox(8f, 26f, 0f, 1f, 4.5f, 4.5f, 1.0f, 0.29f, 0.35f, 0.25f)
                        .material(ProcTex.MAT_SHELL, 14f)
                        .sphere(4f, 0f, 8f, 4.2f, 7, 10, 0.34f, 0.41f, 0.27f)
                        .material(ProcTex.MAT_GRIP, 7f)
                        .box(-3f, -3f, -9f, 3f, 3f, -2f, grip, grip * 1.05f, grip);
                break;
            case WeaponDef.ROCKET:
                mb.material(ProcTex.MAT_GUNMETAL, 18f)
                        .box(-8f, -5f, -4f, 6f, 5f, 5f, steel * 0.75f, steel * 0.72f, steel * 0.70f)
                        .taperedBox(4f, 32f, 0f, 1f, 6f, 6f, 1.0f, steel * 0.85f, steel * 0.80f, steel * 0.78f)
                        .box(6f, -2f, 6f, 22f, 2f, 9.5f, steel, steel * 0.95f, steel * 0.9f)
                        .material(ProcTex.MAT_SHELL, 16f)
                        // Warning band around the muzzle.
                        .box(24f, -7f, -6f, 30f, 7f, 7f, 0.58f, 0.20f, 0.12f)
                        .material(ProcTex.MAT_GRIP, 7f)
                        .box(-4f, -3f, -11f, 4f, 3f, -3f, grip, grip, grip);
                break;
            case WeaponDef.LIGHTNING:
                mb.material(ProcTex.MAT_GUNMETAL, 15f)
                        .box(-6f, -3f, -3f, 8f, 3f, 5f, 0.31f, 0.34f, 0.45f)
                        .taperedBox(8f, 28f, 0f, 1f, 3f, 3f, 0.8f, 0.34f, 0.38f, 0.50f)
                        .box(2f, -4.5f, 4f, 16f, 4.5f, 8f, 0.29f, 0.33f, 0.43f)
                        .material(ProcTex.MAT_ENERGY, 11f)
                        .sphere(28f, 0f, 1f, 5f, 7, 11, 0.45f, 0.85f, 1.55f)
                        .material(ProcTex.MAT_GRIP, 7f)
                        .box(-3f, -2.8f, -9f, 3f, 2.8f, -2f, grip, grip, grip * 1.1f);
                break;
            case WeaponDef.RAILGUN:
                mb.material(ProcTex.MAT_GUNMETAL, 18f)
                        .box(-10f, -3f, -3f, 6f, 3f, 5f, 0.27f, 0.34f, 0.29f)
                        .taperedBox(4f, 40f, 0f, 1f, 3.4f, 3.4f, 0.9f, 0.30f, 0.38f, 0.33f)
                        .box(8f, -4.5f, 5f, 30f, 4.5f, 8.5f, 0.26f, 0.33f, 0.28f)
                        .material(ProcTex.MAT_GLOW, 9f)
                        // The accelerator rail down the top.
                        .box(12f, -1.5f, 8.5f, 30f, 1.5f, 10.5f, 0.25f, 1.25f, 0.65f)
                        .material(ProcTex.MAT_GRIP, 7f)
                        .box(-6f, -3f, -10f, 2f, 3f, -2f, grip, grip * 1.05f, grip);
                break;
            case WeaponDef.PLASMA:
                mb.material(ProcTex.MAT_GUNMETAL, 15f)
                        .box(-6f, -4f, -3f, 8f, 4f, 5f, 0.30f, 0.34f, 0.45f)
                        .taperedBox(8f, 26f, 0f, 1f, 4f, 4f, 0.7f, 0.33f, 0.37f, 0.49f)
                        .box(20f, -5f, -4f, 27f, 5f, 5f, 0.28f, 0.33f, 0.44f)
                        .material(ProcTex.MAT_ENERGY, 12f)
                        .sphere(10f, 0f, 8.5f, 5f, 7, 11, 0.40f, 0.65f, 1.45f)
                        .material(ProcTex.MAT_GRIP, 7f)
                        .box(-3f, -3f, -9f, 3f, 3f, -2f, grip, grip, grip * 1.1f);
                break;
            default:
                mb.material(ProcTex.MAT_DARK_METAL, 16f)
                        .box(-10f, -6f, -5f, 6f, 6f, 6f, 0.26f, 0.22f, 0.32f)
                        .material(ProcTex.MAT_GUNMETAL, 18f)
                        .taperedBox(4f, 30f, 0f, 0f, 7f, 7f, 0.8f, 0.30f, 0.26f, 0.37f)
                        .box(-6f, -4f, 6f, 10f, 4f, 10f, 0.29f, 0.25f, 0.36f)
                        .material(ProcTex.MAT_ENERGY, 14f)
                        .sphere(30f, 0f, 0f, 7.5f, 8, 12, 0.70f, 0.38f, 1.55f)
                        .material(ProcTex.MAT_GRIP, 7f)
                        .box(-6f, -4f, -11f, 2f, 4f, -4f, grip, grip, grip * 1.15f);
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
                float r = 0.90f, g = 0.22f, b = 0.24f;
                if (itemId == ItemDef.HEALTH_MEGA) {
                    r = 0.32f;
                    g = 0.85f;
                    b = 1.00f;
                }
                mb.material(ProcTex.MAT_SHELL, 10f * s)
                        .boxCentered(0, 0, 0, 5.5f * s, 5.5f * s, 5.5f * s, 0.92f, 0.92f, 0.95f)
                        .boxCentered(0, 0, 0, 13 * s, 4 * s, 4 * s, r, g, b)
                        .boxCentered(0, 0, 0, 4 * s, 13 * s, 4 * s, r, g, b)
                        .boxCentered(0, 0, 0, 4 * s, 4 * s, 13 * s, r, g, b);
                break;
            }
            case ItemDef.CAT_ARMOR: {
                float r, g, b;
                if (itemId == ItemDef.ARMOR_RED) {
                    r = 0.88f;
                    g = 0.22f;
                    b = 0.20f;
                } else if (itemId == ItemDef.ARMOR_YELLOW) {
                    r = 0.92f;
                    g = 0.74f;
                    b = 0.20f;
                } else {
                    r = 0.38f;
                    g = 0.78f;
                    b = 0.48f;
                }
                float s = itemId == ItemDef.ARMOR_SHARD ? 0.55f : 1f;
                // A chest plate: broad shoulders, a narrowing waist, a dark core.
                mb.material(ProcTex.MAT_SHELL, 14f * s)
                        .box(-3.5f * s, -15 * s, 2 * s, 3.5f * s, 15 * s, 11 * s, r, g, b)
                        .box(-3.5f * s, -11 * s, -6 * s, 3.5f * s, 11 * s, 2 * s,
                                r * 1.06f, g * 1.06f, b * 1.06f)
                        .box(-3.5f * s, -6 * s, -14 * s, 3.5f * s, 6 * s, -6 * s,
                                r * 0.86f, g * 0.86f, b * 0.86f)
                        .box(-4.2f * s, -16 * s, 8 * s, 3.5f * s, -12 * s, 13 * s,
                                r * 0.92f, g * 0.92f, b * 0.92f)
                        .box(-4.2f * s, 12 * s, 8 * s, 3.5f * s, 16 * s, 13 * s,
                                r * 0.92f, g * 0.92f, b * 0.92f)
                        .material(ProcTex.MAT_DARK_METAL, 9f * s)
                        .box(-4.6f * s, -5 * s, -1 * s, -3f * s, 5 * s, 8 * s, 0.30f, 0.31f, 0.36f);
                break;
            }
            case ItemDef.CAT_AMMO: {
                mb.material(ProcTex.MAT_DARK_METAL, 12f)
                        .boxCentered(0, 0, 0, 11, 8, 7, 0.55f, 0.56f, 0.62f)
                        .material(ProcTex.MAT_SHELL, 10f)
                        .boxCentered(0, 0, 7.5f, 12, 9, 1.8f, 0.85f, 0.68f, 0.20f)
                        .material(ProcTex.MAT_GRIP, 7f)
                        .boxCentered(0, 0, -7.5f, 12, 9, 1.8f, 0.34f, 0.34f, 0.38f)
                        .material(ProcTex.MAT_GUNMETAL, 9f)
                        .boxCentered(0, 0, 0, 11.5f, 3f, 7.5f, 0.62f, 0.62f, 0.66f);
                break;
            }
            case ItemDef.CAT_POWERUP: {
                float r, g, b;
                switch (def.powerup) {
                    case ItemDef.PW_QUAD: r = 0.40f; g = 0.50f; b = 1.70f; break;
                    case ItemDef.PW_HASTE: r = 1.50f; g = 0.95f; b = 0.32f; break;
                    case ItemDef.PW_REGEN: r = 1.50f; g = 0.38f; b = 0.38f; break;
                    case ItemDef.PW_INVIS: r = 0.75f; g = 0.90f; b = 1.40f; break;
                    default: r = 1.30f; g = 0.75f; b = 0.28f; break;
                }
                mb.material(ProcTex.MAT_ENERGY, 16f)
                        .sphere(0, 0, 0, 13f, 9, 14, r, g, b)
                        .material(ProcTex.MAT_DARK_METAL, 8f)
                        // Gimbal rings around the core.
                        .boxCentered(0, 0, 0, 18, 2.5f, 2.5f, 0.42f, 0.43f, 0.50f)
                        .boxCentered(0, 0, 0, 2.5f, 18, 2.5f, 0.42f, 0.43f, 0.50f);
                break;
            }
            default:
                mb.material(ProcTex.MAT_SHELL, 12f)
                        .boxCentered(0, 0, 0, 10, 10, 10, 0.7f, 0.7f, 0.7f);
                break;
        }
        return mb.build();
    }

    /** Rocket, grenade or energy ball in flight. */
    public static MeshBuilder.MeshData projectile(int weapon) {
        MeshBuilder mb = new MeshBuilder();
        switch (weapon) {
            case WeaponDef.ROCKET:
                mb.material(ProcTex.MAT_SHELL, 10f)
                        .taperedBox(-9f, 6f, 0f, 0f, 3f, 3f, 1.2f, 0.80f, 0.78f, 0.76f)
                        .taperedBox(6f, 12f, 0f, 0f, 3.6f, 3.6f, 0.15f, 0.85f, 0.32f, 0.22f)
                        .material(ProcTex.MAT_DARK_METAL, 7f)
                        .box(-10f, -5f, -1f, -5f, 5f, 1f, 0.45f, 0.44f, 0.46f)
                        .box(-10f, -1f, -5f, -5f, 1f, 5f, 0.45f, 0.44f, 0.46f);
                break;
            case WeaponDef.GRENADE:
                mb.material(ProcTex.MAT_DARK_METAL, 8f)
                        .sphere(0, 0, 0, 5f, 6, 8, 0.42f, 0.52f, 0.34f)
                        .material(ProcTex.MAT_GLOW, 5f)
                        .boxCentered(0, 0, 5.5f, 1.8f, 1.8f, 2.2f, 1.30f, 0.55f, 0.30f);
                break;
            case WeaponDef.PLASMA:
                mb.material(ProcTex.MAT_ENERGY, 8f)
                        .sphere(0, 0, 0, 5.5f, 6, 9, 0.55f, 0.85f, 1.85f);
                break;
            default:
                mb.material(ProcTex.MAT_ENERGY, 12f)
                        .sphere(0, 0, 0, 9f, 7, 11, 0.80f, 0.45f, 1.85f)
                        .boxCentered(0, 0, 0, 13f, 2f, 2f, 1.00f, 0.65f, 1.90f);
                break;
        }
        return mb.build();
    }

    /** Chunk thrown out when someone is gibbed. */
    public static MeshBuilder.MeshData gib(int variant) {
        MeshBuilder mb = new MeshBuilder();
        mb.material(ProcTex.MAT_MESH, 6f);
        float r = 0.52f, g = 0.12f, b = 0.12f;
        switch (variant % 3) {
            case 0: mb.boxCentered(0, 0, 0, 5, 4, 3, r, g, b); break;
            case 1: mb.boxCentered(0, 0, 0, 3, 6, 4, r * 0.8f, g, b * 1.2f); break;
            default: mb.sphere(0, 0, 0, 4.5f, 4, 6, r * 1.1f, g * 1.2f, b); break;
        }
        return mb.build();
    }
}
