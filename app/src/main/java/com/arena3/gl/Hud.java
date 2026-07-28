package com.arena3.gl;

import com.arena3.core.MathUtil;
import com.arena3.core.Vec3;
import com.arena3.game.GameEvent;
import com.arena3.game.GameWorld;
import com.arena3.game.ItemDef;
import com.arena3.game.PlayerState;
import com.arena3.game.WeaponDef;
import com.arena3.render.Models;

/** Draws the in-game interface: status, crosshair, feed, callouts, scoreboard. */
public final class Hud {

    private static final int FEED_LINES = 5;
    private static final float FEED_LIFE = 5f;

    private final String[] feedText = new String[FEED_LINES];
    private final float[] feedTime = new float[FEED_LINES];
    private int feedHead;

    private String announcement;
    private float announceTime = -100f;

    private String pickupText;
    private float pickupTime = -100f;

    private float hitMarkerTime = -100f;
    private float damageFlash;
    private float lastHealth = 100;

    /** Direction indicators: angle around the player, plus a fading strength. */
    private final float[] damageAngle = new float[6];
    private final float[] damageLife = new float[6];
    private int damageCursor;

    private float fps;
    private float fpsAccum;
    private int fpsFrames;

    private final Vec3 tmp = new Vec3();

    public void reset() {
        java.util.Arrays.fill(feedText, null);
        java.util.Arrays.fill(feedTime, -100f);
        java.util.Arrays.fill(damageLife, 0f);
        announcement = null;
        announceTime = -100f;
        pickupText = null;
        pickupTime = -100f;
        damageFlash = 0f;
        lastHealth = 100;
    }

