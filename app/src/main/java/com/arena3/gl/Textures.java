package com.arena3.gl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import com.arena3.game.Tex;
import com.arena3.render.ProcTex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Builds every GL texture the game uses. Nothing is loaded from disk. */
public final class Textures {

    /** All world materials in one array texture, so the level is one draw call. */
    public int worldArray;
    /** Materials for fighters, weapons and pickups. */
    public int modelArray;
    /** Soft round sprite for particles. */
    public int particle;
    /** Bitmap font atlas plus a white pixel for solid HUD fills. */
    public int font;

    public static final int FONT_COLS = 16;
    public static final int FONT_ROWS = 8;
    public static final int FONT_CELL = 32;
    /** Per-character advance widths, in atlas pixels. */
    public final float[] charWidth = new float[128];
    public float fontBaseline;
    /** UV of a fully opaque texel, for untextured quads. */
    public static final float WHITE_U = (FONT_COLS - 0.5f) / FONT_COLS;
    public static final float WHITE_V = (FONT_ROWS - 0.5f) / FONT_ROWS;

    public void create() {
        worldArray = createWorldArray();
        modelArray = createModelArray();
        particle = createParticle();
        font = createFont();
    }

    private int createWorldArray() {
        return createArray(ProcTex.SIZE, Tex.COUNT, ProcTex.generateAllWorld());
    }

    private int createModelArray() {
        return createArray(ProcTex.MODEL_SIZE, ProcTex.MAT_COUNT, ProcTex.generateAllModel());
    }

    /** Uploads a set of same-sized materials as one mipmapped array texture. */
    private int createArray(int size, int layers, int[][] pixels) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, tex[0]);

        int levels = 1 + (int) (Math.log(size) / Math.log(2));
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, levels, GLES30.GL_RGBA8, size, size, layers);

        ByteBuffer buffer = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());
        for (int layer = 0; layer < layers; layer++) {
            buffer.clear();
            for (int p : pixels[layer]) {
                // ARGB from the generator, RGBA for GL.
                buffer.put((byte) ((p >> 16) & 0xFF));
                buffer.put((byte) ((p >> 8) & 0xFF));
                buffer.put((byte) (p & 0xFF));
                buffer.put((byte) ((p >>> 24) & 0xFF));
            }
            buffer.position(0);
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, size, size, 1,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer);
        }
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT);
        return tex[0];
    }

    private int createParticle() {
        int size = 64;
        int[] pixels = ProcTex.particleSprite(size);
        Bitmap bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        int handle = uploadBitmap(bmp, true);
        bmp.recycle();
        return handle;
    }

    /**
     * Renders an ASCII atlas with the platform's text engine — a condensed
     * sans-serif in the spirit of the genre — and reserves the last cell as a
     * solid white block for filled quads.
     */
    private int createFont() {
        int w = FONT_COLS * FONT_CELL, h = FONT_ROWS * FONT_CELL;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.TRANSPARENT);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextSize(FONT_CELL * 0.76f);
        paint.setTextAlign(Paint.Align.LEFT);

        Paint.FontMetrics fm = paint.getFontMetrics();
        fontBaseline = -fm.ascent;

        float[] widths = new float[1];
        for (int c = 32; c < 127; c++) {
            int index = c - 32;
            int col = index % FONT_COLS;
            int row = index / FONT_COLS;
            String s = String.valueOf((char) c);
            paint.getTextWidths(s, widths);
            charWidth[c] = widths[0];
            canvas.drawText(s, col * FONT_CELL + 2f, row * FONT_CELL + fontBaseline * 0.94f, paint);
        }
        charWidth[' '] = paint.measureText(" ");

        // Solid block in the final cell for untextured drawing.
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect((FONT_COLS - 1) * FONT_CELL, (FONT_ROWS - 1) * FONT_CELL,
                FONT_COLS * FONT_CELL, FONT_ROWS * FONT_CELL, paint);

        int handle = uploadBitmap(bmp, true);
        bmp.recycle();
        return handle;
    }

    private static int uploadBitmap(Bitmap bmp, boolean linear) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                linear ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER,
                linear ? GLES30.GL_LINEAR : GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    public void dispose() {
        int[] tex = {worldArray, modelArray, particle, font};
        GLES30.glDeleteTextures(tex.length, tex, 0);
    }
}
