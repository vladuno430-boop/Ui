package com.arena3.game;

import com.arena3.core.Vec3;

/**
 * Small construction kit for carving arenas out of brushes. Every method returns
 * the builder (or the brush it made) so a map reads roughly like a .map file.
 *
 * <p>Coordinates are Quake units with Z up. A player is 30 wide and 56 tall, can
 * step up 18 and clears a 64-unit-high doorway comfortably.
 */
public final class MapBuilder {

    public final MapDef map = new MapDef();

    public MapBuilder(String id, String name, String subtitle, String description) {
        map.id = id;
        map.name = name;
        map.subtitle = subtitle;
        map.description = description;
    }

    // ---------------------------------------------------------------- geometry

    public Brush solid(float x0, float y0, float z0, float x1, float y1, float z1, int tex) {
        Brush b = Brush.box(x0, y0, z0, x1, y1, z1, Contents.SOLID).setTexture(tex);
        map.brushes.add(b);
        return b;
    }

    public Brush solid(float x0, float y0, float z0, float x1, float y1, float z1, int tex, float scale) {
        return solid(x0, y0, z0, x1, y1, z1, tex).setScale(scale);
    }

    /** Non-solid decoration — trims, light fixtures, banners. */
    public Brush decor(float x0, float y0, float z0, float x1, float y1, float z1, int tex) {
        Brush b = Brush.box(x0, y0, z0, x1, y1, z1, Contents.DECOR).setTexture(tex);
        b.detail = true;
        map.brushes.add(b);
        return b;
    }

    /** Invisible wall that blocks players but not shots — used to fence off ledges. */
    public Brush clip(float x0, float y0, float z0, float x1, float y1, float z1) {
        Brush b = Brush.box(x0, y0, z0, x1, y1, z1, Contents.PLAYER_CLIP);
        b.setAllFlags(Contents.SURF_NODRAW);
        map.brushes.add(b);
        return b;
    }

    public Brush lava(float x0, float y0, float z0, float x1, float y1, float z1) {
        Brush b = Brush.box(x0, y0, z0, x1, y1, z1, Contents.LAVA).setTexture(Tex.LAVA);
        b.setAllFlags(Contents.SURF_GLOW);
        map.brushes.add(b);
        MapDef.HurtVolume h = new MapDef.HurtVolume();
        h.mins.set(b.mins);
        h.maxs.set(b.maxs.x, b.maxs.y, b.maxs.z + 24);
        h.damage = 30;
        h.interval = 0.4f;
        map.hurtVolumes.add(h);
        return b;
    }

    public Brush sky(float x0, float y0, float z0, float x1, float y1, float z1) {
        Brush b = Brush.box(x0, y0, z0, x1, y1, z1, Contents.SOLID).setTexture(Tex.SKY);
        b.setAllFlags(Contents.SURF_SKY);
        map.brushes.add(b);
        return b;
    }

    public Brush ramp(float x0, float y0, float z0, float x1, float y1, float z1,
                      int axis, boolean rising, int tex) {
        Brush b = Brush.ramp(x0, y0, z0, x1, y1, z1, axis, rising, Contents.SOLID).setTexture(tex);
        map.brushes.add(b);
        return b;
    }

    /**
     * A closed room. Walls are built outside the given interior volume so the
     * numbers passed in are the usable space.
     */
    public MapBuilder room(float x0, float y0, float z0, float x1, float y1, float z1,
                           float wall, int floorTex, int wallTex, int ceilTex, boolean ceiling) {
        solid(x0 - wall, y0 - wall, z0 - wall, x1 + wall, y1 + wall, z0, floorTex);           // floor
        if (ceiling) {
            solid(x0 - wall, y0 - wall, z1, x1 + wall, y1 + wall, z1 + wall, ceilTex);
        }
        solid(x0 - wall, y0 - wall, z0, x0, y1 + wall, z1, wallTex);                          // -X
        solid(x1, y0 - wall, z0, x1 + wall, y1 + wall, z1, wallTex);                          // +X
        solid(x0, y0 - wall, z0, x1, y0, z1, wallTex);                                        // -Y
        solid(x0, y1, z0, x1, y1 + wall, z1, wallTex);                                        // +Y
        return this;
    }

    /** The four walls of a room, without floor or ceiling. */
    public MapBuilder walls(float x0, float y0, float z0, float x1, float y1, float z1,
                            float wall, int tex) {
        solid(x0 - wall, y0 - wall, z0, x0, y1 + wall, z1, tex);
        solid(x1, y0 - wall, z0, x1 + wall, y1 + wall, z1, tex);
        solid(x0, y0 - wall, z0, x1, y0, z1, tex);
        solid(x0, y1, z0, x1, y1 + wall, z1, tex);
        return this;
    }

