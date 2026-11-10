package local.mcgl.render.backend;

import java.io.IOException;
import java.nio.file.Path;
import org.lwjgl.bgfx.BGFXInit;
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
        if (!BgfxTerrainRenderer.supported(frame)) return false;
        renderer.draw(frame, null);
        return true;
    }

    @Override public String uploads() {
        return "meshUploads=" + renderer.meshUploads + " textureUploads=" + renderer.textureUploads
                + " pageUploads=" + renderer.pageUploads + " renderTargets=" + renderer.targetAllocations
                + " retainedUploads=" + renderer.retainedUploads;
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
