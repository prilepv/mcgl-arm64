package org.lwjgl.opengl;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class FrameProfilerTest {
    static final long MS = 1000000L;
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static void eq(long actual, long expected, String why) { check(actual == expected, why + ": " + actual + " != " + expected); }

    static void accounting() throws Exception {
        MCGLFrameProfiler.Tracker t = new MCGLFrameProfiler.Tracker(null);
        t.frame(0, 0, 0, 0, 0);
        t.phase(0, 1*MS, 4*MS); // animations nested in tick
        t.phase(2, 0, 5*MS);
        t.phase(3, 6*MS, 8*MS); // chunks nested in render
        t.phase(1, 5*MS, 15*MS);
        t.diagnostic(MS);
        t.frame(30*MS, 8*MS, 2*MS, 5*MS, MS);
        long[] row = t.window.slow[0];
        eq(row[2], 30*MS, "frame"); eq(row[3], 10*MS, "render");
        eq(row[4], 5*MS, "tick"); eq(row[5], 3*MS, "animations");
        eq(row[6], 2*MS, "chunks"); eq(row[7], 8*MS, "update");
        eq(row[8], 2*MS, "cap"); eq(row[9], 5*MS, "swap");
        eq(row[10], 7*MS, "exclusive remainder, no nested double count");
        eq(row[11], 2*MS, "logs"); eq(row[12], 1, "ticks"); eq(row[13], 1, "builds");
        eq(t.window.withTick, 1, "tick frames");

        // Another thread cannot contaminate the Display owner's frame.
        Thread other = new Thread(() -> { t.phase(2, 30*MS, 35*MS); t.diagnostic(90*MS); });
        other.start(); other.join();
        t.frame(35*MS, 2*MS, 0, MS, 0);
        eq(t.window.frames, 2, "two frames"); eq(t.window.withTick, 1, "no foreign tick");
        eq(t.window.hist[2][50], 1, "5ms no-tick bin");
        for (long n : t.phases) eq(n, 0, "phase reset");

        t.phase(1, 25*MS, 36*MS); // crossed boundary: clipped and flagged
        t.frame(55*MS, MS, 0, 0, 0);
        eq(t.window.overlapFrames, 1, "crossed phase flag");
        eq(t.window.slow[1][3], MS, "clipped render");
        eq(t.window.slow[1][14], 1, "crossed phase count");
        t.reset();
        t.frame(100*MS, 500*MS, 500*MS, 500*MS, 0);
        eq(t.window.frames, 0, "omit first/recreated frame");
        t.frame(110*MS, MS, 0, 0, 0);
        eq(t.window.frames, 1, "no old window after reset");
        eq(t.window.max, 10*MS, "startup gap omitted");

        MCGLFrameProfiler.Tracker wrap = new MCGLFrameProfiler.Tracker(null);
        wrap.frame(Long.MAX_VALUE - MS, 0, 0, 0, 0);
        wrap.frame(Long.MAX_VALUE - MS + 5*MS, MS, 0, 0, 0);
        eq(wrap.window.total, 5*MS, "nanoTime wrap");
    }

    static void histogram() {
        MCGLFrameProfiler.Window w = new MCGLFrameProfiler.Window();
        long[] phases = new long[4], calls = new long[4];
        for (int i = 1; i <= 100; i++) w.add(i, i*MS, i*100000L, phases, calls, 0, 0, 0, 0, 0);
        check(MCGLFrameProfiler.Window.percentile(w.hist[0], 100, 50).equals("<=5.0"), "p50");
        check(MCGLFrameProfiler.Window.percentile(w.hist[0], 100, 95).equals("<=9.5"), "p95");
        check(MCGLFrameProfiler.Window.percentile(w.hist[0], 100, 99).equals("<=9.9"), "p99");
        check(MCGLFrameProfiler.Window.percentile(w.hist[1], 0, 95).equals("n/a"), "empty subset");
        w.clear();
        w.add(1, MS, 1000001, phases, calls, 0, 0, 0, 0, 0);
        check(MCGLFrameProfiler.Window.percentile(w.hist[0], 1, 99).equals("<=1.1"), "ceil bound");
        w.clear();
        for (long n : new long[]{20, 50, 30, 40, 10, 120, 25}) w.add(n, n*MS, n*MS, phases, calls, 0, 0, 0, 0, 0);
        eq(w.slow[0][2], 120*MS, "slowest"); eq(w.slow[1][2], 50*MS, "second");
        eq(w.slow[2][2], 40*MS, "third");
        check(MCGLFrameProfiler.Window.percentile(w.hist[0], 7, 99).equals(">100"), "overflow explicit");
        w.seconds = 5; w.endNano = 200*MS; w.endEpochMillis = 2000;
        String log = w.format();
        check(log.contains("endEpochMs=1920"), "event timestamp, not delayed report time");
        check(log.split("\\[MCGL SlowFrame\\]", -1).length == 4, "bounded three slow rows");
        check(!log.contains("NaN") && !log.contains("Infinity"), "finite output");
        w.clear();
        eq(w.frames, 0, "window reset");
        for (long[] bins : w.hist) for (long n : bins) eq(n, 0, "hist reset");
    }

    static void backpressure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1);
        AtomicInteger reports = new AtomicInteger();
        MCGLFrameProfiler.AsyncReporter reporter = new MCGLFrameProfiler.AsyncReporter(w -> {
            int count = reports.incrementAndGet();
            if (count == 1) {
                entered.countDown();
                try { check(release.await(5, TimeUnit.SECONDS), "release reporter"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                eq(w.frames, 1, "published window remains immutable");
            } else second.countDown();
        });
        check(reporter.thread.isDaemon(), "daemon");
        MCGLFrameProfiler.Tracker t = new MCGLFrameProfiler.Tracker(reporter);
        try {
            t.frame(0, 0, 0, 0, 0); t.frame(5_000*MS, 0, 0, 0, 0);
            check(entered.await(2, TimeUnit.SECONDS), "report started");
            for (int i = 1; i <= 10000; i++) t.frame((5000L + i)*MS, 0, 0, 0, 0);
            eq(t.window.frames, 10000, "blocked output accumulates without lost frames or extra queue");
            eq(reports.get(), 1, "no duplicate publish");
            release.countDown();
            long deadline = System.nanoTime() + 2_000*MS;
            for (int i = 0; second.getCount() != 0 && System.nanoTime() < deadline; i++) {
                t.frame((15001L+i)*MS, 0, 0, 0, 0);
                Thread.sleep(1);
            }
            check(second.await(1, TimeUnit.SECONDS), "recycled second window");
        } finally {
            release.countDown(); reporter.thread.interrupt(); reporter.thread.join(2000);
        }
        check(!reporter.thread.isAlive(), "test reporter stopped");
    }

    static void overhead() {
        MCGLFrameProfiler.Tracker t = new MCGLFrameProfiler.Tracker(null);
        long now = 0; t.frame(now, 0, 0, 0, 0);
        for (int i=0; i<100000; i++) { t.phase(1, now, now+2*MS); now+=6*MS; t.frame(now, 3*MS, 0, 3*MS, 0); }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (bean.isThreadAllocatedMemorySupported()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        long beforeBytes = bean.getThreadAllocatedBytes(thread), before = System.nanoTime();
        for (int i=0; i<1000000; i++) {
            t.phase(0, now, now+MS); t.phase(2, now, now+2*MS);
            t.phase(3, now+2*MS, now+3*MS); t.phase(1, now+2*MS, now+4*MS);
            now += (i % 101 == 0 ? 20 : 8)*MS; t.frame(now, 3*MS, MS, 2*MS, 0);
        }
        long elapsed = System.nanoTime()-before, allocated = bean.getThreadAllocatedBytes(thread)-beforeBytes;
        eq(t.window.frames, 1100000, "benchmark results consumed");
        eq(t.window.slow[0][5], MS, "benchmark includes retained nested phases");
        System.out.printf(java.util.Locale.US, "FRAME_TRACKER_BENCH ns/frame=%.1f bytes/millionFrames=%d (four phases; excludes GL, clocks and reporter)%n",
                elapsed/1000000.0, allocated);
        check(allocated < 8192, "bounded hot-path allocation, not per-frame objects");
    }

    public static void main(String[] args) throws Exception {
        accounting(); histogram(); backpressure(); overhead();
        Field field = MCGLFrameProfiler.class.getDeclaredField("TRACKER"); field.setAccessible(true);
        boolean enabled = Boolean.getBoolean("mcgl.graphics.profile") && !"false".equalsIgnoreCase(System.getProperty("mcgl.frame.profile"));
        check((field.get(null) != null) == enabled, "feature switch / no disabled worker");
        MCGLFrameProfiler.phase(1, 0, 1); MCGLFrameProfiler.diagnostic(1); MCGLFrameProfiler.reset();
        System.out.println("FRAME_PROFILER_PASS enabled=" + enabled);
    }
}
