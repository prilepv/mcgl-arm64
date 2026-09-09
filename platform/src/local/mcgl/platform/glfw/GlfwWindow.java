package local.mcgl.platform.glfw;

import local.mcgl.platform.*;
import org.lwjgl.glfw.*;
import org.lwjgl.system.Configuration;
import static org.lwjgl.glfw.GLFW.*;

/** GLFW owns native lifecycle and geometry; neither the game nor the old Display ABI is imported. */
public final class GlfwWindow implements WindowBackend {
    private final HostServices host;
    private final InputBackend input;
    private volatile long window;
    private volatile boolean initialized, active, visible, closeRequested, dirty, resized;
    private volatile boolean fullscreen, resizable;
    private volatile int width = 900, height = 700, x = 80, y = 80;
    private volatile int framebufferWidth = 900, framebufferHeight = 700;
    private volatile WindowMode mode = new WindowMode(900, 700), desktop;
    private WindowMode windowedMode = mode;
    private int windowedX = 80, windowedY = 80, swapInterval;
    private String title = "Minecraft Galaxy";
    private GLFWErrorCallback errorCallback;
    private Thread contextOwner;
    private int nativeFullscreenState;
    private boolean nativeFullscreenAttached;

    public GlfwWindow(HostServices host) {
        if (host == null) throw new NullPointerException("host");
        this.host = host;
        input = new GlfwInput(this);
    }
    @Override public HostServices host() { return host; }
    @Override public InputBackend input() { return input; }
    /** Backend-native access for callback registration and white-box GPU probes, not a client API. */
    public long nativeHandle() {
        long value = window;
        if (value == 0) throw new IllegalStateException("Display not created");
        return value;
    }
    private void initialize() {
        if (initialized) return;
        host.invoke(() -> {
            if (initialized) return;
            Configuration.GLFW_LIBRARY_NAME.set(NativeLibraries.resolve(NativeLibraries.Library.GLFW).getAbsolutePath());
            errorCallback = GLFWErrorCallback.createPrint(System.err);
            glfwSetErrorCallback(errorCallback);
            try {
                host.configureWindowing();
                if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
                GLFWVidMode video = glfwGetVideoMode(glfwGetPrimaryMonitor());
                if (video == null) throw new IllegalStateException("GLFW has no primary monitor mode");
                desktop = displayMode(video);
                System.setProperty("mcgl.window.backend", "GLFW");
                System.out.println("[MCGL GLFW] version=" + glfwGetVersionString()
                        + " window/events=" + host.description());
                initialized = true;
            } catch (Throwable failure) {
                initialized = false; desktop = null;
                glfwTerminate();
                freeErrorCallback();
                throw failure;
            }
        });
    }
    private static WindowMode displayMode(GLFWVidMode video) {
        return new WindowMode(video.width(), video.height(),
                video.redBits() + video.greenBits() + video.blueBits(), video.refreshRate());
    }
    @Override public WindowMode desktopMode() { initialize(); return desktop; }
    @Override public WindowMode[] availableModes() {
        initialize();
        final WindowMode[][] result = {null};
        host.invoke(() -> {
            GLFWVidMode.Buffer modes = glfwGetVideoModes(glfwGetPrimaryMonitor());
            if (modes == null) { result[0] = new WindowMode[] {desktop}; return; }
            result[0] = new WindowMode[modes.remaining()];
            for (int i = 0; i < result[0].length; i++) result[0][i] = displayMode(modes.get(i));
        });
        return result[0];
    }
    @Override public WindowMode mode() { return mode; }
    @Override public void setMode(WindowMode value) {
        if (value == null) throw new NullPointerException("mode");
        if (value.width <= 0 || value.height <= 0) throw new IllegalArgumentException("Invalid display size");
        if (host.usesNativeFullscreen()) {
            // AppKit chooses and restores the content rectangle. Never turn a
            // desktop-mode request into a window resize or monitor mode switch.
            if (value.fullscreenCapable || fullscreen || (nativeFullscreenState & 4) != 0) return;
            mode = windowedMode = value;
            width = value.width; height = value.height;
            if (isCreated()) host.invoke(() -> {
                glfwSetWindowSize(nativeHandle(), value.width, value.height); refreshSize();
            });
            return;
        }
        mode = value;
        if (!fullscreen && !value.fullscreenCapable) windowedMode = value;
        width = value.width; height = value.height;
        // MCGL selects desktop mode immediately before entering fullscreen.
        // Preserve the saved window bounds during that intermediate request.
        if (!fullscreen && value.fullscreenCapable) return;
        if (isCreated()) host.invoke(() -> {
            if (fullscreen) glfwSetWindowMonitor(nativeHandle(), glfwGetPrimaryMonitor(), 0, 0,
                    width, height, value.frequency > 0 ? value.frequency : GLFW_DONT_CARE);
            else glfwSetWindowSize(nativeHandle(), width, height);
            refreshSize();
        });
    }
    @Override public void setFullscreen(boolean value) {
        if (host.usesNativeFullscreen()) {
            if (!isCreated()) { fullscreen = value; return; }
            host.invoke(() -> {
                host.requestNativeFullscreen(nativeHandle(), value);
                refreshNativeFullscreen(false);
            });
            return;
        }
        if (value == fullscreen) return;
        initialize();
        if (value) { windowedX = x; windowedY = y; mode = desktop; }
        else mode = windowedMode;
        fullscreen = value;
        width = mode.width; height = mode.height;
        if (isCreated()) host.invoke(() -> {
            glfwSetWindowMonitor(nativeHandle(), value ? glfwGetPrimaryMonitor() : 0,
                    value ? 0 : windowedX, value ? 0 : windowedY, width, height,
                    value ? desktop.frequency : GLFW_DONT_CARE);
            refreshSize();
        });
    }
    @Override public boolean isFullscreen() { return fullscreen; }
    @Override public void create(ContextRequest request, WindowEvents events) {
        if (isCreated()) throw new IllegalStateException("Display already created");
        if (request == null || events == null) throw new NullPointerException("window creation arguments");
        if (request.major >= 3 && (!request.coreProfile || !request.forwardCompatible
                || (request.major == 3 && request.minor < 2)))
            throw new IllegalArgumentException("macOS requires a forward-compatible Core profile for OpenGL 3.2 and later");
        if (request.coreProfile && request.major < 3)
            throw new IllegalArgumentException("Core profiles require OpenGL 3.2 or later");
        initialize();
        try {
            host.invoke(() -> {
                glfwDefaultWindowHints();
                glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, request.major);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, request.minor);
                glfwWindowHint(GLFW_OPENGL_PROFILE, request.coreProfile ? GLFW_OPENGL_CORE_PROFILE : GLFW_OPENGL_ANY_PROFILE);
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, request.forwardCompatible ? GLFW_TRUE : GLFW_FALSE);
                glfwWindowHint(GLFW_SCALE_FRAMEBUFFER, request.scaleFramebuffer ? GLFW_TRUE : GLFW_FALSE);
                glfwWindowHint(GLFW_RED_BITS, request.redBits);
                glfwWindowHint(GLFW_GREEN_BITS, request.greenBits);
                glfwWindowHint(GLFW_BLUE_BITS, request.blueBits);
                glfwWindowHint(GLFW_DEPTH_BITS, request.depthBits);
                glfwWindowHint(GLFW_STENCIL_BITS, request.stencilBits);
                glfwWindowHint(GLFW_ALPHA_BITS, request.alphaBits);
                glfwWindowHint(GLFW_SAMPLES, request.samples);
                glfwWindowHint(GLFW_RESIZABLE, resizable ? GLFW_TRUE : GLFW_FALSE);
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
                window = glfwCreateWindow(width, height, title,
                        fullscreen && !host.usesNativeFullscreen() ? glfwGetPrimaryMonitor() : 0, 0);
                if (window == 0) throw new IllegalStateException("GLFW could not create the requested OpenGL window");
                if (host.usesNativeFullscreen()) {
                    host.attachNativeFullscreen(window); nativeFullscreenAttached = true;
                }
                if (request.coreProfile) {
                    int actualMajor = glfwGetWindowAttrib(window, GLFW_CONTEXT_VERSION_MAJOR);
                    int actualMinor = glfwGetWindowAttrib(window, GLFW_CONTEXT_VERSION_MINOR);
                    if (actualMajor < request.major || (actualMajor == request.major && actualMinor < request.minor)
                            || glfwGetWindowAttrib(window, GLFW_OPENGL_PROFILE) != GLFW_OPENGL_CORE_PROFILE
                            || glfwGetWindowAttrib(window, GLFW_OPENGL_FORWARD_COMPAT) != GLFW_TRUE)
                        throw new IllegalStateException("GLFW did not create the requested Core context; fallback is disabled");
                }
                if (!fullscreen || host.usesNativeFullscreen()) glfwSetWindowPos(window, x, y);
                glfwSetWindowSizeCallback(window, (handle, w, h) -> {
                    if (w > 0 && h > 0) {
                        width = w; height = h; resized = true; events.resized();
                        updateActualMode(w, h);
                    }
                });
                glfwSetFramebufferSizeCallback(window, (handle, w, h) -> {
                    framebufferWidth = w; framebufferHeight = h; dirty = true;
                });
                glfwSetWindowPosCallback(window, (handle, px, py) -> { x = px; y = py; });
                glfwSetWindowFocusCallback(window, (handle, focused) -> {
                    active = focused;
                    if (!focused) events.focusLost();
                });
                glfwSetWindowIconifyCallback(window, (handle, iconified) -> visible = !iconified);
                glfwSetWindowCloseCallback(window, handle -> closeRequested = true);
                glfwSetWindowRefreshCallback(window, handle -> dirty = true);
                glfwShowWindow(window);
                glfwFocusWindow(window);
                active = glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE;
                visible = true; closeRequested = false; dirty = true;
                refreshSize();
                if (host.usesNativeFullscreen()) {
                    host.requestNativeFullscreen(window, fullscreen);
                    refreshNativeFullscreen(false);
                }
            });
        } catch (Throwable failure) {
            try { destroy(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private void refreshSize() {
        int[] w = {0}, h = {0}, px = {0}, py = {0};
        glfwGetWindowSize(nativeHandle(), w, h);
        if (w[0] > 0 && h[0] > 0) {
            width = w[0]; height = h[0]; resized = true;
            updateActualMode(width, height);
        }
        glfwGetFramebufferSize(nativeHandle(), w, h);
        framebufferWidth = w[0]; framebufferHeight = h[0];
        glfwGetWindowPos(nativeHandle(), px, py); x = px[0]; y = py[0];
    }
    private void updateActualMode(int w, int h) {
        mode = fullscreen ? new WindowMode(w, h, mode.bitsPerPixel, mode.frequency) : new WindowMode(w, h);
        if (!fullscreen && !host.usesNativeFullscreen()) windowedMode = mode;
    }
    private void refreshNativeFullscreen(boolean reconcile) {
        int state = host.nativeFullscreenState(nativeHandle(), reconcile);
        if (state != nativeFullscreenState || fullscreen != ((state & 1) != 0)) {
            nativeFullscreenState = state; fullscreen = (state & 1) != 0;
            resized = dirty = true; updateActualMode(width, height);
        }
    }
    @Override public void destroy() {
        if (!isCreated()) return;
        releaseContext();
        host.invoke(() -> {
            if (nativeFullscreenAttached) {
                host.detachNativeFullscreen(nativeHandle()); nativeFullscreenAttached = false;
            }
            Callbacks.glfwFreeCallbacks(nativeHandle());
            glfwDestroyWindow(window);
            window = 0; active = visible = false; closeRequested = false;
        });
        fullscreen = false; nativeFullscreenState = 0; mode = windowedMode;
        width = mode.width; height = mode.height;
    }
    @Override public boolean isCreated() { return window != 0; }
    @Override public void shutdown() {
        destroy();
        if (!initialized) return;
        host.invoke(() -> { glfwTerminate(); initialized = false; freeErrorCallback(); });
    }
    private void freeErrorCallback() {
        GLFWErrorCallback previous = glfwSetErrorCallback(null);
        if (previous != null) previous.free();
        errorCallback = null;
    }
    @Override public synchronized void makeCurrent() {
        requireOwnerOrUnbound();
        long handle = nativeHandle();
        glfwMakeContextCurrent(handle);
        if (glfwGetCurrentContext() != handle) throw new IllegalStateException("Could not make the window context current");
        contextOwner = Thread.currentThread();
        try {
            host.lockContext();
            glfwSwapInterval(swapInterval);
        } catch (Throwable failure) {
            try { releaseContext(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    @Override public synchronized void releaseContext() {
        requireOwnerOrUnbound();
        if (contextOwner == null) return;
        host.unlockContext();
        glfwMakeContextCurrent(0);
        contextOwner = null;
    }
    private synchronized void requireOwnerOrUnbound() {
        if (contextOwner != null && contextOwner != Thread.currentThread())
            throw new IllegalStateException("OpenGL context belongs to another thread");
    }
    @Override public void checkContextThread() { requireOwnerOrUnbound(); }
    private synchronized void requireCurrent() {
        if (contextOwner != Thread.currentThread() || !isCurrent())
            throw new IllegalStateException("No current window context on this thread");
    }
    @Override public boolean isCurrent() { return isCreated() && glfwGetCurrentContext() == window; }
    @Override public void swapBuffers() { requireCurrent(); glfwSwapBuffers(nativeHandle()); }
    @Override public void setSwapInterval(int interval) {
        if (isCreated()) { requireCurrent(); glfwSwapInterval(interval); }
        swapInterval = interval;
    }
    @Override public void awaitWithoutContextLock(Runnable wait) {
        requireCurrent();
        host.unlockContext();
        try { wait.run(); } finally { host.lockContext(); }
    }
    @Override public void pollEvents() {
        nativeHandle();
        host.invoke(() -> {
            glfwPollEvents();
            if (nativeFullscreenAttached) refreshNativeFullscreen(true);
        });
    }
    @Override public String clipboard() {
        initialize();
        final String[] value = {null};
        host.invoke(() -> value[0] = glfwGetClipboardString(0));
        return value[0];
    }
    @Override public void setTitle(String value) { title = value; if (isCreated()) host.invoke(() -> glfwSetWindowTitle(nativeHandle(), value)); }
    @Override public String title() { return title; }
    @Override public void setLocation(int px, int py) { x = px; y = py; if (isCreated()) host.invoke(() -> glfwSetWindowPos(nativeHandle(), px, py)); }
    @Override public void setResizable(boolean value) { resizable = value; if (isCreated()) host.invoke(() -> glfwSetWindowAttrib(nativeHandle(), GLFW_RESIZABLE, value ? GLFW_TRUE : GLFW_FALSE)); }
    @Override public boolean isResizable() { return resizable; }
    @Override public boolean wasResized() { boolean value = resized; resized = false; return value; }
    @Override public boolean isActive() { return active; }
    @Override public boolean isVisible() { return visible; }
    @Override public boolean isCloseRequested() { return closeRequested; }
    @Override public boolean isDirty() { return dirty; }
    @Override public boolean consumeDirty() { boolean value = dirty; dirty = false; return value; }
    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int x() { return fullscreen ? 0 : x; }
    @Override public int y() { return fullscreen ? 0 : y; }
    @Override public int framebufferWidth() { return framebufferWidth; }
    @Override public int framebufferHeight() { return framebufferHeight; }
}
