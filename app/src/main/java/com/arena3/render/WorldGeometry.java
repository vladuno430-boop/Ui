package com.arena3.render;

import com.arena3.core.Vec3;
import com.arena3.game.Brush;
import com.arena3.game.CollisionWorld;
import com.arena3.game.Contents;
import com.arena3.game.MapDef;
import com.arena3.game.Trace;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the brush soup into drawable triangles with lighting baked into the
 * vertices.
 *
 * <p>Each brush plane is converted into a polygon by clipping a large quad
 * against the brush's other planes — the standard way to get faces out of a
 * convex solid. Faces buried inside other brushes are dropped, the survivors are
 * diced on a grid so that per-vertex lighting has somewhere to live, and each
 * vertex is lit by the map's static lights with a shadow ray per light.
 *
 * <p>No GL here: the output is plain float arrays, which the device uploads into
 * a vertex buffer and the offline preview rasterises directly.
 */
public final class WorldGeometry {

    /** px py pz | nx ny nz | u v | r g b | textureLayer */
    public static final int VERTEX_FLOATS = 12;
    /** Edge length of the lighting grid, in world units. */
    private static final float LIGHT_GRID = 128f;
    private static final int MAX_POLY = 64;

    public float[] verts = new float[4096 * VERTEX_FLOATS];
    public int vertexCount;
    public int[] indices = new int[8192];
    public int indexCount;

    /** Sky faces are drawn by a separate pass, so they are kept apart. */
    public float[] skyVerts = new float[256 * VERTEX_FLOATS];
    public int skyVertexCount;
    public int[] skyIndices = new int[512];
    public int skyIndexCount;

    public final Vec3 boundsMin = new Vec3();
    public final Vec3 boundsMax = new Vec3();

    // working buffers
    private final float[] polyIn = new float[MAX_POLY * 3];
    private final float[] polyOut = new float[MAX_POLY * 3];
    private final float[] cell2d = new float[MAX_POLY * 2];
    private final float[] cellTmp = new float[MAX_POLY * 2];
    private final Vec3 normal = new Vec3();
    private final Vec3 vertex = new Vec3();
    private final Vec3 lightDir = new Vec3();
    private final Vec3 rayStart = new Vec3();
    private final Trace trace = new Trace();

    private MapDef map;
    private CollisionWorld world;
    private List<MapDef.Light> lights;

    public void build(MapDef map, CollisionWorld world) {
        this.map = map;
        this.world = world;
        this.lights = map.lights;
        vertexCount = 0;
        indexCount = 0;
        skyVertexCount = 0;
        skyIndexCount = 0;
        boundsMin.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        boundsMax.set(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);

        for (Brush brush : map.brushes) {
            if ((brush.contents & Contents.PLAYER_CLIP) != 0) continue;
            for (int face = 0; face < brush.planeCount(); face++) {
                if ((brush.surfaceFlags[face] & Contents.SURF_NODRAW) != 0) continue;
                emitFace(brush, face);
            }
        }
    }

    public int triangleCount() {
        return indexCount / 3;
    }

    // ------------------------------------------------------------------ faces

    private void emitFace(Brush brush, int face) {
        int o = face * 4;
        float nx = brush.planes[o], ny = brush.planes[o + 1], nz = brush.planes[o + 2];
        float dist = brush.planes[o + 3];
        normal.set(nx, ny, nz);

        int count = buildPolygon(brush, face);
        if (count < 3) return;

        // Drop faces that are sealed inside another solid — the underside of a
        // floor slab, the shared wall between two boxes, and so on.
        if (isBuried(count)) return;

        boolean isSky = (brush.surfaceFlags[face] & Contents.SURF_SKY) != 0;
        boolean glow = (brush.surfaceFlags[face] & Contents.SURF_GLOW) != 0;
        int layer = brush.texture[face];
        float scale = brush.texScale[face];

        int axis = dominantAxis(nx, ny, nz);
        // Project to the plane's 2D space, which doubles as the texture space.
        float minS = Float.MAX_VALUE, maxS = -Float.MAX_VALUE;
        float minT = Float.MAX_VALUE, maxT = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float px = polyIn[i * 3], py = polyIn[i * 3 + 1], pz = polyIn[i * 3 + 2];
            float s = projectS(axis, px, py, pz);
            float t = projectT(axis, px, py, pz);
            cell2d[i * 2] = s;
            cell2d[i * 2 + 1] = t;
            minS = Math.min(minS, s);
            maxS = Math.max(maxS, s);
            minT = Math.min(minT, t);
            maxT = Math.max(maxT, t);
        }

