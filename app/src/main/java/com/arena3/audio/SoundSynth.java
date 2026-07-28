package com.arena3.audio;

import java.util.Random;

/**
 * Synthesises every sound effect as raw PCM at start-up. Nothing is loaded from
 * a file: weapons, impacts, pickups and voice-free announcer stings are all
 * built from noise, oscillators and envelopes.
 */
public final class SoundSynth {

    public static final int SAMPLE_RATE = 22050;

    // ---- sound ids ----
    public static final int GAUNTLET = 0;
    public static final int MACHINEGUN = 1;
    public static final int SHOTGUN = 2;
    public static final int GRENADE_FIRE = 3;
    public static final int ROCKET_FIRE = 4;
    public static final int LIGHTNING = 5;
    public static final int RAILGUN = 6;
    public static final int PLASMA_FIRE = 7;
    public static final int VOID_FIRE = 8;
    public static final int EXPLOSION = 9;
    public static final int EXPLOSION_SMALL = 10;
    public static final int IMPACT = 11;
    public static final int FLESH_HIT = 12;
    public static final int BOUNCE = 13;
    public static final int PICKUP_ITEM = 14;
    public static final int PICKUP_HEALTH = 15;
    public static final int PICKUP_ARMOR = 16;
    public static final int PICKUP_WEAPON = 17;
    public static final int PICKUP_POWERUP = 18;
    public static final int JUMP = 19;
    public static final int LAND = 20;
    public static final int PAIN = 21;
    public static final int DEATH = 22;
    public static final int GIB = 23;
    public static final int TELEPORT = 24;
    public static final int JUMPPAD = 25;
    public static final int HIT_CONFIRM = 26;
    public static final int NO_AMMO = 27;
    public static final int WEAPON_SWITCH = 28;
    public static final int ANNOUNCE = 29;
    public static final int COUNT = 30;

    private SoundSynth() {
    }

    /** Generates one effect as mono samples in [-1, 1]. */
    public static float[] generate(int id) {
        Random rnd = new Random(id * 7919L + 13);
        switch (id) {
            case GAUNTLET: return gauntlet(rnd);
            case MACHINEGUN: return machinegun(rnd);
            case SHOTGUN: return shotgun(rnd);
            case GRENADE_FIRE: return grenadeFire(rnd);
            case ROCKET_FIRE: return rocketFire(rnd);
            case LIGHTNING: return lightning(rnd);
            case RAILGUN: return railgun(rnd);
            case PLASMA_FIRE: return plasmaFire(rnd);
            case VOID_FIRE: return voidFire(rnd);
            case EXPLOSION: return explosion(rnd, 1.0f);
            case EXPLOSION_SMALL: return explosion(rnd, 0.45f);
            case IMPACT: return impact(rnd);
            case FLESH_HIT: return fleshHit(rnd);
            case BOUNCE: return bounce(rnd);
            case PICKUP_ITEM: return chime(rnd, 880f, 1320f, 0.22f);
            case PICKUP_HEALTH: return chime(rnd, 660f, 990f, 0.28f);
            case PICKUP_ARMOR: return chime(rnd, 520f, 780f, 0.30f);
            case PICKUP_WEAPON: return pickupWeapon(rnd);
            case PICKUP_POWERUP: return powerup(rnd);
            case JUMP: return jump(rnd);
            case LAND: return land(rnd);
            case PAIN: return pain(rnd);
            case DEATH: return death(rnd);
            case GIB: return gib(rnd);
            case TELEPORT: return teleport(rnd);
            case JUMPPAD: return jumpPad(rnd);
            case HIT_CONFIRM: return tick(rnd);
            case NO_AMMO: return noAmmo(rnd);
            case WEAPON_SWITCH: return weaponSwitch(rnd);
            case ANNOUNCE: return announce(rnd);
            default: return new float[1];
        }
    }

    // ------------------------------------------------------------- primitives

    private static float[] buffer(float seconds) {
        return new float[Math.max(1, (int) (seconds * SAMPLE_RATE))];
    }

    /** Exponential decay envelope. */
    private static float decay(float t, float rate) {
        return (float) Math.exp(-t * rate);
    }

    private static float sine(float phase) {
        return (float) Math.sin(phase * 2 * Math.PI);
    }

    /** One-pole low-pass, applied in place. */
    private static void lowPass(float[] buf, float cutoffHz) {
        float rc = 1f / (2f * (float) Math.PI * cutoffHz);
        float dt = 1f / SAMPLE_RATE;
        float alpha = dt / (rc + dt);
        float prev = 0f;
        for (int i = 0; i < buf.length; i++) {
            prev += alpha * (buf[i] - prev);
            buf[i] = prev;
        }
    }

