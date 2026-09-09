package org.lwjgl.opengl;

import local.mcgl.platform.Platform;
import local.mcgl.platform.glfw.GlfwWindow;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.glfw.GLFW;

/** Bounded real window/context test; deliberately contains no client or credentials. */
public final class GLFWWindowProbe {
    private static int checks;
    public static void main(String[] args) throws Exception {
        check(!Platform.host().isMainThread(), "render owner stays on the worker");
        Platform.host().invoke(() -> check(Platform.host().isMainThread(), "window calls use the macOS main thread"));
        for (int lifetime = 0; lifetime < 2; lifetime++) {
            Display.setDisplayMode(new DisplayMode(420, 310));
            Display.setLocation(120, 110); Display.setResizable(true);
            Display.setTitle("MCGL GLFW lifecycle check");
            Display.create(new PixelFormat().withDepthBits(24).withStencilBits(8));
            try {
                check(Mouse.isCreated() && Keyboard.isCreated(), "legacy input automatically created");
                check(Display.getPixelScaleFactor() == 1, "unchanged 1x render resolution");
                check(Display.isCurrent(), "context current on render owner");
                check(Platform.host().isContextLocked(), "render owner holds the CGL lock");
                try {
                    Platform.host().invoke(() -> { throw new IllegalArgumentException("lock-unwind-test"); });
                    throw new AssertionError("main-thread exception was swallowed");
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().equals("lock-unwind-test")
                            && Platform.host().isContextLocked(), "exception propagation restores render lock");
                }
                ContextCapabilities caps = GLContext.getCapabilities();
                int texture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer)null);
                WindowResizeFixture client = new WindowResizeFixture();
                for (int toggle = 0; toggle < 3; toggle++) {
                    // Reproduce the exact client order, including its separate desktop-mode call.
                    Display.setDisplayMode(Display.getDesktopDisplayMode());
                    client.width = Display.getDisplayMode().getWidth();
                    client.height = Display.getDisplayMode().getHeight();
                    Display.setFullscreen(true); awaitFullscreen(true);
                    check(Display.isFullscreen(), "fullscreen entered");
                    System.out.println("GLFW_GEOMETRY requested=" + client.width + "x" + client.height
                            + " actual=" + Display.getWidth() + "x" + Display.getHeight());
                    client.sync();
                    check(client.fullscreen, "client fullscreen flag follows native request");
                    check(client.width == Display.getWidth() && client.height == Display.getHeight(),
                            "client viewport follows actual fullscreen size");
                    check(Display.getDisplayMode().getWidth() == Display.getWidth()
                            && Display.getDisplayMode().getHeight() == Display.getHeight(),
                            "reported mode matches actual window");
                    assertFilled(client.width, client.height);
                    Display.setFullscreen(false); awaitFullscreen(false);
                    client.sync();
                    check(!client.fullscreen, "client fullscreen flag follows native exit");
                    check(!Display.isFullscreen(), "fullscreen exited");
                    check(Display.getWidth() == 420 && Display.getHeight() == 310, "window dimensions restored");
                    check(GLContext.getCapabilities() == caps && GL11.glIsTexture(texture), "fullscreen preserves GL context/resources");
                }
                Mouse.setGrabbed(true);
                final int[] mode = {0};
                Platform.host().invoke(() -> mode[0] = GLFW.glfwGetInputMode(((GlfwWindow)Platform.window()).nativeHandle(), GLFW.GLFW_CURSOR));
                check(mode[0] == GLFW.GLFW_CURSOR_DISABLED, "relative cursor mode");
                Mouse.setGrabbed(false);
                Platform.host().invoke(() -> mode[0] = GLFW.glfwGetInputMode(((GlfwWindow)Platform.window()).nativeHandle(), GLFW.GLFW_CURSOR));
                check(mode[0] == GLFW.GLFW_CURSOR_NORMAL, "cursor restored");
                IntBuffer pixels = BufferUtils.createIntBuffer(16 * 16);
                for (int i = 0; i < pixels.capacity(); i++) pixels.put(i, 0xFF336699);
                Cursor cursor = new Cursor(16, 16, 2, 3, 1, pixels, null);
                try { Mouse.setNativeCursor(cursor); Display.update(); check(true, "custom cursor created and set"); }
                finally { Mouse.setNativeCursor(null); cursor.destroy(); }
                Display.setDisplayMode(new DisplayMode(460, 340)); Display.update();
                check(Display.getWidth() == 460 && Display.getHeight() == 340, "resize after fullscreen");
                check(GLContext.getCapabilities() == caps, "resize keeps capabilities");
                resizeWhileRendering(texture);
                GL11.glDeleteTextures(texture);
                check(GL11.glGetError() == GL11.GL_NO_ERROR, "no graphics error");
            } finally { Display.destroy(); }
            check(!Display.isCreated() && !Mouse.isCreated() && !Keyboard.isCreated(), "window and input destroyed");
        }
        Display.shutdown();
        System.out.println("GLFW_WINDOW_PASS checks=" + checks + " two lifetimes/six fullscreen cycles/resize/cursor/threading");
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static void awaitFullscreen(boolean value) throws Exception {
        if (!Platform.host().usesNativeFullscreen()) { Display.update(); return; }
        long deadline = System.nanoTime() + 10_000_000_000L;
        int[] state = {-1};
        do {
            Display.update();
            Platform.host().invoke(() -> state[0] = Platform.host().nativeFullscreenState(
                    ((GlfwWindow)Platform.window()).nativeHandle(), false));
            if (state[0] == (value ? 3 : 0)) {
                Platform.host().invoke(() -> check(GLFW.glfwGetWindowMonitor(
                        ((GlfwWindow)Platform.window()).nativeHandle()) == 0, "Spaces never acquires a monitor"));
                return;
            }
            Platform.window().awaitWithoutContextLock(() -> {
                try { Thread.sleep(10); } catch (InterruptedException failed) { throw new RuntimeException(failed); }
            });
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Native fullscreen transition timed out: " + state[0]);
    }
    private static void assertFilled(int width, int height) {
        int[] fw = {0}, fh = {0};
        Platform.host().invoke(() -> GLFW.glfwGetFramebufferSize(((GlfwWindow)Platform.window()).nativeHandle(), fw, fh));
        check(width == fw[0] && height == fh[0], "client viewport fills the framebuffer at 1x");
        GL11.glViewport(0, 0, width, height);
        GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
        GL11.glClearColor(1, 0, 0, 1); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glColor4f(0, 1, 0, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-1, -1); GL11.glVertex2f(1, -1);
        GL11.glVertex2f(1, 1); GL11.glVertex2f(-1, 1); GL11.glEnd();
        java.nio.ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        for (int px : new int[] {0, fw[0] - 1}) for (int py : new int[] {0, fh[0] - 1}) {
            GL11.glReadPixels(px, py, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            check((pixel.get(1) & 255) > 240 && (pixel.get(0) & 255) < 8,
                    "fullscreen corner covered at " + px + "," + py);
        }
    }
    private static void resizeWhileRendering(int texture) throws Exception {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        java.nio.ByteBuffer texels = BufferUtils.createByteBuffer(64);
        for (int i = 0; i < 16; i++) texels.putInt(0xffffffff);
        texels.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        java.nio.FloatBuffer vertices = BufferUtils.createFloatBuffer(16);
        vertices.put(new float[] {-1,-1,0,0, 1,-1,1,0, 1,1,1,1, -1,1,0,1}).flip();
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        GL11.glVertexPointer(2, GL11.GL_FLOAT, 16, 0L);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 16, 8L);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        try {
            for (int step = 0; step < 40; step++) {
                final int size = step;
                java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                Thread change = new Thread(() -> {
                    try {
                        Platform.host().invoke(() -> {
                            entered.countDown();
                            GLFW.glfwSetWindowSize(((GlfwWindow)Platform.window()).nativeHandle(), 450 + size % 9, 330 + size % 7);
                            finished.set(true);
                        });
                    } catch (Throwable problem) { failure.set(problem); }
                }, "GLFW asynchronous resize fixture");
                change.start();
                check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "asynchronous resize entered AppKit");
                // The main thread is now resizing independently of Display.update.
                // Its NSGL update must wait for the context owned by this thread.
                for (int draw = 0; draw < 100; draw++) GL11.glDrawArrays(GL11.GL_QUADS, 0, 4);
                check(!finished.get(), "NSGL update cannot race the locked render context");
                Display.update();
                change.join(2000);
                check(!change.isAlive() && failure.get() == null && finished.get(), "resize completes at frame boundary");
                check(Platform.host().isContextLocked() && GL11.glIsTexture(texture), "resize retains lock and texture");
            }
        } finally {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); GL15.glDeleteBuffers(buffer);
        }
    }
}