        // Sky and glowing faces are flat-shaded, so one cell covers the lot.
        boolean unlit = isSky || glow;
        float grid = unlit ? Math.max(maxS - minS, maxT - minT) + 2f : LIGHT_GRID;
        float s0 = unlit ? minS - 1f : (float) Math.floor(minS / grid) * grid;
        float t0 = unlit ? minT - 1f : (float) Math.floor(minT / grid) * grid;

        for (float s = s0; s < maxS; s += grid) {
            for (float t = t0; t < maxT; t += grid) {
                int n = clipToCell(count, s, s + grid, t, t + grid);
                if (n < 3) continue;
                emitPolygon(n, axis, nx, ny, nz, dist, layer, scale, glow, isSky);
            }
        }
    }

    /** Clips a large quad on the face plane against the brush's other planes. */
    private int buildPolygon(Brush brush, int face) {
        int o = face * 4;
        float nx = brush.planes[o], ny = brush.planes[o + 1], nz = brush.planes[o + 2];
        float dist = brush.planes[o + 3];

        // Build an oversized quad centred on the plane.
        Vec3 n = normal.set(nx, ny, nz);
        Vec3 up = new Vec3();
        if (Math.abs(n.z) > 0.9f) up.set(1, 0, 0);
        else up.set(0, 0, 1);
        Vec3 t1 = new Vec3().setCross(up, n);
        t1.normalize();
        Vec3 t2 = new Vec3().setCross(n, t1);
        t2.normalize();

        float size = 1f;
        size = Math.max(size, Math.abs(brush.maxs.x - brush.mins.x));
        size = Math.max(size, Math.abs(brush.maxs.y - brush.mins.y));
        size = Math.max(size, Math.abs(brush.maxs.z - brush.mins.z));
        size = size * 2f + 64f;

        // Centre the quad on the brush itself, not on the world origin — a plane
        // far from the origin would otherwise be missed entirely.
        float bx = (brush.mins.x + brush.maxs.x) * 0.5f;
        float by = (brush.mins.y + brush.maxs.y) * 0.5f;
        float bz = (brush.mins.z + brush.maxs.z) * 0.5f;
        float off = nx * bx + ny * by + nz * bz - dist;
        float cx = bx - nx * off, cy = by - ny * off, cz = bz - nz * off;
        int count = 4;
        for (int i = 0; i < 4; i++) {
            float su = ((i == 0 || i == 3) ? -1f : 1f) * size;
            float sv = ((i < 2) ? -1f : 1f) * size;
            polyIn[i * 3] = cx + t1.x * su + t2.x * sv;
            polyIn[i * 3 + 1] = cy + t1.y * su + t2.y * sv;
            polyIn[i * 3 + 2] = cz + t1.z * su + t2.z * sv;
        }

        for (int p = 0; p < brush.planeCount() && count >= 3; p++) {
            if (p == face) continue;
            count = clipPolygon(count, brush.planes[p * 4], brush.planes[p * 4 + 1],
                    brush.planes[p * 4 + 2], brush.planes[p * 4 + 3]);
        }
        normal.set(nx, ny, nz);
        return count;
    }

    /** Sutherland-Hodgman clip of polyIn against dot(n,p) &lt;= dist. */
    private int clipPolygon(int count, float nx, float ny, float nz, float dist) {
        int out = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            float ax = polyIn[i * 3], ay = polyIn[i * 3 + 1], az = polyIn[i * 3 + 2];
            float bx = polyIn[j * 3], by = polyIn[j * 3 + 1], bz = polyIn[j * 3 + 2];
            float da = nx * ax + ny * ay + nz * az - dist;
            float db = nx * bx + ny * by + nz * bz - dist;
            if (da <= 0f) {
                polyOut[out * 3] = ax;
                polyOut[out * 3 + 1] = ay;
                polyOut[out * 3 + 2] = az;
                out++;
            }
            if ((da < 0f && db > 0f) || (da > 0f && db < 0f)) {
                float f = da / (da - db);
                if (out < MAX_POLY) {
                    polyOut[out * 3] = ax + (bx - ax) * f;
                    polyOut[out * 3 + 1] = ay + (by - ay) * f;
                    polyOut[out * 3 + 2] = az + (bz - az) * f;
                    out++;
                }
            }
            if (out >= MAX_POLY - 1) break;
        }
        System.arraycopy(polyOut, 0, polyIn, 0, out * 3);
        return out;
    }

    /** Clips the projected polygon to one grid cell, writing into cellTmp. */
    private int clipToCell(int count, float s0, float s1, float t0, float t1) {
        int n = count;
        System.arraycopy(cell2d, 0, cellTmp, 0, count * 2);
        n = clip2d(cellTmp, n, 1, 0, s0);     // s >= s0
        n = clip2d(cellTmp, n, -1, 0, -s1);   // s <= s1
        n = clip2d(cellTmp, n, 0, 1, t0);     // t >= t0
        n = clip2d(cellTmp, n, 0, -1, -t1);   // t <= t1
        return n;
    }

    private final float[] clipScratch = new float[MAX_POLY * 2];

    /** Keeps the half-plane dot((a,b),(s,t)) &gt;= c. */
    private int clip2d(float[] poly, int count, float a, float b, float c) {
        int out = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            float sx = poly[i * 2], sy = poly[i * 2 + 1];
            float ex = poly[j * 2], ey = poly[j * 2 + 1];
            float ds = a * sx + b * sy - c;
            float de = a * ex + b * ey - c;
            if (ds >= 0f) {
                clipScratch[out * 2] = sx;
                clipScratch[out * 2 + 1] = sy;
                out++;
            }
            if ((ds < 0f && de > 0f) || (ds > 0f && de < 0f)) {
                float f = ds / (ds - de);
                if (out < MAX_POLY) {
                    clipScratch[out * 2] = sx + (ex - sx) * f;
                    clipScratch[out * 2 + 1] = sy + (ey - sy) * f;
                    out++;
                }
            }
            if (out >= MAX_POLY - 1) break;
        }
        System.arraycopy(clipScratch, 0, poly, 0, out * 2);
        return out;
    }

    /** True when the face is pressed up against the inside of another solid. */
    private boolean isBuried(int count) {
        float cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < count; i++) {
            cx += polyIn[i * 3];
            cy += polyIn[i * 3 + 1];
            cz += polyIn[i * 3 + 2];
        }
        cx /= count;
        cy /= count;
        cz /= count;
        // Step just outside the face: if that is inside solid, nobody can see it.
        vertex.set(cx + normal.x * 0.5f, cy + normal.y * 0.5f, cz + normal.z * 0.5f);
        return (world.pointContents(vertex) & Contents.SOLID) != 0;
    }

    // ------------------------------------------------------------- projection

    private static int dominantAxis(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (az >= ax && az >= ay) return 2;
        return ax >= ay ? 0 : 1;
    }

    private static float projectS(int axis, float x, float y, float z) {
        switch (axis) {
            case 0: return y;
            case 1: return x;
            default: return x;
        }
    }

    private static float projectT(int axis, float x, float y, float z) {
        switch (axis) {
            case 0: return -z;
            case 1: return -z;
            default: return -y;
        }
    }

    /** Recovers the 3D point on the plane from its projected coordinates. */
    private void unproject(int axis, float s, float t, float nx, float ny, float nz, float dist,
                           Vec3 out) {
        switch (axis) {
            case 0: {
                float y = s, z = -t;
                out.set((dist - ny * y - nz * z) / nx, y, z);
                break;
            }
            case 1: {
                float x = s, z = -t;
                out.set(x, (dist - nx * x - nz * z) / ny, z);
                break;
            }
            default: {
                float x = s, y = -t;
                out.set(x, y, (dist - nx * x - ny * y) / nz);
                break;
            }
        }
    }

    // -------------------------------------------------------------- emission

    private void emitPolygon(int count, int axis, float nx, float ny, float nz, float dist,
                             int layer, float scale, boolean glow, boolean isSky) {
        int base = isSky ? skyVertexCount : vertexCount;
        for (int i = 0; i < count; i++) {
            float s = cellTmp[i * 2], t = cellTmp[i * 2 + 1];
            unproject(axis, s, t, nx, ny, nz, dist, vertex);
            float r, g, b;
            if (isSky) {
                r = g = b = 1f;
            } else if (glow) {
                r = g = b = 1.55f;
            } else {
                computeLight(vertex, nx, ny, nz);
                r = lightAccum[0];
                g = lightAccum[1];
                b = lightAccum[2];
            }
            addVertex(isSky, vertex.x, vertex.y, vertex.z, nx, ny, nz,
                    s / scale, t / scale, r, g, b, layer);
        }
        // Fan triangulation — every polygon here is convex.
        for (int i = 1; i + 1 < count; i++) {
            addIndex(isSky, base);
            addIndex(isSky, base + i);
            addIndex(isSky, base + i + 1);
        }
    }

    private void addVertex(boolean sky, float x, float y, float z, float nx, float ny, float nz,
                           float u, float v, float r, float g, float b, float layer) {
        if (sky) {
            skyVerts = ensure(skyVerts, (skyVertexCount + 1) * VERTEX_FLOATS);
            int o = skyVertexCount * VERTEX_FLOATS;
            writeVertex(skyVerts, o, x, y, z, nx, ny, nz, u, v, r, g, b, layer);
            skyVertexCount++;
            return;
        }
        verts = ensure(verts, (vertexCount + 1) * VERTEX_FLOATS);
        int o = vertexCount * VERTEX_FLOATS;
        writeVertex(verts, o, x, y, z, nx, ny, nz, u, v, r, g, b, layer);
        vertexCount++;

        boundsMin.set(Math.min(boundsMin.x, x), Math.min(boundsMin.y, y), Math.min(boundsMin.z, z));
        boundsMax.set(Math.max(boundsMax.x, x), Math.max(boundsMax.y, y), Math.max(boundsMax.z, z));
    }

    private static void writeVertex(float[] a, int o, float x, float y, float z,
                                    float nx, float ny, float nz, float u, float v,
                                    float r, float g, float b, float layer) {
        a[o] = x;
        a[o + 1] = y;
        a[o + 2] = z;
        a[o + 3] = nx;
        a[o + 4] = ny;
        a[o + 5] = nz;
        a[o + 6] = u;
        a[o + 7] = v;
        a[o + 8] = r;
        a[o + 9] = g;
        a[o + 10] = b;
        a[o + 11] = layer;
    }

    private void addIndex(boolean sky, int index) {
        if (sky) {
            skyIndices = ensure(skyIndices, skyIndexCount + 1);
            skyIndices[skyIndexCount++] = index;
        } else {
            indices = ensure(indices, indexCount + 1);
            indices[indexCount++] = index;
        }
    }

    private static float[] ensure(float[] a, int needed) {
        if (a.length >= needed) return a;
        int n = a.length;
        while (n < needed) n *= 2;
        return java.util.Arrays.copyOf(a, n);
    }

    private static int[] ensure(int[] a, int needed) {
        if (a.length >= needed) return a;
        int n = a.length;
        while (n < needed) n *= 2;
        return java.util.Arrays.copyOf(a, n);
    }

    // -------------------------------------------------------------- lighting

    private final float[] lightAccum = new float[3];
    private final Vec3 aoDir = new Vec3();
    private final Vec3 aoEnd = new Vec3();
    private final Vec3 aoTangent = new Vec3();
    private final Vec3 aoBitangent = new Vec3();

    /** Distance over which nearby geometry darkens a vertex. */
    private static final float AO_RADIUS = 110f;
    /** Hemisphere sample directions, as (tangent, bitangent, normal) weights. */
    private static final float[] AO_SAMPLES = {
            0f, 0f, 1f,
            0.72f, 0f, 0.70f,
            -0.72f, 0f, 0.70f,
            0f, 0.72f, 0.70f,
            0f, -0.72f, 0.70f,
            0.52f, 0.52f, 0.68f,
            -0.52f, 0.52f, 0.68f,
            0.52f, -0.52f, 0.68f,
            -0.52f, -0.52f, 0.68f,
    };

    /**
     * Fraction of the hemisphere above the surface that is open, from 1 (fully
     * exposed) down to about 0.28 (wedged into a corner).
     */
    private float ambientOcclusion(Vec3 p, float nx, float ny, float nz) {
        // Any two vectors perpendicular to the normal will do for the sample basis.
        if (Math.abs(nz) > 0.9f) aoTangent.set(1, 0, 0);
        else aoTangent.set(0, 0, 1);
        aoBitangent.setCross(aoTangent, aoDir.set(nx, ny, nz));
        aoBitangent.normalize();
        aoTangent.setCross(aoDir, aoBitangent);
        aoTangent.normalize();

        int samples = AO_SAMPLES.length / 3;
        float open = 0f;
        for (int i = 0; i < samples; i++) {
            float wt = AO_SAMPLES[i * 3], wb = AO_SAMPLES[i * 3 + 1], wn = AO_SAMPLES[i * 3 + 2];
            aoDir.set(aoTangent.x * wt + aoBitangent.x * wb + nx * wn,
                    aoTangent.y * wt + aoBitangent.y * wb + ny * wn,
                    aoTangent.z * wt + aoBitangent.z * wb + nz * wn);
            aoDir.normalize();
            rayStart.set(p.x + nx * 1.5f, p.y + ny * 1.5f, p.z + nz * 1.5f);
            aoEnd.setMa(rayStart, aoDir, AO_RADIUS);
            world.traceRay(trace, rayStart, aoEnd, Contents.MASK_SHOT);
            // Partial credit for distant hits keeps the falloff smooth.
            open += trace.startSolid ? 0f : trace.fraction;
        }
        float f = open / samples;
        return 0.28f + 0.72f * f;
    }

    /**
     * Ambient, plus a sun term, plus every static light in range with a shadow
     * ray. The result is written into {@link #lightAccum}.
     */
    private void computeLight(Vec3 p, float nx, float ny, float nz) {
        // Ambient and the sun are the same everywhere, so without occlusion the
        // whole level reads as one flat grey. Corners and undersides get darkened
        // by sampling the surroundings.
        float ao = ambientOcclusion(p, nx, ny, nz);

        float r = map.ambient.x * ao, g = map.ambient.y * ao, b = map.ambient.z * ao;

        float sun = -(nx * map.sunDir.x + ny * map.sunDir.y + nz * map.sunDir.z);
        if (sun > 0f) {
            sun *= ao;
            r += map.sunColor.x * sun;
            g += map.sunColor.y * sun;
            b += map.sunColor.z * sun;
        }

        for (int i = 0; i < lights.size(); i++) {
            MapDef.Light light = lights.get(i);
            lightDir.setSub(light.pos, p);
            float distSq = lightDir.lengthSq();
            if (distSq > light.radius * light.radius) continue;
            float dist = (float) Math.sqrt(distSq);
            if (dist < 1e-3f) continue;
            float inv = 1f / dist;
            float lambert = (lightDir.x * nx + lightDir.y * ny + lightDir.z * nz) * inv;
            if (lambert <= 0.01f) continue;

            float atten = 1f - dist / light.radius;
            atten = atten * atten * light.intensity;
            float contribution = lambert * atten;
            if (contribution < 0.004f) continue;

            // Shadow ray, started slightly off the surface so it does not hit
            // the face it belongs to.
            rayStart.set(p.x + nx * 1.5f, p.y + ny * 1.5f, p.z + nz * 1.5f);
            world.traceRay(trace, rayStart, light.pos, Contents.MASK_SHOT);
            if (trace.fraction < 0.98f || trace.startSolid) continue;

            r += light.color.x * contribution;
            g += light.color.y * contribution;
            b += light.color.z * contribution;
        }

        lightAccum[0] = Math.min(r, 2.2f);
        lightAccum[1] = Math.min(g, 2.2f);
        lightAccum[2] = Math.min(b, 2.2f);
    }
}
