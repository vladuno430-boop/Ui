package com.arena3.ui;

import android.view.MotionEvent;

import com.arena3.core.MathUtil;
import com.arena3.game.MoveInput;
import com.arena3.gl.SpriteBatch;

/**
 * On-screen controls: a floating movement stick on the left, look-drag on the
 * right, and the action buttons. Touches arrive on the UI thread and are read on
 * the render thread, so the shared state is guarded.
 */
public final class TouchControls {

    private static final int MAX_POINTERS = 10;

    // per-pointer role
    private static final int ROLE_NONE = 0;
    private static final int ROLE_MOVE = 1;
    private static final int ROLE_LOOK = 2;
    private static final int ROLE_FIRE = 3;
    private static final int ROLE_JUMP = 4;
    private static final int ROLE_CROUCH = 5;
    private static final int ROLE_WEAPON = 6;

    private final int[] role = new int[MAX_POINTERS];
    private final float[] startX = new float[MAX_POINTERS];
    private final float[] startY = new float[MAX_POINTERS];
    private final float[] curX = new float[MAX_POINTERS];
    private final float[] curY = new float[MAX_POINTERS];

    private int viewWidth = 1280, viewHeight = 720;

    // resolved input
    private float moveX, moveY;
    private float pendingYaw, pendingPitch;
    private boolean fire, jump, crouch;
    private boolean weaponTapped;
    private boolean anyTap;

    // settings
    public float sensitivity = 0.16f;
    public float scale = 1f;
    public boolean invertY;
    public boolean southpaw;
    public boolean showZones;

    /** Set when the pause corner is tapped. */
    private boolean pauseRequested;

    private float stickBaseX, stickBaseY, stickX, stickY;
    private boolean stickActive;

    public void setViewport(int width, int height) {
        viewWidth = width;
        viewHeight = height;
    }

    private float ui() {
        return (viewHeight / 720f) * scale;
    }

    // --------------------------------------------------------------- geometry

    private float fireX() {
        return southpaw ? 116 * ui() : viewWidth - 116 * ui();
    }

    private float fireY() {
        return viewHeight - 116 * ui();
    }

    private float fireR() {
        return 68 * ui();
    }

    private float jumpX() {
        return southpaw ? 244 * ui() : viewWidth - 244 * ui();
    }

    private float jumpY() {
        return viewHeight - 92 * ui();
    }

    private float jumpR() {
        return 48 * ui();
    }

    private float crouchX() {
        return southpaw ? 108 * ui() : viewWidth - 108 * ui();
    }

    private float crouchY() {
        return viewHeight - 258 * ui();
    }

    private float crouchR() {
        return 40 * ui();
    }

    private float weaponX() {
        return southpaw ? 246 * ui() : viewWidth - 246 * ui();
    }

    private float weaponY() {
        return viewHeight - 224 * ui();
    }

    private float weaponR() {
        return 42 * ui();
    }

