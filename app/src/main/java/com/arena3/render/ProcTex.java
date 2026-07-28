package com.arena3.render;

import com.arena3.game.Tex;

import java.util.Random;

/**
 * Generates every world texture procedurally as ARGB pixels. The game ships no
 * image files: metal plate, rusted panels, grating, concrete, lava and the rest
 * are all synthesised here at start-up.
 *
 * <p>Pure Java on purpose — the same code feeds the GL texture array on the
 * device and the offline preview renderer used to check the art.
 */
public final class ProcTex {

    public static final int SIZE = 256;

    private ProcTex() {
    }

    /** Generates one texture slot as SIZE*SIZE ARGB pixels. */
    public static int[] generate(int slot) {
        switch (slot) {
            case Tex.WALL_TECH: return wallTech();
            case Tex.WALL_PANEL: return wallPanel();
            case Tex.WALL_RUST: return wallRust();
            case Tex.FLOOR_METAL: return floorMetal();
            case Tex.FLOOR_GRATE: return floorGrate();
            case Tex.CONCRETE: return concrete();
            case Tex.TRIM: return trim();
            case Tex.TRIM_LIGHT: return trimLight();
            case Tex.LAVA: return lava();
            case Tex.ROCK: return rock();
            case Tex.SKY: return flat(0xFF0A0C12);
            case Tex.TELEPAD: return telepad();
            case Tex.JUMPPAD: return jumpPad();
            case Tex.METAL_DARK: return metalDark();
            case Tex.HAZARD: return hazard();
            case Tex.CEILING: return ceiling();
            default: return flat(0xFF808080);
        }
    }

    // ------------------------------------------------------------------ noise

    /** Value noise with a tileable integer lattice. */
    private static float noise(float x, float y, int period, int seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;
        float sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
        float n00 = hash(x0, y0, period, seed);
        float n10 = hash(x0 + 1, y0, period, seed);
        float n01 = hash(x0, y0 + 1, period, seed);
        float n11 = hash(x0 + 1, y0 + 1, period, seed);
        float a = n00 + (n10 - n00) * sx;
        float b = n01 + (n11 - n01) * sx;
        return a + (b - a) * sy;
    }

    private static float hash(int x, int y, int period, int seed) {
        x = Math.floorMod(x, period);
        y = Math.floorMod(y, period);
        int h = x * 374761393 + y * 668265263 + seed * 1442695040;
        h = (h ^ (h >> 13)) * 1274126177;
        h = h ^ (h >> 16);
        return (h & 0xFFFF) / 65535f;
    }

    /** Tileable fractal noise over the [0,1] texture square. */
    private static float fbm(float u, float v, int baseFreq, int octaves, int seed) {
        float sum = 0f, amp = 0.5f, total = 0f;
        int freq = baseFreq;
        for (int o = 0; o < octaves; o++) {
            sum += noise(u * freq, v * freq, freq, seed + o * 71) * amp;
            total += amp;
            amp *= 0.5f;
            freq *= 2;
        }
        return sum / total;
    }

    // ------------------------------------------------------------- primitives

    private static int[] flat(int argb) {
        int[] p = new int[SIZE * SIZE];
        java.util.Arrays.fill(p, argb);
        return p;
    }

