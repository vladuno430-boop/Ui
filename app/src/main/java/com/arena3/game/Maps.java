package com.arena3.game;

/**
 * The shipped arenas. Each is built from brushes in code — same idea as a .map
 * file, just expressed in Java so there is nothing to load at runtime.
 */
public final class Maps {

    public static final int COUNT = 3;

    private Maps() {
    }

    public static String nameOf(int index) {
        switch (index) {
            case 0: return "THE FORGE";
            case 1: return "CRUCIBLE";
            default: return "THE VOID";
        }
    }

    public static String subtitleOf(int index) {
        switch (index) {
            case 0: return "Small · 2-4 players";
            case 1: return "Medium · 3-6 players";
            default: return "Vertical · 2-5 players";
        }
    }

    public static String descriptionOf(int index) {
        switch (index) {
            case 0: return "A sealed foundry. Ramps climb to a central dais holding the "
                    + "Quad, a walkway rings the walls above the lava, and the Railgun "
                    + "watches the whole floor from the east balcony.";
            case 1: return "Open-air fortress. Two towers face each other across a sunken "
                    + "trench; a bridge crosses the middle and paired teleporters flip you "
                    + "from one end to the other in an instant.";
            default: return "Platforms suspended over nothing. Jump pads are the only "
                    + "reliable way across, every miss is fatal, and the Railgun rules the "
                    + "open air.";
        }
    }

    public static MapDef build(int index) {
        switch (index) {
            case 0: return forge();
            case 1: return crucible();
            default: return theVoid();
        }
    }

    // ==================================================================== FORGE

