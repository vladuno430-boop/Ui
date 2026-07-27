package com.arena3.core;

/**
 * Column-major 4x4 matrix stored the way OpenGL wants it, so {@link #m} can be
 * handed straight to {@code glUniformMatrix4fv}.
 */
public final class Mat4 {

    public final float[] m = new float[16];

    public Mat4() {
        identity();
    }

    public Mat4 identity() {
        java.util.Arrays.fill(m, 0f);
        m[0] = m[5] = m[10] = m[15] = 1f;
        return this;
    }

    public Mat4 set(Mat4 o) {
        System.arraycopy(o.m, 0, m, 0, 16);
        return this;
    }

    public Mat4 perspective(float fovYDeg, float aspect, float near, float far) {
        float f = 1f / (float) Math.tan(fovYDeg * MathUtil.DEG2RAD * 0.5f);
        java.util.Arrays.fill(m, 0f);
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1f;
        m[14] = (2f * far * near) / (near - far);
        return this;
    }

    public Mat4 ortho(float l, float r, float b, float t, float near, float far) {
        java.util.Arrays.fill(m, 0f);
        m[0] = 2f / (r - l);
        m[5] = 2f / (t - b);
        m[10] = -2f / (far - near);
        m[12] = -(r + l) / (r - l);
        m[13] = -(t + b) / (t - b);
        m[14] = -(far + near) / (far - near);
        m[15] = 1f;
        return this;
    }

    /**
     * View matrix for an eye at {@code eye} with the given basis vectors. The
     * game's world is Z-up while GL clip space is Y-up, so the basis is remapped:
     * GL right = world right, GL up = world up, GL back = -world forward.
     */
    public Mat4 view(Vec3 eye, Vec3 forward, Vec3 right, Vec3 up) {
        m[0] = right.x;   m[4] = right.y;   m[8]  = right.z;   m[12] = -right.dot(eye);
        m[1] = up.x;      m[5] = up.y;      m[9]  = up.z;      m[13] = -up.dot(eye);
        m[2] = -forward.x; m[6] = -forward.y; m[10] = -forward.z; m[14] = forward.dot(eye);
        m[3] = 0;         m[7] = 0;         m[11] = 0;         m[15] = 1;
        return this;
    }

    /** this = a * b */
    public Mat4 setMul(Mat4 a, Mat4 b) {
        final float[] A = a.m, B = b.m;
        for (int c = 0; c < 4; c++) {
            int c4 = c * 4;
            float b0 = B[c4], b1 = B[c4 + 1], b2 = B[c4 + 2], b3 = B[c4 + 3];
            m[c4]     = A[0] * b0 + A[4] * b1 + A[8]  * b2 + A[12] * b3;
            m[c4 + 1] = A[1] * b0 + A[5] * b1 + A[9]  * b2 + A[13] * b3;
            m[c4 + 2] = A[2] * b0 + A[6] * b1 + A[10] * b2 + A[14] * b3;
            m[c4 + 3] = A[3] * b0 + A[7] * b1 + A[11] * b2 + A[15] * b3;
        }
        return this;
    }

    public Mat4 translate(float x, float y, float z) {
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
        return this;
    }

    public Mat4 setTranslation(float x, float y, float z) {
        identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return this;
    }

    public Mat4 scale(float sx, float sy, float sz) {
        for (int i = 0; i < 4; i++) {
            m[i] *= sx;
            m[i + 4] *= sy;
            m[i + 8] *= sz;
        }
        return this;
    }

    public Mat4 rotateZ(float deg) {
        float s = (float) Math.sin(deg * MathUtil.DEG2RAD), c = (float) Math.cos(deg * MathUtil.DEG2RAD);
        for (int i = 0; i < 4; i++) {
            float a = m[i], b = m[i + 4];
            m[i] = a * c + b * s;
            m[i + 4] = b * c - a * s;
        }
        return this;
    }

    public Mat4 rotateY(float deg) {
        float s = (float) Math.sin(deg * MathUtil.DEG2RAD), c = (float) Math.cos(deg * MathUtil.DEG2RAD);
        for (int i = 0; i < 4; i++) {
            float a = m[i], b = m[i + 8];
            m[i] = a * c - b * s;
            m[i + 8] = a * s + b * c;
        }
        return this;
    }

    public Mat4 rotateX(float deg) {
        float s = (float) Math.sin(deg * MathUtil.DEG2RAD), c = (float) Math.cos(deg * MathUtil.DEG2RAD);
        for (int i = 0; i < 4; i++) {
            float a = m[i + 4], b = m[i + 8];
            m[i + 4] = a * c + b * s;
            m[i + 8] = b * c - a * s;
        }
        return this;
    }

    /** Transforms a point, writing the result into {@code out}. */
    public void transformPoint(Vec3 p, Vec3 out) {
        float x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12];
        float y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13];
        float z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14];
        out.set(x, y, z);
    }

    /**
     * Extracts the six frustum planes of this (view-projection) matrix into
     * {@code out} as [nx, ny, nz, d] rows, normals pointing inwards.
     */
    public void extractFrustum(float[] out) {
        for (int i = 0; i < 6; i++) {
            int sign = (i & 1) == 0 ? 1 : -1;
            int row = i >> 1;
            int o = i * 4;
            out[o]     = m[3]  + sign * m[row];
            out[o + 1] = m[7]  + sign * m[row + 4];
            out[o + 2] = m[11] + sign * m[row + 8];
            out[o + 3] = m[15] + sign * m[row + 12];
            float len = (float) Math.sqrt(out[o] * out[o] + out[o + 1] * out[o + 1] + out[o + 2] * out[o + 2]);
            if (len > 1e-6f) {
                out[o] /= len;
                out[o + 1] /= len;
                out[o + 2] /= len;
                out[o + 3] /= len;
            }
        }
    }
}
