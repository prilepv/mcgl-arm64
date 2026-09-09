package org.lwjgl.opengl;

import java.nio.*;
import java.util.Arrays;
import local.mcgl.platform.*;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import static org.lwjgl.glfw.GLFW.*;

/** GLFW events translated into the unchanged LWJGL 2 Keyboard/Mouse queue format. */
final class GLFWInput implements InputImplementation, InputEvents {
    private final WindowBackend window;
    private final InputBackend nativeInput;
    private final Object lock = new Object();
    private final EventQueue keys = new EventQueue(Keyboard.EVENT_SIZE);
    private final EventQueue mouse = new EventQueue(Mouse.EVENT_SIZE);
    private final ByteBuffer keyEvent = ByteBuffer.allocate(Keyboard.EVENT_SIZE);
    private final ByteBuffer mouseEvent = ByteBuffer.allocate(Mouse.EVENT_SIZE);
    private final byte[] keyState = new byte[Keyboard.KEYBOARD_SIZE], buttons = new byte[8];
    private boolean keyboardCreated, mouseCreated, grabbed, inside, havePosition;
    private double cursorX, cursorY, deltaX, deltaY, wheel, eventRemainderX, eventRemainderY, wheelRemainder;
    private int pendingKey = -1;
    private boolean pendingRepeat;
    private long pendingTime;
    private static final int[] LETTER_KEYS = {30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44};
    private static final int[] NUMPAD_KEYS = {82,79,80,81,75,76,77,71,72,73};

    GLFWInput() { this(Platform.window()); }
    GLFWInput(WindowBackend window) { this.window = window; nativeInput = window.input(); }