    /**
     * A floor slab with a rectangular hole cut out of it, built as up to four
     * boxes. Used for pits, shafts and open trenches.
     */
    public MapBuilder floorWithHole(float x0, float y0, float x1, float y1,
                                    float hx0, float hy0, float hx1, float hy1,
                                    float top, float thickness, int tex) {
        float bottom = top - thickness;
        if (hy0 > y0) solid(x0, y0, bottom, x1, hy0, top, tex);              // south strip
        if (hy1 < y1) solid(x0, hy1, bottom, x1, y1, top, tex);              // north strip
        if (hx0 > x0) solid(x0, hy0, bottom, hx0, hy1, top, tex);            // west strip
        if (hx1 < x1) solid(hx1, hy0, bottom, x1, hy1, top, tex);            // east strip
        return this;
    }

    /** Shallow pool of lava sitting flush with the floor. */
    public MapBuilder lavaPool(float x0, float y0, float x1, float y1, float z) {
        lava(x0, y0, z, x1, y1, z + 14f);
        light((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, z + 90f, 420f, 1.1f, 1.0f, 0.35f, 0.12f);
        return this;
    }

    /** A run of steps climbing along +X (dir=1) or -X (dir=-1). */
    public MapBuilder stairsX(float xStart, float y0, float y1, float zBase,
                              int steps, float stepW, float stepH, int dir, int tex) {
        for (int i = 0; i < steps; i++) {
            float x = xStart + dir * i * stepW;
            float xa = Math.min(x, x + dir * stepW);
            float xb = Math.max(x, x + dir * stepW);
            solid(xa, y0, zBase, xb, y1, zBase + (i + 1) * stepH, tex);
        }
        return this;
    }

    /** A run of steps climbing along +Y (dir=1) or -Y (dir=-1). */
    public MapBuilder stairsY(float yStart, float x0, float x1, float zBase,
                              int steps, float stepD, float stepH, int dir, int tex) {
        for (int i = 0; i < steps; i++) {
            float y = yStart + dir * i * stepD;
            float ya = Math.min(y, y + dir * stepD);
            float yb = Math.max(y, y + dir * stepD);
            solid(x0, ya, zBase, x1, yb, zBase + (i + 1) * stepH, tex);
        }
        return this;
    }

    /** Walkway with a lip on both long edges. */
    public MapBuilder walkwayX(float x0, float x1, float yCenter, float width, float z, int tex) {
        float h = width * 0.5f;
        solid(x0, yCenter - h, z - 16, x1, yCenter + h, z, tex);
        decor(x0, yCenter - h - 2, z, x1, yCenter - h + 4, z + 8, Tex.TRIM);
        decor(x0, yCenter + h - 4, z, x1, yCenter + h + 2, z + 8, Tex.TRIM);
        return this;
    }

    public MapBuilder walkwayY(float y0, float y1, float xCenter, float width, float z, int tex) {
        float h = width * 0.5f;
        solid(xCenter - h, y0, z - 16, xCenter + h, y1, z, tex);
        decor(xCenter - h - 2, y0, z, xCenter - h + 4, y1, z + 8, Tex.TRIM);
        decor(xCenter + h - 4, y0, z, xCenter + h + 2, y1, z + 8, Tex.TRIM);
        return this;
    }

    /** Square column with a glowing band near the top. */
    public MapBuilder pillar(float cx, float cy, float z0, float z1, float halfWidth, int tex) {
        solid(cx - halfWidth, cy - halfWidth, z0, cx + halfWidth, cy + halfWidth, z1, tex);
        float band = z1 - 40;
        decor(cx - halfWidth - 2, cy - halfWidth - 2, band, cx + halfWidth + 2, cy + halfWidth + 2, band + 10,
                Tex.TRIM_LIGHT).setAllFlags(Contents.SURF_GLOW);
        return this;
    }

    // ---------------------------------------------------------------- entities

    public MapBuilder spawn(float x, float y, float z, float yaw) {
        map.spawns.add(new MapDef.Spawn(x, y, z, yaw));
        return this;
    }

    public MapBuilder item(int itemId, float x, float y, float z) {
        map.items.add(new MapDef.ItemSpawn(itemId, x, y, z));
        return this;
    }

    /** Row of {@code n} items spaced along X. */
    public MapBuilder itemRowX(int itemId, float x, float y, float z, int n, float spacing) {
        for (int i = 0; i < n; i++) item(itemId, x + i * spacing, y, z);
        return this;
    }

    public MapBuilder itemRowY(int itemId, float x, float y, float z, int n, float spacing) {
        for (int i = 0; i < n; i++) item(itemId, x, y + i * spacing, z);
        return this;
    }

    public MapBuilder light(float x, float y, float z, float radius, float intensity,
                            float r, float g, float b) {
        map.lights.add(new MapDef.Light(x, y, z, radius, intensity, r, g, b));
        return this;
    }

    /** Warm ceiling lamp: a glowing fixture brush plus the light itself. */
    public MapBuilder lamp(float x, float y, float z, float radius, float intensity) {
        decor(x - 32, y - 32, z - 6, x + 32, y + 32, z, Tex.TRIM_LIGHT).setAllFlags(Contents.SURF_GLOW);
        return light(x, y, z - 12, radius, intensity, 1.0f, 0.88f, 0.66f);
    }

    /**
     * Jump pad on the floor at (x, y, z) that throws players onto the floor spot
     * (tx, ty, tz). The launch velocity is solved from the ballistic arc, aimed
     * to arrive comfortably above the landing surface rather than exactly at it —
     * a player who arrives level with the floor clips its edge and falls.
     */
    public MapBuilder jumpPad(float x, float y, float z, float tx, float ty, float tz) {
        MapDef.JumpPad pad = new MapDef.JumpPad();
        pad.mins.set(x - 32, y - 32, z);
        pad.maxs.set(x + 32, y + 32, z + 24);
        pad.target.set(tx, ty, tz);

        // The trajectory is solved to a point above the destination floor, so the
        // player clears the lip and drops the last stretch.
        float aimZ = tz + 48f;
        float launchZ = z + 24f;                 // pads act on the player's origin
        // Long pads get a higher arc so they come down steeply instead of
        // skimming in flat and clipping the near edge of the landing pad.
        float span = (float) Math.hypot(tx - x, ty - y);
        float apex = Math.max(launchZ, aimZ) + 96f + span * 0.22f;
        float gravity = PlayerMove.GRAVITY;
        float vz = (float) Math.sqrt(2f * gravity * (apex - launchZ));
        float timeUp = vz / gravity;
        float timeDown = (float) Math.sqrt(2f * (apex - aimZ) / gravity);
        float total = Math.max(0.15f, timeUp + timeDown);
        pad.velocity.set((tx - x) / total, (ty - y) / total, vz);
        map.jumpPads.add(pad);

        decor(x - 32, y - 32, z, x + 32, y + 32, z + 2, Tex.JUMPPAD).setAllFlags(Contents.SURF_GLOW);
        light(x, y, z + 40, 220f, 0.7f, 0.35f, 0.75f, 1.0f);
        return this;
    }

    public MapBuilder teleporter(float x, float y, float z, float destX, float destY, float destZ, float destYaw) {
        MapDef.Teleporter t = new MapDef.Teleporter();
        t.mins.set(x - 40, y - 40, z);
        t.maxs.set(x + 40, y + 40, z + 72);
        t.dest.set(destX, destY, destZ);
        t.destYaw = destYaw;
        map.teleporters.add(t);

        decor(x - 40, y - 40, z, x + 40, y + 40, z + 2, Tex.TELEPAD).setAllFlags(Contents.SURF_GLOW);
        light(x, y, z + 50, 260f, 0.9f, 0.55f, 0.35f, 1.0f);
        return this;
    }

    /** Kill volume covering the pit under a floating arena. */
    public MapBuilder voidPit(float x0, float y0, float z0, float x1, float y1, float z1) {
        MapDef.HurtVolume h = new MapDef.HurtVolume();
        h.mins.set(Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1));
        h.maxs.set(Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1));
        h.instantKill = true;
        map.hurtVolumes.add(h);
        return this;
    }

    public MapBuilder ambient(float r, float g, float b) {
        map.ambient.set(r, g, b);
        return this;
    }

    public MapBuilder sun(float dx, float dy, float dz, float r, float g, float b) {
        map.sunDir.set(dx, dy, dz);
        map.sunDir.normalize();
        map.sunColor.set(r, g, b);
        return this;
    }

    public MapBuilder fog(float r, float g, float b, float near, float far) {
        map.fogColor.set(r, g, b);
        map.fogNear = near;
        map.fogFar = far;
        return this;
    }

    public MapBuilder skyStyle(int style) {
        map.skyStyle = style;
        return this;
    }

    public MapBuilder killZ(float z) {
        map.killZ = z;
        return this;
    }

    public MapDef build() {
        for (Brush b : map.brushes) b.computeBounds();
        return map;
    }

    /** Handy when a caller wants the centre of a brush it just made. */
    public static Vec3 center(Brush b) {
        return new Vec3((b.mins.x + b.maxs.x) * 0.5f, (b.mins.y + b.maxs.y) * 0.5f,
                (b.mins.z + b.maxs.z) * 0.5f);
    }
}
