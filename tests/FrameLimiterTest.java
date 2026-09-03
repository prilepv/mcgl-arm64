package org.lwjgl.opengl;

public final class FrameLimiterTest {
    static final class Timer implements MCGLFrameLimiter.Clock, MCGLFrameLimiter.Sleeper {
        long now, slept;
        int calls;
        boolean interrupt;
        public long nanoTime() { return now; }
        public void sleep(long nanos) throws InterruptedException {
            check(nanos > 0, "nonpositive sleep"); calls++;
            if (interrupt) throw new InterruptedException();
            slept += nanos; now += nanos;
        }
    }
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) {
        for (int fps : new int[]{60, 120, 144, 165, 180, 240}) {
            Timer time = new Timer();
            MCGLFrameLimiter limiter = new MCGLFrameLimiter(fps, time, time);
            long period = 1000000000L / fps;
            limiter.awaitFrame();
            for (int i = 0; i < 600; i++) { time.now += period / 4; limiter.awaitFrame(); }
            check(time.now == 600 * period, "frame pacing " + fps);
            time.now += 2000000000L;
            long before = time.now;
            limiter.awaitFrame();
            check(time.now == before, "slow frame must not wait");
            limiter.awaitFrame();
            check(time.now == before + period, "no burst after pause");
            check(MCGLFrameLimiter.parseLimit(Integer.toString(fps)) == fps, "valid setting");
        }
        for (int disabled : new int[]{0, -1, 1, 999}) {
            Timer time = new Timer();
            MCGLFrameLimiter limiter = new MCGLFrameLimiter(disabled, time, time);
            for (int i = 0; i < 100; i++) limiter.awaitFrame();
            check(time.calls == 0, "disabled limiter must not sleep");
        }
        for (String value : new String[]{null, "", "NaN", "-1", "999999999999999", "60fps", "61"})
            check(MCGLFrameLimiter.parseLimit(value) == 0, "invalid setting");
        Timer wrap = new Timer(); wrap.now = Long.MAX_VALUE - 10;
        MCGLFrameLimiter limiter = new MCGLFrameLimiter(60, wrap, wrap);
        limiter.awaitFrame(); limiter.awaitFrame();
        check(wrap.slept == 1000000000L / 60, "nanoTime wraparound");
        Timer interrupted = new Timer(); interrupted.interrupt = true;
        limiter = new MCGLFrameLimiter(60, interrupted, interrupted);
        limiter.awaitFrame(); limiter.awaitFrame();
        check(Thread.interrupted(), "preserve interruption");
        System.out.println("FPS_LIMITER_PASS six limits, unlimited, stalls, invalid values, overflow, interruption");
    }
}
