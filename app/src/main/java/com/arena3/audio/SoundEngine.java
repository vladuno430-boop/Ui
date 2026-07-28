package com.arena3.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.arena3.core.Vec3;
import com.arena3.game.ItemDef;
import com.arena3.game.WeaponDef;

/**
 * Software mixer over a single {@link AudioTrack}. Every effect is synthesised
 * once at start-up, then played through voices that are attenuated by distance
 * and panned by where the sound sits relative to the player's view.
 *
 * <p>Mixing our own is what makes positional audio possible without any assets
 * or third-party library.
 */
public final class SoundEngine {

    private static final int MAX_VOICES = 24;
    private static final int BLOCK_FRAMES = 512;
    /** Beyond this, a sound is inaudible. */
    private static final float MAX_DISTANCE = 2600f;

    private final float[][] samples = new float[SoundSynth.COUNT][];

    // voice state
    private final int[] voiceSound = new int[MAX_VOICES];
    private final float[] voicePos = new float[MAX_VOICES];
    private final float[] voiceStep = new float[MAX_VOICES];
    private final float[] voiceGainL = new float[MAX_VOICES];
    private final float[] voiceGainR = new float[MAX_VOICES];
    private final boolean[] voiceActive = new boolean[MAX_VOICES];
    private int voiceCursor;

    private final Object lock = new Object();

    private AudioTrack track;
    private Thread mixThread;
    private volatile boolean running;
    private volatile float masterVolume = 0.8f;

    private final float[] mixBuffer = new float[BLOCK_FRAMES * 2];
    private final short[] outBuffer = new short[BLOCK_FRAMES * 2];

    // listener frame
    private final Vec3 listenerPos = new Vec3();
    private final Vec3 listenerRight = new Vec3(0, 1, 0);

    public void start(float volume) {
        masterVolume = volume;
        for (int i = 0; i < SoundSynth.COUNT; i++) {
            samples[i] = SoundSynth.generate(i);
        }

        int minBytes = AudioTrack.getMinBufferSize(SoundSynth.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(minBytes, BLOCK_FRAMES * 2 * 2 * 4);

        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SoundSynth.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();

        running = true;
        mixThread = new Thread(this::mixLoop, "arena3-audio");
        mixThread.setPriority(Thread.NORM_PRIORITY + 2);
        mixThread.start();
    }

    public void stop() {
        running = false;
        if (mixThread != null) {
            try {
                mixThread.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mixThread = null;
        }
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
                // Already stopped; nothing to do.
            }
            track.release();
            track = null;
        }
    }

    public void setVolume(float v) {
        masterVolume = v;
    }

    private void mixLoop() {
        while (running) {
            java.util.Arrays.fill(mixBuffer, 0f);
            synchronized (lock) {
                for (int v = 0; v < MAX_VOICES; v++) {
                    if (!voiceActive[v]) continue;
                    float[] data = samples[voiceSound[v]];
                    float pos = voicePos[v];
                    float step = voiceStep[v];
                    float gl = voiceGainL[v], gr = voiceGainR[v];
                    for (int i = 0; i < BLOCK_FRAMES; i++) {
                        int index = (int) pos;
                        if (index >= data.length - 1) {
                            voiceActive[v] = false;
                            break;
                        }
                        // Linear interpolation, so pitch shifting stays smooth.
                        float frac = pos - index;
                        float s = data[index] + (data[index + 1] - data[index]) * frac;
                        mixBuffer[i * 2] += s * gl;
                        mixBuffer[i * 2 + 1] += s * gr;
                        pos += step;
                    }
                    voicePos[v] = pos;
                }
            }

            float master = masterVolume;
            for (int i = 0; i < mixBuffer.length; i++) {
                // Soft clip: loud moments compress instead of tearing.
                float s = mixBuffer[i] * master;
                if (s > 1f) s = 1f - 1f / (1f + (s - 1f) * 4f) * 0.25f;
                else if (s < -1f) s = -(1f - 1f / (1f + (-s - 1f) * 4f) * 0.25f);
                outBuffer[i] = (short) (Math.max(-1f, Math.min(1f, s)) * 32000f);
            }
            AudioTrack t = track;
            if (t == null) break;
            try {
                t.write(outBuffer, 0, outBuffer.length);
            } catch (IllegalStateException e) {
                break;
            }
        }
    }

