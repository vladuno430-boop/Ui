package com.arena3.render;

import com.arena3.core.Mat4;
import com.arena3.core.Vec3;

/**
 * Builds the view-projection matrix used to render the sun's shadow map.
 *
 * <p>The light is directional, so the projection is orthographic and sized to
 * enclose the whole arena: one pass, one texture, no cascades. Kept free of GL
 * so the offline preview can rasterise the same shadows and confirm they land
 * where they should.
 */
public final class ShadowFit {

    /** Slack around the world bounds, so nothing clips at the edges. */
    private static final float PADDING = 64f;

    public final Mat4 lightViewProj = new Mat4();
    /** Light basis, exposed for anyone reproducing the projection by hand. */
    public final Vec3 forward = new Vec3();
    public final Vec3 right = new Vec3();
    public final Vec3 up = new Vec3();
    public final Vec3 eye = new Vec3();

    /** Depth the projection spans, in world units. */
    public float depthRange;
    /** Longest side of the light box, in world units. */
    public float extent;

    private final Mat4 view = new Mat4();
    private final Mat4 projection = new Mat4();
    private final Vec3 centre = new Vec3();
    private final Vec3 corner = new Vec3();

    /** Fits the light box around the given world bounds. */
    public void fit(Vec3 boundsMin, Vec3 boundsMax, Vec3 sunDir) {
        centre.set((boundsMin.x + boundsMax.x) * 0.5f,
                (boundsMin.y + boundsMax.y) * 0.5f,
                (boundsMin.z + boundsMax.z) * 0.5f);

        forward.set(sunDir);
        if (forward.normalize() < 1e-4f) forward.set(0, 0, -1);
        // Any vector not parallel to the light will do to start the basis.
        boolean steep = Math.abs(forward.z) > 0.95f;
        up.set(steep ? 1f : 0f, 0f, steep ? 0f : 1f);
        right.setCross(forward, up);
        if (right.normalize() < 1e-4f) right.set(1, 0, 0);
        up.setCross(right, forward);
        up.normalize();

        // Project every corner of the world box into light space to size the box.
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            corner.set((i & 1) == 0 ? boundsMin.x : boundsMax.x,
                    (i & 2) == 0 ? boundsMin.y : boundsMax.y,
                    (i & 4) == 0 ? boundsMin.z : boundsMax.z);
            corner.sub(centre);
            float x = corner.dot(right);
            float y = corner.dot(up);
            float z = corner.dot(forward);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }

        // Pull the eye back behind everything so nothing falls in front of the
        // near plane. The view matrix expects a right-handed basis, and the
        // light looks along +forward, so the GL "back" axis is -forward.
        eye.setMa(centre, forward, minZ - PADDING);
        view.view(eye, forward, right, up);
        depthRange = (maxZ - minZ) + PADDING * 2f;
        extent = Math.max(maxX - minX, maxY - minY) + PADDING * 2f;
        projection.ortho(minX - PADDING, maxX + PADDING, minY - PADDING, maxY + PADDING,
                0f, depthRange);
        lightViewProj.setMul(projection, view);
    }

    /**
     * Converts a bias measured in shadow-map texels into the depth units the
     * shader compares. Tying the bias to the texel footprint rather than a fixed
     * constant keeps contact shadows on small maps and stops acne on large ones.
     */
    public float depthBiasPerTexel(int resolution) {
        if (depthRange < 1e-4f || resolution <= 0) return 0f;
        return (extent / resolution) / depthRange;
    }
}
