package local.mcgl.platform.glfw;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import local.mcgl.platform.InputBackend;
import local.mcgl.platform.InputEvents;
import org.lwjgl.glfw.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.glfw.GLFW.*;

/** Native callback/cursor lifecycle. Legacy queue layout and polling live in the client adapter. */
final class GlfwInput implements InputBackend {
    private final GlfwWindow window;
    GlfwInput(GlfwWindow window) { this.window = window; }
    private void requireEventThread() {
        if (!window.host().isMainThread()) throw new IllegalStateException("Input operation requires the event thread");
    }
    private long handle() {
        requireEventThread();
        return window.nativeHandle();
    }
    @Override public void createMouse(InputEvents events) {
        long handle = handle();
        glfwSetCursorPosCallback(handle, (w, px, py) -> events.cursorPosition(px, py));
        glfwSetMouseButtonCallback(handle, (w, button, action, mods) -> events.mouseButton(button, action));
        glfwSetScrollCallback(handle, (w, horizontal, vertical) -> events.scroll(vertical));
        glfwSetCursorEnterCallback(handle, (w, entered) -> events.cursorEntered(entered));
    }
    @Override public void destroyMouse() {
        long handle = handle();
        GLFWCursorPosCallback position = glfwSetCursorPosCallback(handle, null);
        GLFWMouseButtonCallback button = glfwSetMouseButtonCallback(handle, null);
        GLFWScrollCallback scroll = glfwSetScrollCallback(handle, null);
        GLFWCursorEnterCallback enter = glfwSetCursorEnterCallback(handle, null);
        if (position != null) position.free(); if (button != null) button.free();
        if (scroll != null) scroll.free(); if (enter != null) enter.free();
        glfwSetInputMode(handle, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
    }
    @Override public void createKeyboard(InputEvents events) {
        long handle = handle();
        glfwSetKeyCallback(handle, (w, key, scan, action, mods) -> events.key(key, action));
        glfwSetCharCallback(handle, (w, codepoint) -> events.character(codepoint));
    }
    @Override public void destroyKeyboard() {
        long handle = handle();
        GLFWKeyCallback key = glfwSetKeyCallback(handle, null);
        GLFWCharCallback character = glfwSetCharCallback(handle, null);
        if (key != null) key.free(); if (character != null) character.free();
    }
    @Override public void cursorPosition(double[] x, double[] y) { glfwGetCursorPos(handle(), x, y); }
    @Override public boolean cursorInside() { return glfwGetWindowAttrib(handle(), GLFW_HOVERED) == GLFW_TRUE; }
    @Override public void grabMouse(boolean grabbed) { glfwSetInputMode(handle(), GLFW_CURSOR, grabbed ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL); }
    @Override public void setCursorPosition(double x, double y) { glfwSetCursorPos(handle(), x, y); }
    @Override public void setCursor(Object cursor) { glfwSetCursor(handle(), cursor == null ? 0 : ((Long)cursor).longValue()); }
    @Override public Object createCursor(int width, int height, int hotspotX, int hotspotY, IntBuffer images) {
        requireEventThread();
        ByteBuffer pixels = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width, height), 4));
        try {
            for (int i = 0; i < width * height; i++) {
                int abgr = images.get(images.position() + i);
                pixels.put((byte)abgr).put((byte)(abgr >>> 8)).put((byte)(abgr >>> 16)).put((byte)(abgr >>> 24));
            }
            pixels.flip();
            try (GLFWImage image = GLFWImage.malloc()) {
                image.width(width).height(height).pixels(pixels);
                long cursor = glfwCreateCursor(image, hotspotX, hotspotY);
                if (cursor == 0) throw new IllegalStateException("GLFW cursor creation failed");
                return Long.valueOf(cursor);
            }
        } finally { MemoryUtil.memFree(pixels); }
    }
    @Override public void destroyCursor(Object cursor) { requireEventThread(); glfwDestroyCursor(((Long)cursor).longValue()); }
}
