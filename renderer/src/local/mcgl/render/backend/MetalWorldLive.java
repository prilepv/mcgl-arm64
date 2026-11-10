package local.mcgl.render.backend;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** The game can replace a queued frame without waiting for the Metal drawable or AppKit. */
final class MetalWorldLive {
    interface BgfxSink extends AutoCloseable {
        boolean draw(MetalWorldFrame frame);
        String uploads();
        int presentationPolicy();
        @Override void close();
    }

    private static final long[] THERMAL_INTERVAL = {0, 33_333_334L, 50_000_000L, 100_000_000L};
    private final ArrayBlockingQueue<MetalWorldFrame> latest = new ArrayBlockingQueue<>(1);
    private final Thread worker;
    private volatile boolean bgfx;
    private volatile boolean closed;
    private volatile boolean acceptingFrames = true;
    private volatile long minimumInterval;

    MetalWorldLive(Path library, boolean full, long cocoaWindow, boolean bgfx) {
        this.bgfx = bgfx;
        worker = new Thread(() -> run(library, full, cocoaWindow), "MCGL live Metal world");
        worker.setDaemon(true);
        worker.start();
    }

    boolean isClosed() { return closed; }
    boolean usesBgfx() { return bgfx; }
    boolean acceptsFrames() { return acceptingFrames && !closed && (!bgfx || latest.isEmpty()); }
    long minimumInterval() { return minimumInterval; }

    void offer(MetalWorldFrame frame) {
        if (closed) return;
        if (!latest.offer(frame)) {
            latest.poll();
            latest.offer(frame);
        }
    }

    private void run(Path library, boolean full, long cocoaWindow) {
        try {
            MetalWorldFrame first = awaitFrame();
            if (first == null) return;
            double scale = Math.min(1, Math.min(480.0 / first.width, 360.0 / first.height));
            int width = Math.max(1, (int)Math.round(first.width * scale));
            int height = Math.max(1, (int)Math.round(first.height * scale));
            if (bgfx) {
                try {
                    runBgfx(library, width, height, cocoaWindow, first);
                    return;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    String fallback = System.getenv("MCGL_METAL_LIVE_LIBRARY");
                    if (MetalGameTarget.independent() || cocoaWindow == 0 || fallback == null || closed) throw error;
                    System.out.println("[MCGL bgfx live] using native Metal fallback: "
                            + (error.getCause() == null ? error : error.getCause()));
                    library = Paths.get(fallback);
                    bgfx = false;
                    acceptingFrames = true;
                    minimumInterval = 0;
                }
            }
            String title = cocoaWindow != 0 ? "Minecraft Galaxy · Metal"
                    : full ? "Minecraft Galaxy · Metal Scene" : "Minecraft Galaxy · Metal Live";
            try (MetalTerrainWindow window = cocoaWindow == 0 ? MetalTerrainWindow.openPassive(library, title, width, height)
                    : MetalTerrainWindow.openAttached(library, title, width, height, cocoaWindow)) {
                MetalWorldPlayback player = new MetalWorldPlayback(window);
                MetalWorldFrame frame = first;
                int submitted = 0, transforms = 0, previousTransform = 0, minDraws = Integer.MAX_VALUE, menus = 0;
                long opened = System.nanoTime(), fpsStart = opened, lastPresented = 0, nextPolicy = opened;
                int previousPolicy = -1;
                while (!closed && !window.isClosed()) {
                    long now = System.nanoTime();
                    if (full && now >= nextPolicy) {
                        int policy = window.presentationPolicy();
                        int thermal = Math.min(policy >>> 1, THERMAL_INTERVAL.length - 1);
                        acceptingFrames = (policy & 1) != 0 || now - opened < 1_000_000_000L;
                        minimumInterval = THERMAL_INTERVAL[thermal];
                        if (policy != previousPolicy) {
                            System.out.println("[MCGL Metal scene] visible=" + ((policy & 1) != 0) + " thermal=" + thermal);
                            previousPolicy = policy;
                        }
                        nextPolicy = now + 200_000_000L;
                    }
                    if (frame != null && (full ? acceptingFrames : window.isVisible()) && player.draw(frame, null)) {
                        if (frame.menu) menus++;
                        else minDraws = Math.min(minDraws, frame.drawCount());
                        int transform = firstScreenTransform(frame);
                        if (submitted == 0 || transform != previousTransform) transforms++;
                        previousTransform = transform;
                        if (++submitted == 1 || submitted % 30 == 0) {
                            System.out.println("[MCGL Metal live] submitted=" + submitted + " presented="
                                    + window.presentedFrames() + " transformChanges=" + transforms + " draws="
                                    + frame.drawCount() + " passes=" + frame.passes.size() + " skipped=" + frame.skipped
                                    + (full ? " scene=" + frame.sceneDraws + " text=" + frame.textDraws
                                    + " depthResets=" + frame.depthResets + " sceneSkipped=" + frame.sceneSkipped
                                    + " stencil=" + frame.stencilDraws + " menu=" + frame.menu : "")
                                    + (cocoaWindow != 0 ? " uploadLevels=" + frame.uploadedTextureLevels
                                    + " readbackLevels=" + frame.readbackTextureLevels : ""));
                        }
                    }
                    now = System.nanoTime();
                    if (full && now - fpsStart >= 1_000_000_000L) {
                        long presented = window.presentedFrames();
                        long fps = Math.round((presented - lastPresented) * 1_000_000_000.0 / (now - fpsStart));
                        window.title(title + " · " + fps + " FPS");
                        fpsStart = now;
                        lastPresented = presented;
                    }
                    frame = latest.poll(200, TimeUnit.MILLISECONDS);
                }
                System.out.println("[MCGL Metal live] closed submitted=" + submitted + " presented="
                        + window.presentedFrames() + " transformChanges=" + transforms
                        + " minDraws=" + (minDraws == Integer.MAX_VALUE ? 0 : minDraws)
                        + (full ? " menus=" + menus : ""));
            }
        } catch (InterruptedException expected) {
            Thread.currentThread().interrupt();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            System.out.println("[MCGL Metal live] unavailable: " + error.getMessage());
        } finally {
            closed = true;
            latest.clear();
        }
    }

