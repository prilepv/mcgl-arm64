package local.mcgl.perf;

import java.util.Locale;
import org.lwjgl.opengl.MCGLFrameProfiler;

/** Main game-thread wall times, not GPU timings or exclusive CPU samples.
 * Disabled paths allocate nothing and never read the clock. Animation work can
 * be nested in a tick; phase totals must not be added together. */
public final class RenderDiagnostics {
    private static final boolean ENABLED = Boolean.getBoolean("mcgl.graphics.profile");
    private static final long PERIOD = 5000000000L;
    private static final long[] count = new long[4], total = new long[4], max = new long[4];
    private static long windowStart, skippedCopies, skippedBytes, skippedUploads;

    private RenderDiagnostics() {}

    public static long begin() {
        if (!ENABLED) return 0;
        long now = System.nanoTime();
        if (windowStart == 0) windowStart = now;
        return now;
    }

    public static void end(int phase, long started) {
        if (!ENABLED) return;
        long now = System.nanoTime(), elapsed = now - started;
        count[phase]++;
        total[phase] += elapsed;
        if (elapsed > max[phase]) max[phase] = elapsed;
        MCGLFrameProfiler.phase(phase, started, now);
        // Print after a render call, outside its measured interval.
        if (phase == 1 && now - windowStart >= PERIOD) {
            System.out.println(String.format(Locale.US,
                "[MCGL Phases] %.1fs | render avg/max %.3f/%.3f ms (%d) | tick avg/max %.3f/%.3f ms (%d) | animations avg/max %.3f/%.3f ms (%d) | avoided buffer copies %d (%.2f MiB), texture uploads %d | wall times; nested phases are not additive",
                (now - windowStart) / 1e9,
                average(1), max[1] / 1e6, count[1],
                average(2), max[2] / 1e6, count[2],
                average(0), max[0] / 1e6, count[0],
                skippedCopies, skippedBytes / 1048576.0, skippedUploads));
            System.out.println(String.format(Locale.US,
                "[MCGL Chunks] builds=%d total=%.3f ms avg/max=%.3f/%.3f ms | window %.1fs; included in render time",
                count[3], total[3] / 1e6, average(3), max[3] / 1e6, (now - windowStart) / 1e9));
            for (int i = 0; i < count.length; i++) count[i] = total[i] = max[i] = 0;
            skippedCopies = skippedBytes = skippedUploads = 0;
            QuadSort.report();
            LightmapCache.report();
            windowStart = System.nanoTime();
            MCGLFrameProfiler.diagnostic(windowStart - now);
        }
    }

    public static void skippedAnimation(int bytes, int repeats) {
        if (!ENABLED) return;
        skippedCopies++;
        skippedBytes += bytes;
        skippedUploads += (long)repeats * repeats;
    }

    private static double average(int phase) {
        return count[phase] == 0 ? 0 : total[phase] / (double)count[phase] / 1e6;
    }
}
