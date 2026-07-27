package com.arena3.game;

/**
 * Texture slots. The renderer generates every one of these procedurally at
 * start-up into a single array texture, so a slot here is just an index.
 */
public final class Tex {

    public static final int WALL_TECH = 0;
    public static final int WALL_PANEL = 1;
    public static final int WALL_RUST = 2;
    public static final int FLOOR_METAL = 3;
    public static final int FLOOR_GRATE = 4;
    public static final int CONCRETE = 5;
    public static final int TRIM = 6;
    public static final int TRIM_LIGHT = 7;
    public static final int LAVA = 8;
    public static final int ROCK = 9;
    public static final int SKY = 10;
    public static final int TELEPAD = 11;
    public static final int JUMPPAD = 12;
    public static final int METAL_DARK = 13;
    public static final int HAZARD = 14;
    public static final int CEILING = 15;

    public static final int COUNT = 16;

    private Tex() {
    }
}
