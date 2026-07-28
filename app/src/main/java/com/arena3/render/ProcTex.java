package com.arena3.render;

import com.arena3.game.Tex;

/**
 * Generates every texture in the game procedurally as ARGB pixels — world
 * materials and the ones that clothe the fighters and their weapons. Nothing is
 * loaded from a file.
 *
 * <p>Each material is built in three tiers so it reads at every distance: large
 * panel structure that survives the mip chain, mid-scale detail like bolts,
 * vents and tread, and a fine grain on top. Wear, grime and edge highlights are
 * layered over that, which is what stops a procedural surface looking like flat
 * noise.
 *
 * <p>Pure Java on purpose — the same code feeds the GL texture arrays on the
 * device and the offline preview renderers used to check the art.
 */
public final class ProcTex {

    /** Edge length of a world material. */
    public static final int SIZE = 512;
    /** Edge length of a model material. */
    public static final int MODEL_SIZE = 256;

    // ---- model material layers ----
    public static final int MAT_ARMOR = 0;
    public static final int MAT_DARK_METAL = 1;
    public static final int MAT_MESH = 2;
    public static final int MAT_GLOW = 3;
    public static final int MAT_GUNMETAL = 4;
    public static final int MAT_GRIP = 5;
    public static final int MAT_ENERGY = 6;
    public static final int MAT_SHELL = 7;
    public static final int MAT_COUNT = 8;

    private ProcTex() {
    }

