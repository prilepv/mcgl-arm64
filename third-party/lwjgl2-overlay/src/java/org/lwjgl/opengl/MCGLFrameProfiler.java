package org.lwjgl.opengl;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-of-update to end-of-update wall times, not GPU timestamps or CPU usage.
 * Only the Display owner writes frame counters. Two fixed windows are exchanged
 * with a sleeping daemon; a blocked log consumer never waits on the game thread.
 */
public final class MCGLFrameProfiler {
    static final long PERIOD = 5000000000L, STEP = 100000L, SLOW = 16666667L;
    static final int BINS = 1002; // ceil to 0.1 ms, final bucket is >100 ms
    private static final boolean ENABLED = Boolean.getBoolean("mcgl.graphics.profile")
            && !"false".equalsIgnoreCase(System.getProperty("mcgl.frame.profile"));
    private static final Tracker TRACKER = ENABLED ? new Tracker(new AsyncReporter(new Sink() {
        public void write(Window window) { System.out.print(window.format()); }
    })) : null;

    private MCGLFrameProfiler() {}
    public static void phase(int phase, long start, long end) {
        if (ENABLED) TRACKER.phase(phase, start, end);
    }
    public static void diagnostic(long elapsed) {
        if (ENABLED) TRACKER.diagnostic(elapsed);
    }
    static void frame(long end, long update, long limiter, long swap, long diagnostic) {
        if (ENABLED) TRACKER.frame(end, update, limiter, swap, diagnostic);
    }
    static void reset() { if (ENABLED) TRACKER.reset(); }

    interface Publisher { Window exchange(Window complete); }
    interface Sink { void write(Window window); }

    static final class AsyncReporter implements Publisher, Runnable {
        private final AtomicReference<Window> spare = new AtomicReference<Window>(new Window());
        private final Semaphore wake = new Semaphore(0);
        private final Sink sink;
        private volatile Window pending;
        final Thread thread;
        AsyncReporter(Sink sink) {
            this.sink = sink;
            thread = new Thread(this, "MCGL frame diagnostics");
            thread.setDaemon(true);
            thread.start();
        }
        public Window exchange(Window complete) {
            Window next = spare.getAndSet(null);
            if (next == null) return null; // continue the current window, never queue without bound
            pending = complete;
            wake.release();
            return next;
        }
        public void run() {
            try {
                for (;;) {
                    wake.acquire();
                    Window complete = pending;
                    pending = null;
                    try { sink.write(complete); }
                    catch (RuntimeException ignored) { /* optional diagnostics cannot stop the game */ }
                    finally { complete.clear(); spare.set(complete); }
                }
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        }
    }

    static final class Tracker {
        private final Publisher publisher;
        final long[] phases = new long[4], calls = new long[4];
        private volatile Thread owner;
        private boolean started;
        private long previous, windowStart, sequence, diagnostic, crossings;
        Window window = new Window();
        Tracker(Publisher publisher) { this.publisher = publisher; }
        void reset() {
            owner = null; started = false;
            window.clear();
            Arrays.fill(phases, 0); Arrays.fill(calls, 0);
            diagnostic = crossings = 0;
        }
        void phase(int phase, long start, long end) {
            if (Thread.currentThread() != owner || phase < 0 || phase >= 4) return;
            long elapsed = end - start;
            if (elapsed < 0) return;
            // Loading screens can call Display.update inside a measured method.
            // Clip to this frame and flag it rather than invent an exclusive total.
            if (start - previous < 0) {
                elapsed = Math.max(0L, end - previous);
                crossings++;
            }
            phases[phase] += elapsed; calls[phase]++;
        }
        void diagnostic(long elapsed) {
            if (Thread.currentThread() == owner && elapsed > 0) diagnostic += elapsed;
        }
        void frame(long now, long update, long limiter, long swap, long logs) {
            if (!started || Thread.currentThread() != owner) {
                reset(); owner = Thread.currentThread(); started = true;
                previous = windowStart = now;
                return; // incomplete startup/recreated-context frame
            }
            long elapsed = now - previous;
            window.add(++sequence, now, elapsed, phases, calls, update, limiter, swap,
                    diagnostic + logs, crossings);
            previous = now;
            Arrays.fill(phases, 0); Arrays.fill(calls, 0);
            diagnostic = crossings = 0;
            if (now - windowStart >= PERIOD && publisher != null) {
                window.seconds = (now - windowStart) / 1e9;
                window.endNano = now;
                window.endEpochMillis = System.currentTimeMillis();
                Window next = publisher.exchange(window);
                if (next != null) { window = next; windowStart = now; }
            }
        }
    }

