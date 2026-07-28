package com.arena3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/** Slow drifting grid and embers behind the main menu. */
public final class MenuBackdropView extends View {

    private static final int EMBERS = 40;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] emberX = new float[EMBERS];
    private final float[] emberY = new float[EMBERS];
    private final float[] emberSpeed = new float[EMBERS];
    private final float[] emberSize = new float[EMBERS];
    private final Random rnd = new Random(7);
    private long lastFrame;
    private float time;
    private Shader gradient;

    public MenuBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        for (int i = 0; i < EMBERS; i++) {
            emberX[i] = rnd.nextFloat();
            emberY[i] = rnd.nextFloat();
            emberSpeed[i] = 0.008f + rnd.nextFloat() * 0.03f;
            emberSize[i] = 1f + rnd.nextFloat() * 2.4f;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        gradient = new LinearGradient(0, 0, 0, h, 0xFF12151C, 0xFF07080B, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        time += dt;

        int w = getWidth(), h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(gradient);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Perspective grid receding towards a vanishing point.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0x14E8A33D);
        float horizon = h * 0.62f;
        for (int i = 1; i <= 14; i++) {
            float t = i / 14f;
            float y = horizon + (h - horizon) * t * t;
            canvas.drawLine(0, y, w, y, paint);
        }
        for (int i = -10; i <= 10; i++) {
            float x = w * 0.5f + i * w * 0.10f;
            canvas.drawLine(w * 0.5f, horizon, x, h, paint);
        }

        // Embers rising through the frame.
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < EMBERS; i++) {
            emberY[i] -= emberSpeed[i] * dt;
            if (emberY[i] < -0.05f) {
                emberY[i] = 1.05f;
                emberX[i] = rnd.nextFloat();
            }
            float drift = (float) Math.sin(time * 0.6f + i) * 0.01f;
            float alpha = 0.20f + 0.55f * (float) Math.abs(Math.sin(time * 1.5f + i * 2.1f));
            paint.setColor(((int) (alpha * 190) << 24) | 0x00E8A33D);
            canvas.drawCircle((emberX[i] + drift) * w, emberY[i] * h, emberSize[i], paint);
        }

        postInvalidateOnAnimation();
    }
}