    // -------------------------------------------------------------- playback

    public void setListener(Vec3 position, Vec3 forward, Vec3 right) {
        listenerPos.set(position);
        listenerRight.set(right);
    }

    /** Plays a sound at a world position. */
    public void playAt(int sound, Vec3 at, float gain, float pitch) {
        float dx = at.x - listenerPos.x, dy = at.y - listenerPos.y, dz = at.z - listenerPos.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > MAX_DISTANCE) return;

        // Inverse-ish falloff with a soft floor near the listener.
        float atten = 1f - dist / MAX_DISTANCE;
        atten *= atten;
        float volume = gain * atten;
        if (volume < 0.004f) return;

        float pan = 0f;
        if (dist > 1f) {
            pan = (dx * listenerRight.x + dy * listenerRight.y + dz * listenerRight.z) / dist;
            pan = Math.max(-1f, Math.min(1f, pan)) * 0.8f;
        }
        float left = volume * (float) Math.sqrt(Math.max(0f, (1f - pan) * 0.5f));
        float right = volume * (float) Math.sqrt(Math.max(0f, (1f + pan) * 0.5f));
        queue(sound, left, right, pitch);
    }

    /** Plays a sound with no positioning, for interface feedback. */
    public void play2d(int sound, float gain, float pitch) {
        queue(sound, gain, gain, pitch);
    }

    private void queue(int sound, float gainL, float gainR, float pitch) {
        if (sound < 0 || sound >= SoundSynth.COUNT || samples[sound] == null) return;
        synchronized (lock) {
            int chosen = -1;
            for (int i = 0; i < MAX_VOICES; i++) {
                int v = (voiceCursor + i) % MAX_VOICES;
                if (!voiceActive[v]) {
                    chosen = v;
                    break;
                }
            }
            // All busy: steal the one that has played the longest.
            if (chosen < 0) {
                float best = -1f;
                for (int v = 0; v < MAX_VOICES; v++) {
                    float progress = voicePos[v] / Math.max(1, samples[voiceSound[v]].length);
                    if (progress > best) {
                        best = progress;
                        chosen = v;
                    }
                }
            }
            voiceCursor = (chosen + 1) % MAX_VOICES;
            voiceSound[chosen] = sound;
            voicePos[chosen] = 0f;
            voiceStep[chosen] = Math.max(0.25f, Math.min(4f, pitch));
            voiceGainL[chosen] = gainL;
            voiceGainR[chosen] = gainR;
            voiceActive[chosen] = true;
        }
    }

    private static float vary(float centre, float spread) {
        return centre + (float) (Math.random() * 2 - 1) * spread;
    }

    // ------------------------------------------------------- game-facing API

    public void weaponFire(int weapon, Vec3 at, boolean local) {
        int sound;
        float gain = local ? 0.85f : 0.7f;
        switch (weapon) {
            case WeaponDef.GAUNTLET: sound = SoundSynth.GAUNTLET; break;
            case WeaponDef.MACHINEGUN: sound = SoundSynth.MACHINEGUN; break;
            case WeaponDef.SHOTGUN: sound = SoundSynth.SHOTGUN; break;
            case WeaponDef.GRENADE: sound = SoundSynth.GRENADE_FIRE; break;
            case WeaponDef.ROCKET: sound = SoundSynth.ROCKET_FIRE; break;
            case WeaponDef.LIGHTNING: sound = SoundSynth.LIGHTNING; gain *= 0.5f; break;
            case WeaponDef.RAILGUN: sound = SoundSynth.RAILGUN; break;
            case WeaponDef.PLASMA: sound = SoundSynth.PLASMA_FIRE; gain *= 0.6f; break;
            default: sound = SoundSynth.VOID_FIRE; break;
        }
        if (local) {
            play2d(sound, gain, vary(1f, 0.04f));
        } else {
            playAt(sound, at, gain, vary(1f, 0.06f));
        }
    }

    public void explosion(Vec3 at, int weapon) {
        boolean small = weapon == WeaponDef.PLASMA;
        playAt(at != null ? at : listenerPos, small ? SoundSynth.EXPLOSION_SMALL : SoundSynth.EXPLOSION,
                small ? 0.6f : 1.0f, vary(1f, 0.08f));
    }

    private void playAt(Vec3 at, int sound, float gain, float pitch) {
        playAt(sound, at, gain, pitch);
    }

    public void impact(Vec3 at) {
        playAt(SoundSynth.IMPACT, at, 0.45f, vary(1f, 0.15f));
    }

    public void fleshHit(Vec3 at) {
        playAt(SoundSynth.FLESH_HIT, at, 0.55f, vary(1f, 0.12f));
    }

    public void grenadeBounce(Vec3 at) {
        playAt(SoundSynth.BOUNCE, at, 0.5f, vary(1f, 0.12f));
    }

    public void pickup(int itemId, Vec3 at, boolean local) {
        ItemDef def = ItemDef.get(itemId);
        int sound;
        switch (def.category) {
            case ItemDef.CAT_HEALTH: sound = SoundSynth.PICKUP_HEALTH; break;
            case ItemDef.CAT_ARMOR: sound = SoundSynth.PICKUP_ARMOR; break;
            case ItemDef.CAT_WEAPON: sound = SoundSynth.PICKUP_WEAPON; break;
            case ItemDef.CAT_POWERUP: sound = SoundSynth.PICKUP_POWERUP; break;
            default: sound = SoundSynth.PICKUP_ITEM; break;
        }
        if (local) {
            play2d(sound, 0.7f, 1f);
        } else {
            playAt(sound, at, 0.35f, 1f);
        }
    }

    public void jump(Vec3 at, boolean local) {
        if (local) {
            play2d(SoundSynth.JUMP, 0.28f, vary(1f, 0.1f));
        } else {
            playAt(SoundSynth.JUMP, at, 0.3f, vary(1f, 0.1f));
        }
    }

    public void land(Vec3 at, float force, boolean local) {
        float gain = Math.min(0.8f, force / 700f);
        if (local) {
            play2d(SoundSynth.LAND, gain * 0.8f, vary(1f, 0.08f));
        } else {
            playAt(SoundSynth.LAND, at, gain, vary(1f, 0.1f));
        }
    }

    public void pain(Vec3 at, boolean local) {
        if (local) {
            play2d(SoundSynth.PAIN, 0.6f, vary(1f, 0.08f));
        } else {
            playAt(SoundSynth.PAIN, at, 0.5f, vary(1f, 0.15f));
        }
    }

    public void death(Vec3 at, boolean gibbed) {
        playAt(gibbed ? SoundSynth.GIB : SoundSynth.DEATH, at, 0.8f, vary(1f, 0.1f));
    }

    public void teleport(Vec3 at) {
        playAt(SoundSynth.TELEPORT, at, 0.55f, vary(1f, 0.05f));
    }

    public void jumpPad(Vec3 at) {
        playAt(SoundSynth.JUMPPAD, at, 0.6f, vary(1f, 0.05f));
    }

    public void hitConfirm() {
        play2d(SoundSynth.HIT_CONFIRM, 0.5f, vary(1.05f, 0.06f));
    }

    public void noAmmo() {
        play2d(SoundSynth.NO_AMMO, 0.5f, 1f);
    }

    public void weaponSwitch() {
        play2d(SoundSynth.WEAPON_SWITCH, 0.45f, 1f);
    }

    public void announce() {
        play2d(SoundSynth.ANNOUNCE, 0.7f, 1f);
    }
}