    private void runBgfx(Path library, int width, int height, long cocoaWindow, MetalWorldFrame first)
            throws ReflectiveOperationException, InterruptedException {
        // bgfx is packaged only in the experimental builds.
        Class<? extends BgfxSink> type = Class.forName("local.mcgl.render.backend.BgfxTerrainStream").asSubclass(BgfxSink.class);
        boolean attached = cocoaWindow != 0;
        try (BgfxSink sink = type.getDeclaredConstructor(Path.class, int.class, int.class, long.class)
                .newInstance(library, attached ? first.width : width, attached ? first.height : height, cocoaWindow)) {
            System.out.println("[MCGL bgfx live] backend=Metal source=game " + (attached ? "scene window=attached" : "terrain"));
            MetalWorldFrame frame = first;
            int submitted = 0, transforms = 0, previous = 0, rejected = 0, menus = 0, worlds = 0;
            int previousWidth = 0, previousHeight = 0;
            boolean previousMenu = false, previousWorldTarget = false;
            long opened = System.nanoTime(), nextPolicy = opened;
            while (!closed) {
                long now = System.nanoTime();
                if (attached && now >= nextPolicy) {
                    int policy = sink.presentationPolicy();
                    acceptingFrames = (policy & 1) != 0 || now - opened < 1_000_000_000L;
                    minimumInterval = THERMAL_INTERVAL[Math.min(policy >>> 1, THERMAL_INTERVAL.length - 1)];
                    nextPolicy = now + 200_000_000L;
                }
                if (frame != null && acceptingFrames) {
                    if (sink.draw(frame)) {
                        if (frame.menu) menus++; else worlds++;
                        int transform = firstScreenTransform(frame);
                        if (submitted == 0 || transform != previous) transforms++;
                        previous = transform;
                        boolean changed = frame.menu != previousMenu || frame.width != previousWidth
                                || frame.height != previousHeight || frame.worldFromTarget != previousWorldTarget;
                        previousMenu = frame.menu;
                        previousWorldTarget = frame.worldFromTarget;
                        previousWidth = frame.width;
                        previousHeight = frame.height;
                        if (++submitted == 1 || submitted % 120 == 0 || attached && changed) {
                            System.out.println("[MCGL bgfx live] submitted=" + submitted + " transforms=" + transforms
                                    + " draws=" + frame.drawCount() + " " + sink.uploads()
                                    + (attached ? " menu=" + frame.menu + " scene=" + frame.sceneDraws
                                    + " text=" + frame.textDraws + " depthResets=" + frame.depthResets
                                    + " stencil=" + frame.stencilDraws + " sceneSkipped=" + frame.sceneSkipped
                                    + " regions=" + frame.regionDraws + " worldTarget=" + frame.worldFromTarget
                                    + " size=" + frame.width + "x" + frame.height : ""));
                        }
                    } else if (++rejected == 1 || rejected % 120 == 0) {
                        System.out.println("[MCGL bgfx live] holding previous terrain; unsupported frames=" + rejected);
                    }
                }
                frame = latest.poll(200, TimeUnit.MILLISECONDS);
            }
            System.out.println("[MCGL bgfx live] closed submitted=" + submitted + " transforms=" + transforms
                    + " rejected=" + rejected + " " + sink.uploads() + (attached ? " menus=" + menus + " worlds=" + worlds : ""));
        }
    }

    private static int firstScreenTransform(MetalWorldFrame frame) {
        for (MetalWorldFrame.Pass pass : frame.passes) {
            if (pass.target == null && !pass.draws.isEmpty()) return Arrays.hashCode(pass.draws.get(0).model);
        }
        return 0;
    }

    private MetalWorldFrame awaitFrame() throws InterruptedException {
        while (!closed) {
            MetalWorldFrame frame = latest.poll(200, TimeUnit.MILLISECONDS);
            if (frame != null) return frame;
        }
        return null;
    }

    void close() {
        closed = true;
        try { worker.join(2000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { latest.clear(); }
    }
}