    private static int rgb(float r, float g, float b) {
        int ri = clamp255(r * 255f), gi = clamp255(g * 255f), bi = clamp255(b * 255f);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static int clamp255(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    /** Darkens near the edges of a cell of the given size, for panel seams. */
    private static float bevel(int x, int y, int cell, float depth) {
        int lx = Math.floorMod(x, cell), ly = Math.floorMod(y, cell);
        int dx = Math.min(lx, cell - 1 - lx), dy = Math.min(ly, cell - 1 - ly);
        int d = Math.min(dx, dy);
        if (d == 0) return -depth;
        if (d == 1) return -depth * 0.55f;
        if (d == 2) return -depth * 0.2f;
        // A soft highlight on the top-left of each panel gives it relief.
        if (lx == 3 || ly == 3) return depth * 0.35f;
        return 0f;
    }

    /** Scattered rivets on a cell grid. */
    private static float rivets(int x, int y, int cell, int inset, float strength) {
        int lx = Math.floorMod(x, cell), ly = Math.floorMod(y, cell);
        float best = 0f;
        for (int sx = 0; sx < 2; sx++) {
            for (int sy = 0; sy < 2; sy++) {
                float cx = sx == 0 ? inset : cell - inset;
                float cy = sy == 0 ? inset : cell - inset;
                float d = (float) Math.hypot(lx - cx, ly - cy);
                if (d < 2.6f) {
                    // Lit from the upper left.
                    float shade = (float) ((cx - lx) + (cy - ly)) / 5f;
                    best = strength * (1f - d / 2.6f) * (0.35f + shade);
                }
            }
        }
        return best;
    }

    // -------------------------------------------------------------- materials

    private static int[] wallTech() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float grain = fbm(x / (float) SIZE, y / (float) SIZE, 16, 4, 3) - 0.5f;
                float v = 0.34f + grain * 0.16f;
                v += bevel(x, y, 64, 0.13f);
                v += rivets(x, y, 64, 8, 0.16f);
                // Recessed strip down the middle of each panel.
                int lx = Math.floorMod(x, 64);
                if (lx > 28 && lx < 36) v -= 0.07f;
                p[y * SIZE + x] = rgb(v * 1.02f, v * 1.0f, v * 1.06f);
            }
        }
        return p;
    }

    private static int[] wallPanel() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float grain = fbm(x / (float) SIZE, y / (float) SIZE, 24, 4, 11) - 0.5f;
                float v = 0.40f + grain * 0.13f;
                // Tall narrow panels with a horizontal band across the middle.
                v += bevel(x, y, 32, 0.10f);
                int ly = Math.floorMod(y, 128);
                if (ly > 60 && ly < 68) v -= 0.10f;
                if (ly == 60 || ly == 68) v += 0.10f;
                p[y * SIZE + x] = rgb(v * 1.0f, v * 0.99f, v * 0.94f);
            }
        }
        return p;
    }

    private static int[] wallRust() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, vv = y / (float) SIZE;
                float base = 0.30f + (fbm(u, vv, 12, 4, 23) - 0.5f) * 0.2f;
                float rustMask = fbm(u, vv, 6, 5, 47);
                // Rust creeps down from above, so bias the mask vertically.
                rustMask = Math.min(1f, Math.max(0f, (rustMask - 0.42f) * 3.2f));
                float r = base + rustMask * 0.30f;
                float g = base + rustMask * 0.11f;
                float b = base - rustMask * 0.06f;
                float bv = bevel(x, y, 64, 0.10f);
                p[y * SIZE + x] = rgb(r + bv, g + bv, b + bv);
            }
        }
        return p;
    }

    private static int[] floorMetal() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float grain = fbm(x / (float) SIZE, y / (float) SIZE, 32, 4, 5) - 0.5f;
                float v = 0.38f + grain * 0.12f;
                v += bevel(x, y, 128, 0.10f);
                // Diamond tread plate.
                int lx = Math.floorMod(x, 32), ly = Math.floorMod(y, 32);
                float d = Math.abs(lx - 16) / 16f + Math.abs(ly - 16) / 16f;
                if (d < 0.55f) v += 0.10f * (0.55f - d) / 0.55f + 0.03f;
                p[y * SIZE + x] = rgb(v, v * 1.0f, v * 1.03f);
            }
        }
        return p;
    }

    private static int[] floorGrate() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int lx = Math.floorMod(x, 32), ly = Math.floorMod(y, 32);
                boolean bar = lx < 9 || ly < 9;
                float grain = fbm(x / (float) SIZE, y / (float) SIZE, 32, 3, 9) - 0.5f;
                float v;
                if (bar) {
                    v = 0.42f + grain * 0.1f;
                    // Round the bars a little.
                    int e = Math.min(lx < 9 ? Math.min(lx, 8 - lx) : 9, ly < 9 ? Math.min(ly, 8 - ly) : 9);
                    if (e == 0) v -= 0.12f;
                } else {
                    // The holes show the dark underside.
                    v = 0.09f + grain * 0.05f;
                }
                p[y * SIZE + x] = rgb(v * 1.0f, v * 1.02f, v * 1.05f);
            }
        }
        return p;
    }

    private static int[] concrete() {
        int[] p = new int[SIZE * SIZE];
        Random rnd = new Random(17);
        float[] speck = new float[SIZE * SIZE];
        for (int i = 0; i < speck.length; i++) speck[i] = rnd.nextFloat();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, vv = y / (float) SIZE;
                float v = 0.44f + (fbm(u, vv, 8, 5, 31) - 0.5f) * 0.22f;
                v += (speck[y * SIZE + x] - 0.5f) * 0.05f;
                // Form-work seams every 128 units.
                if (Math.floorMod(x, 128) < 2 || Math.floorMod(y, 128) < 2) v -= 0.09f;
                p[y * SIZE + x] = rgb(v * 1.0f, v * 0.99f, v * 0.95f);
            }
        }
        return p;
    }

    private static int[] trim() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float grain = fbm(x / (float) SIZE, y / (float) SIZE, 32, 3, 61) - 0.5f;
                float v = 0.34f + grain * 0.1f;
                int ly = Math.floorMod(y, 32);
                if (ly < 3 || ly > 28) v += 0.12f;
                if (ly >= 12 && ly <= 19) v -= 0.10f;
                p[y * SIZE + x] = rgb(v * 1.12f, v * 0.95f, v * 0.62f);
            }
        }
        return p;
    }

    private static int[] trimLight() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int ly = Math.floorMod(y, 64);
                float glow = 1f - Math.min(1f, Math.abs(ly - 32) / 26f);
                glow = glow * glow;
                float flicker = fbm(x / (float) SIZE, y / (float) SIZE, 24, 3, 5) * 0.12f;
                float r = 0.30f + glow * 0.98f + flicker;
                float g = 0.24f + glow * 0.80f + flicker;
                float b = 0.16f + glow * 0.42f;
                p[y * SIZE + x] = rgb(r, g, b);
            }
        }
        return p;
    }

    private static int[] lava() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, vv = y / (float) SIZE;
                float t = fbm(u, vv, 6, 5, 77);
                // Cool crust floating on bright molten cracks.
                float crust = Math.max(0f, (t - 0.46f)) * 2.4f;
                float heat = 1f - Math.min(1f, crust);
                float r = 0.35f + heat * 1.25f;
                float g = 0.06f + heat * heat * 0.72f;
                float b = 0.03f + heat * heat * heat * 0.20f;
                p[y * SIZE + x] = rgb(r, g, b);
            }
        }
        return p;
    }

    private static int[] rock() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, vv = y / (float) SIZE;
                float v = 0.33f + (fbm(u, vv, 5, 6, 91) - 0.5f) * 0.34f;
                p[y * SIZE + x] = rgb(v * 1.02f, v * 0.96f, v * 0.88f);
            }
        }
        return p;
    }

    private static int[] metalDark() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float grain = fbm(x / (float) SIZE, y / (float) SIZE, 48, 4, 13) - 0.5f;
                float v = 0.20f + grain * 0.09f;
                v += bevel(x, y, 64, 0.07f);
                v += rivets(x, y, 64, 10, 0.10f);
                p[y * SIZE + x] = rgb(v * 0.96f, v * 1.0f, v * 1.12f);
            }
        }
        return p;
    }

    private static int[] hazard() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float grain = fbm(x / (float) SIZE, y / (float) SIZE, 32, 3, 19) - 0.5f;
                // Diagonal warning stripes with worn edges.
                int band = Math.floorMod(x + y, 64);
                boolean yellow = band < 32;
                float wear = Math.max(0f, fbm(x / (float) SIZE, y / (float) SIZE, 16, 4, 41) - 0.55f) * 2f;
                float v = 1f - wear;
                float r = (yellow ? 0.86f : 0.14f) * v + grain * 0.06f;
                float g = (yellow ? 0.68f : 0.13f) * v + grain * 0.06f;
                float b = (yellow ? 0.10f : 0.13f) * v + grain * 0.06f;
                p[y * SIZE + x] = rgb(r, g, b);
            }
        }
        return p;
    }

    private static int[] ceiling() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float grain = fbm(x / (float) SIZE, y / (float) SIZE, 20, 4, 29) - 0.5f;
                float v = 0.24f + grain * 0.1f;
                v += bevel(x, y, 128, 0.09f);
                // Exposed ribs.
                if (Math.floorMod(y, 42) < 5) v += 0.06f;
                p[y * SIZE + x] = rgb(v * 0.98f, v * 0.98f, v * 1.06f);
            }
        }
        return p;
    }

    private static int[] telepad() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float dx = (x - SIZE / 2f) / (SIZE / 2f);
                float dy = (y - SIZE / 2f) / (SIZE / 2f);
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                // Concentric rings that read as an energy pad.
                float ring = (float) Math.abs(Math.sin(d * 12.5f));
                float glow = Math.max(0f, 1f - d) * (0.35f + ring * 0.65f);
                float r = 0.16f + glow * 0.55f;
                float g = 0.06f + glow * 0.25f;
                float b = 0.24f + glow * 1.05f;
                p[y * SIZE + x] = rgb(r, g, b);
            }
        }
        return p;
    }

    private static int[] jumpPad() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float dx = (x - SIZE / 2f) / (SIZE / 2f);
                float dy = (y - SIZE / 2f) / (SIZE / 2f);
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                // Chevrons pointing outwards from the middle.
                float ang = (float) Math.atan2(dy, dx);
                float chev = (float) Math.abs(Math.sin(ang * 4f + d * 9f));
                float glow = Math.max(0f, 1f - d) * (0.3f + chev * 0.7f);
                float r = 0.08f + glow * 0.30f;
                float g = 0.20f + glow * 0.95f;
                float b = 0.18f + glow * 0.70f;
                p[y * SIZE + x] = rgb(r, g, b);
            }
        }
        return p;
    }

    /** Soft round particle sprite used for smoke, sparks and flares. */
    public static int[] particleSprite(int size) {
        int[] p = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x + 0.5f) / size * 2f - 1f;
                float dy = (y + 0.5f) / size * 2f - 1f;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float a = Math.max(0f, 1f - d);
                a = a * a;
                int ai = clamp255(a * 255f);
                p[y * size + x] = (ai << 24) | 0x00FFFFFF;
            }
        }
        return p;
    }
}
