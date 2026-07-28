package com.arena3.gl;

import android.opengl.GLES30;
import android.util.Log;

import com.arena3.core.Mat4;
import com.arena3.core.Vec3;
import com.arena3.render.ShadowFit;

/**
 * Depth buffer rendered from the sun's point of view, used to cut real-time
 * shadows out of the sunlight.
 *
 * <p>This class owns the GL side only. The matrix that positions the light lives
 * in {@link ShadowFit}, which has no GL dependency, so the offline preview can
 * rasterise the same shadows and check they land where they should.
 */
public final class ShadowMap {

    private static final String TAG = "Arena3";

    private int framebuffer;
    private int depthTexture;
    private int size;
    private boolean valid;

    private final ShadowFit fitter = new ShadowFit();
    /** Same object as {@code fitter.lightViewProj}; handy for uniform uploads. */
    public final Mat4 lightViewProj = fitter.lightViewProj;

    /** Allocates the depth target. Returns false if the driver refuses it. */
    public boolean create(int resolution) {
        size = resolution;

        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        depthTexture = tex[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0,
                GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        // Clamping to a white border would need an extension; clamping to edge
        // plus the in-shader bounds test does the same job.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        int[] fbo = new int[1];
        GLES30.glGenFramebuffers(1, fbo, 0);
        framebuffer = fbo[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_TEXTURE_2D, depthTexture, 0);
        // No colour attachment: this pass only writes depth.
        GLES30.glDrawBuffers(1, new int[]{GLES30.GL_NONE}, 0);
        GLES30.glReadBuffer(GLES30.GL_NONE);

        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

        valid = status == GLES30.GL_FRAMEBUFFER_COMPLETE;
        if (!valid) {
            Log.w(TAG, "shadow map unavailable, framebuffer status " + status);
            dispose();
        }
        return valid;
    }

    public boolean isValid() {
        return valid;
    }

    public int texture() {
        return depthTexture;
    }

    public float texelSize() {
        return 1f / Math.max(1, size);
    }

    /** Depth spanned by one texel, the unit the shader's bias is expressed in. */
    public float depthBiasUnit() {
        return fitter.depthBiasPerTexel(size);
    }

    /**
     * Fits the light's orthographic box around the world bounds and builds the
     * matrix the shaders use to look the shadow up.
     */
    public void fit(Vec3 boundsMin, Vec3 boundsMax, Vec3 sunDir) {
        fitter.fit(boundsMin, boundsMax, sunDir);
    }

    /** Binds the depth target and clears it, ready for the depth-only pass. */
    public void beginPass() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
        GLES30.glViewport(0, 0, size, size);
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);
        // Front-face culling during the depth pass pushes shadow acne to the
        // back of objects, where nobody sees it.
        GLES30.glCullFace(GLES30.GL_FRONT);
    }

    public void endPass(int viewWidth, int viewHeight) {
        GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, viewWidth, viewHeight);
    }

    public void dispose() {
        if (framebuffer != 0) {
            GLES30.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
            framebuffer = 0;
        }
        if (depthTexture != 0) {
            GLES30.glDeleteTextures(1, new int[]{depthTexture}, 0);
            depthTexture = 0;
        }
        valid = false;
    }
}