    /** Folds this frame's events into the HUD's own state. */
    public void consume(GameWorld world) {
        PlayerState me = world.localPlayer();
        for (GameEvent ev : world.events) {
            switch (ev.type) {
                case GameEvent.FRAG:
                    feedText[feedHead] = ev.text;
                    feedTime[feedHead] = world.matchTime;
                    feedHead = (feedHead + 1) % FEED_LINES;
                    break;
                case GameEvent.ANNOUNCE:
                    // Only callouts aimed at us, or global ones.
                    if (ev.b < 0 || ev.b == me.index) {
                        announcement = ev.text;
                        announceTime = world.matchTime;
                    }
                    break;
                case GameEvent.HIT_CONFIRM:
                    hitMarkerTime = world.matchTime;
                    break;
                case GameEvent.PICKUP:
                    if (ev.b == me.index) {
                        pickupText = ItemDef.get(ev.a).name;
                        pickupTime = world.matchTime;
                    }
                    break;
                case GameEvent.PAIN:
                    if (ev.b == me.index) {
                        damageFlash = Math.min(1f, damageFlash + ev.a / 90f);
                        // Remember which way it came from for the direction arcs.
                        tmp.set(me.lastDamageDir);
                        damageAngle[damageCursor] = MathUtil.yawOf(tmp);
                        damageLife[damageCursor] = 1f;
                        damageCursor = (damageCursor + 1) % damageAngle.length;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    public void update(float dt) {
        damageFlash = Math.max(0f, damageFlash - dt * 1.6f);
        for (int i = 0; i < damageLife.length; i++) {
            damageLife[i] = Math.max(0f, damageLife[i] - dt * 0.7f);
        }
        fpsAccum += dt;
        fpsFrames++;
        if (fpsAccum >= 0.5f) {
            fps = fpsFrames / fpsAccum;
            fpsAccum = 0f;
            fpsFrames = 0;
        }
    }

    public void draw(SpriteBatch b, GameWorld world, boolean showFps, boolean showScores) {
        PlayerState me = world.localPlayer();
        float w = b.width(), h = b.height();
        float s = h / 720f;

        drawFullScreenTints(b, me, w, h);
        if (me.alive) {
            drawCrosshair(b, world, me, w, h, s);
        }
        drawDamageDirections(b, me, w, h, s);
        drawStatus(b, me, w, h, s);
        drawWeaponStrip(b, me, w, h, s);
        drawMatchBar(b, world, w, h, s);
        drawFeed(b, world, w, h, s);
        drawCallouts(b, world, me, w, h, s);
        drawPowerups(b, me, w, h, s);

        if (!me.alive) drawDeathOverlay(b, world, me, w, h, s);
        if (showScores || world.state == GameWorld.STATE_OVER) drawScoreboard(b, world, w, h, s);
        if (world.state == GameWorld.STATE_COUNTDOWN) drawCountdown(b, world, w, h, s);

        if (showFps) {
            b.textRight(String.format(java.util.Locale.US, "%.0f FPS", fps), w - 12 * s, 8 * s,
                    16 * s, 0.55f, 0.60f, 0.70f, 0.8f);
        }
    }

    private void drawFullScreenTints(SpriteBatch b, PlayerState me, float w, float h) {
        if (damageFlash > 0.01f) {
            b.rect(0, 0, w, h, 0.75f, 0.05f, 0.05f, damageFlash * 0.32f);
        }
        // Health vignette: the lower it gets, the redder the edges.
        if (me.alive && me.health < 40) {
            float t = (40 - me.health) / 40f;
            float band = h * 0.16f;
            b.rect(0, 0, w, band, 0.6f, 0.05f, 0.05f, t * 0.22f);
            b.rect(0, h - band, w, band, 0.6f, 0.05f, 0.05f, t * 0.22f);
        }
        if (me.hasPowerup(ItemDef.PW_QUAD)) {
            b.rect(0, 0, w, h, 0.20f, 0.30f, 0.95f, 0.07f);
        }
    }

    private void drawCrosshair(SpriteBatch b, GameWorld world, PlayerState me,
                               float w, float h, float s) {
        float cx = w * 0.5f, cy = h * 0.5f;
        float t = 2f * s;
        float r = 0.85f, g = 0.90f, bl = 0.95f, a = 0.85f;

        boolean hit = world.matchTime - hitMarkerTime < 0.25f;
        if (hit) {
            r = 1f;
            g = 0.35f;
            bl = 0.25f;
            a = 1f;
        }

        // Each weapon gets its own reticle, as in the original.
        switch (me.weapon) {
            case WeaponDef.RAILGUN: {
                float gap = 9 * s, len = 13 * s;
                b.line(cx - gap - len, cy, cx - gap, cy, t, r, g, bl, a);
                b.line(cx + gap, cy, cx + gap + len, cy, t, r, g, bl, a);
                b.line(cx, cy - gap - len, cx, cy - gap, t, r, g, bl, a);
                b.line(cx, cy + gap, cx, cy + gap + len, t, r, g, bl, a);
                b.rect(cx - 1.5f * s, cy - 1.5f * s, 3 * s, 3 * s, r, g, bl, a);
                break;
            }
            case WeaponDef.ROCKET:
            case WeaponDef.VOIDCANNON: {
                float rad = 13 * s;
                circle(b, cx, cy, rad, t, r, g, bl, a * 0.9f);
                b.rect(cx - 1.5f * s, cy - 1.5f * s, 3 * s, 3 * s, r, g, bl, a);
                break;
            }
            case WeaponDef.SHOTGUN: {
                float rad = 17 * s;
                circle(b, cx, cy, rad, t, r, g, bl, a * 0.7f);
                b.line(cx - 5 * s, cy, cx + 5 * s, cy, t, r, g, bl, a);
                b.line(cx, cy - 5 * s, cx, cy + 5 * s, t, r, g, bl, a);
                break;
            }
            case WeaponDef.GAUNTLET: {
                float gap = 5 * s, len = 9 * s;
                for (int i = 0; i < 4; i++) {
                    double ang = Math.PI / 4 + i * Math.PI / 2;
                    float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
                    b.line(cx + dx * gap, cy + dy * gap, cx + dx * (gap + len), cy + dy * (gap + len),
                            t, r, g, bl, a);
                }
                break;
            }
            default: {
                float gap = 6 * s, len = 10 * s;
                b.line(cx - gap - len, cy, cx - gap, cy, t, r, g, bl, a);
                b.line(cx + gap, cy, cx + gap + len, cy, t, r, g, bl, a);
                b.line(cx, cy - gap - len, cx, cy - gap, t, r, g, bl, a);
                b.line(cx, cy + gap, cx, cy + gap + len, t, r, g, bl, a);
                break;
            }
        }

        if (hit) {
            float d = 7 * s, l = 6 * s;
            for (int i = 0; i < 4; i++) {
                double ang = Math.PI / 4 + i * Math.PI / 2;
                float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
                b.line(cx + dx * d, cy + dy * d, cx + dx * (d + l), cy + dy * (d + l), 2.5f * s,
                        1f, 0.4f, 0.25f, 1f);
            }
        }
    }

    private void circle(SpriteBatch b, float cx, float cy, float radius, float thickness,
                        float r, float g, float bl, float a) {
        int segments = 24;
        float prevX = cx + radius, prevY = cy;
        for (int i = 1; i <= segments; i++) {
            double ang = i * 2 * Math.PI / segments;
            float x = cx + (float) Math.cos(ang) * radius;
            float y = cy + (float) Math.sin(ang) * radius;
            b.line(prevX, prevY, x, y, thickness, r, g, bl, a);
            prevX = x;
            prevY = y;
        }
    }

    private void drawDamageDirections(SpriteBatch b, PlayerState me, float w, float h, float s) {
        float cx = w * 0.5f, cy = h * 0.5f;
        for (int i = 0; i < damageLife.length; i++) {
            if (damageLife[i] <= 0f) continue;
            // Where the hit came from, relative to where we are looking.
            float rel = MathUtil.angleDelta(me.yaw, damageAngle[i] + 180f) * MathUtil.DEG2RAD;
            float dist = 120f * s;
            float dx = (float) Math.sin(rel), dy = -(float) Math.cos(rel);
            float px = cx + dx * dist, py = cy + dy * dist;
            float sx = -dy, sy = dx;
            float len = 34f * s, thick = 7f * s;
            b.line(px - sx * len * 0.5f, py - sy * len * 0.5f, px + sx * len * 0.5f, py + sy * len * 0.5f,
                    thick, 0.95f, 0.20f, 0.15f, damageLife[i] * 0.8f);
        }
    }

    private void drawStatus(SpriteBatch b, PlayerState me, float w, float h, float s) {
        float pad = 22 * s;
        float baseY = h - 74 * s;

        // Health
        float healthColorR = me.health <= 25 ? 1.0f : 0.92f;
        float healthColorG = me.health <= 25 ? 0.25f : 0.94f;
        float healthColorB = me.health <= 25 ? 0.22f : 0.98f;
        b.textShadowed(String.valueOf(Math.max(0, me.health)), pad, baseY, 54 * s,
                healthColorR, healthColorG, healthColorB, 0.95f);
        b.text("HEALTH", pad + 2 * s, baseY + 50 * s, 14 * s, 0.55f, 0.60f, 0.68f, 0.8f);

        // Armor
        float armorX = pad + 130 * s;
        b.textShadowed(String.valueOf(me.armor), armorX, baseY, 54 * s, 0.92f, 0.74f, 0.24f, 0.95f);
        b.text("ARMOR", armorX + 2 * s, baseY + 50 * s, 14 * s, 0.55f, 0.60f, 0.68f, 0.8f);

        // Ammo
        WeaponDef def = WeaponDef.get(me.weapon);
        String ammo = def.usesAmmo() ? String.valueOf(me.ammo[me.weapon]) : "--";
        boolean low = def.usesAmmo() && me.ammo[me.weapon] <= def.ammoPerShot * 3;
        b.textRight(ammo, w - pad, baseY, 54 * s, low ? 1f : 0.92f, low ? 0.3f : 0.94f,
                low ? 0.25f : 0.98f, 0.95f);
        b.textRight(def.name.toUpperCase(), w - pad, baseY + 50 * s, 14 * s, 0.55f, 0.60f, 0.68f, 0.8f);
    }

    /** Small row of owned weapons, with the current one highlighted. */
    private void drawWeaponStrip(SpriteBatch b, PlayerState me, float w, float h, float s) {
        float cell = 34 * s;
        int owned = 0;
        for (int i = 0; i < WeaponDef.COUNT; i++) if (me.hasWeapon(i)) owned++;
        float totalW = owned * (cell + 4 * s);
        float x = w * 0.5f - totalW * 0.5f;
        float y = h - 40 * s;

        for (int i = 0; i < WeaponDef.COUNT; i++) {
            if (!me.hasWeapon(i)) continue;
            WeaponDef def = WeaponDef.get(i);
            boolean active = i == me.weapon;
            boolean hasAmmo = !def.usesAmmo() || me.ammo[i] >= def.ammoPerShot;
            b.rect(x, y, cell, cell * 0.8f, 0.05f, 0.06f, 0.08f, active ? 0.75f : 0.45f);
            if (active) b.frame(x, y, cell, cell * 0.8f, 1.5f * s, 0.90f, 0.64f, 0.24f, 0.9f);
            float alpha = hasAmmo ? (active ? 1f : 0.65f) : 0.25f;
            b.textCentered(def.shortName, x + cell * 0.5f, y + cell * 0.16f, 15 * s,
                    active ? 0.95f : 0.75f, active ? 0.80f : 0.78f, active ? 0.45f : 0.82f, alpha);
            x += cell + 4 * s;
        }
    }

    private void drawMatchBar(SpriteBatch b, GameWorld world, float w, float h, float s) {
        PlayerState me = world.localPlayer();
        PlayerState[] board = world.scoreboard();
        int lead = board[0].frags;
        int mine = me.frags;

        float y = 10 * s;
        b.textShadowed(String.valueOf(mine), 22 * s, y, 30 * s, 0.92f, 0.72f, 0.26f, 0.95f);
        b.text("YOU", 22 * s, y + 30 * s, 12 * s, 0.5f, 0.55f, 0.62f, 0.8f);

        String leadLabel = board[0] == me && board.length > 1 ? board[1].name : board[0].name;
        int leadScore = board[0] == me && board.length > 1 ? board[1].frags : lead;
        b.textShadowed(String.valueOf(leadScore), 92 * s, y, 30 * s, 0.85f, 0.88f, 0.94f, 0.9f);
        b.text(leadLabel.toUpperCase(), 92 * s, y + 30 * s, 12 * s, 0.5f, 0.55f, 0.62f, 0.8f);

        if (world.config.timeLimitSeconds > 0) {
            int remaining = (int) Math.ceil(world.timeRemaining());
            String clock = String.format(java.util.Locale.US, "%d:%02d", remaining / 60, remaining % 60);
            b.textCentered(clock, w * 0.5f, y, 26 * s, 0.85f, 0.88f, 0.94f, 0.85f);
        }
        if (world.config.fragLimit > 0) {
            b.textCentered("FRAGS TO " + world.config.fragLimit, w * 0.5f, y + 30 * s, 12 * s,
                    0.5f, 0.55f, 0.62f, 0.75f);
        }
    }

    private void drawFeed(SpriteBatch b, GameWorld world, float w, float h, float s) {
        float y = 12 * s;
        for (int i = 0; i < FEED_LINES; i++) {
            int index = (feedHead + i) % FEED_LINES;
            if (feedText[index] == null) continue;
            float age = world.matchTime - feedTime[index];
            if (age > FEED_LIFE) continue;
            float alpha = Math.min(1f, (FEED_LIFE - age) / 1.2f);
            b.textRight(feedText[index], w - 22 * s, y, 15 * s, 0.86f, 0.88f, 0.92f, alpha * 0.95f);
            y += 20 * s;
        }
    }

    private void drawCallouts(SpriteBatch b, GameWorld world, PlayerState me,
                              float w, float h, float s) {
        float announceAge = world.matchTime - announceTime;
        if (announcement != null && announceAge < 2.2f) {
            float alpha = Math.min(1f, (2.2f - announceAge) / 0.6f);
            float scale = 1f + Math.max(0f, 0.25f - announceAge) * 1.6f;
            b.textCenteredShadowed(announcement, w * 0.5f, h * 0.26f, 42 * s * scale,
                    0.95f, 0.72f, 0.25f, alpha);
        }
        float pickupAge = world.matchTime - pickupTime;
        if (pickupText != null && pickupAge < 1.6f) {
            float alpha = Math.min(1f, (1.6f - pickupAge) / 0.5f);
            b.textCentered(pickupText.toUpperCase(), w * 0.5f, h * 0.62f, 18 * s,
                    0.80f, 0.85f, 0.92f, alpha * 0.9f);
        }
    }

    private void drawPowerups(SpriteBatch b, PlayerState me, float w, float h, float s) {
        float y = h * 0.34f;
        String[] names = {"QUAD", "HASTE", "REGEN", "INVIS", "SUIT"};
        float[][] colors = {
                {0.45f, 0.55f, 1f}, {1f, 0.85f, 0.35f}, {1f, 0.4f, 0.4f},
                {0.75f, 0.85f, 1f}, {1f, 0.75f, 0.3f},
        };
        for (int i = 0; i < ItemDef.PW_COUNT; i++) {
            if (me.powerupTime[i] <= 0f) continue;
            String label = names[i] + "  " + (int) Math.ceil(me.powerupTime[i]);
            b.textRight(label, w - 22 * s, y, 18 * s, colors[i][0], colors[i][1], colors[i][2], 0.95f);
            y += 24 * s;
        }
    }

    private void drawDeathOverlay(SpriteBatch b, GameWorld world, PlayerState me,
                                  float w, float h, float s) {
        b.rect(0, 0, w, h, 0.35f, 0.02f, 0.02f, 0.28f);
        String killer = me.lastAttacker >= 0 && me.lastAttacker != me.index
                ? world.players[me.lastAttacker].name : null;
        if (killer != null) {
            b.textCenteredShadowed("FRAGGED BY " + killer, w * 0.5f, h * 0.36f, 30 * s,
                    0.92f, 0.35f, 0.30f, 0.95f);
        } else {
            b.textCenteredShadowed("YOU DIED", w * 0.5f, h * 0.36f, 30 * s, 0.92f, 0.35f, 0.30f, 0.95f);
        }
        float wait = world.respawnRemaining(me.index);
        String prompt = wait > 0.05f
                ? String.format(java.util.Locale.US, "RESPAWN IN %.1f", wait)
                : "TAP TO RESPAWN";
        b.textCentered(prompt, w * 0.5f, h * 0.46f, 20 * s, 0.85f, 0.88f, 0.92f, 0.9f);
    }

    private void drawCountdown(SpriteBatch b, GameWorld world, float w, float h, float s) {
        int n = (int) Math.ceil(world.countdown);
        if (n <= 0) return;
        b.textCenteredShadowed(String.valueOf(n), w * 0.5f, h * 0.30f, 90 * s, 0.95f, 0.72f, 0.25f, 0.9f);
        b.textCentered(world.map.name, w * 0.5f, h * 0.18f, 26 * s, 0.85f, 0.88f, 0.94f, 0.9f);
    }

    private void drawScoreboard(SpriteBatch b, GameWorld world, float w, float h, float s) {
        PlayerState[] board = world.scoreboard();
        float rowH = 30 * s;
        float boardW = Math.min(w * 0.7f, 520 * s);
        float boardH = rowH * (board.length + 2.4f);
        float x = w * 0.5f - boardW * 0.5f;
        float y = h * 0.5f - boardH * 0.5f;

        b.rect(x, y, boardW, boardH, 0.03f, 0.04f, 0.06f, 0.88f);
        b.frame(x, y, boardW, boardH, 1.5f * s, 0.35f, 0.40f, 0.48f, 0.8f);

        String title = world.state == GameWorld.STATE_OVER
                ? (world.winner == 0 ? "YOU WIN" : world.players[world.winner].name + " WINS")
                : "SCOREBOARD";
        b.textCentered(title, w * 0.5f, y + 8 * s, 22 * s, 0.95f, 0.72f, 0.25f, 1f);

        float rowY = y + rowH * 1.4f;
        for (PlayerState ps : board) {
            float[] color = Models.PLAYER_COLORS[ps.colorIndex % Models.PLAYER_COLORS.length];
            boolean isMe = ps.index == 0;
            if (isMe) b.rect(x + 6 * s, rowY - 3 * s, boardW - 12 * s, rowH - 4 * s,
                    0.90f, 0.64f, 0.24f, 0.13f);
            b.rect(x + 16 * s, rowY + 4 * s, 12 * s, 12 * s, color[0], color[1], color[2], 0.95f);
            b.text(ps.name, x + 38 * s, rowY, 18 * s, 0.90f, 0.92f, 0.95f, 1f);
            b.textRight(String.valueOf(ps.frags), x + boardW - 90 * s, rowY, 18 * s,
                    0.95f, 0.80f, 0.35f, 1f);
            b.textRight(String.valueOf(ps.deaths), x + boardW - 26 * s, rowY, 18 * s,
                    0.65f, 0.68f, 0.74f, 1f);
            rowY += rowH;
        }
        b.textRight("FRAGS", x + boardW - 90 * s, y + rowH * 0.95f, 11 * s, 0.5f, 0.55f, 0.62f, 0.9f);
        b.textRight("DEATHS", x + boardW - 26 * s, y + rowH * 0.95f, 11 * s, 0.5f, 0.55f, 0.62f, 0.9f);
    }
}
