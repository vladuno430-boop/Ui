import com.arena3.audio.SoundSynth;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;

/**
 * Checks every synthesised effect for the faults that are inaudible in code but
 * obvious in a speaker: silence, NaN, clipping, DC offset, or a tail that ends
 * abruptly enough to click. Optionally writes the lot to WAV files.
 *
 *   java -cp out SoundTest [outputDir]
 */
public class SoundTest {

    static final String[] NAMES = {
            "gauntlet", "machinegun", "shotgun", "grenade_fire", "rocket_fire", "lightning",
            "railgun", "plasma_fire", "void_fire", "explosion", "explosion_small", "impact",
            "flesh_hit", "bounce", "pickup_item", "pickup_health", "pickup_armor", "pickup_weapon",
            "pickup_powerup", "jump", "land", "pain", "death", "gib", "teleport", "jumppad",
            "hit_confirm", "no_ammo", "weapon_switch", "announce",
    };

    static int failures;

    public static void main(String[] args) throws Exception {
        File dir = args.length > 0 ? new File(args[0]) : null;
        if (dir != null) dir.mkdirs();

        System.out.println("=== sound synthesis ===");
        for (int i = 0; i < SoundSynth.COUNT; i++) {
            float[] s = SoundSynth.generate(i);
            String name = i < NAMES.length ? NAMES[i] : ("sound" + i);

            float peak = 0f, sum = 0f, energy = 0f;
            boolean bad = false;
            for (float v : s) {
                if (Float.isNaN(v) || Float.isInfinite(v)) bad = true;
                peak = Math.max(peak, Math.abs(v));
                sum += v;
                energy += v * v;
            }
            float rms = (float) Math.sqrt(energy / Math.max(1, s.length));
            float dc = sum / Math.max(1, s.length);
            float duration = s.length / (float) SoundSynth.SAMPLE_RATE;
            // A click at the end is the most audible defect a synth can have.
            float tail = Math.abs(s[s.length - 1]);

            String problem = null;
            if (bad) problem = "NaN/Inf";
            else if (peak < 0.05f) problem = "silent";
            else if (peak > 1.001f) problem = "clipped";
            else if (Math.abs(dc) > 0.05f) problem = "DC offset";
            else if (tail > 0.02f) problem = "clicks at end";
            else if (duration < 0.03f || duration > 2.5f) problem = "odd duration";

            if (problem != null) failures++;
            System.out.printf("  [%s] %-16s %5.0f ms  peak %.2f  rms %.3f  dc %+.4f%s%n",
                    problem == null ? "ok  " : "FAIL", name, duration * 1000, peak, rms, dc,
                    problem == null ? "" : "   <-- " + problem);

            if (dir != null) writeWav(new File(dir, name + ".wav"), s);
        }
        System.out.println(failures == 0 ? "\nALL SOUNDS OK" : "\n" + failures + " SOUND(S) FAILED");
        if (failures > 0) System.exit(1);
    }

    static void writeWav(File file, float[] samples) throws Exception {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int v = (int) (Math.max(-1f, Math.min(1f, samples[i])) * 32000);
            pcm[i * 2] = (byte) (v & 0xFF);
            pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        AudioFormat format = new AudioFormat(SoundSynth.SAMPLE_RATE, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), format,
                samples.length)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file);
        }
    }
}