    private static MapDef forge() {
        MapBuilder b = new MapBuilder("forge", nameOf(0), subtitleOf(0), descriptionOf(0));
        b.ambient(0.115f, 0.112f, 0.140f)
                .sun(-0.4f, -0.3f, -0.87f, 0.10f, 0.10f, 0.13f)
                .fog(0.05f, 0.045f, 0.06f, 1600f, 4200f)
                .skyStyle(1)
                .killZ(-800f);

        final float H = 896f;          // interior half-extent
        final float CEIL = 576f;
        final float WALL = 64f;

        // Shell: floor, walls, ceiling.
        b.solid(-H - WALL, -H - WALL, -64f, H + WALL, H + WALL, 0f, Tex.FLOOR_METAL, 128f);
        b.walls(-H, -H, 0f, H, H, CEIL, WALL, Tex.WALL_TECH);
        b.solid(-H - WALL, -H - WALL, CEIL, H + WALL, H + WALL, CEIL + WALL, Tex.CEILING, 128f);

        // Wall trim band, purely visual.
        for (int i = 0; i < 4; i++) {
            float z = 200f;
            if (i == 0) b.decor(-H, -H, z, H, -H + 6f, z + 12f, Tex.TRIM_LIGHT).setAllFlags(Contents.SURF_GLOW);
            if (i == 1) b.decor(-H, H - 6f, z, H, H, z + 12f, Tex.TRIM_LIGHT).setAllFlags(Contents.SURF_GLOW);
            if (i == 2) b.decor(-H, -H, z, -H + 6f, H, z + 12f, Tex.TRIM_LIGHT).setAllFlags(Contents.SURF_GLOW);
            if (i == 3) b.decor(H - 6f, -H, z, H, H, z + 12f, Tex.TRIM_LIGHT).setAllFlags(Contents.SURF_GLOW);
        }

        // --- central dais with ramps on all four sides ---
        b.solid(-224f, -224f, 0f, 224f, 224f, 144f, Tex.METAL_DARK, 96f);
        b.decor(-232f, -232f, 132f, 232f, 232f, 144f, Tex.TRIM);
        b.ramp(-448f, -112f, 0f, -224f, 112f, 144f, 0, true, Tex.FLOOR_METAL);
        b.ramp(224f, -112f, 0f, 448f, 112f, 144f, 0, false, Tex.FLOOR_METAL);
        b.ramp(-112f, -448f, 0f, 112f, -224f, 144f, 1, true, Tex.FLOOR_METAL);
        b.ramp(-112f, 224f, 0f, 112f, 448f, 144f, 1, false, Tex.FLOOR_METAL);

        // --- upper ring walkway ---
        final float RING_Z = 288f;
        b.walkwayX(-H, H, -816f, 160f, RING_Z, Tex.FLOOR_GRATE);
        b.walkwayX(-H, H, 816f, 160f, RING_Z, Tex.FLOOR_GRATE);
        b.walkwayY(-736f, 736f, -816f, 160f, RING_Z, Tex.FLOOR_GRATE);
        b.walkwayY(-736f, 736f, 816f, 160f, RING_Z, Tex.FLOOR_GRATE);

        // Staircase from the floor up to the west balcony. It runs outward from
        // the arena so it never passes under the walkway it feeds.
        b.stairsX(-336f, -500f, -330f, 0f, 18, 24f, 16f, -1, Tex.CONCRETE);

        // Support columns.
        b.pillar(-560f, -560f, 0f, RING_Z, 48f, Tex.CONCRETE);
        b.pillar(560f, -560f, 0f, RING_Z, 48f, Tex.CONCRETE);
        b.pillar(-560f, 560f, 0f, RING_Z, 48f, Tex.CONCRETE);

        // --- lava pool in the north-east corner ---
        b.lavaPool(448f, 448f, 864f, 864f, 0f);
        b.decor(432f, 432f, 0f, 448f, 880f, 18f, Tex.HAZARD);
        b.decor(432f, 432f, 0f, 880f, 448f, 18f, Tex.HAZARD);

        // --- movement ---
        b.jumpPad(0f, -640f, 0f, 0f, -816f, RING_Z);
        b.jumpPad(-640f, 0f, 0f, -816f, 0f, RING_Z);
        b.jumpPad(640f, 0f, 0f, 816f, 0f, RING_Z);
        b.teleporter(-816f, -816f, RING_Z, 640f, -640f, 24f, 135f);

        // --- lighting ---
        b.lamp(-448f, -448f, CEIL - 8f, 900f, 2.3f);
        b.lamp(448f, -448f, CEIL - 8f, 900f, 2.3f);
        b.lamp(-448f, 448f, CEIL - 8f, 900f, 2.3f);
        b.lamp(448f, 448f, CEIL - 8f, 900f, 1.8f);
        b.lamp(0f, 0f, CEIL - 8f, 1000f, 2.6f);
        b.lamp(-816f, -400f, CEIL - 8f, 700f, 1.6f);
        b.lamp(816f, 400f, CEIL - 8f, 700f, 1.6f);
        // Cold fill from the balcony strips, to set off the warm lamps.
        b.light(0f, 0f, 240f, 620f, 1.1f, 0.42f, 0.58f, 1.0f);
        b.light(-816f, 0f, RING_Z + 90f, 520f, 1.3f, 0.55f, 0.72f, 1.0f);
        b.light(816f, 0f, RING_Z + 90f, 520f, 1.3f, 0.55f, 0.72f, 1.0f);
        b.light(0f, -816f, RING_Z + 90f, 520f, 1.1f, 0.55f, 0.72f, 1.0f);
        b.light(0f, 816f, RING_Z + 90f, 520f, 1.1f, 0.55f, 0.72f, 1.0f);

        // --- items ---
        b.item(ItemDef.POWERUP_QUAD, 0f, 0f, 168f);
        b.item(ItemDef.WEAPON_ROCKET, -680f, 680f, 24f);
        b.itemRowX(ItemDef.AMMO_ROCKETS, -760f, 600f, 24f, 2, 64f);
        b.item(ItemDef.WEAPON_LIGHTNING, -680f, -680f, 24f);
        b.item(ItemDef.AMMO_LIGHTNING, -600f, -760f, 24f);
        b.item(ItemDef.WEAPON_SHOTGUN, 680f, -680f, 24f);
        b.itemRowX(ItemDef.AMMO_SHELLS, 600f, -760f, 24f, 2, 64f);
        b.item(ItemDef.WEAPON_PLASMA, 0f, -560f, 24f);
        b.item(ItemDef.AMMO_CELLS, 0f, -480f, 24f);
        b.item(ItemDef.WEAPON_RAILGUN, 816f, 0f, RING_Z + 24f);
        b.itemRowY(ItemDef.AMMO_SLUGS, 816f, -120f, RING_Z + 24f, 2, 80f);
        b.item(ItemDef.WEAPON_GRENADE, 0f, 816f, RING_Z + 24f);
        b.item(ItemDef.HEALTH_MEGA, 0f, -816f, RING_Z + 24f);
        b.item(ItemDef.ARMOR_YELLOW, -816f, 0f, RING_Z + 24f);
        b.item(ItemDef.ARMOR_RED, 380f, 380f, 24f);
        b.itemRowX(ItemDef.ARMOR_SHARD, -120f, 0f, 168f, 3, 120f);
        b.itemRowY(ItemDef.HEALTH, -560f, -120f, 24f, 2, 240f);
        b.itemRowY(ItemDef.HEALTH, 560f, -120f, 24f, 2, 240f);
        b.item(ItemDef.HEALTH_LARGE, -816f, 500f, RING_Z + 24f);
        b.itemRowX(ItemDef.HEALTH_SMALL, -80f, 700f, 24f, 3, 80f);
        b.item(ItemDef.AMMO_BULLETS, 300f, -300f, 24f);
        b.item(ItemDef.AMMO_BULLETS, -300f, 300f, 24f);

        // --- spawns ---
        b.spawn(-700f, -300f, 32f, 0f);
        b.spawn(700f, 300f, 32f, 180f);
        b.spawn(-300f, 700f, 32f, -90f);
        b.spawn(300f, -700f, 32f, 90f);
        b.spawn(-816f, 400f, RING_Z + 32f, -45f);
        b.spawn(816f, -400f, RING_Z + 32f, 135f);
        b.spawn(0f, 0f, 176f, 45f);
        b.spawn(-440f, -640f, 32f, 45f);

        return b.build();
    }

