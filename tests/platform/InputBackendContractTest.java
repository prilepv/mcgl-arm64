package org.lwjgl.opengl;

import java.lang.reflect.Proxy;
import java.nio.*;
import local.mcgl.platform.*;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Exercises the real legacy input adapter through an injected backend, without GLFW/native calls. */
public final class InputBackendContractTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        FakeHost host = new FakeHost();
        FakeInput backend = new FakeInput(host);
        WindowBackend window = (WindowBackend)Proxy.newProxyInstance(InputBackendContractTest.class.getClassLoader(),
                new Class<?>[] {WindowBackend.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "host": return host;
                        case "input": return backend;
                        case "width": return 640;
                        case "height": return 480;
                        case "desktopMode": return new WindowMode(1920, 1200, 30, 60);
                        default: throw new AssertionError("Unexpected adapter dependency: " + method.getName());
                    }
                });
        GLFWInput input = new GLFWInput(window);
        input.createMouse(); input.createKeyboard();
        check(backend.mouse == input && backend.keyboard == input, "callbacks use the injected backend");
        check(input.getWidth() == 640 && input.getHeight() == 480 && input.getMaxCursorSize() == 600,
                "dimensions do not come from static Display state");
        check(input.isInsideWindow(), "initial hover state");
        backend.mouse.cursorEntered(false); check(!input.isInsideWindow(), "enter/leave callback");
        backend.keyboard.key(87, 1); backend.keyboard.character('ф'); // portable physical W / press
        ByteBuffer keys = ByteBuffer.allocate(Keyboard.EVENT_SIZE * 8);
        input.readKeyboard(keys); keys.flip();
        check(keys.remaining() == Keyboard.EVENT_SIZE && keys.getInt() == Keyboard.KEY_W
                && keys.get() == 1 && keys.getInt() == 'ф', "native key/text callbacks preserve legacy event pairing");
        backend.move(35, 26);
        IntBuffer coords = IntBuffer.allocate(3); ByteBuffer buttons = ByteBuffer.allocate(8);
        input.pollMouse(coords, buttons);
        check(coords.get(0) == 35 && coords.get(1) == 453, "injected-height coordinate conversion");
        input.grabMouse(true); check(backend.grabbed, "capture delegated on event thread");
        backend.move(35.6, 25.4); input.pollMouse(coords, buttons);
        check(coords.get(0) == 0 && coords.get(1) == 0, "subpixel movement retained");
        backend.move(36.2, 24.8); input.pollMouse(coords, buttons);
        check(coords.get(0) == 1 && coords.get(1) == 1, "fractional movement accumulates");
        input.setCursorPosition(80, 90);
        check(backend.x == 80 && backend.y == 389, "cursor warp converted for native top-left coordinates");
        backend.mouse.scroll(0.25); input.pollMouse(coords, buttons);
        check(coords.get(2) == 30, "wheel units preserved through callback boundary");
        backend.mouse.mouseButton(1, 1);
        input.releaseAll(); input.pollMouse(coords, buttons);
        ByteBuffer state = ByteBuffer.allocate(256); input.pollKeyboard(state);
        check(buttons.get(1) == 0 && state.get(Keyboard.KEY_W) == 0, "focus loss releases both queues");
        IntBuffer pixels = IntBuffer.wrap(new int[] {0xFF336699, 0xFFFFFFFF, 0xFF000000, 0});
        Object cursor = input.createCursor(2, 2, 1, 0, 1, pixels, null);
        check(cursor == backend.cursor && backend.pixels == pixels && pixels.position() == 0, "opaque cursor handle and unchanged pixel input");
        input.setNativeCursor(cursor); check(backend.selectedCursor == cursor, "cursor selection delegated");
        input.setNativeCursor(null); check(backend.selectedCursor == null, "default cursor restored");
        input.destroyCursor(cursor); check(backend.cursorDestroyed, "cursor released");
        backend.rejectCursor = true;
        try { input.createCursor(2, 2, 1, 0, 1, pixels, null); throw new AssertionError("native cursor failure accepted"); }
        catch (LWJGLException expected) {
            check(expected.getCause() instanceof IllegalStateException, "native cursor failure retains legacy checked exception");
        }
        try { input.createCursor(2, 2, 0, 0, 2, pixels, null); throw new AssertionError("animation batching accepted"); }
        catch (LWJGLException expected) { check(true, "existing animated-cursor contract retained"); }
        InputEvents lateKeyboard = backend.keyboard, lateMouse = backend.mouse;
        input.destroyKeyboard(); input.destroyMouse();
        check(backend.keyboard == null && backend.mouse == null && !backend.grabbed, "callbacks/capture removed");
        lateKeyboard.key(65, 1); lateKeyboard.character('a'); lateMouse.cursorPosition(999, 999); lateMouse.mouseButton(0, 1);
        keys.clear(); input.readKeyboard(keys);
        ByteBuffer events = ByteBuffer.allocate(Mouse.EVENT_SIZE * 8); input.readMouse(events);
        check(keys.position() == 0 && events.position() == 0, "late callbacks cannot repopulate destroyed input");
        input.createMouse(); input.createKeyboard();
        check(backend.mouse == input && backend.keyboard == input, "second input lifetime");
        input.destroyKeyboard(); input.destroyMouse();
        check(!host.onEventThread && host.calls > 10, "dispatch scope restored after all operations");
        System.out.println("INPUT_BACKEND_CONTRACT_PASS checks=" + checks + " callback-lifecycle/injected-size/opaque-cursor/focus/no-native");
    }
    private static final class FakeHost implements HostServices {
        boolean onEventThread; int calls;
        public void invoke(Runnable action) { boolean before = onEventThread; onEventThread = true; calls++; try { action.run(); } finally { onEventThread = before; } }
        public boolean isMainThread() { return onEventThread; }
        public boolean isContextLocked() { return false; }
        public void lockContext() { throw new AssertionError("Input adapter touched render context"); }
        public void unlockContext() { throw new AssertionError("Input adapter touched render context"); }
        public void configureWindowing() { throw new AssertionError("Input adapter initialized native windowing"); }
        public boolean openURL(String url) { throw new AssertionError("No external URL should be opened"); }
        public String description() { return "test-only event host"; }
    }
    private static final class FakeInput implements InputBackend {
        final FakeHost host;
        InputEvents mouse, keyboard; double x = 25, y = 16; boolean grabbed, cursorDestroyed, rejectCursor;
        final Object cursor = new Object(); Object selectedCursor; IntBuffer pixels;
        FakeInput(FakeHost host) { this.host = host; }
        void eventThread() { check(host.onEventThread, "native input operation dispatched to event thread"); }
        void move(double x, double y) { this.x = x; this.y = y; host.invoke(() -> mouse.cursorPosition(x, y)); }
        public void createMouse(InputEvents events) { eventThread(); mouse = events; }
        public void destroyMouse() { eventThread(); mouse = null; grabbed = false; }
        public void createKeyboard(InputEvents events) { eventThread(); keyboard = events; }
        public void destroyKeyboard() { eventThread(); keyboard = null; }
        public void cursorPosition(double[] x, double[] y) { eventThread(); x[0] = this.x; y[0] = this.y; }
        public boolean cursorInside() { eventThread(); return true; }
        public void grabMouse(boolean value) { eventThread(); grabbed = value; }
        public void setCursorPosition(double x, double y) { eventThread(); this.x = x; this.y = y; mouse.cursorPosition(x, y); }
        public void setCursor(Object value) { eventThread(); selectedCursor = value; }
        public Object createCursor(int width, int height, int hotspotX, int hotspotY, IntBuffer pixels) {
            eventThread(); check(width == 2 && height == 2 && hotspotX == 1 && hotspotY == 0, "cursor geometry retained");
            if (rejectCursor) throw new IllegalStateException("test-only cursor allocation failure");
            this.pixels = pixels; return cursor;
        }
        public void destroyCursor(Object cursor) { eventThread(); check(cursor == this.cursor, "cursor identity retained"); cursorDestroyed = true; }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
