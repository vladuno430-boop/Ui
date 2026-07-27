package com.arena3.game;

/** Brush content and surface bit flags. */
public final class Contents {

    /** Blocks players, projectiles and vision. */
    public static final int SOLID = 1 << 0;
    /** Blocks players but not shots or vision — used to smooth out clutter. */
    public static final int PLAYER_CLIP = 1 << 1;
    /** Hurts on contact and is not solid. */
    public static final int LAVA = 1 << 2;
    /** Not solid at all: decoration only. */
    public static final int DECOR = 1 << 3;
    /** Instantly kills whatever falls into it. */
    public static final int VOID = 1 << 4;

    /** Mask for "stops a moving player". */
    public static final int MASK_PLAYER = SOLID | PLAYER_CLIP;
    /** Mask for "stops a bullet or a rocket". */
    public static final int MASK_SHOT = SOLID;

    // ---- per-face surface flags ----
    /** Face is not drawn (shared/interior faces). */
    public static final int SURF_NODRAW = 1 << 0;
    /** Face glows and does not receive shading. */
    public static final int SURF_GLOW = 1 << 1;
    /** Almost frictionless. */
    public static final int SURF_SLICK = 1 << 2;
    /** Scrolls its texture (conveyor look). */
    public static final int SURF_SCROLL = 1 << 3;
    /** Renders with the sky shader. */
    public static final int SURF_SKY = 1 << 4;
    /** Alpha-blended (grates, glass). */
    public static final int SURF_TRANS = 1 << 5;

    private Contents() {
    }
}
