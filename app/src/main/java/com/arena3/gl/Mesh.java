package com.arena3.gl;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Vertex array plus its buffers. Handles both static geometry uploaded once and
 * streaming geometry (particles, HUD) rewritten every frame.
 */
public final class Mesh {

    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int[] attributeSizes;
    private final int strideFloats;
    private int indexCount;
    private int vertexCount;
    private FloatBuffer vertexBuffer;
    private final boolean dynamic;

    /**
     * @param attributeSizes component count for each attribute, in location order
     * @param capacityVerts  vertices to preallocate for dynamic meshes
     */
    public Mesh(int[] attributeSizes, boolean dynamic, int capacityVerts) {
        this.attributeSizes = attributeSizes;
        this.dynamic = dynamic;
        int stride = 0;
        for (int size : attributeSizes) stride += size;
        this.strideFloats = stride;

        int[] tmp = new int[1];
        GLES30.glGenVertexArrays(1, tmp, 0);
        vao = tmp[0];
        GLES30.glGenBuffers(1, tmp, 0);
        vbo = tmp[0];
        GLES30.glGenBuffers(1, tmp, 0);
        ebo = tmp[0];

        GLES30.glBindVertexArray(vao);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        if (dynamic) {
            int bytes = capacityVerts * strideFloats * 4;
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bytes, null, GLES30.GL_DYNAMIC_DRAW);
            vertexBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        bindAttributes();
        GLES30.glBindVertexArray(0);
    }

    private void bindAttributes() {
        int offset = 0;
        for (int i = 0; i < attributeSizes.length; i++) {
            GLES30.glEnableVertexAttribArray(i);
            GLES30.glVertexAttribPointer(i, attributeSizes[i], GLES30.GL_FLOAT, false,
                    strideFloats * 4, offset * 4);
            offset += attributeSizes[i];
        }
    }

    /** Uploads static geometry. Empty uploads are legal and draw nothing. */
    public void upload(float[] verts, int vertFloats, int[] indices, int indexCount) {
        if (vertFloats <= 0) {
            this.vertexCount = 0;
            this.indexCount = 0;
            return;
        }
        GLES30.glBindVertexArray(vao);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        FloatBuffer fb = ByteBuffer.allocateDirect(vertFloats * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        fb.put(verts, 0, vertFloats).position(0);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertFloats * 4, fb, GLES30.GL_STATIC_DRAW);

        this.vertexCount = vertFloats / strideFloats;
        this.indexCount = indexCount;
        if (indices != null && indexCount > 0) {
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo);
            IntBuffer ib = ByteBuffer.allocateDirect(indexCount * 4).order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            ib.put(indices, 0, indexCount).position(0);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexCount * 4, ib, GLES30.GL_STATIC_DRAW);
        }
        bindAttributes();
        GLES30.glBindVertexArray(0);
    }

    /** Uploads a static index buffer only — used for the shared quad indices. */
    public void uploadIndices(int[] indices, int count) {
        GLES30.glBindVertexArray(vao);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer ib = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(indices, 0, count).position(0);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, count * 4, ib, GLES30.GL_STATIC_DRAW);
        GLES30.glBindVertexArray(0);
        indexCount = count;
    }

    /** Rewrites the vertex data of a dynamic mesh. */
    public void stream(float[] verts, int floatCount) {
        if (!dynamic) throw new IllegalStateException("mesh is static");
        vertexBuffer.position(0);
        vertexBuffer.put(verts, 0, floatCount);
        vertexBuffer.position(0);
        GLES30.glBindVertexArray(vao);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, floatCount * 4, vertexBuffer);
        GLES30.glBindVertexArray(0);
        vertexCount = floatCount / strideFloats;
    }

    public void drawIndexed(int count) {
        GLES30.glBindVertexArray(vao);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_INT, 0);
        GLES30.glBindVertexArray(0);
    }

    public void draw() {
        drawIndexed(indexCount);
    }

    public void drawArrays(int mode, int first, int count) {
        GLES30.glBindVertexArray(vao);
        GLES30.glDrawArrays(mode, first, count);
        GLES30.glBindVertexArray(0);
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int indexCount() {
        return indexCount;
    }

    public void dispose() {
        int[] tmp = {vbo, ebo};
        GLES30.glDeleteBuffers(2, tmp, 0);
        tmp[0] = vao;
        GLES30.glDeleteVertexArrays(1, tmp, 0);
    }

    /** Index buffer for {@code quads} quads drawn as two triangles each. */
    public static int[] quadIndices(int quads) {
        int[] idx = new int[quads * 6];
        for (int i = 0; i < quads; i++) {
            int v = i * 4;
            idx[i * 6] = v;
            idx[i * 6 + 1] = v + 1;
            idx[i * 6 + 2] = v + 2;
            idx[i * 6 + 3] = v;
            idx[i * 6 + 4] = v + 2;
            idx[i * 6 + 5] = v + 3;
        }
        return idx;
    }
}
