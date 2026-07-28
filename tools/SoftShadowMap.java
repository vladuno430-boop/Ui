import com.arena3.core.Mat4;
import com.arena3.core.Vec3;
import com.arena3.render.ShadowFit;
import com.arena3.render.WorldGeometry;

/**
 * Software twin of the GL shadow map. It runs the same {@link ShadowFit}
 * projection, the same front-face culling and the same four-tap PCF lookup as
 * {@code Shaders.LIGHTING_COMMON}, so the offline preview and the test suite can
 * check the shadow maths without a device.
 */
public final class SoftShadowMap {

    /** Matches the default shadow resolution on the device. */
    public static final int SIZE = 1024;
    /** Depth bias in shadow-map texels, grazing and face-on. Mirrors the shader. */
    /** Depth bias in shadow-map texels, grazing and face-on. Mirrors the shader. */
    public static final float BIAS_GRAZE = 3.0f, BIAS_FACE = 0.75f;

    public final ShadowFit fit = new ShadowFit();
    public final float[] depth = new float[SIZE * SIZE];

    private final Vec3 sunDir = new Vec3();
    private final Vec3 p = new Vec3(), out = new Vec3(), n = new Vec3();
    private final float[] px = new float[3], py = new float[3], pz = new float[3];

    /** Sizes the light box around the world and clears the buffer. */
    public void begin(Vec3 boundsMin, Vec3 boundsMax, Vec3 sun) {
        fit.fit(boundsMin, boundsMax, sun);
        sunDir.set(sun);
        sunDir.normalize();
        java.util.Arrays.fill(depth, 1f);
    }

    /** Convenience: fit to the level and draw it in one go. */
    public void build(WorldGeometry geo, Vec3 sun) {
        begin(geo.boundsMin, geo.boundsMax, sun);
        addMesh(geo.verts, geo.indices, geo.indexCount, WorldGeometry.VERTEX_FLOATS, null);
    }

    /**
     * Rasterises a mesh into the depth buffer. {@code transform} may be null for
     * geometry already in world space.
     */
    public void addMesh(float[] v, int[] idx, int count, int stride, Mat4 transform) {
        float[] m = fit.lightViewProj.m;
        for (int i = 0; i < count; i += 3) {
            // The GL pass culls front faces so acne lands on the far side of the
            // caster; do the same here or this would not match the device.
            int o0 = idx[i] * stride;
            n.set(v[o0 + 3], v[o0 + 4], v[o0 + 5]);
            if (transform != null) {
                n.set(transform.m[0] * n.x + transform.m[4] * n.y + transform.m[8] * n.z,
                        transform.m[1] * n.x + transform.m[5] * n.y + transform.m[9] * n.z,
                        transform.m[2] * n.x + transform.m[6] * n.y + transform.m[10] * n.z);
            }
            if (n.dot(sunDir) < 0f) continue;

            boolean clipped = false;
            for (int k = 0; k < 3; k++) {
                int o = idx[i + k] * stride;
                p.set(v[o], v[o + 1], v[o + 2]);
                if (transform != null) transform.transformPoint(p, out);
                else out.set(p);
                float w = m[3] * out.x + m[7] * out.y + m[11] * out.z + m[15];
                if (w <= 1e-6f) { clipped = true; break; }
                px[k] = (((m[0] * out.x + m[4] * out.y + m[8] * out.z + m[12]) / w) * 0.5f + 0.5f) * SIZE;
                py[k] = (((m[1] * out.x + m[5] * out.y + m[9] * out.z + m[13]) / w) * 0.5f + 0.5f) * SIZE;
                pz[k] = ((m[2] * out.x + m[6] * out.y + m[10] * out.z + m[14]) / w) * 0.5f + 0.5f;
            }
            if (clipped) continue;
            raster();
        }
    }

    /** Linear (orthographic) depth fill — no perspective divide is needed here. */
    private void raster() {
        int minX = (int) Math.max(0, Math.floor(Math.min(px[0], Math.min(px[1], px[2]))));
        int maxX = (int) Math.min(SIZE - 1, Math.ceil(Math.max(px[0], Math.max(px[1], px[2]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(py[0], Math.min(py[1], py[2]))));
        int maxY = (int) Math.min(SIZE - 1, Math.ceil(Math.max(py[0], Math.max(py[1], py[2]))));
        if (minX > maxX || minY > maxY) return;

        float area = edge(px[0], py[0], px[1], py[1], px[2], py[2]);
        if (Math.abs(area) < 1e-6f) return;
        float inv = 1f / area;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float cx = x + 0.5f, cy = y + 0.5f;
                float w0 = edge(px[1], py[1], px[2], py[2], cx, cy) * inv;
                float w1 = edge(px[2], py[2], px[0], py[0], cx, cy) * inv;
                float w2 = edge(px[0], py[0], px[1], py[1], cx, cy) * inv;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float z = w0 * pz[0] + w1 * pz[1] + w2 * pz[2];
                int o = y * SIZE + x;
                if (z < depth[o]) depth[o] = z;
            }
        }
    }

    /** Four-tap PCF, the same offsets and bias the fragment shader uses. */
    public float factor(float wx, float wy, float wz, float ndl) {
        float[] m = fit.lightViewProj.m;
        float w = m[3] * wx + m[7] * wy + m[11] * wz + m[15];
        if (Math.abs(w) < 1e-6f) return 1f;
        float sx = ((m[0] * wx + m[4] * wy + m[8] * wz + m[12]) / w) * 0.5f + 0.5f;
        float sy = ((m[1] * wx + m[5] * wy + m[9] * wz + m[13]) / w) * 0.5f + 0.5f;
        float sz = ((m[2] * wx + m[6] * wy + m[10] * wz + m[14]) / w) * 0.5f + 0.5f;
        if (sx < 0f || sx > 1f || sy < 0f || sy > 1f || sz > 1f) return 1f;

        float clamped = ndl < 0f ? 0f : (ndl > 1f ? 1f : ndl);
        float bias = (BIAS_GRAZE + (BIAS_FACE - BIAS_GRAZE) * clamped)
                * fit.depthBiasPerTexel(SIZE);
        float texel = 1f / SIZE;
        float lit = 0f;
        for (int i = 0; i < 4; i++) {
            float ox = i == 0 ? -0.7f : (i == 1 ? 0.7f : (i == 2 ? -0.3f : 0.3f));
            float oy = i == 0 ? -0.3f : (i == 1 ? 0.3f : (i == 2 ? 0.7f : -0.7f));
            int tx = clampi((int) ((sx + ox * texel) * SIZE));
            int ty = clampi((int) ((sy + oy * texel) * SIZE));
            if (sz - bias <= depth[ty * SIZE + tx]) lit += 1f;
        }
        return lit * 0.25f;
    }

    /** Fraction of the map that actually received geometry. */
    public float coverage() {
        int hit = 0;
        for (float d : depth) if (d < 1f) hit++;
        return hit / (float) depth.length;
    }

    private static int clampi(int v) {
        return v < 0 ? 0 : (v >= SIZE ? SIZE - 1 : v);
    }

    private static float edge(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }
}