    /** One-pole high-pass, applied in place. */
    private static void highPass(float[] buf, float cutoffHz) {
        float rc = 1f / (2f * (float) Math.PI * cutoffHz);
        float dt = 1f / SAMPLE_RATE;
        float alpha = rc / (rc + dt);
        float prevIn = 0f, prevOut = 0f;
        for (int i = 0; i < buf.length; i++) {
            float in = buf[i];
            prevOut = alpha * (prevOut + in - prevIn);
            prevIn = in;
            buf[i] = prevOut;
        }
    }

    private static void normalize(float[] buf, float peak) {
        float max = 0f;
        for (float v : buf) max = Math.max(max, Math.abs(v));
        if (max < 1e-6f) return;
        float k = peak / max;
        for (int i = 0; i < buf.length; i++) buf[i] *= k;
    }

    /** Short fade at both ends so nothing clicks. */
    private static void deClick(float[] buf) {
        int n = Math.min(64, buf.length / 4);
        for (int i = 0; i < n; i++) {
            float k = i / (float) n;
            buf[i] *= k;
            buf[buf.length - 1 - i] *= k;
        }
    }

    // --------------------------------------------------------------- weapons

    private static float[] machinegun(Random rnd) {
        float[] b = buffer(0.13f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = decay(t, 55f);
            float crack = (rnd.nextFloat() * 2 - 1) * env;
            float body = sine(t * 190f) * decay(t, 28f) * 0.5f;
            b[i] = crack * 0.8f + body;
        }
        highPass(b, 320f);
        normalize(b, 0.85f);
        deClick(b);
        return b;
    }

    private static float[] shotgun(Random rnd) {
        float[] b = buffer(0.42f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = decay(t, 14f);
            float blast = (rnd.nextFloat() * 2 - 1) * env;
            float boom = sine(t * 95f) * decay(t, 11f) * 0.7f;
            b[i] = blast * 0.85f + boom;
        }
        lowPass(b, 4200f);
        normalize(b, 0.95f);
        deClick(b);
        return b;
    }

