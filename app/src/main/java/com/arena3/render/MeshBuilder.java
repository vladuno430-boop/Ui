package com.arena3.render;

/**
 * Accumulates textured, coloured boxes into a vertex/index pair. Everything the
 * game draws that is not level geometry — fighters, weapons, pickups, gibs — is
 * built out of these.
 *
 * <p>Every primitive is mapped onto one of the model materials in
 * {@link ProcTex}, so a fighter is plated metal over a woven undersuit rather
 * than flat-shaded boxes. Texture coordinates are projected from the surface's
 * dominant axis and divided by the material's tile size, which keeps the detail
 * the same scale across parts of very different proportions.
 */
public final class MeshBuilder {

    /** px py pz | nx ny nz | u v | r g b | material */
    public static final int VERTEX_FLOATS = 12;

    private float[] verts = new float[512 * VERTEX_FLOATS];
    private int vertexCount;
    private int[] indices = new int[1024];
    private int indexCount;

    private int material = ProcTex.MAT_ARMOR;
    private float tileSize = 24f;

    public static final class MeshData {
        public final float[] verts;
        public final int[] indices;
        public final int vertexCount;
        public final int indexCount;

        MeshData(float[] v, int vc, int[] i, int ic) {
            verts = v;
            vertexCount = vc;
            indices = i;
            indexCount = ic;
        }
    }

    public MeshBuilder reset() {
        vertexCount = 0;
        indexCount = 0;
        material = ProcTex.MAT_ARMOR;
        tileSize = 24f;
        return this;
    }

    /**
     * Selects the material for everything added next.
     *
     * @param tile world units covered by one repeat of the texture
     */
    public MeshBuilder material(int material, float tile) {
        this.material = material;
        this.tileSize = tile;
        return this;
    }

    /** Axis-aligned box spanning the given corners. */
    public MeshBuilder box(float x0, float y0, float z0, float x1, float y1, float z1,
                           float r, float g, float b) {
        float ax0 = Math.min(x0, x1), ax1 = Math.max(x0, x1);
        float ay0 = Math.min(y0, y1), ay1 = Math.max(y0, y1);
        float az0 = Math.min(z0, z1), az1 = Math.max(z0, z1);

        // Per-face shading so the silhouette reads even before lighting.
        face(ax1, ay0, az0, ax1, ay1, az0, ax1, ay1, az1, ax1, ay0, az1, 1, 0, 0, r, g, b, 1.00f);
        face(ax0, ay1, az0, ax0, ay0, az0, ax0, ay0, az1, ax0, ay1, az1, -1, 0, 0, r, g, b, 0.80f);
        face(ax1, ay1, az0, ax0, ay1, az0, ax0, ay1, az1, ax1, ay1, az1, 0, 1, 0, r, g, b, 0.92f);
        face(ax0, ay0, az0, ax1, ay0, az0, ax1, ay0, az1, ax0, ay0, az1, 0, -1, 0, r, g, b, 0.86f);
        face(ax0, ay0, az1, ax1, ay0, az1, ax1, ay1, az1, ax0, ay1, az1, 0, 0, 1, r, g, b, 1.10f);
        face(ax0, ay1, az0, ax1, ay1, az0, ax1, ay0, az0, ax0, ay0, az0, 0, 0, -1, r, g, b, 0.55f);
        return this;
    }

    public MeshBuilder boxCentered(float cx, float cy, float cz, float hx, float hy, float hz,
                                   float r, float g, float b) {
        return box(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz, r, g, b);
    }

    /**
     * Box tapered along X: the +X end is scaled by {@code taper}. Gives weapons
     * and limbs a shape without needing real modelling.
     */
    public MeshBuilder taperedBox(float x0, float x1, float cy, float cz, float hy, float hz,
                                  float taper, float r, float g, float b) {
        float ty = hy * taper, tz = hz * taper;
        quad(x0, cy - hy, cz - hz, x1, cy - ty, cz - tz, x1, cy - ty, cz + tz, x0, cy - hy, cz + hz,
                r, g, b, 0.86f);
        quad(x0, cy + hy, cz + hz, x1, cy + ty, cz + tz, x1, cy + ty, cz - tz, x0, cy + hy, cz - hz,
                r, g, b, 0.92f);
        quad(x0, cy - hy, cz + hz, x1, cy - ty, cz + tz, x1, cy + ty, cz + tz, x0, cy + hy, cz + hz,
                r, g, b, 1.08f);
        quad(x0, cy + hy, cz - hz, x1, cy + ty, cz - tz, x1, cy - ty, cz - tz, x0, cy - hy, cz - hz,
                r, g, b, 0.58f);
        quad(x1, cy - ty, cz - tz, x1, cy + ty, cz - tz, x1, cy + ty, cz + tz, x1, cy - ty, cz + tz,
                r, g, b, 1.0f);
        quad(x0, cy + hy, cz - hz, x0, cy - hy, cz - hz, x0, cy - hy, cz + hz, x0, cy + hy, cz + hz,
                r, g, b, 0.75f);
        return this;
    }