    @Override public void createMouse() {
        window.host().invoke(() -> {
            synchronized (lock) {
                mouseCreated = true; havePosition = false;
                mouse.clearEvents(); Arrays.fill(buttons, (byte)0);
                deltaX = deltaY = wheel = eventRemainderX = eventRemainderY = wheelRemainder = 0;
            }
            nativeInput.createMouse(this);
            double[] px = {0}, py = {0};
            nativeInput.cursorPosition(px, py);
            cursorPosition(px[0], py[0]);
            synchronized (lock) { inside = nativeInput.cursorInside(); }
        });
    }
    @Override public void destroyMouse() {
        window.host().invoke(() -> {
            nativeInput.destroyMouse();
            synchronized (lock) {
                mouseCreated = false; grabbed = false; havePosition = false;
                mouse.clearEvents(); Arrays.fill(buttons, (byte)0);
            }
        });
    }
    @Override public void createKeyboard() {
        window.host().invoke(() -> {
            synchronized (lock) { keyboardCreated = true; pendingKey = -1; keys.clearEvents(); Arrays.fill(keyState, (byte)0); }
            nativeInput.createKeyboard(this);
        });
    }
    @Override public void destroyKeyboard() {
        window.host().invoke(() -> {
            nativeInput.destroyKeyboard();
            synchronized (lock) { keyboardCreated = false; pendingKey = -1; keys.clearEvents(); Arrays.fill(keyState, (byte)0); }
        });
    }
    @Override public void key(int glfwKey, int action) {
        synchronized (lock) {
            if (!keyboardCreated) return;
            flushKey(0);
            int key = legacyKey(glfwKey);
            if (key == Keyboard.KEY_NONE) return;
            boolean pressed = action != GLFW_RELEASE;
            keyState[key] = (byte)(pressed ? 1 : 0);
            if (!pressed) putKey(key, false, 0, System.nanoTime(), false);
            else { pendingKey = key; pendingRepeat = action == GLFW_REPEAT; pendingTime = System.nanoTime(); }
        }
    }
    @Override public void character(int codepoint) {
        synchronized (lock) {
            if (!keyboardCreated || !Character.isValidCodePoint(codepoint)) return;
            char[] chars = Character.toChars(codepoint);
            boolean repeat = pendingKey >= 0 && pendingRepeat;
            if (pendingKey >= 0) flushKey(chars[0]);
            else putKey(Keyboard.KEY_NONE, true, chars[0], System.nanoTime(), false);
            for (int index = 1; index < chars.length; index++)
                putKey(Keyboard.KEY_NONE, true, chars[index], System.nanoTime(), repeat);
        }
    }
    private void flushKey(int character) {
        if (pendingKey < 0) return;
        putKey(pendingKey, true, character, pendingTime, pendingRepeat); pendingKey = -1;
    }
    private void putKey(int key, boolean pressed, int character, long time, boolean repeat) {
        keyEvent.clear();
        keyEvent.putInt(key).put((byte)(pressed ? 1 : 0)).putInt(character).putLong(time).put((byte)(repeat ? 1 : 0));
        keyEvent.flip(); keys.putEvent(keyEvent);
    }
    @Override public void cursorPosition(double px, double py) {
        synchronized (lock) {
            if (!mouseCreated) return;
            double invertedY = window.height() - 1 - py;
            if (!havePosition) {
                cursorX = px; cursorY = invertedY; havePosition = true;
                return;
            }
            double dx = px - cursorX, dy = invertedY - cursorY;
            cursorX = px; cursorY = invertedY;
            if (grabbed) {
                deltaX += dx; deltaY += dy;
                eventRemainderX += dx; eventRemainderY += dy;
                int ix = (int)eventRemainderX, iy = (int)eventRemainderY;
                eventRemainderX -= ix; eventRemainderY -= iy;
                if (ix != 0 || iy != 0) putMouse(-1, false, ix, iy, 0);
            } else putMouse(-1, false, (int)px, (int)invertedY, 0);
        }
    }
    @Override public void mouseButton(int button, int action) {
        synchronized (lock) {
            if (!mouseCreated || button < 0 || button >= buttons.length) return;
            buttons[button] = (byte)(action == GLFW_RELEASE ? 0 : 1);
            putMouse(button, action != GLFW_RELEASE, grabbed ? 0 : (int)cursorX, grabbed ? 0 : (int)cursorY, 0);
        }
    }
    @Override public void scroll(double vertical) {
        synchronized (lock) {
            if (!mouseCreated) return;
            double units = vertical * 120.0;
            wheel += units; wheelRemainder += units;
            int amount = (int)wheelRemainder; wheelRemainder -= amount;
            if (amount != 0) putMouse(-1, false, grabbed ? 0 : (int)cursorX, grabbed ? 0 : (int)cursorY, amount);
        }
    }
    private void putMouse(int button, boolean pressed, int px, int py, int wheel) {
        mouseEvent.clear();
        mouseEvent.put((byte)button).put((byte)(pressed ? 1 : 0)).putInt(px).putInt(py).putInt(wheel).putLong(System.nanoTime());
        mouseEvent.flip(); mouse.putEvent(mouseEvent);
    }
    @Override public void cursorEntered(boolean entered) { synchronized (lock) { inside = entered; } }
    void releaseAll() {
        synchronized (lock) {
            flushKey(0);
            for (int key = 1; key < keyState.length; key++) if (keyState[key] != 0) {
                keyState[key] = 0; putKey(key, false, 0, System.nanoTime(), false);
            }
            for (int button = 0; button < buttons.length; button++) if (buttons[button] != 0) {
                buttons[button] = 0; putMouse(button, false, grabbed ? 0 : (int)cursorX, grabbed ? 0 : (int)cursorY, 0);
            }
            deltaX = deltaY = eventRemainderX = eventRemainderY = 0; havePosition = false;
        }
    }
    void rebasePosition() {
        synchronized (lock) { havePosition = false; deltaX = deltaY = eventRemainderX = eventRemainderY = 0; }
    }
    @Override public void pollKeyboard(ByteBuffer target) {
        synchronized (lock) {
            flushKey(0);
            int position = target.position(); target.put(keyState); target.position(position);
        }
    }
    @Override public void readKeyboard(ByteBuffer target) { synchronized (lock) { flushKey(0); keys.copyEvents(target); } }
    @Override public void pollMouse(IntBuffer coords, ByteBuffer target) {
        synchronized (lock) {
            int dx = (int)deltaX, dy = (int)deltaY, dw = (int)wheel;
            coords.put(0, grabbed ? dx : (int)cursorX).put(1, grabbed ? dy : (int)cursorY).put(2, dw);
            deltaX -= dx; deltaY -= dy; wheel -= dw;
            int position = target.position(); target.put(buttons); target.position(position);
        }
    }
    @Override public void readMouse(ByteBuffer target) { synchronized (lock) { mouse.copyEvents(target); } }
    @Override public void grabMouse(boolean grab) {
        window.host().invoke(() -> {
            synchronized (lock) {
                grabbed = grab; havePosition = false;
                deltaX = deltaY = eventRemainderX = eventRemainderY = 0; mouse.clearEvents();
            }
            nativeInput.grabMouse(grab);
            // Keep the existing accelerated movement; raw motion is a separate user-visible change.
            double[] px = {0}, py = {0};
            nativeInput.cursorPosition(px, py);
            synchronized (lock) { cursorX = px[0]; cursorY = window.height() - 1 - py[0]; havePosition = true; }
        });
    }
    @Override public void setCursorPosition(int px, int py) {
        window.host().invoke(() -> {
            synchronized (lock) { havePosition = false; }
            nativeInput.setCursorPosition(px, window.height() - 1 - py);
            synchronized (lock) { cursorX = px; cursorY = py; havePosition = true; }
        });
    }
    @Override public void setNativeCursor(Object handle) {
        window.host().invoke(() -> nativeInput.setCursor(handle));
    }
    @Override public Object createCursor(int width, int height, int xHotspot, int yHotspot, int count, IntBuffer images, IntBuffer delays) throws LWJGLException {
        if (count != 1) throw new LWJGLException("Cursor animation frames are supplied separately by the legacy API");
        // Legacy macOS Cursor has already flipped rows/hotspot and converted ARGB to ABGR.
        final Object[] cursor = {null};
        try {
            window.host().invoke(() -> cursor[0] = nativeInput.createCursor(width, height, xHotspot, yHotspot, images));
        } catch (IllegalStateException failure) {
            throw new LWJGLException("GLFW cursor creation failed", failure);
        }
        return cursor[0];
    }
    @Override public void destroyCursor(Object handle) { window.host().invoke(() -> nativeInput.destroyCursor(handle)); }
    @Override public int getNativeCursorCapabilities() { return Cursor.CURSOR_ONE_BIT_TRANSPARENCY | Cursor.CURSOR_8_BIT_ALPHA | Cursor.CURSOR_ANIMATION; }
    @Override public int getMinCursorSize() { return 1; }
    @Override public int getMaxCursorSize() { WindowMode mode = window.desktopMode(); return Math.min(mode.width, mode.height) / 2; }
    @Override public boolean hasWheel() { return true; }
    @Override public int getButtonCount() { return buttons.length; }
    @Override public int getWidth() { return window.width(); }
    @Override public int getHeight() { return window.height(); }
    @Override public boolean isInsideWindow() { synchronized (lock) { return inside; } }