    // ================================================================= CRUCIBLE

    private static MapDef crucible() {
        MapBuilder b = new MapBuilder("crucible", nameOf(1), subtitleOf(1), descriptionOf(1));
        // The sun is cast through the shadow map rather than baked flat, so it
        // carries more of the exposure and the ambient carries less: that is what
        // gives the shadows something to cut into.
        b.ambient(0.118f, 0.128f, 0.180f)
                .sun(-0.45f, -0.25f, -0.85f, 0.62f, 0.55f, 0.42f)
                .fog(0.09f, 0.10f, 0.13f, 2200f, 6000f)
                .skyStyle(0)
                .killZ(-900f);

        final float HX = 1280f;
        final float HY = 960f;
        final float TOP = 832f;
        final float WALL = 96f;

        // Courtyard floor with a sunken trench down the middle.
        b.floorWithHole(-HX - WALL, -HY - WALL, HX + WALL, HY + WALL,
                -320f, -HY, 320f, HY, 0f, 96f, Tex.CONCRETE);
        b.solid(-320f, -HY, -224f, 320f, HY, -128f, Tex.FLOOR_METAL, 128f);   // trench floor
        b.solid(-336f, -HY, -128f, -320f, HY, 0f, Tex.WALL_RUST);             // trench sides
        b.solid(320f, -HY, -128f, 336f, HY, 0f, Tex.WALL_RUST);
        // Ramps down into the trench at both ends.
        b.ramp(-320f, -HY, -128f, 320f, -HY + 384f, 0f, 1, false, Tex.FLOOR_METAL);
        b.ramp(-320f, HY - 384f, -128f, 320f, HY, 0f, 1, true, Tex.FLOOR_METAL);

        b.walls(-HX, -HY, -224f, HX, HY, TOP, WALL, Tex.WALL_PANEL);
        // Sky lid: an open ceiling that still bounds the playable volume.
        b.sky(-HX - WALL, -HY - WALL, TOP, HX + WALL, HY + WALL, TOP + WALL);

        // --- two towers ---
        for (int side = -1; side <= 1; side += 2) {
            float cx = side * 880f;
            b.solid(cx - 320f, -320f, 0f, cx + 320f, 320f, 320f, Tex.WALL_TECH, 96f);
            b.decor(cx - 328f, -328f, 308f, cx + 328f, 328f, 320f, Tex.TRIM);
            // Staircase up the north face of the tower.
            b.stairsY(800f, cx - 96f, cx + 96f, 0f, 20, 24f, 16f, -1, Tex.CONCRETE);
            // Corner posts on the deck.
            b.pillar(cx - 288f, -288f, 320f, 520f, 28f, Tex.CONCRETE);
            b.pillar(cx + 288f, -288f, 320f, 520f, 28f, Tex.CONCRETE);
            b.pillar(cx - 288f, 288f, 320f, 520f, 28f, Tex.CONCRETE);
            b.pillar(cx + 288f, 288f, 320f, 520f, 28f, Tex.CONCRETE);
            b.light(cx, 0f, 500f, 1000f, 2.4f, 1.0f, 0.84f, 0.58f);
        }

        // --- bridge joining the two tower decks ---
        b.walkwayX(-880f, 880f, 0f, 224f, 320f, Tex.FLOOR_GRATE);

        // Side galleries along the long walls, with stairs up at the far ends.
        // The stairs need enough run to stay climbable and to clear the gallery
        // they feed, hence the gap between where they end and where it starts.
        b.walkwayX(-900f, 900f, -HY + 112f, 224f, 256f, Tex.FLOOR_METAL);
        b.walkwayX(-900f, 900f, HY - 112f, 224f, 256f, Tex.FLOOR_METAL);
        b.stairsX(-1240f, -940f, -760f, 0f, 16, 21f, 16f, 1, Tex.CONCRETE);
        b.stairsX(1240f, 760f, 940f, 0f, 16, 21f, 16f, -1, Tex.CONCRETE);

        // --- movement ---
        b.jumpPad(0f, -640f, 0f, 0f, -96f, 320f);
        b.jumpPad(0f, 640f, 0f, 0f, 96f, 320f);
        b.jumpPad(-1120f, -700f, 0f, -880f, -240f, 320f);
        b.jumpPad(1120f, 700f, 0f, 880f, 240f, 320f);
        b.teleporter(-1160f, 780f, 0f, 1080f, -700f, 24f, 135f);
        b.teleporter(1160f, -780f, 0f, -1080f, 700f, 24f, -45f);

        // --- lighting ---
        b.lamp(0f, -HY + 200f, TOP - 40f, 1000f, 2.0f);
        b.lamp(0f, HY - 200f, TOP - 40f, 1000f, 2.0f);
        b.light(0f, 0f, 540f, 1400f, 1.4f, 0.62f, 0.72f, 1.0f);
        b.light(0f, 0f, -40f, 700f, 1.8f, 1.0f, 0.55f, 0.25f);
        b.light(-900f, -848f, 300f, 700f, 1.2f, 0.95f, 0.8f, 0.6f);
        b.light(900f, 848f, 300f, 700f, 1.2f, 0.95f, 0.8f, 0.6f);

        // --- items ---
        b.item(ItemDef.WEAPON_ROCKET, 0f, 0f, -104f);
        b.itemRowY(ItemDef.AMMO_ROCKETS, 0f, -220f, -104f, 2, 440f);
        b.item(ItemDef.POWERUP_QUAD, 0f, 0f, 344f);
        b.item(ItemDef.WEAPON_RAILGUN, -880f, 0f, 344f);
        b.item(ItemDef.WEAPON_VOID, 880f, 0f, 344f);
        b.itemRowY(ItemDef.AMMO_SLUGS, -880f, -160f, 344f, 2, 320f);
        b.itemRowY(ItemDef.AMMO_VOID, 880f, -160f, 344f, 2, 320f);
        b.item(ItemDef.WEAPON_LIGHTNING, -1120f, 0f, 24f);
        b.item(ItemDef.WEAPON_PLASMA, 1120f, 0f, 24f);
        b.item(ItemDef.WEAPON_SHOTGUN, -400f, -HY + 112f, 280f);
        b.item(ItemDef.WEAPON_GRENADE, 400f, HY - 112f, 280f);
        b.item(ItemDef.ARMOR_RED, 0f, -HY + 112f, 280f);
        b.item(ItemDef.ARMOR_YELLOW, 0f, HY - 112f, 280f);
        b.item(ItemDef.HEALTH_MEGA, 0f, HY - 380f, -104f);
        b.itemRowX(ItemDef.HEALTH, -560f, 0f, -104f, 2, 1120f);
        b.itemRowX(ItemDef.ARMOR_SHARD, -120f, 0f, 344f, 3, 120f);
        b.itemRowX(ItemDef.HEALTH_SMALL, -1000f, -700f, 24f, 3, 90f);
        b.itemRowX(ItemDef.HEALTH_SMALL, 820f, 700f, 24f, 3, 90f);
        b.item(ItemDef.HEALTH_LARGE, -880f, 0f, 24f);
        b.item(ItemDef.HEALTH_LARGE, 880f, 0f, 24f);
        b.item(ItemDef.AMMO_BULLETS, -600f, -400f, 24f);
        b.item(ItemDef.AMMO_BULLETS, 600f, 400f, 24f);
        b.item(ItemDef.AMMO_CELLS, 1120f, 160f, 24f);
        b.item(ItemDef.AMMO_LIGHTNING, -1120f, -160f, 24f);
        b.item(ItemDef.POWERUP_REGEN, -1160f, -780f, 24f);
        b.item(ItemDef.POWERUP_HASTE, 1160f, 780f, 24f);

        // --- spawns ---
        b.spawn(-1100f, -640f, 32f, 45f);
        b.spawn(1100f, 640f, 32f, -135f);
        b.spawn(-600f, 700f, 32f, -60f);
        b.spawn(600f, -700f, 32f, 120f);
        b.spawn(-880f, 0f, 352f, 0f);
        b.spawn(880f, 0f, 352f, 180f);
        b.spawn(0f, -848f, 288f, 90f);
        b.spawn(0f, 848f, 288f, -90f);
        b.spawn(0f, -260f, -96f, 90f);

        return b.build();
    }