    /** Low-poly sphere, used for powerups and energy balls. */
    public MeshBuilder sphere(float cx, float cy, float cz, float radius, int rings, int segments,
                              float r, float g, float b) {
        int base = vertexCount;
        for (int i = 0; i <= rings; i++) {
            double phi = Math.PI * i / rings;
            float z = (float) Math.cos(phi), rr = (float) Math.sin(phi);
            for (int j = 0; j <= segments; j++) {
                double theta = 2 * Math.PI * j / segments;
                float x = (float) (rr * Math.cos(theta));
                float y = (float) (rr * Math.sin(theta));
                // Spherical mapping, scaled so the tiling matches flat surfaces.
                float u = (float) (j / (double) segments) * (radius * 6.2831855f / tileSize);
                float v = (float) (i / (double) rings) * (radius * 3.14159f / tileSize);
                addVertex(cx + x * radius, cy + y * radius, cz + z * radius, x, y, z, u, v, r, g, b);
            }
        }
        for (int i = 0; i < rings; i++) {
            for (int j = 0; j < segments; j++) {
                int a = base + i * (segments + 1) + j;
                int c = a + segments + 1;
                addTri(a, c, a + 1);
                addTri(a + 1, c, c + 1);
            }
        }
        return this;
    }

    /** Flat-shaded quad with an explicit normal, UVs projected from its plane. */
    private void face(float x0, float y0, float z0, float x1, float y1, float z1,
                      float x2, float y2, float z2, float x3, float y3, float z3,
                      float nx, float ny, float nz, float r, float g, float b, float shade) {
        int base = vertexCount;
        int axis = dominantAxis(nx, ny, nz);
        addProjected(x0, y0, z0, nx, ny, nz, axis, r * shade, g * shade, b * shade);
        addProjected(x1, y1, z1, nx, ny, nz, axis, r * shade, g * shade, b * shade);
        addProjected(x2, y2, z2, nx, ny, nz, axis, r * shade, g * shade, b * shade);
        addProjected(x3, y3, z3, nx, ny, nz, axis, r * shade, g * shade, b * shade);
        addTri(base, base + 1, base + 2);
        addTri(base, base + 2, base + 3);
    }

    /** Quad whose normal is derived from its corners. */
    private void quad(float x0, float y0, float z0, float x1, float y1, float z1,
                      float x2, float y2, float z2, float x3, float y3, float z3,
                      float r, float g, float b, float shade) {
        float ux = x1 - x0, uy = y1 - y0, uz = z1 - z0;
        float vx = x3 - x0, vy = y3 - y0, vz = z3 - z0;
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        face(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, nx, ny, nz, r, g, b, shade);
    }

    private static int dominantAxis(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (az >= ax && az >= ay) return 2;
        return ax >= ay ? 0 : 1;
    }

    /** Adds a vertex with UVs projected along the surface's dominant axis. */
    private void addProjected(float x, float y, float z, float nx, float ny, float nz, int axis,
                              float r, float g, float b) {
        float u, v;
        switch (axis) {
            case 0:
                u = y;
                v = -z;
                break;
            case 1:
                u = x;
                v = -z;
                break;
            default:
                u = x;
                v = -y;
                break;
        }
        addVertex(x, y, z, nx, ny, nz, u / tileSize, v / tileSize, r, g, b);
    }

    private void addVertex(float x, float y, float z, float nx, float ny, float nz,
                           float u, float v, float r, float g, float b) {
        if ((vertexCount + 1) * VERTEX_FLOATS > verts.length) {
            verts = java.util.Arrays.copyOf(verts, verts.length * 2);
        }
        int o = vertexCount * VERTEX_FLOATS;
        verts[o] = x;
        verts[o + 1] = y;
        verts[o + 2] = z;
        verts[o + 3] = nx;
        verts[o + 4] = ny;
        verts[o + 5] = nz;
        verts[o + 6] = u;
        verts[o + 7] = v;
        verts[o + 8] = r;
        verts[o + 9] = g;
        verts[o + 10] = b;
        verts[o + 11] = material;
        vertexCount++;
    }

    private void addTri(int a, int b, int c) {
        if (indexCount + 3 > indices.length) {
            indices = java.util.Arrays.copyOf(indices, indices.length * 2);
        }
        indices[indexCount++] = a;
        indices[indexCount++] = b;
        indices[indexCount++] = c;
    }

    public MeshData build() {
        return new MeshData(java.util.Arrays.copyOf(verts, vertexCount * VERTEX_FLOATS), vertexCount,
                java.util.Arrays.copyOf(indices, indexCount), indexCount);
    }
}