    static final class Window {
        // Wall time: all frames, frames with ticks, frames without ticks.
        final long[][] hist = new long[3][BINS];
        // id, nanoTime, wall, render, tick, animation, chunks, update, limiter,
        // swap, other, diagnostic logs (may be nested), ticks, builds, crossings
        final long[][] slow = new long[3][15];
        long frames, withTick, total, max, overBudget, overlapFrames;
        long endNano, endEpochMillis;
        double seconds;
        void add(long id, long end, long wall, long[] phases, long[] calls,
                long update, long limiter, long swap, long diagnostic, long crossings) {
            wall = Math.max(0L, wall);
            int bin = (int)Math.min(BINS - 1, wall / STEP + (wall % STEP == 0 ? 0 : 1));
            frames++; total += wall; max = Math.max(max, wall);
            hist[0][bin]++;
            boolean tick = calls[2] != 0;
            if (tick) withTick++;
            hist[tick ? 1 : 2][bin]++;
            long other = wall - phases[1] - phases[2] - update;
            if (other < 0 || crossings != 0) overlapFrames++;
            if (wall > SLOW) overBudget++;
            if (wall <= SLOW || wall <= slow[2][2]) return;
            int slot = 2;
            while (slot > 0 && wall > slow[slot - 1][2]) {
                System.arraycopy(slow[slot - 1], 0, slow[slot], 0, slow[slot].length);
                slot--;
            }
            long[] row = slow[slot];
            row[0] = id; row[1] = end; row[2] = wall;
            row[3] = phases[1]; row[4] = phases[2]; row[5] = phases[0]; row[6] = phases[3];
            row[7] = update; row[8] = limiter; row[9] = swap; row[10] = Math.max(0L, other);
            row[11] = diagnostic; row[12] = calls[2]; row[13] = calls[3]; row[14] = crossings;
        }
        static String percentile(long[] bins, long count, int percent) {
            if (count == 0) return "n/a";
            long target = (count * percent + 99) / 100, seen = 0;
            for (int i = 0; i < bins.length; i++) {
                seen += bins[i];
                if (seen >= target) return i == BINS - 1 ? ">100" :
                    String.format(Locale.US, "<=%.1f", i / 10.0);
            }
            return "n/a";
        }
        String format() {
            StringBuilder out = new StringBuilder(1400);
            out.append(String.format(Locale.US,
                "[MCGL FramePacing] %.3fs frames=%d avg=%.3f max=%.3f ms p50/p95/p99=%s/%s/%s ms"
                + " over16.67=%d withTick=%d tickP95=%s noTickP95=%s ms overlapFrames=%d"
                + " | end-to-end wall; percentile bounds 0.1ms; animations in tick, chunks in render, swap/cap in update\n",
                seconds, frames, frames == 0 ? 0.0 : total / (double)frames / 1e6, max / 1e6,
                percentile(hist[0], frames, 50), percentile(hist[0], frames, 95),
                percentile(hist[0], frames, 99), overBudget, withTick,
                percentile(hist[1], withTick, 95), percentile(hist[2], frames - withTick, 95), overlapFrames));
            for (long[] row : slow) if (row[0] != 0) {
                out.append(String.format(Locale.US,
                    "[MCGL SlowFrame] id=%d endEpochMs=%d wall=%.3f render=%.3f tick=%.3f"
                    + " animations=%.3f chunks=%.3f update=%.3f cap=%.3f swap=%.3f"
                    + " other=%.3f diagnosticLogs=%.3f ms ticks=%d builds=%d crossedPhase=%d\n",
                    row[0], endEpochMillis - (endNano - row[1])/1000000L,
                    row[2]/1e6, row[3]/1e6, row[4]/1e6, row[5]/1e6, row[6]/1e6,
                    row[7]/1e6, row[8]/1e6, row[9]/1e6, row[10]/1e6, row[11]/1e6,
                    row[12], row[13], row[14]));
            }
            return out.toString();
        }
        void clear() {
            for (long[] bins : hist) Arrays.fill(bins, 0);
            for (long[] row : slow) Arrays.fill(row, 0);
            frames = withTick = total = max = overBudget = overlapFrames = 0; seconds = 0;
            endNano = endEpochMillis = 0;
        }
    }
}
