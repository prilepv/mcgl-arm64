package org.lwjgl.opengl;

import java.awt.Canvas;
import java.nio.ByteBuffer;
import local.mcgl.platform.*;
import local.mcgl.render.RenderSystem;
import local.mcgl.render.RenderProfile;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.*;

/** Legacy client ABI only. Native window/input/host lifecycle lives behind WindowBackend. */
public final class Display {
    private static final WindowBackend window = Platform.window();
    private static final GLFWInput input = new GLFWInput(window);
    private static final MCGLFrameLimiter limiter = new MCGLFrameLimiter();
    private static final Runnable frameWait = limiter::awaitFrame;
    private static Object context;
    private static RenderProfile contextProfile = RenderProfile.COMPATIBILITY_21;
    private static WindowMode cachedMode;
    private static DisplayMode cachedLegacyMode;
    private Display() {}

    static InputImplementation getImplementation() { return input; }
    private static DisplayMode legacyMode(WindowMode mode) {
        return mode.fullscreenCapable
                ? new DisplayMode(mode.width, mode.height, mode.bitsPerPixel, mode.frequency)
                : new DisplayMode(mode.width, mode.height);
    }
    private static WindowMode platformMode(DisplayMode mode) {
        return mode.isFullscreenCapable()
                ? new WindowMode(mode.getWidth(), mode.getHeight(), mode.getBitsPerPixel(), mode.getFrequency())
                : new WindowMode(mode.getWidth(), mode.getHeight());
    }
    public static DisplayMode getDesktopDisplayMode() { return legacyMode(window.desktopMode()); }
    public static DisplayMode[] getAvailableDisplayModes() throws LWJGLException {
        WindowMode[] modes = window.availableModes();
        DisplayMode[] result = new DisplayMode[modes.length];
        for (int i = 0; i < result.length; i++) result[i] = legacyMode(modes[i]);
        return result;
    }
    public static synchronized DisplayMode getDisplayMode() {
        WindowMode mode = window.mode();
        if (mode != cachedMode) { cachedLegacyMode = legacyMode(mode); cachedMode = mode; }
        return cachedLegacyMode;
    }
    public static void setDisplayMode(DisplayMode mode) throws LWJGLException {
        if (mode == null) throw new NullPointerException("mode");
        WindowMode value = platformMode(mode);
        window.setMode(value);
        synchronized (Display.class) {
            if (window.mode() == value) { cachedMode = value; cachedLegacyMode = mode; }
        }
    }
    public static void setDisplayModeAndFullscreen(DisplayMode value) throws LWJGLException {
        setDisplayMode(value); setFullscreen(value.isFullscreenCapable());
    }
    public static void setFullscreen(boolean value) throws LWJGLException { window.setFullscreen(value); }
    public static boolean isFullscreen() { return window.isFullscreen(); }
    public static void setParent(Canvas parent) throws LWJGLException {
        if (parent != null) throw new LWJGLException("GLFW owns a standalone window; AWT embedding is unsupported");
    }
    public static Canvas getParent() { return null; }
    public static void create() throws LWJGLException { create(new PixelFormat()); }
    public static void create(PixelFormat format) throws LWJGLException {
        create(format, RenderProfile.COMPATIBILITY_21);
    }
    static void create(PixelFormat format, RenderProfile profile) throws LWJGLException {
        if (isCreated()) throw new IllegalStateException("Display already created");
        if (format == null || profile == null) throw new NullPointerException("display creation arguments");
        try {
            // Core changes only the requested profile; preserve RGB10 hints and 1:1 framebuffer.
            ContextRequest request = profile == RenderProfile.CORE_41
                    ? ContextRequest.core41(format.getAlphaBits(), format.getDepthBits(), format.getStencilBits(), format.getSamples())
                    : ContextRequest.compatibility21(format.getAlphaBits(), format.getDepthBits(), format.getStencilBits(), format.getSamples());
            window.create(request, new WindowEvents() {
                public void resized() { input.rebasePosition(); }
                public void focusLost() { input.releaseAll(); }
            });
            context = new Object();
            contextProfile = profile;
            makeCurrent();
            Mouse.create(); Keyboard.create();
            MCGLFrameProfiler.reset();
            System.out.println("[MCGL GLFW] window=" + getWidth() + "x" + getHeight()
                    + " framebuffer=" + window.framebufferWidth() + "x" + window.framebufferHeight());
        } catch (Throwable failure) {
            try { destroy(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof LWJGLException) throw (LWJGLException) failure;
            throw new LWJGLException("GLFW window creation failed", failure);
        }
    }
    public static void destroy() {
        if (!isCreated()) return;
        window.checkContextThread();
        Mouse.destroy(); Keyboard.destroy();
        GLContext.destroyContext(context);
        try { releaseContext(); } catch (LWJGLException failure) { throw new IllegalStateException(failure); }
        window.destroy(); context = null;
        contextProfile = RenderProfile.COMPATIBILITY_21;
        MCGLFrameProfiler.reset();
    }
    public static boolean isCreated() { return window.isCreated(); }
    public static void shutdown() { destroy(); window.shutdown(); }
    public static void makeCurrent() throws LWJGLException {
        window.makeCurrent();
        try { GLContext.useContext(context, contextProfile, contextProfile == RenderProfile.CORE_41); }
        catch (Throwable failure) {
            window.releaseContext();
            if (failure instanceof LWJGLException) throw (LWJGLException) failure;
            throw new LWJGLException("Could not attach OpenGL bindings", failure);
        }
    }
    public static void releaseContext() throws LWJGLException {
        window.releaseContext(); GLContext.useContext(null);
    }
    public static boolean isCurrent() throws LWJGLException { return window.isCurrent(); }
    public static void swapBuffers() throws LWJGLException { window.swapBuffers(); }
    public static void update() { update(true); }
    public static void update(boolean processMessages) {
        if (!isCreated()) throw new IllegalStateException("Display not created");
        long started = MCGLDisplayMetrics.mcglProfileUpdateStarted();
        window.awaitWithoutContextLock(frameWait);
        long limited = started == 0 ? 0 : System.nanoTime() - started;
        long swapped = 0;
        boolean presented = false;
        if (window.isVisible() || window.isDirty()) {
            long swapping = started == 0 ? 0 : System.nanoTime();
            window.swapBuffers();
            presented = true;
            swapped = MCGLDisplayMetrics.mcglProfileSwapFinished(swapping);
            window.consumeDirty();
        }
        if (processMessages) processMessages();
        RenderSystem.completeFrame(window.framebufferWidth(), window.framebufferHeight(), presented);
        MCGLDisplayMetrics.mcglProfileUpdateFinished(started, limited, swapped);
    }
    public static void processMessages() {
        window.pollEvents();
        if (Mouse.isCreated()) { Mouse.poll(); Mouse.updateCursor(); }
        if (Keyboard.isCreated()) Keyboard.poll();
        if (Controllers.isCreated()) Controllers.poll();
    }
    public static void sync(int fps) { Sync.sync(fps); }
    static void mcglRecordDisplayList() { MCGLDisplayMetrics.mcglRecordDisplayList(); }
    public static void setTitle(String value) { window.setTitle(value); }
    public static String getTitle() { return window.title(); }
    public static void setLocation(int x, int y) { window.setLocation(x, y); }
    public static void setResizable(boolean value) { window.setResizable(value); }
    public static boolean isResizable() { return window.isResizable(); }
    public static boolean wasResized() { return window.wasResized(); }
    public static boolean isActive() { return window.isActive(); }
    public static boolean isVisible() { return window.isVisible(); }
    public static boolean isCloseRequested() { return window.isCloseRequested(); }
    public static boolean isDirty() { return window.consumeDirty(); }
    public static int getWidth() { return window.width(); }
    public static int getHeight() { return window.height(); }
    public static int getX() { return window.x(); }
    public static int getY() { return window.y(); }
    public static float getPixelScaleFactor() { return getWidth() > 0 ? window.framebufferWidth() / (float)getWidth() : 1; }
    public static void setSwapInterval(int interval) { window.setSwapInterval(interval); }
    public static void setVSyncEnabled(boolean enabled) { setSwapInterval(enabled ? 1 : 0); }
    public static String getAdapter() { return isCreated() ? RenderSystem.current().capabilities().vendor : null; }
    public static String getVersion() { return isCreated() ? RenderSystem.current().capabilities().version : null; }
    public static int setIcon(ByteBuffer[] icons) { return 0; } // Bundle icon remains host-owned.
    public static void setInitialBackground(float r, float g, float b) { if (isCreated()) RenderSystem.frameCommands().clearColor(r, g, b, 1); }
}
