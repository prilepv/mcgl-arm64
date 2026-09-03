package org.lwjgl.opengl;

/** Optional launcher cap. Sleeps on the game thread, never busy-spins and never
 * changes simulation timing, vsync, or rendering settings. */
final class MCGLFrameLimiter {
    interface Clock { long nanoTime(); }
    interface Sleeper { void sleep(long nanos) throws InterruptedException; }

    private final Clock clock;
    private final Sleeper sleeper;
    private final long period;
    private boolean started;
    private long deadline;

    MCGLFrameLimiter() {
        this(parseLimit(System.getProperty("mcgl.fps.limit")), new Clock() {
            public long nanoTime() { return System.nanoTime(); }
        }, new Sleeper() {
            public void sleep(long nanos) throws InterruptedException {
                Thread.sleep(nanos / 1000000L, (int)(nanos % 1000000L));
            }
        });
    }

    MCGLFrameLimiter(int fps, Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
        period = supported(fps) && fps > 0 ? 1000000000L / fps : 0L;
    }

    static int parseLimit(String value) {
        try {
            int fps = Integer.parseInt(value);
            return supported(fps) ? fps : 0;
        } catch (NumberFormatException ignored) { return 0; }
    }

    private static boolean supported(int fps) {
        return fps == 0 || fps == 60 || fps == 120 || fps == 144 ||
            fps == 165 || fps == 180 || fps == 240;
    }

    void awaitFrame() {
        if (period == 0L) return;
        long now = clock.nanoTime();
        if (!started) {
            started = true;
            deadline = now + period;
            return;
        }
        try {
            for (long remaining = deadline - now; remaining > 0L; remaining = deadline - now) {
                sleeper.sleep(remaining);
                now = clock.nanoTime();
            }
        } catch (InterruptedException interrupted) {
            started = false;
            Thread.currentThread().interrupt();
            return;
        }
        // Keep fractional scheduling error bounded, but never issue a burst of
        // catch-up frames after a long frame, GC pause, or window transition.
        deadline = now - deadline >= period ? now + period : deadline + period;
    }
}