    // ================================================================= THE VOID

    private static MapDef theVoid() {
        MapBuilder b = new MapBuilder("void", nameOf(2), subtitleOf(2), descriptionOf(2));
        b.ambient(0.088f, 0.094f, 0.140f)
                .sun(0.2f, -0.4f, -0.89f, 0.34f, 0.34f, 0.44f)
                .fog(0.02f, 0.02f, 0.04f, 3000f, 9000f)
                .skyStyle(2)
                .killZ(-700f);

        // Central island.
        b.solid(-320f, -320f, -64f, 320f, 320f, 0f, Tex.METAL_DARK, 96f);
        b.decor(-328f, -328f, -12f, 328f, 328f, 0f, Tex.TRIM_LIGHT).setAllFlags(Contents.SURF_GLOW);
        b.pillar(-272f, -272f, 0f, 176f, 32f, Tex.WALL_TECH);
        b.pillar(272f, -272f, 0f, 176f, 32f, Tex.WALL_TECH);
        b.pillar(-272f, 272f, 0f, 176f, 32f, Tex.WALL_TECH);
        b.pillar(272f, 272f, 0f, 176f, 32f, Tex.WALL_TECH);
        b.light(0f, 0f, 220f, 900f, 2.2f, 0.72f, 0.84f, 1.0f);

        // Four outer platforms, alternating heights.
        float[][] outer = {
                {-960f, 0f, 128f}, {960f, 0f, 128f}, {0f, -960f, -64f}, {0f, 960f, -64f},
        };
        for (float[] p : outer) {
            float cx = p[0], cy = p[1], cz = p[2];
            b.solid(cx - 256f, cy - 256f, cz - 64f, cx + 256f, cy + 256f, cz, Tex.FLOOR_METAL, 96f);
            b.decor(cx - 264f, cy - 264f, cz - 12f, cx + 264f, cy + 264f, cz, Tex.TRIM);
            b.light(cx, cy, cz + 190f, 700f, 2.0f, 1.0f, 0.86f, 0.66f);
        }

        // Diagonal stepping stones — reachable with a good strafe jump.
        float[][] steps = {
                {-620f, -620f, 32f}, {620f, -620f, 32f}, {-620f, 620f, 32f}, {620f, 620f, 32f},
        };
        for (float[] p : steps) {
            b.solid(p[0] - 176f, p[1] - 176f, p[2] - 32f, p[0] + 176f, p[1] + 176f, p[2], Tex.CONCRETE, 96f);
            b.light(p[0], p[1], p[2] + 150f, 460f, 1.7f, 0.72f, 0.8f, 1.0f);
        }

        // A high sniper deck over the centre, reached by a pad.
        b.solid(-232f, -232f, 384f, 232f, 232f, 416f, Tex.FLOOR_GRATE, 96f);
        b.decor(-240f, -240f, 404f, 240f, 240f, 416f, Tex.TRIM_LIGHT).setAllFlags(Contents.SURF_GLOW);

        // --- jump pads: nothing here is walkable, so every route is a launch ---
        // Landing spots are deliberately kept away from the pad that sends you
        // back, or the two would volley players between them forever.

        // Centre island out to the near end of each deck.
        b.jumpPad(-200f, -200f, 0f, 0f, -830f, -64f);
        b.jumpPad(200f, 200f, 0f, 0f, 830f, -64f);
        b.jumpPad(-200f, 200f, 0f, -830f, 0f, 128f);
        b.jumpPad(200f, -200f, 0f, 830f, 0f, 128f);
        // Each deck returns to the middle from its far end.
        b.jumpPad(-1120f, 0f, 128f, 0f, 0f, 0f);
        b.jumpPad(1120f, 0f, 128f, 0f, 0f, 0f);
        b.jumpPad(0f, -1120f, -64f, 0f, 0f, 0f);
        b.jumpPad(140f, 1120f, -64f, 0f, 0f, 0f);
        // The perch above the island is only reachable on a long arc from the
        // north deck — straight up from below just means a head full of its
        // underside.
        b.jumpPad(-140f, 1120f, -64f, 0f, 0f, 416f);
        // The north and south decks feed the four corner stones.
        b.jumpPad(-200f, -960f, -64f, -620f, -700f, 32f);
        b.jumpPad(200f, -960f, -64f, 620f, -540f, 32f);
        b.jumpPad(-200f, 960f, -64f, -620f, 540f, 32f);
        b.jumpPad(200f, 960f, -64f, 620f, 700f, 32f);
        // Two stones launch back to the middle, two hold cross-map teleports
        // whose exits sit on the outer decks, well clear of any other trigger.
        b.jumpPad(-620f, -524f, 32f, 0f, 0f, 0f);
        b.jumpPad(620f, 524f, 32f, 0f, 0f, 0f);
        b.teleporter(-620f, 700f, 32f, 860f, 160f, 152f, 180f);
        b.teleporter(620f, -700f, 32f, -860f, -160f, 152f, 0f);

        // Everything below is fatal.
        b.voidPit(-4000f, -4000f, -600f, 4000f, 4000f, -400f);

        // --- items ---
        b.item(ItemDef.WEAPON_RAILGUN, 0f, 0f, 416f + 24f);
        b.itemRowX(ItemDef.AMMO_SLUGS, -80f, 0f, 440f, 3, 80f);
        b.item(ItemDef.POWERUP_QUAD, 0f, 0f, 152f);
        b.item(ItemDef.WEAPON_ROCKET, -960f, 0f, 152f);
        b.item(ItemDef.WEAPON_VOID, 960f, 0f, 152f);
        b.item(ItemDef.WEAPON_LIGHTNING, 0f, -960f, -40f);
        b.item(ItemDef.WEAPON_PLASMA, 0f, 960f, -40f);
        b.itemRowY(ItemDef.AMMO_ROCKETS, -960f, -140f, 152f, 2, 280f);
        b.itemRowY(ItemDef.AMMO_VOID, 960f, -140f, 152f, 2, 280f);
        b.item(ItemDef.AMMO_LIGHTNING, 120f, -960f, -40f);
        b.item(ItemDef.AMMO_CELLS, 120f, 960f, -40f);
        b.item(ItemDef.ARMOR_RED, -620f, 620f, 56f);
        b.item(ItemDef.ARMOR_YELLOW, 620f, -620f, 56f);
        b.item(ItemDef.HEALTH_MEGA, -620f, -620f, 56f);
        b.item(ItemDef.POWERUP_INVIS, 620f, 620f, 56f);
        b.itemRowX(ItemDef.HEALTH, -840f, 0f, 152f, 2, 1680f);
        b.itemRowX(ItemDef.ARMOR_SHARD, -100f, -180f, 24f, 3, 100f);
        b.itemRowX(ItemDef.HEALTH_SMALL, -100f, 180f, 24f, 3, 100f);
        b.item(ItemDef.POWERUP_BATTLESUIT, -180f, 960f, -40f);

        // --- spawns ---
        b.spawn(-200f, 0f, 32f, 0f);
        b.spawn(200f, 0f, 32f, 180f);
        b.spawn(-960f, 160f, 160f, -45f);
        b.spawn(960f, -160f, 160f, 135f);
        b.spawn(160f, -960f, -32f, 90f);
        b.spawn(-160f, 960f, -32f, -90f);
        b.spawn(-620f, 620f, 64f, -45f);
        b.spawn(620f, -620f, 64f, 135f);

        return b.build();
    }
}
