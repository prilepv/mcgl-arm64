package local.mcgl.render.backend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXStats;
import org.lwjgl.system.Configuration;
import static org.lwjgl.bgfx.BGFX.*;

/** Owns bgfx and its Cocoa presentation on the consumer thread. */
final class BgfxTerrainStream implements MetalWorldLive.BgfxSink {
    private final long window;
    private final boolean gameWindow;
    private final BgfxTerrainRenderer renderer;

    BgfxTerrainStream(Path windowLibrary, int width, int height, long cocoaWindow) throws IOException {
        Path resources = windowLibrary.getParent();
        Configuration.BGFX_LIBRARY_NAME.set(resources.resolve("libbgfx.dylib").toString());
        System.load(windowLibrary.toString());
        gameWindow = cocoaWindow != 0;
        window = gameWindow ? attachGameWindow(cocoaWindow) : openPreview(width, height);
        if (window == 0) throw new IllegalStateException("Cocoa bgfx window unavailable");
        int resetFlags = "0".equals(System.getenv("MCGL_METAL_VSYNC")) ? BGFX_RESET_NONE : BGFX_RESET_VSYNC;
        boolean initialized = false;
        try (BGFXInit init = BGFXInit.calloc()) {
            bgfx_init_ctor(init);
            // Reserve one transform for each possible draw, plus the identity.
            // Keep the reservation during quiet frames so the next turn cannot exhaust it.
            init.limits().numDrawCalls(Math.max(init.limits().numDrawCalls(), MetalWorldFrame.MAX_DRAWS + 1))
                    .numDrawCallPeakFrames(0);
            init.type(BGFX_RENDERER_TYPE_METAL).platformData().nwh(window);
            init.resolution().width(width).height(height).reset(resetFlags)
                    .formatDepthStencil(BGFX_TEXTURE_FORMAT_D32FS8);
            initialized = bgfx_init(init);
            if (!initialized) throw new IllegalStateException("bgfx Metal initialization failed");
            renderer = new BgfxTerrainRenderer(resources, width, height, gameWindow, resetFlags);
        } catch (IOException | RuntimeException | LinkageError error) {
            try { if (initialized) bgfx_shutdown(); }
            finally { closeWindow(); }
            throw error;
        }
    }

    @Override public boolean draw(MetalWorldFrame frame) {
        return renderer.draw(frame, null);
    }

    @Override public String uploads() {
        String uploads = "meshUploads=" + renderer.meshUploads + " textureUploads=" + renderer.textureUploads
                + " pageUploads=" + renderer.pageUploads + " renderTargets=" + renderer.targetAllocations
                + " retainedUploads=" + renderer.retainedUploads;
        if (!Boolean.getBoolean("mcgl.graphics.profile")) return uploads;
        BGFXStats stats = bgfx_get_stats();
        if (stats == null) return uploads;
        return "matrixUploads=" + renderer.matrixUploads
                + " stateUploads=" + renderer.stateUploads
                + " streamPages=" + renderer.streamPages
                + " residentPages=" + renderer.residentPages() + " geometryRepacks=" + renderer.geometryRepacks
                + " geometryCompactions=" + renderer.geometryCompactions
                + " renderCpuMs=" + milliseconds(stats.cpuTimeBegin(), stats.cpuTimeEnd(), stats.cpuTimerFreq())
                + " gpuMs=" + milliseconds(stats.gpuTimeBegin(), stats.gpuTimeEnd(), stats.gpuTimerFreq())
                + " " + uploads;
    }

    private static String milliseconds(long start, long end, long frequency) {
        return frequency > 0 && end > start
                ? String.format(Locale.ROOT, "%.3f", (end - start) * 1000.0 / frequency) : "n/a";
    }

    @Override public int presentationPolicy() {
        return gameWindow ? windowPolicy(window) : 1;
    }

    @Override public void close() {
        try {
            try { renderer.close(); }
            finally { bgfx_shutdown(); }
        } finally {
            closeWindow();
        }
    }

    private void closeWindow() {
        if (gameWindow) detachGameWindow(window);
        else closePreview(window);
    }

    private static native long openPreview(int width, int height);
    private static native void closePreview(long window);
    private static native long attachGameWindow(long window);
    private static native void detachGameWindow(long window);
    private static native int windowPolicy(long window);
}
