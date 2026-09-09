package org.lwjgl.opengl;

import java.nio.ByteBuffer;
import local.mcgl.platform.Platform;
import local.mcgl.platform.glfw.GlfwWindow;
import local.mcgl.render.RenderProfile;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.input.Mouse;

/** Real AppKit Spaces + Core 4.1, with the actual patched client resize/state helper. */
public final class NativeFullscreenProbe {
    private static int checks;
    private static native int cocoaFacts(long window);
    private static native void systemToggle(long window, boolean greenButton);
    private static long handle() { return ((GlfwWindow)Platform.window()).nativeHandle(); }
    private static long cocoa() { return GLFWNativeCocoa.glfwGetCocoaWindow(handle()); }
    public static void main(String[] args) throws Exception {
        System.load(new java.io.File("bin/natives/libmcgl-fullscreen-test.dylib").getAbsolutePath());
        check(Platform.host().usesNativeFullscreen(), "native Spaces host selected");
        for (int lifetime = 0; lifetime < 2; lifetime++) {
            Display.setDisplayMode(new DisplayMode(480, 340));
            Display.setLocation(130, 120); Display.setResizable(true);
            Display.setTitle("MCGL native fullscreen verification");
            Display.setFullscreen(lifetime == 0); // include startup-in-fullscreen
            Display.create(new PixelFormat().withDepthBits(24).withStencilBits(8), RenderProfile.CORE_41);
            try {
                check(GL11.glGetString(GL11.GL_VERSION).startsWith("4.1"), "Core 4.1 context");
                ContextCapabilities capabilities = GLContext.getCapabilities();
                int texture = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
                WindowResizeFixture client = new WindowResizeFixture();
                settle(lifetime == 0, client);
                if (lifetime == 0) { Display.setFullscreen(false); settle(false, client); }
                int[] video = videoMode();
                int savedX = Display.getX(), savedY = Display.getY();
                for (int cycle = 0; cycle < 2; cycle++) {
                    Display.setDisplayMode(Display.getDesktopDisplayMode());
                    check(Display.getWidth() == 480 && Display.getHeight() == 340,
                            "desktop request does not resize the window before entry");
                    Mouse.setGrabbed(cycle == 1);
                    Display.setFullscreen(true); settle(true, client);
                    preserved(texture, capabilities); pixels(client);
                    check(java.util.Arrays.equals(video, videoMode()), "monitor mode unchanged in fullscreen");
                    Display.setFullscreen(false); settle(false, client);
                    check(Display.getWidth() == 480 && Display.getHeight() == 340, "AppKit restores window size");
                    check(Display.getX() == savedX && Display.getY() == savedY, "AppKit restores window position");
                    preserved(texture, capabilities); pixels(client);
                    Mouse.setGrabbed(false);
                }
                // Use the real standard window button; this bypasses the Java request path.
                Platform.host().invoke(() -> systemToggle(cocoa(), true)); settle(true, client);
                check(client.fullscreen, "green-button entry synchronizes actual client field");
                Display.setFullscreen(!client.fullscreen); settle(false, client);
                // A system action exits fullscreen without calling Display.setFullscreen.
                Display.setFullscreen(true); settle(true, client);
                Platform.host().invoke(() -> systemToggle(cocoa(), false)); settle(false, client);
                check(!client.fullscreen, "system exit synchronizes actual client field");
                client.fullscreen = true; client.sync();
                check(!client.fullscreen, "client field is corrected even without pending resize");
                Display.setFullscreen(true); Display.setFullscreen(false); settle(false, client);
                Display.setFullscreen(true); Display.setFullscreen(false); Display.setFullscreen(true); settle(true, client);
                Display.setFullscreen(true); // idempotent while settled
                settle(true, client); preserved(texture, capabilities);
                Display.setFullscreen(false); settle(false, client);
                Display.setDisplayMode(new DisplayMode(510, 360)); Display.update(); client.sync();
                check(client.width == 510 && client.height == 360, "ordinary resize still works");
                Display.setFullscreen(true); settle(true, client);
                Display.setFullscreen(false); settle(false, client);
                check(client.width == 510 && client.height == 360, "fullscreen restores the latest resized bounds");
                check(java.util.Arrays.equals(video, videoMode()), "monitor mode unchanged after every transition");
                check(GL11.glGetError() == GL11.GL_NO_ERROR, "Core remains error-free");
                GL11.glDeleteTextures(texture);
                if (lifetime == 1) Display.setFullscreen(true); // teardown in flight, independent of startup test
            } finally { Display.destroy(); }
            check(!Display.isCreated() && !Platform.host().isContextLocked(), "transition teardown releases window/context lock");
        }
        // Drain late AppKit animation callbacks after the transitioned window
        // has been destroyed; the observer/delegate must not outlive GLFW.
        for (int step = 0; step < 10; step++) {
            Platform.host().invoke(GLFW::glfwPollEvents); Thread.sleep(100);
        }
        Display.shutdown();
        System.out.println("NATIVE_FULLSCREEN_GPU_PASS checks=" + checks
                + " Core41/green-button/queued-reversals/startup/restore/resize/context/transition-teardown");
    }
    private static int[] videoMode() {
        int[] result = new int[3];
        Platform.host().invoke(() -> {
            GLFWVidMode video = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
            result[0] = video.width(); result[1] = video.height(); result[2] = video.refreshRate();
        });
        return result;
    }
    private static void settle(boolean fullscreen, WindowResizeFixture client) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        int[] state = {-1};
        do {
            Display.update(); client.sync();
            Platform.host().invoke(() -> state[0] = Platform.host().nativeFullscreenState(handle(), false));
            if (state[0] == (fullscreen ? 3 : 0)) {
                check(Display.isFullscreen() == fullscreen && client.fullscreen == fullscreen, "platform/client state agrees");
                check(client.width == Display.getWidth() && client.height == Display.getHeight(), "actual client dimensions agree");
                check(Display.getWidth() == Platform.window().framebufferWidth()
                        && Display.getHeight() == Platform.window().framebufferHeight(), "unchanged 1x framebuffer");
                Platform.host().invoke(() -> {
                    check(GLFW.glfwGetWindowMonitor(handle()) == 0, "no exclusive GLFW monitor");
                    int facts = cocoaFacts(cocoa());
                    check((facts & 1) == (fullscreen ? 1 : 0), "actual NSWindow fullscreen style agrees");
                    check((facts & 6) == 6, "primary Space and GLFW failure callbacks enabled");
                });
                return;
            }
            Platform.window().awaitWithoutContextLock(() -> {
                try { Thread.sleep(5); } catch (InterruptedException e) { throw new RuntimeException(e); }
            });
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Spaces transition timed out; requested=" + fullscreen + " state=" + state[0]);
    }
    private static void preserved(int texture, ContextCapabilities capabilities) throws Exception {
        check(Display.isCurrent() && Platform.host().isContextLocked(), "render owner/lock preserved");
        check(GLContext.getCapabilities() == capabilities && GL11.glIsTexture(texture), "context and texture preserved");
    }
    private static void pixels(WindowResizeFixture client) {
        GL11.glViewport(0, 0, client.width, client.height);
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glClearColor(0.25f, 0.5f, 0.75f, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        ByteBuffer pixel = org.lwjgl.BufferUtils.createByteBuffer(4);
        for (int x : new int[] {0, client.width - 1}) for (int y : new int[] {0, client.height - 1}) {
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            check(Math.abs((pixel.get(0) & 255) - 64) <= 2 && Math.abs((pixel.get(1) & 255) - 128) <= 2
                    && Math.abs((pixel.get(2) & 255) - 191) <= 2, "all framebuffer corners readable after transition");
        }
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