    private static float[] rocketFire(Random rnd) {
        float[] b = buffer(0.55f);
        float phase = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            // A falling whoosh over a noisy exhaust.
            float freq = 420f * (float) Math.exp(-t * 3.2f) + 70f;
            phase += freq / SAMPLE_RATE;
            float env = decay(t, 6f);
            float hiss = (rnd.nextFloat() * 2 - 1) * decay(t, 4.5f) * 0.7f;
            b[i] = (sine(phase) * 0.8f + hiss) * env;
        }
        lowPass(b, 5200f);
        normalize(b, 0.9f);
        deClick(b);
        return b;
    }

    private static float[] grenadeFire(Random rnd) {
        float[] b = buffer(0.3f);
        float phase = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float freq = 260f * (float) Math.exp(-t * 9f) + 90f;
            phase += freq / SAMPLE_RATE;
            float env = decay(t, 16f);
            b[i] = (sine(phase) * 0.9f + (rnd.nextFloat() * 2 - 1) * 0.35f) * env;
        }
        lowPass(b, 2600f);
        normalize(b, 0.8f);
        deClick(b);
        return b;
    }

    private static float[] railgun(Random rnd) {
        float[] b = buffer(0.85f);
        float phase = 0f, phase2 = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            // Charge up, then a bright discharge that sweeps down.
            float sweep = t < 0.08f ? 300f + t * 9000f : 1050f * (float) Math.exp(-(t - 0.08f) * 3.4f) + 120f;
            phase += sweep / SAMPLE_RATE;
            phase2 += sweep * 1.5f / SAMPLE_RATE;
            float env = t < 0.08f ? t / 0.08f : decay(t - 0.08f, 4.2f);
            float tone = sine(phase) * 0.7f + sine(phase2) * 0.3f;
            float sizzle = (rnd.nextFloat() * 2 - 1) * decay(t, 9f) * 0.35f;
            b[i] = (tone + sizzle) * env;
        }
        normalize(b, 0.9f);
        deClick(b);
        return b;
    }

    private static float[] plasmaFire(Random rnd) {
        float[] b = buffer(0.16f);
        float phase = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float freq = 300f + t * 5200f;
            phase += freq / SAMPLE_RATE;
            float env = decay(t, 30f);
            b[i] = (sine(phase) * 0.85f + sine(phase * 2.01f) * 0.15f) * env;
        }
        normalize(b, 0.72f);
        deClick(b);
        return b;
    }

    private static float[] voidFire(Random rnd) {
        float[] b = buffer(0.5f);
        float phase = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float freq = 120f + 60f * sine(t * 9f);
            phase += freq / SAMPLE_RATE;
            float env = decay(t, 6f);
            float grind = (rnd.nextFloat() * 2 - 1) * 0.4f * decay(t, 5f);
            b[i] = (sine(phase) * 0.9f + sine(phase * 1.5f) * 0.5f + grind) * env;
        }
        lowPass(b, 3000f);
        normalize(b, 0.95f);
        deClick(b);
        return b;
    }

    private static float[] lightning(Random rnd) {
        float[] b = buffer(0.22f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            // Crackle: noise gated by a fast wobble.
            float gate = 0.5f + 0.5f * sine(t * 62f);
            float n = (rnd.nextFloat() * 2 - 1);
            b[i] = n * gate * decay(t, 5f);
        }
        highPass(b, 900f);
        normalize(b, 0.55f);
        deClick(b);
        return b;
    }

    private static float[] gauntlet(Random rnd) {
        float[] b = buffer(0.3f);
        float phase = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float freq = 150f + 40f * sine(t * 24f);
            phase += freq / SAMPLE_RATE;
            float saw = (phase % 1f) * 2f - 1f;
            b[i] = saw * 0.6f * decay(t, 5f);
        }
        lowPass(b, 2200f);
        normalize(b, 0.7f);
        deClick(b);
        return b;
    }

    // --------------------------------------------------------------- impacts

    private static float[] explosion(Random rnd, float scale) {
        float[] b = buffer(1.3f * scale + 0.3f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = decay(t, 3.4f / scale);
            float rumble = sine(t * (46f / scale)) * decay(t, 2.4f / scale) * 0.9f;
            float blast = (rnd.nextFloat() * 2 - 1) * env;
            b[i] = blast * 0.9f + rumble;
        }
        lowPass(b, 1500f * scale + 300f);
        normalize(b, 1.0f);
        deClick(b);
        return b;
    }

    private static float[] impact(Random rnd) {
        float[] b = buffer(0.14f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            b[i] = (rnd.nextFloat() * 2 - 1) * decay(t, 60f);
        }
        highPass(b, 1400f);
        normalize(b, 0.5f);
        deClick(b);
        return b;
    }

    private static float[] fleshHit(Random rnd) {
        float[] b = buffer(0.18f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float n = (rnd.nextFloat() * 2 - 1) * decay(t, 40f);
            float thud = sine(t * 130f) * decay(t, 26f);
            b[i] = n * 0.55f + thud * 0.7f;
        }
        lowPass(b, 1800f);
        normalize(b, 0.6f);
        deClick(b);
        return b;
    }

    private static float[] bounce(Random rnd) {
        float[] b = buffer(0.1f);
        float phase = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            phase += (620f * (float) Math.exp(-t * 12f) + 180f) / SAMPLE_RATE;
            b[i] = sine(phase) * decay(t, 42f);
        }
        normalize(b, 0.45f);
        deClick(b);
        return b;
    }

    // --------------------------------------------------------------- pickups

    private static float[] chime(Random rnd, float f0, float f1, float length) {
        float[] b = buffer(length);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = decay(t, 11f);
            b[i] = (sine(t * f0) * 0.6f + sine(t * f1) * 0.4f + sine(t * f0 * 2f) * 0.15f) * env;
        }
        normalize(b, 0.55f);
        deClick(b);
        return b;
    }

    private static float[] pickupWeapon(Random rnd) {
        float[] b = buffer(0.3f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            // Two mechanical clacks, then a tone.
            float clack = t < 0.03f || (t > 0.07f && t < 0.10f)
                    ? (rnd.nextFloat() * 2 - 1) * 0.8f : 0f;
            float tone = sine(t * 440f) * decay(Math.max(0f, t - 0.1f), 14f) * 0.5f;
            b[i] = clack * decay(t, 30f) + tone;
        }
        lowPass(b, 5000f);
        normalize(b, 0.6f);
        deClick(b);
        return b;
    }

    private static float[] powerup(Random rnd) {
        float[] b = buffer(0.9f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float rise = 1f + t * 0.9f;
            float env = Math.min(1f, t * 8f) * decay(t, 2.6f);
            b[i] = (sine(t * 330f * rise) * 0.5f + sine(t * 495f * rise) * 0.3f
                    + sine(t * 660f * rise) * 0.2f) * env;
        }
        normalize(b, 0.7f);
        deClick(b);
        return b;
    }

    // --------------------------------------------------------------- movement

    private static float[] jump(Random rnd) {
        float[] b = buffer(0.2f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            b[i] = (rnd.nextFloat() * 2 - 1) * decay(t, 22f);
        }
        lowPass(b, 1100f);
        normalize(b, 0.32f);
        deClick(b);
        return b;
    }

    private static float[] land(Random rnd) {
        float[] b = buffer(0.26f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float thud = sine(t * 78f) * decay(t, 18f);
            float grit = (rnd.nextFloat() * 2 - 1) * decay(t, 34f) * 0.5f;
            b[i] = thud * 0.9f + grit;
        }
        lowPass(b, 1400f);
        normalize(b, 0.55f);
        deClick(b);
        return b;
    }

    private static float[] pain(Random rnd) {
        float[] b = buffer(0.28f);
        float phase = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float freq = 210f * (float) Math.exp(-t * 3.5f) + 90f;
            phase += freq / SAMPLE_RATE;
            float growl = (sine(phase) + sine(phase * 1.51f) * 0.5f) * 0.5f;
            float rasp = (rnd.nextFloat() * 2 - 1) * 0.45f;
            b[i] = (growl + rasp) * decay(t, 9f);
        }
        lowPass(b, 2400f);
        normalize(b, 0.6f);
        deClick(b);
        return b;
    }

    private static float[] death(Random rnd) {
        float[] b = buffer(0.7f);
        float phase = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float freq = 260f * (float) Math.exp(-t * 2.4f) + 60f;
            phase += freq / SAMPLE_RATE;
            float body = sine(phase) * 0.6f + sine(phase * 1.33f) * 0.3f;
            float rasp = (rnd.nextFloat() * 2 - 1) * 0.5f * decay(t, 3.2f);
            b[i] = (body + rasp) * decay(t, 3.6f);
        }
        lowPass(b, 2000f);
        normalize(b, 0.7f);
        deClick(b);
        return b;
    }

    private static float[] gib(Random rnd) {
        float[] b = buffer(0.4f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float squelch = (rnd.nextFloat() * 2 - 1) * decay(t, 9f);
            float pop = sine(t * 110f) * decay(t, 16f);
            b[i] = squelch * 0.7f + pop * 0.6f;
        }
        lowPass(b, 1600f);
        normalize(b, 0.8f);
        deClick(b);
        return b;
    }

    private static float[] teleport(Random rnd) {
        float[] b = buffer(0.6f);
        float phase = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float freq = 180f + t * 1400f;
            phase += freq / SAMPLE_RATE;
            float shimmer = sine(phase) * 0.5f + sine(phase * 1.5f) * 0.3f + sine(phase * 2.02f) * 0.2f;
            float env = Math.min(1f, t * 6f) * decay(t, 4.2f);
            b[i] = shimmer * env;
        }
        normalize(b, 0.6f);
        deClick(b);
        return b;
    }

    private static float[] jumpPad(Random rnd) {
        float[] b = buffer(0.45f);
        float phase = 0f;
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float freq = 160f + t * 900f;
            phase += freq / SAMPLE_RATE;
            b[i] = (sine(phase) * 0.7f + sine(phase * 0.5f) * 0.3f) * decay(t, 5.5f);
        }
        normalize(b, 0.7f);
        deClick(b);
        return b;
    }

    // -------------------------------------------------------------- interface

    private static float[] tick(Random rnd) {
        float[] b = buffer(0.06f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            b[i] = sine(t * 1650f) * decay(t, 90f);
        }
        normalize(b, 0.45f);
        deClick(b);
        return b;
    }

    private static float[] noAmmo(Random rnd) {
        float[] b = buffer(0.1f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            b[i] = (rnd.nextFloat() * 2 - 1) * decay(t, 90f) * 0.6f
                    + sine(t * 240f) * decay(t, 60f);
        }
        highPass(b, 600f);
        normalize(b, 0.4f);
        deClick(b);
        return b;
    }

    private static float[] weaponSwitch(Random rnd) {
        float[] b = buffer(0.16f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            float clack = t < 0.02f || (t > 0.06f && t < 0.085f) ? (rnd.nextFloat() * 2 - 1) : 0f;
            b[i] = clack * decay(t, 26f);
        }
        highPass(b, 500f);
        lowPass(b, 5500f);
        normalize(b, 0.4f);
        deClick(b);
        return b;
    }

    private static float[] announce(Random rnd) {
        float[] b = buffer(0.75f);
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SAMPLE_RATE;
            // Two-note sting: a fifth, second note entering part way through.
            float a = sine(t * 587f) * decay(t, 3.6f);
            float c = t > 0.14f ? sine((t - 0.14f) * 880f) * decay(t - 0.14f, 3.2f) : 0f;
            b[i] = (a * 0.55f + c * 0.55f) * Math.min(1f, t * 20f);
        }
        normalize(b, 0.65f);
        deClick(b);
        return b;
    }
}
