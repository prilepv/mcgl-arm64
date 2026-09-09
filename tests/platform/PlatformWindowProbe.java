package local.mcgl.platform;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicReference;
import local.mcgl.platform.glfw.GlfwWindow;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Failure/ownership/restart checks on real native windows; no account, clipboard read or URL open. */
public final class PlatformWindowProbe {
    private static int checks;
    private static final ContextRequest REQUEST = new ContextRequest(2, 1, false, false, false, 10, 10, 10, 8, 24, 8, 0);
    private static final WindowEvents EVENTS = new WindowEvents() {
        public void resized() {}
        public void focusLost() {}
    };
    public static void main(String[] args) throws Exception {
        FaultHost host = new FaultHost(Platform.host());
        WindowBackend window = new GlfwWindow(host);
        try {
            host.failConfiguration = true;
            expected(() -> window.desktopMode(), "injected host-init failure");
            check(!window.isCreated() && !host.isContextLocked(), "failed initialization retains no window/context lock");
            host.failDescription = true;
            expected(() -> window.desktopMode(), "injected late-init failure");
            check(window.desktopMode().fullscreenCapable, "initialization can retry after callback cleanup");
            window.setMode(new WindowMode(420, 310)); window.setLocation(140, 130);
            window.setTitle("MCGL platform lifecycle check"); window.setResizable(true);
            host.failAfterEventAction = true;
            expected(() -> window.create(REQUEST, EVENTS), "injected post-window failure");
            check(!window.isCreated() && !host.isContextLocked() && GLFW.glfwGetCurrentContext() == 0,
                    "partially created native window rolled back");
            for (int lifetime = 0; lifetime < 2; lifetime++) {
                window.create(REQUEST, EVENTS);
                host.failContextLock = true;
                expected(window::makeCurrent, "injected context-lock failure");
                check(!window.isCurrent() && !host.isContextLocked(), "failed binding leaves no current context or lock");
                window.makeCurrent();
                GLCapabilities capabilities = GL.createCapabilities();
                check(window.isCreated() && window.isCurrent() && host.isContextLocked(), "backend owns a locked current context");
                check(GL11.glGetString(GL11.GL_VERSION).startsWith("2.1"), "context request keeps legacy 2.1");
                check(window.width() == window.framebufferWidth() && window.height() == window.framebufferHeight(), "backend preserves 1x coordinates");
                try { window.input().grabMouse(true); throw new AssertionError("off-main input mutation accepted"); }
                catch (IllegalStateException expected) { check(expected.getMessage().contains("event thread"), "native input requires event dispatch"); }
                foreignThread(window);
                expected(() -> window.awaitWithoutContextLock(() -> { throw new IllegalStateException("injected frame-wait failure"); }),
                        "injected frame-wait failure");
                check(host.isContextLocked() && window.isCurrent(), "frame-wait exception restores drawable lock");
                host.invoke(() -> {
                    check(host.isMainThread() && !host.isContextLocked(), "event action does not inherit render thread lock");
                    host.invoke(() -> check(host.isMainThread(), "nested event dispatch stays on main thread"));
                });
                check(host.isContextLocked(), "nested event dispatch restores worker lock");
                int texture = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
                window.releaseContext(); GL.setCapabilities(null);
                check(!host.isContextLocked() && !window.isCurrent(), "context release clears ownership/lock");
                window.makeCurrent(); GL.setCapabilities(capabilities);
                check(host.isContextLocked() && GL11.glIsTexture(texture), "reattachment preserves context resources");
                GL11.glViewport(0, 0, window.width(), window.height());
                GL11.glClearColor(0.25f, 0.5f, 0.75f, 1); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                ByteBuffer pixel = org.lwjgl.system.MemoryUtil.memAlloc(4);
                try {
                    GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
                    check(Math.abs((pixel.get(0) & 255) - 64) <= 2 && Math.abs((pixel.get(1) & 255) - 128) <= 2
                            && Math.abs((pixel.get(2) & 255) - 191) <= 2, "backend context renders and reads expected pixels");
                } finally { org.lwjgl.system.MemoryUtil.memFree(pixel); }
                GL11.glDeleteTextures(texture);
                check(GL11.glGetError() == GL11.GL_NO_ERROR, "no GPU errors after ownership tests");
                final Object[] cursor = {null};
                IntBuffer pixels = IntBuffer.allocate(16 * 16);
                for (int i = 0; i < pixels.capacity(); i++) pixels.put(i, 0xFF336699);
                host.invoke(() -> cursor[0] = window.input().createCursor(16, 16, 2, 3, pixels));
                window.destroy(); GL.setCapabilities(null);
                check(!window.isCreated() && !host.isContextLocked(), "native window destroyed without retained lock");
                host.invoke(() -> window.input().destroyCursor(cursor[0]));
                check(true, "cursor can be freed after its window closes");
                window.shutdown(); window.shutdown();
                check(!window.isCreated(), "idempotent shutdown before full GLFW restart");
            }
        } finally { window.shutdown(); GL.setCapabilities(null); }
        adapterOwnership();
        System.out.println("PLATFORM_WINDOW_PASS checks=" + checks + " failure-rollback/context-ownership/lock-unwind/full-restart");
    }
    private static void foreignThread(WindowBackend window) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread foreign = new Thread(() -> {
            try {
                for (Runnable action : new Runnable[] {window::makeCurrent, window::releaseContext, window::swapBuffers,
                        () -> window.setSwapInterval(1), window::destroy, window::shutdown}) {
                    try { action.run(); throw new AssertionError("foreign thread changed an owned context"); }
                    catch (IllegalStateException expected) { check(true, "foreign context operation rejected before native call"); }
                }
                check(GLFW.glfwGetCurrentContext() == 0 && !window.host().isContextLocked(), "foreign thread stays unbound");
            } catch (Throwable problem) { failure.set(problem); }
        }, "platform ownership probe");
        foreign.start(); foreign.join(2500);
        check(!foreign.isAlive() && failure.get() == null, "ownership checks complete without deadlock: " + failure.get());
        check(window.isCreated() && window.isCurrent() && window.host().isContextLocked(), "original owner/window remain intact");
    }
    private static void adapterOwnership() throws Exception {
        Display.setDisplayMode(new DisplayMode(420, 310));
        Display.create(new PixelFormat().withDepthBits(24).withStencilBits(8));
        try {
            ContextCapabilities capabilities = GLContext.getCapabilities();
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            Thread foreign = new Thread(() -> {
                try { Display.destroy(); failure.set(new AssertionError("foreign adapter destroy succeeded")); }
                catch (IllegalStateException expected) { /* must reject before Mouse/Keyboard destruction */ }
                catch (Throwable problem) { failure.set(problem); }
            }, "legacy adapter ownership probe");
            foreign.start(); foreign.join(2500);
            check(!foreign.isAlive() && failure.get() == null, "legacy adapter rejects foreign destruction");
            check(Display.isCreated() && Mouse.isCreated() && Keyboard.isCreated(), "failed foreign destruction preserves input callbacks");
            Display.releaseContext(); Display.makeCurrent();
            check(GLContext.getCapabilities() == capabilities && Platform.host().isContextLocked(), "legacy capabilities survive reattachment");
            Display.update();
        } finally { Display.shutdown(); }
        check(!Display.isCreated() && !Mouse.isCreated() && !Keyboard.isCreated(), "adapter and native lifecycle end together");
    }
    private static final class FaultHost implements HostServices {
        final HostServices real;
        volatile boolean failConfiguration, failAfterEventAction, failDescription, failContextLock;
        FaultHost(HostServices real) { this.real = real; }
        public boolean isMainThread() { return real.isMainThread(); }
        public void invoke(Runnable action) {
            boolean fail = failAfterEventAction; failAfterEventAction = false;
            real.invoke(() -> { action.run(); if (fail) throw new IllegalStateException("injected post-window failure"); });
        }
        public void configureWindowing() {
            if (failConfiguration) { failConfiguration = false; throw new IllegalStateException("injected host-init failure"); }
            real.configureWindowing();
        }
        public void lockContext() {
            if (failContextLock) { failContextLock = false; throw new IllegalStateException("injected context-lock failure"); }
            real.lockContext();
        }
        public void unlockContext() { real.unlockContext(); }
        public boolean isContextLocked() { return real.isContextLocked(); }
        public boolean usesNativeFullscreen() { return real.usesNativeFullscreen(); }
        public void attachNativeFullscreen(long window) { real.attachNativeFullscreen(window); }
        public void requestNativeFullscreen(long window, boolean value) { real.requestNativeFullscreen(window, value); }
        public int nativeFullscreenState(long window, boolean reconcile) { return real.nativeFullscreenState(window, reconcile); }
        public void detachNativeFullscreen(long window) { real.detachNativeFullscreen(window); }
        public boolean openURL(String url) { throw new AssertionError("No external URL should be opened"); }
        public String description() {
            if (failDescription) { failDescription = false; throw new IllegalStateException("injected late-init failure"); }
            return real.description();
        }
    }
    private static void expected(Runnable action, String message) {
        try { action.run(); throw new AssertionError("Expected failure was swallowed: " + message); }
        catch (IllegalStateException expected) { check(message.equals(expected.getMessage()), "original failure preserved: " + expected.getMessage()); }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
