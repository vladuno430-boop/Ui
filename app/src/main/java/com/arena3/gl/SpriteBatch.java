package com.arena3.gl;

import android.opengl.GLES30;

import com.arena3.core.Mat4;

/**
 * Batched 2D drawing for the HUD and menus: coloured rectangles, lines and text
 * from the generated font atlas, all in one buffer and one draw call.
 */
public final class SpriteBatch {

    private static final int MAX_QUADS = 3072;
    /** x y | u v | r g b a */
    private static final int VERTEX_FLOATS = 8;

    private final float[] verts = new float[MAX_QUADS * 4 * VERTEX_FLOATS];
    private int quads;

    private final Mesh mesh;
    private final ShaderProgram program;
    private final Textures textures;
    private final Mat4 projection = new Mat4();

    private float screenWidth, screenHeight;

    public SpriteBatch(ShaderProgram program, Textures textures) {
        this.program = program;
        this.textures = textures;
        mesh = new Mesh(new int[]{2, 2, 4}, true, MAX_QUADS * 4);
        mesh.uploadIndices(Mesh.quadIndices(MAX_QUADS), MAX_QUADS * 6);
    }

    public void begin(int width, int height) {
        screenWidth = width;
        screenHeight = height;
        // Top-left origin, y growing downwards, like every 2D UI.
        projection.ortho(0, width, height, 0, -1, 1);
        quads = 0;
    }

    public float width() {
        return screenWidth;
    }

    public float height() {
        return screenHeight;
    }

    public void rect(float x, float y, float w, float h, float r, float g, float b, float a) {
        quad(x, y, x + w, y + h, Textures.WHITE_U, Textures.WHITE_V, Textures.WHITE_U, Textures.WHITE_V,
                r, g, b, a);
    }

    /** Rectangle outline of the given thickness, drawn inside the bounds. */
    public void frame(float x, float y, float w, float h, float t, float r, float g, float b, float a) {
        rect(x, y, w, t, r, g, b, a);
        rect(x, y + h - t, w, t, r, g, b, a);
        rect(x, y + t, t, h - t * 2, r, g, b, a);
        rect(x + w - t, y + t, t, h - t * 2, r, g, b, a);
    }

    /** Line between two points, for crosshairs and indicators. */
    public void line(float x0, float y0, float x1, float y1, float thickness,
                     float r, float g, float b, float a) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return;
        float nx = -dy / len * thickness * 0.5f;
        float ny = dx / len * thickness * 0.5f;
        pushQuad(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny,
                Textures.WHITE_U, Textures.WHITE_V, r, g, b, a);
    }

    private void quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
                      float r, float g, float b, float a) {
        if (quads >= MAX_QUADS) return;
        int o = quads * 4 * VERTEX_FLOATS;
        vertex(o, x0, y0, u0, v0, r, g, b, a);
        vertex(o + VERTEX_FLOATS, x1, y0, u1, v0, r, g, b, a);
        vertex(o + VERTEX_FLOATS * 2, x1, y1, u1, v1, r, g, b, a);
        vertex(o + VERTEX_FLOATS * 3, x0, y1, u0, v1, r, g, b, a);
        quads++;
    }

    private void pushQuad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                          float u, float v, float r, float g, float b, float a) {
        if (quads >= MAX_QUADS) return;
        int o = quads * 4 * VERTEX_FLOATS;
        vertex(o, x0, y0, u, v, r, g, b, a);
        vertex(o + VERTEX_FLOATS, x1, y1, u, v, r, g, b, a);
        vertex(o + VERTEX_FLOATS * 2, x2, y2, u, v, r, g, b, a);
        vertex(o + VERTEX_FLOATS * 3, x3, y3, u, v, r, g, b, a);
        quads++;
    }

    private void vertex(int o, float x, float y, float u, float v, float r, float g, float b, float a) {
        verts[o] = x;
        verts[o + 1] = y;
        verts[o + 2] = u;
        verts[o + 3] = v;
        verts[o + 4] = r;
        verts[o + 5] = g;
        verts[o + 6] = b;
        verts[o + 7] = a;
    }

    // ------------------------------------------------------------------- text

    /** Width the string will occupy at the given cap height. */
    public float measure(String s, float size) {
        float scale = size / Textures.FONT_CELL;
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) c = '?';
            w += textures.charWidth[c] * scale;
        }
        return w;
    }

    /** Draws text with its top-left at (x, y). Returns the advance width. */
    public float text(String s, float x, float y, float size, float r, float g, float b, float a) {
        float scale = size / Textures.FONT_CELL;
        float cell = Textures.FONT_CELL * scale;
        float cursor = x;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) c = '?';
            int index = c - 32;
            int col = index % Textures.FONT_COLS;
            int row = index / Textures.FONT_COLS;
            float u0 = col / (float) Textures.FONT_COLS;
            float v0 = row / (float) Textures.FONT_ROWS;
            float u1 = (col + 1) / (float) Textures.FONT_COLS;
            float v1 = (row + 1) / (float) Textures.FONT_ROWS;
            if (c != ' ') {
                quad(cursor, y, cursor + cell, y + cell, u0, v0, u1, v1, r, g, b, a);
            }
            cursor += textures.charWidth[c] * scale;
        }
        return cursor - x;
    }

    public void textCentered(String s, float cx, float y, float size,
                             float r, float g, float b, float a) {
        text(s, cx - measure(s, size) * 0.5f, y, size, r, g, b, a);
    }

    public void textRight(String s, float rightX, float y, float size,
                          float r, float g, float b, float a) {
        text(s, rightX - measure(s, size), y, size, r, g, b, a);
    }

    /** Text with a soft dark backing, so it stays readable over bright scenery. */
    public void textShadowed(String s, float x, float y, float size,
                             float r, float g, float b, float a) {
        float off = Math.max(1f, size * 0.06f);
        text(s, x + off, y + off, size, 0f, 0f, 0f, a * 0.75f);
        text(s, x, y, size, r, g, b, a);
    }

    public void textCenteredShadowed(String s, float cx, float y, float size,
                                     float r, float g, float b, float a) {
        textShadowed(s, cx - measure(s, size) * 0.5f, y, size, r, g, b, a);
    }

    // ------------------------------------------------------------------ flush

    public void flush() {
        if (quads == 0) return;
        program.use();
        program.setMatrix("uProj", projection.m);
        program.set("uTex", 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures.font);

        mesh.stream(verts, quads * 4 * VERTEX_FLOATS);
        mesh.drawIndexed(quads * 6);
        quads = 0;
    }

    public void dispose() {
        mesh.dispose();
    }
}