    private boolean inCircle(float x, float y, float cx, float cy, float r) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }

    /** The half of the screen that drives movement. */
    private boolean inMoveZone(float x, float y) {
        return southpaw ? x > viewWidth * 0.55f : x < viewWidth * 0.45f;
    }

    // ------------------------------------------------------------------ input

    public synchronized boolean onTouch(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                if (id >= MAX_POINTERS) return true;
                float x = event.getX(index), y = event.getY(index);
                startX[id] = curX[id] = x;
                startY[id] = curY[id] = y;
                role[id] = classify(x, y);
                applyPress(id, true);
                anyTap = true;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    if (id >= MAX_POINTERS || role[id] == ROLE_NONE) continue;
                    float x = event.getX(i), y = event.getY(i);
                    if (role[id] == ROLE_LOOK) {
                        pendingYaw += (x - curX[id]);
                        pendingPitch += (y - curY[id]);
                    }
                    curX[id] = x;
                    curY[id] = y;
                }
                updateStick();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                if (id >= MAX_POINTERS) return true;
                applyPress(id, false);
                role[id] = ROLE_NONE;
                updateStick();
                break;
            }
            default:
                break;
        }
        return true;
    }

    private int classify(float x, float y) {
        // The pause corner sits opposite the action buttons.
        float corner = 54 * ui();
        if (y < corner && (southpaw ? x > viewWidth - corner * 2 : x < corner * 2)) {
            pauseRequested = true;
            return ROLE_NONE;
        }
        if (inCircle(x, y, fireX(), fireY(), fireR())) return ROLE_FIRE;
        if (inCircle(x, y, jumpX(), jumpY(), jumpR())) return ROLE_JUMP;
        if (inCircle(x, y, crouchX(), crouchY(), crouchR())) return ROLE_CROUCH;
        if (inCircle(x, y, weaponX(), weaponY(), weaponR())) return ROLE_WEAPON;
        if (inMoveZone(x, y)) return ROLE_MOVE;
        return ROLE_LOOK;
    }

    private void applyPress(int id, boolean down) {
        switch (role[id]) {
            case ROLE_FIRE: fire = down; break;
            case ROLE_JUMP: jump = down; break;
            case ROLE_CROUCH: crouch = down; break;
            case ROLE_WEAPON: if (down) weaponTapped = true; break;
            default: break;
        }
    }

    private void updateStick() {
        stickActive = false;
        moveX = 0;
        moveY = 0;
        for (int id = 0; id < MAX_POINTERS; id++) {
            if (role[id] != ROLE_MOVE) continue;
            stickActive = true;
            stickBaseX = startX[id];
            stickBaseY = startY[id];
            stickX = curX[id];
            stickY = curY[id];
            float radius = 92 * ui();
            float dx = (curX[id] - startX[id]) / radius;
            float dy = (curY[id] - startY[id]) / radius;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            // A small dead zone stops the player drifting on a resting thumb.
            float dead = 0.12f;
            if (len < dead) {
                dx = dy = 0;
            } else {
                float t = Math.min(1f, (len - dead) / (1f - dead)) / len;
                dx *= t;
                dy *= t;
            }
            moveX = dx;
            moveY = -dy;
            break;
        }
    }

    /** Applies the accumulated touch state to a movement command. */
    public synchronized void fill(MoveInput cmd, boolean allowLook) {
        cmd.right = moveX;
        cmd.forward = moveY;
        cmd.fire = fire;
        cmd.jump = jump;
        cmd.crouch = crouch;
        if (allowLook) {
            float s = sensitivity * (1280f / Math.max(1, viewWidth)) * 0.9f;
            cmd.deltaYaw = -pendingYaw * s;
            cmd.deltaPitch = (invertY ? -1f : 1f) * pendingPitch * s;
        }
        pendingYaw = 0;
        pendingPitch = 0;
    }

    public synchronized boolean consumeWeaponTap() {
        boolean v = weaponTapped;
        weaponTapped = false;
        return v;
    }

    public synchronized boolean consumeTap() {
        boolean v = anyTap;
        anyTap = false;
        return v;
    }

    public synchronized boolean consumePause() {
        boolean v = pauseRequested;
        pauseRequested = false;
        return v;
    }

    public synchronized void releaseAll() {
        java.util.Arrays.fill(role, ROLE_NONE);
        fire = jump = crouch = false;
        moveX = moveY = 0;
        stickActive = false;
        pendingYaw = pendingPitch = 0;
    }

    // ------------------------------------------------------------------ draw

    public void draw(SpriteBatch b) {
        float u = ui();
        float alpha = 0.30f;

        if (showZones) {
            float split = southpaw ? viewWidth * 0.55f : viewWidth * 0.45f;
            if (southpaw) {
                b.rect(split, 0, viewWidth - split, viewHeight, 0.3f, 0.5f, 0.9f, 0.05f);
            } else {
                b.rect(0, 0, split, viewHeight, 0.3f, 0.5f, 0.9f, 0.05f);
            }
        }

        // Movement stick: only visible while in use.
        if (stickActive) {
            ring(b, stickBaseX, stickBaseY, 92 * u, 2.5f * u, 0.85f, 0.88f, 0.94f, alpha);
            float dx = stickX - stickBaseX, dy = stickY - stickBaseY;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float max = 92 * u;
            if (len > max) {
                dx = dx / len * max;
                dy = dy / len * max;
            }
            disc(b, stickBaseX + dx, stickBaseY + dy, 34 * u, 0.90f, 0.66f, 0.26f, 0.55f);
        } else {
            float cx = southpaw ? viewWidth - 150 * u : 150 * u;
            ring(b, cx, viewHeight - 150 * u, 92 * u, 2f * u, 0.7f, 0.75f, 0.85f, 0.12f);
        }

        button(b, fireX(), fireY(), fireR(), "FIRE", fire, 0.92f, 0.32f, 0.24f);
        button(b, jumpX(), jumpY(), jumpR(), "JUMP", jump, 0.35f, 0.65f, 0.95f);
        button(b, crouchX(), crouchY(), crouchR(), "DUCK", crouch, 0.55f, 0.60f, 0.70f);
        button(b, weaponX(), weaponY(), weaponR(), "WEAP", false, 0.85f, 0.70f, 0.30f);

        // Pause corner.
        float corner = 30 * u;
        float px = southpaw ? viewWidth - corner - 16 * u : 16 * u;
        b.rect(px, 14 * u, 7 * u, corner, 0.85f, 0.88f, 0.94f, 0.5f);
        b.rect(px + 13 * u, 14 * u, 7 * u, corner, 0.85f, 0.88f, 0.94f, 0.5f);
    }

    private void button(SpriteBatch b, float cx, float cy, float r, String label, boolean pressed,
                        float cr, float cg, float cb) {
        disc(b, cx, cy, r, cr, cg, cb, pressed ? 0.40f : 0.16f);
        ring(b, cx, cy, r, 2.5f, cr, cg, cb, pressed ? 0.95f : 0.5f);
        b.textCentered(label, cx, cy - r * 0.19f, r * 0.42f, 0.95f, 0.96f, 0.98f,
                pressed ? 1f : 0.75f);
    }

    private void disc(SpriteBatch b, float cx, float cy, float r,
                      float cr, float cg, float cb, float a) {
        int segments = 20;
        for (int i = 0; i < segments; i++) {
            double a0 = i * 2 * Math.PI / segments;
            double a1 = (i + 1) * 2 * Math.PI / segments;
            // Triangle fan approximated with thin quads from the centre.
            b.line(cx, cy, cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r,
                    (float) (2.2 * Math.PI * r / segments), cr, cg, cb, a);
            b.line(cx + (float) Math.cos(a0) * r * 0.5f, cy + (float) Math.sin(a0) * r * 0.5f,
                    cx + (float) Math.cos(a1) * r * 0.5f, cy + (float) Math.sin(a1) * r * 0.5f,
                    r, cr, cg, cb, a);
        }
    }

    private void ring(SpriteBatch b, float cx, float cy, float r, float thickness,
                      float cr, float cg, float cb, float a) {
        int segments = 28;
        float prevX = cx + r, prevY = cy;
        for (int i = 1; i <= segments; i++) {
            double ang = i * 2 * Math.PI / segments;
            float x = cx + (float) Math.cos(ang) * r;
            float y = cy + (float) Math.sin(ang) * r;
            b.line(prevX, prevY, x, y, thickness, cr, cg, cb, a);
            prevX = x;
            prevY = y;
        }
    }
}