    /** Generates one world material as SIZE*SIZE ARGB pixels. */
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
            case Tex.SKY: return flat(SIZE, 0xFF0A0C12);
            case Tex.TELEPAD: return telepad();
            case Tex.JUMPPAD: return jumpPad();
            case Tex.METAL_DARK: return metalDark();
            case Tex.HAZARD: return hazard();
            case Tex.CEILING: return ceiling();
            default: return flat(SIZE, 0xFF808080);
        }
    }

    /** Generates one model material as MODEL_SIZE*MODEL_SIZE ARGB pixels. */
    public static int[] generateModel(int material) {
        switch (material) {
            case MAT_ARMOR: return armorPlate();
            case MAT_DARK_METAL: return darkMetal();
            case MAT_MESH: return weaveMesh();
            case MAT_GLOW: return glowStrip();
            case MAT_GUNMETAL: return gunmetal();
            case MAT_GRIP: return grip();
            case MAT_ENERGY: return energy();
            case MAT_SHELL: return shell();
            default: return flat(MODEL_SIZE, 0xFFA0A0A0);
        }
    }

    /**
     * Generates every world material, spreading the work across cores. At this
     * resolution doing it serially is a visible pause on a phone.
     */
    public static int[][] generateAllWorld() {
        return parallel(Tex.COUNT, ProcTex::generate);
    }

    public static int[][] generateAllModel() {
        return parallel(MAT_COUNT, ProcTex::generateModel);
    }

    private interface Generator {
        int[] make(int index);
    }

    private static int[][] parallel(int count, Generator generator) {
        int[][] out = new int[count][];
        int threads = Math.max(1, Math.min(count, Runtime.getRuntime().availableProcessors()));
        if (threads <= 1) {
            for (int i = 0; i < count; i++) out[i] = generator.make(i);
            return out;
        }
        Thread[] workers = new Thread[threads];
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                int i;
                while ((i = next.getAndIncrement()) < count) {
                    out[i] = generator.make(i);
                }
            }, "proctex-" + t);
            workers[t].start();
        }
        for (Thread w : workers) {
            try {
                w.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (int i = 0; i < count; i++) {
            if (out[i] == null) out[i] = generator.make(i);
        }
        return out;
    }

    // ------------------------------------------------------------------ noise

    /** Value noise on a tileable integer lattice. */
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

    /** Ridged noise — gives cracks, veins and streaks rather than clouds. */
    private static float ridged(float u, float v, int baseFreq, int octaves, int seed) {
        float sum = 0f, amp = 0.5f, total = 0f;
        int freq = baseFreq;
        for (int o = 0; o < octaves; o++) {
            float n = Math.abs(noise(u * freq, v * freq, freq, seed + o * 53) * 2f - 1f);
            sum += (1f - n) * amp;
            total += amp;
            amp *= 0.5f;
            freq *= 2;
        }
        return sum / total;
    }

    /** Cheap white noise, for aggregate and speckle. */
    private static float speck(int x, int y, int seed) {
        return hash(x, y, 1 << 20, seed);
    }

    // ------------------------------------------------------------- primitives

    private static int[] flat(int size, int argb) {
        int[] p = new int[size * size];
        java.util.Arrays.fill(p, argb);
        return p;
    }

    private static int rgb(float r, float g, float b) {
        return 0xFF000000 | (clamp255(r * 255f) << 16) | (clamp255(g * 255f) << 8) | clamp255(b * 255f);
    }

    private static int clamp255(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    /**
     * Panel relief for a cell grid: a dark recessed seam, a lit top-left chamfer
     * and a shaded bottom-right one. This is what gives flat walls their
     * geometry at a distance.
     */
    private static float panel(int x, int y, int cell, int seam, int chamfer, float depth) {
        int lx = Math.floorMod(x, cell), ly = Math.floorMod(y, cell);
        int dxLow = lx, dyLow = ly;
        int dxHigh = cell - 1 - lx, dyHigh = cell - 1 - ly;
        int d = Math.min(Math.min(dxLow, dxHigh), Math.min(dyLow, dyHigh));
        if (d < seam) return -depth;
        if (d < seam + chamfer) {
            // Light catches the top and left of each raised panel.
            boolean lit = (dxLow < dyLow ? dxLow : dyLow) == d
                    && (dxLow <= dxHigh || dyLow <= dyHigh);
            float t = 1f - (d - seam) / (float) chamfer;
            return (lit ? depth * 0.85f : -depth * 0.5f) * t;
        }
        return 0f;
    }

    /** A round bolt head with a lit rim and a shadow, at each cell corner. */
    private static float bolts(int x, int y, int cell, int inset, float radius, float strength) {
        int lx = Math.floorMod(x, cell), ly = Math.floorMod(y, cell);
        float best = 0f;
        for (int sx = 0; sx < 2; sx++) {
            for (int sy = 0; sy < 2; sy++) {
                float cx = sx == 0 ? inset : cell - inset;
                float cy = sy == 0 ? inset : cell - inset;
                float dx = lx - cx, dy = ly - cy;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > radius + 1.5f) continue;
                if (d > radius) {
                    // Contact shadow just outside the head.
                    best = Math.min(best, -strength * 0.7f * (1f - (d - radius) / 1.5f));
                } else {
                    // Dome shading, lit from the upper left.
                    float dome = (float) Math.sqrt(Math.max(0f, 1f - (d / radius) * (d / radius)));
                    float lit = (-dx - dy) / (radius * 1.6f);
                    best = strength * (0.25f + lit * 0.9f) * (0.4f + dome * 0.6f);
                }
            }
        }
        return best;
    }

    /** Horizontal louvre slots, for vents set into panels. */
    private static float vent(int x, int y, int cell, int inset, int slots, float depth) {
        int lx = Math.floorMod(x, cell), ly = Math.floorMod(y, cell);
        int w = cell - inset * 2;
        if (lx < inset || lx >= cell - inset) return 0f;
        int vh = w / 2;
        int top = (cell - vh) / 2;
        if (ly < top || ly >= top + vh) return 0f;
        int band = vh / slots;
        if (band <= 1) return 0f;
        int within = (ly - top) % band;
        if (within < band / 2) return -depth;
        if (within == band / 2) return depth * 0.6f;
        return -depth * 0.15f;
    }

    /** Grime that bleeds downwards from a seam, strongest just under it. */
    private static float streaks(float u, float v, int cell, int seed, float strength) {
        float cellV = (v * SIZE) % cell / (float) cell;
        float mask = fbm(u * 3f, v * 0.35f, 24, 4, seed);
        mask = smoothstep(0.48f, 0.85f, mask);
        // Fades out as it runs down from the top of the cell.
        return -strength * mask * (1f - smoothstep(0f, 0.75f, cellV));
    }

    // -------------------------------------------------------- world materials

    private static int[] wallTech() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                float base = 0.40f;
                float grain = (fbm(u, v, 48, 4, 3) - 0.5f) * 0.10f;
                float brushed = (noise(u * 4f, v * 220f, 220, 17) - 0.5f) * 0.05f;

                float shape = panel(x, y, 256, 4, 7, 0.20f);
                // A second, smaller panel inset inside the big one.
                shape += panel(x, y, 256, 40, 4, 0.09f) * 0.5f;
                shape += bolts(x, y, 256, 22, 5.5f, 0.20f);
                shape += vent(x, y, 256, 88, 9, 0.16f);

                float dirt = streaks(u, v, 256, 91, 0.10f);
                float value = base + grain + brushed + shape + dirt;
                // Cool steel.
                p[y * SIZE + x] = rgb(value * 0.98f, value * 1.00f, value * 1.09f);
            }
        }
        return p;
    }

    private static int[] wallPanel() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                float base = 0.44f;
                float grain = (fbm(u, v, 64, 4, 11) - 0.5f) * 0.08f;

                // Tall ribs: vertical channels with rounded tops.
                int lx = Math.floorMod(x, 64);
                float rib = (float) Math.sin(lx / 64f * Math.PI) * 0.13f - 0.05f;
                if (lx < 3) rib = -0.16f;

                // A heavy horizontal band splits the wall into courses.
                int ly = Math.floorMod(y, 256);
                float band = 0f;
                if (ly > 116 && ly < 140) band = -0.13f;
                else if (ly == 116 || ly == 140) band = 0.16f;
                else if (ly > 140 && ly < 148) band = 0.05f;

                float boltRow = (ly > 118 && ly < 138) ? bolts(x, y, 128, 64, 5.5f, 0.22f) : 0f;
                float dirt = streaks(u, v, 256, 47, 0.11f);

                float value = base + grain + rib + band + boltRow + dirt;
                p[y * SIZE + x] = rgb(value * 1.00f, value * 0.985f, value * 0.93f);
            }
        }
        return p;
    }

    private static int[] wallRust() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                float base = 0.33f + (fbm(u, v, 32, 4, 23) - 0.5f) * 0.12f;
                base += panel(x, y, 256, 4, 6, 0.14f);
                base += bolts(x, y, 256, 26, 5.5f, 0.16f);

                // Rust blooms, with pitting inside the worst of them.
                float bloom = fbm(u, v, 8, 5, 47);
                float rust = smoothstep(0.44f, 0.68f, bloom);
                float pit = smoothstep(0.62f, 0.9f, fbm(u, v, 90, 3, 7)) * rust;

                // Rust bleeds downwards from wherever it takes hold.
                float bleed = smoothstep(0.5f, 0.9f, fbm(u * 2.5f, v * 0.3f, 20, 4, 61)) * 0.5f;
                rust = clamp01(rust + bleed * 0.6f);

                float r = base + rust * 0.34f - pit * 0.14f;
                float g = base + rust * 0.11f - pit * 0.10f;
                float b = base - rust * 0.10f - pit * 0.06f;
                p[y * SIZE + x] = rgb(r, g, b);
            }
        }
        return p;
    }

    private static int[] floorMetal() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                float base = 0.42f + (fbm(u, v, 48, 4, 5) - 0.5f) * 0.08f;
                base += panel(x, y, 256, 3, 5, 0.16f);

                // Diamond tread: two crossed sets of raised lozenges.
                float tread = 0f;
                for (int pass = 0; pass < 2; pass++) {
                    int px = pass == 0 ? x : x + 32;
                    int py = pass == 0 ? y : y + 32;
                    int cx = Math.floorMod(px, 64) - 32;
                    int cy = Math.floorMod(py, 64) - 32;
                    // Rotated box distance makes a lozenge.
                    float d = Math.abs(cx + cy) / 30f + Math.abs(cx - cy) / 11f;
                    if (d < 1f) {
                        float h = (float) Math.sqrt(1f - d);
                        // Lit on the leading edge, shadowed behind.
                        float lit = -(cx + cy) / 40f;
                        tread = Math.max(tread, 0.20f * h + lit * 0.09f);
                    }
                }
                // Traffic wears the tread down in patches.
                float wear = smoothstep(0.42f, 0.75f, fbm(u, v, 6, 4, 71));
                tread *= 1f - wear * 0.55f;

                float value = base + tread - wear * 0.05f;
                p[y * SIZE + x] = rgb(value * 0.99f, value * 1.00f, value * 1.05f);
            }
        }
        return p;
    }

    private static int[] floorGrate() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                int lx = Math.floorMod(x, 48), ly = Math.floorMod(y, 48);
                int barX = 13, barY = 13;
                boolean onX = lx < barX, onY = ly < barY;

                float value;
                if (onX || onY) {
                    // Bars: rounded, with a bright top edge.
                    float across = onX && onY
                            ? Math.min(Math.min(lx, barX - 1 - lx), Math.min(ly, barY - 1 - ly))
                            : (onX ? Math.min(lx, barX - 1 - lx) : Math.min(ly, barY - 1 - ly));
                    float round = across / (barX * 0.5f);
                    value = 0.34f + 0.20f * round;
                    if (across == 0) value = 0.20f;
                    // The crossing points sit slightly proud.
                    if (onX && onY) value += 0.05f;
                    value += (fbm(u, v, 64, 3, 9) - 0.5f) * 0.06f;
                } else {
                    // Voids: dark, with a hint of what is underneath.
                    float depth = fbm(u, v, 24, 3, 33);
                    value = 0.055f + depth * 0.045f;
                    // Ambient occlusion right at the hole's edge.
                    float edge = Math.min(Math.min(lx - barX, 47 - lx), Math.min(ly - barY, 47 - ly));
                    value *= 0.55f + 0.45f * smoothstep(0f, 5f, edge);
                }
                p[y * SIZE + x] = rgb(value * 0.98f, value * 1.02f, value * 1.08f);
            }
        }
        return p;
    }

    private static int[] concrete() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                float base = 0.47f + (fbm(u, v, 12, 5, 31) - 0.5f) * 0.16f;
                // Aggregate: hard-edged speckle of two sizes.
                base += (speck(x / 2, y / 2, 5) - 0.5f) * 0.07f;
                base += (speck(x, y, 9) - 0.5f) * 0.035f;

                // Form-work seams every 256, with tie-rod holes.
                int lx = Math.floorMod(x, 256), ly = Math.floorMod(y, 256);
                if (lx < 3 || ly < 3) base -= 0.13f;
                else if (lx < 5 || ly < 5) base += 0.06f;
                float tie = bolts(x, y, 256, 64, 5f, -0.16f);
                base += tie;

                // Cracks, and water staining that pools low.
                float crack = smoothstep(0.86f, 0.98f, ridged(u, v, 7, 5, 77));
                base -= crack * 0.22f;
                float stain = smoothstep(0.55f, 0.9f, fbm(u * 1.5f, v * 0.5f, 10, 4, 91));
                base -= stain * 0.09f;

                p[y * SIZE + x] = rgb(base * 1.00f, base * 0.985f, base * 0.945f);
            }
        }
        return p;
    }

    private static int[] trim() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                int ly = Math.floorMod(y, 64);
                float value = 0.38f + (fbm(u, v, 64, 3, 61) - 0.5f) * 0.07f;
                // A chamfered band: bright top edge, dark channel, shaded bottom.
                if (ly < 3) value += 0.26f;
                else if (ly < 8) value += 0.13f;
                else if (ly > 60) value -= 0.18f;
                else if (ly > 26 && ly < 38) value -= 0.15f;
                else if (ly == 26 || ly == 38) value += 0.16f;
                value += bolts(x, y, 64, 16, 4f, 0.16f);
                // Warm brass.
                p[y * SIZE + x] = rgb(value * 1.20f, value * 0.97f, value * 0.58f);
            }
        }
        return p;
    }

    private static int[] trimLight() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                int ly = Math.floorMod(y, 128);
                int lx = Math.floorMod(x, 128);

                // Housing at the top and bottom, glowing element in the middle.
                float dist = Math.abs(ly - 64) / 64f;
                float glow = 1f - smoothstep(0.15f, 0.62f, dist);
                glow *= glow;
                float housing = smoothstep(0.62f, 0.72f, dist);

                // Segment breaks along the strip.
                float segment = lx < 6 ? 0f : 1f;
                glow *= 0.25f + 0.75f * segment;

                float flicker = (fbm(u, v, 40, 3, 5) - 0.5f) * 0.10f;
                // Kept below white so the fixture still reads amber once the
                // glow multiplier is applied.
                float metal = 0.20f + housing * 0.15f + flicker;
                float r = metal + glow * 0.62f;
                float g = metal + glow * 0.42f;
                float b = metal + glow * 0.14f;
                p[y * SIZE + x] = rgb(r, g, b);
            }
        }
        return p;
    }

    private static int[] lava() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                // Crust plates floating on molten channels.
                float flow = fbm(u, v, 5, 5, 77);
                float channel = ridged(u * 1.2f, v * 1.2f, 6, 4, 13);
                float heat = clamp01(smoothstep(0.52f, 0.86f, channel) + smoothstep(0.58f, 0.30f, flow) * 0.5f);

                float crustDetail = (fbm(u, v, 60, 3, 41) - 0.5f) * 0.10f;
                float crust = 0.16f + crustDetail;

                float r = crust + heat * 1.55f;
                float g = crust * 0.65f + heat * heat * 0.80f;
                float b = crust * 0.55f + heat * heat * heat * 0.22f;
                p[y * SIZE + x] = rgb(r, g, b);
            }
        }
        return p;
    }

    private static int[] rock() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                float value = 0.34f + (fbm(u, v, 7, 6, 91) - 0.5f) * 0.30f;
                // Fractured facets.
                value -= smoothstep(0.80f, 0.97f, ridged(u, v, 9, 4, 23)) * 0.16f;
                value += (speck(x, y, 3) - 0.5f) * 0.03f;
                p[y * SIZE + x] = rgb(value * 1.03f, value * 0.96f, value * 0.87f);
            }
        }
        return p;
    }

    private static int[] metalDark() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                float value = 0.23f + (fbm(u, v, 64, 4, 13) - 0.5f) * 0.07f;
                value += (noise(u * 3f, v * 260f, 260, 29) - 0.5f) * 0.05f;
                value += panel(x, y, 256, 4, 6, 0.13f);
                value += bolts(x, y, 256, 30, 5.5f, 0.14f);
                // Scuffs where edges get knocked.
                value += smoothstep(0.72f, 0.95f, fbm(u, v, 26, 4, 55)) * 0.10f;
                p[y * SIZE + x] = rgb(value * 0.94f, value * 1.00f, value * 1.16f);
            }
        }
        return p;
    }

    private static int[] hazard() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                int band = Math.floorMod(x + y, 96);
                boolean yellow = band < 48;
                // Crisp stripe edges, softened by a pixel.
                float edge = Math.min(Math.min(band, 47 - band), Math.min(band - 48, 95 - band));
                float grain = (fbm(u, v, 48, 3, 19) - 0.5f) * 0.08f;

                // Paint wears off the raised metal underneath.
                float wear = smoothstep(0.52f, 0.86f, fbm(u, v, 18, 4, 41));
                float metal = 0.34f + grain;

                float r = yellow ? 0.92f : 0.12f;
                float g = yellow ? 0.72f : 0.11f;
                float b = yellow ? 0.09f : 0.12f;
                r = r * (1f - wear) + metal * wear + grain;
                g = g * (1f - wear) + metal * wear + grain;
                b = b * (1f - wear) + metal * wear + grain;
                if (edge < 1f) {
                    r *= 0.8f;
                    g *= 0.8f;
                    b *= 0.8f;
                }
                p[y * SIZE + x] = rgb(r, g, b);
            }
        }
        return p;
    }

    private static int[] ceiling() {
        int[] p = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float u = x / (float) SIZE, v = y / (float) SIZE;
                float value = 0.26f + (fbm(u, v, 40, 4, 29) - 0.5f) * 0.07f;
                value += panel(x, y, 256, 4, 6, 0.14f);
                // Structural ribs running one way.
                int ly = Math.floorMod(y, 64);
                if (ly < 8) value += 0.10f;
                else if (ly < 11) value -= 0.09f;
                value += bolts(x, y, 256, 40, 5f, 0.12f);
                p[y * SIZE + x] = rgb(value * 0.97f, value * 0.98f, value * 1.10f);
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
                float ang = (float) Math.atan2(dy, dx);

                // Metal collar around a ringed energy field.
                float collar = smoothstep(0.82f, 0.88f, d) * (1f - smoothstep(0.97f, 1.0f, d));
                float ring = (float) Math.abs(Math.sin(d * 22f - 1.2f));
                float spokes = 0.5f + 0.5f * (float) Math.cos(ang * 8f);
                float field = (1f - smoothstep(0.35f, 0.86f, d)) * (0.35f + ring * 0.5f + spokes * 0.2f);

                float metal = 0.20f + collar * 0.30f;
                float r = metal + field * 0.55f;
                float g = metal + field * 0.22f;
                float b = metal + field * 1.35f;
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

                // Four chevrons pointing outwards, on a metal deck.
                float ax = Math.abs(dx), ay = Math.abs(dy);
                float along = Math.max(ax, ay);
                float across = Math.min(ax, ay);
                float chevron = 0f;
                for (int i = 0; i < 3; i++) {
                    float centre = 0.30f + i * 0.22f;
                    float band = Math.abs(along - across * 0.55f - centre);
                    chevron = Math.max(chevron, (1f - smoothstep(0f, 0.075f, band)) * (1f - i * 0.22f));
                }
                chevron *= 1f - smoothstep(0.72f, 0.95f, d);

                float collar = smoothstep(0.80f, 0.86f, d) * (1f - smoothstep(0.96f, 1.0f, d));
                float metal = 0.19f + collar * 0.26f;
                float r = metal + chevron * 0.22f;
                float g = metal + chevron * 1.25f;
                float b = metal + chevron * 0.82f;
                p[y * SIZE + x] = rgb(r, g, b);
            }
        }
        return p;
    }

    // -------------------------------------------------------- model materials

    private static final int M = MODEL_SIZE;

    private static int[] armorPlate() {
        int[] p = new int[M * M];
        for (int y = 0; y < M; y++) {
            for (int x = 0; x < M; x++) {
                float u = x / (float) M, v = y / (float) M;
                float value = 0.72f + (fbm(u, v, 40, 4, 3) - 0.5f) * 0.10f;
                // Brushed along one axis.
                value += (noise(u * 5f, v * 180f, 180, 21) - 0.5f) * 0.06f;
                // Plate divisions, smaller than the world's since models are small.
                value += panel(x, y, 64, 2, 3, 0.16f);
                value += bolts(x, y, 64, 10, 3.2f, 0.18f);
                // Scratches down to bare metal on the raised areas.
                float scratch = smoothstep(0.80f, 0.96f, ridged(u, v, 30, 3, 47));
                value += scratch * 0.22f;
                p[y * M + x] = rgb(value, value, value);
            }
        }
        return p;
    }

    private static int[] darkMetal() {
        int[] p = new int[M * M];
        for (int y = 0; y < M; y++) {
            for (int x = 0; x < M; x++) {
                float u = x / (float) M, v = y / (float) M;
                float value = 0.55f + (fbm(u, v, 56, 4, 13) - 0.5f) * 0.10f;
                value += panel(x, y, 32, 1, 2, 0.14f);
                value += (noise(u * 200f, v * 6f, 200, 31) - 0.5f) * 0.07f;
                p[y * M + x] = rgb(value * 0.94f, value, value * 1.10f);
            }
        }
        return p;
    }

    private static int[] weaveMesh() {
        int[] p = new int[M * M];
        for (int y = 0; y < M; y++) {
            for (int x = 0; x < M; x++) {
                // Over-under weave: alternate which thread is on top per cell.
                int cell = 16;
                int cx = Math.floorMod(x, cell), cy = Math.floorMod(y, cell);
                boolean warpOnTop = ((x / cell) + (y / cell)) % 2 == 0;
                float thread = warpOnTop
                        ? (float) Math.sin(cx / (float) cell * Math.PI)
                        : (float) Math.sin(cy / (float) cell * Math.PI);
                float value = 0.42f + thread * 0.30f;
                // Gaps between threads read as shadow.
                float gap = Math.min(cx, cy) < 1 ? -0.14f : 0f;
                value += gap + (fbm(x / (float) M, y / (float) M, 80, 3, 61) - 0.5f) * 0.06f;
                p[y * M + x] = rgb(value * 0.95f, value * 0.97f, value * 1.05f);
            }
        }
        return p;
    }

    private static int[] glowStrip() {
        int[] p = new int[M * M];
        for (int y = 0; y < M; y++) {
            for (int x = 0; x < M; x++) {
                float v = y / (float) M;
                // Scanlines across a bright field, with a dark bezel at the edges.
                float scan = 0.72f + 0.28f * (float) Math.cos(y * 0.9f);
                float bezel = smoothstep(0.0f, 0.10f, v) * (1f - smoothstep(0.90f, 1.0f, v));
                float flicker = 0.9f + 0.1f * noise(x / (float) M * 20f, v * 6f, 20, 7);
                float value = (0.30f + scan * 0.95f * bezel) * flicker;
                p[y * M + x] = rgb(value, value, value);
            }
        }
        return p;
    }

    private static int[] gunmetal() {
        int[] p = new int[M * M];
        for (int y = 0; y < M; y++) {
            for (int x = 0; x < M; x++) {
                float u = x / (float) M, v = y / (float) M;
                float value = 0.62f + (fbm(u, v, 48, 4, 17) - 0.5f) * 0.09f;
                value += (noise(u * 240f, v * 5f, 240, 37) - 0.5f) * 0.08f;
                // Machined grooves along the barrel axis.
                int lx = Math.floorMod(x, 24);
                if (lx < 2) value -= 0.10f;
                else if (lx < 4) value += 0.08f;
                // Bare-metal wear on the edges.
                value += smoothstep(0.78f, 0.95f, ridged(u, v, 22, 3, 5)) * 0.24f;
                p[y * M + x] = rgb(value, value, value);
            }
        }
        return p;
    }

    private static int[] grip() {
        int[] p = new int[M * M];
        for (int y = 0; y < M; y++) {
            for (int x = 0; x < M; x++) {
                // Ribbed polymer: rounded ridges with deep valleys.
                int cell = 12;
                int ly = Math.floorMod(y, cell);
                float ridge = (float) Math.sin(ly / (float) cell * Math.PI);
                float value = 0.34f + ridge * ridge * 0.30f;
                if (ly < 1) value -= 0.10f;
                value += (fbm(x / (float) M, y / (float) M, 96, 3, 71) - 0.5f) * 0.07f;
                p[y * M + x] = rgb(value, value * 0.98f, value * 0.96f);
            }
        }
        return p;
    }

    private static int[] energy() {
        int[] p = new int[M * M];
        for (int y = 0; y < M; y++) {
            for (int x = 0; x < M; x++) {
                float u = x / (float) M, v = y / (float) M;
                // Turbulent core with bright filaments.
                float core = fbm(u, v, 6, 4, 83);
                float filament = smoothstep(0.62f, 0.95f, ridged(u, v, 10, 4, 29));
                float value = 0.55f + core * 0.35f + filament * 0.75f;
                p[y * M + x] = rgb(value, value, value);
            }
        }
        return p;
    }

    private static int[] shell() {
        int[] p = new int[M * M];
        for (int y = 0; y < M; y++) {
            for (int x = 0; x < M; x++) {
                float u = x / (float) M, v = y / (float) M;
                // Clean painted shell, with a seam and a soft sheen.
                float value = 0.85f + (fbm(u, v, 60, 3, 43) - 0.5f) * 0.05f;
                value += panel(x, y, 128, 2, 3, 0.10f);
                float sheen = 1f - smoothstep(0.0f, 0.55f, Math.abs(v - 0.28f));
                value += sheen * 0.16f;
                p[y * M + x] = rgb(value, value, value);
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
                p[y * size + x] = (clamp255(a * 255f) << 24) | 0x00FFFFFF;
            }
        }
        return p;
    }
}