    static int legacyKey(int key) {
        if (key >= GLFW_KEY_A && key <= GLFW_KEY_Z)
            return LETTER_KEYS[key - GLFW_KEY_A];
        if (key >= GLFW_KEY_1 && key <= GLFW_KEY_9) return Keyboard.KEY_1 + key - GLFW_KEY_1;
        if (key >= GLFW_KEY_F1 && key <= GLFW_KEY_F10) return Keyboard.KEY_F1 + key - GLFW_KEY_F1;
        if (key >= GLFW_KEY_KP_0 && key <= GLFW_KEY_KP_9)
            return NUMPAD_KEYS[key - GLFW_KEY_KP_0];
        switch (key) {
            case GLFW_KEY_0: return Keyboard.KEY_0;
            case GLFW_KEY_ESCAPE: return Keyboard.KEY_ESCAPE;
            case GLFW_KEY_MINUS: return Keyboard.KEY_MINUS;
            case GLFW_KEY_EQUAL: return Keyboard.KEY_EQUALS;
            case GLFW_KEY_BACKSPACE: return Keyboard.KEY_BACK;
            case GLFW_KEY_TAB: return Keyboard.KEY_TAB;
            case GLFW_KEY_LEFT_BRACKET: return Keyboard.KEY_LBRACKET;
            case GLFW_KEY_RIGHT_BRACKET: return Keyboard.KEY_RBRACKET;
            case GLFW_KEY_ENTER: return Keyboard.KEY_RETURN;
            case GLFW_KEY_LEFT_CONTROL: return Keyboard.KEY_LCONTROL;
            case GLFW_KEY_RIGHT_CONTROL: return Keyboard.KEY_RCONTROL;
            case GLFW_KEY_SEMICOLON: return Keyboard.KEY_SEMICOLON;
            case GLFW_KEY_APOSTROPHE: return Keyboard.KEY_APOSTROPHE;
            case GLFW_KEY_GRAVE_ACCENT: return Keyboard.KEY_GRAVE;
            case GLFW_KEY_LEFT_SHIFT: return Keyboard.KEY_LSHIFT;
            case GLFW_KEY_RIGHT_SHIFT: return Keyboard.KEY_RSHIFT;
            case GLFW_KEY_BACKSLASH: return Keyboard.KEY_BACKSLASH;
            case GLFW_KEY_COMMA: return Keyboard.KEY_COMMA;
            case GLFW_KEY_PERIOD: return Keyboard.KEY_PERIOD;
            case GLFW_KEY_SLASH: return Keyboard.KEY_SLASH;
            case GLFW_KEY_LEFT_ALT: return Keyboard.KEY_LMENU;
            case GLFW_KEY_RIGHT_ALT: return Keyboard.KEY_RMENU;
            case GLFW_KEY_SPACE: return Keyboard.KEY_SPACE;
            case GLFW_KEY_CAPS_LOCK: return Keyboard.KEY_CAPITAL;
            case GLFW_KEY_NUM_LOCK: return Keyboard.KEY_NUMLOCK;
            case GLFW_KEY_SCROLL_LOCK: return Keyboard.KEY_SCROLL;
            case GLFW_KEY_F11: return Keyboard.KEY_F11;
            case GLFW_KEY_F12: return Keyboard.KEY_F12;
            case GLFW_KEY_F13: return Keyboard.KEY_F13;
            case GLFW_KEY_F14: return Keyboard.KEY_F14;
            case GLFW_KEY_F15: return Keyboard.KEY_F15;
            case GLFW_KEY_F16: return Keyboard.KEY_F16;
            case GLFW_KEY_F17: return Keyboard.KEY_F17;
            case GLFW_KEY_F18: return Keyboard.KEY_F18;
            case GLFW_KEY_F19: return Keyboard.KEY_F19;
            case GLFW_KEY_KP_DECIMAL: return Keyboard.KEY_DECIMAL;
            case GLFW_KEY_KP_DIVIDE: return Keyboard.KEY_DIVIDE;
            case GLFW_KEY_KP_MULTIPLY: return Keyboard.KEY_MULTIPLY;
            case GLFW_KEY_KP_SUBTRACT: return Keyboard.KEY_SUBTRACT;
            case GLFW_KEY_KP_ADD: return Keyboard.KEY_ADD;
            case GLFW_KEY_KP_ENTER: return Keyboard.KEY_NUMPADENTER;
            case GLFW_KEY_KP_EQUAL: return Keyboard.KEY_NUMPADEQUALS;
            case GLFW_KEY_PRINT_SCREEN: return Keyboard.KEY_SYSRQ;
            case GLFW_KEY_PAUSE: return Keyboard.KEY_PAUSE;
            case GLFW_KEY_HOME: return Keyboard.KEY_HOME;
            case GLFW_KEY_UP: return Keyboard.KEY_UP;
            case GLFW_KEY_PAGE_UP: return Keyboard.KEY_PRIOR;
            case GLFW_KEY_LEFT: return Keyboard.KEY_LEFT;
            case GLFW_KEY_RIGHT: return Keyboard.KEY_RIGHT;
            case GLFW_KEY_END: return Keyboard.KEY_END;
            case GLFW_KEY_DOWN: return Keyboard.KEY_DOWN;
            case GLFW_KEY_PAGE_DOWN: return Keyboard.KEY_NEXT;
            case GLFW_KEY_INSERT: return Keyboard.KEY_INSERT;
            case GLFW_KEY_DELETE: return Keyboard.KEY_DELETE;
            case GLFW_KEY_LEFT_SUPER: return Keyboard.KEY_LMETA;
            case GLFW_KEY_RIGHT_SUPER: return Keyboard.KEY_RMETA;
            case GLFW_KEY_MENU: return Keyboard.KEY_APPS;
            case GLFW_KEY_WORLD_1: return Keyboard.KEY_SECTION;
            default: return Keyboard.KEY_NONE;
        }
    }
}
