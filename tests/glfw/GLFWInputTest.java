package org.lwjgl.opengl;

import java.lang.reflect.Field;
import java.nio.*;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import static org.lwjgl.glfw.GLFW.*;

/** Pure input translation checks: no native library, window, account or network. */
public final class GLFWInputTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        GLFWInput input = new GLFWInput();
        set(input, "keyboardCreated", true); set(input, "mouseCreated", true);
        check(GLFWInput.legacyKey(GLFW_KEY_W) == Keyboard.KEY_W, "W mapping");
        check(GLFWInput.legacyKey(GLFW_KEY_F11) == Keyboard.KEY_F11, "F11 mapping");
        check(GLFWInput.legacyKey(GLFW_KEY_RIGHT_SUPER) == Keyboard.KEY_RMETA, "right Command");
        check(GLFWInput.legacyKey(GLFW_KEY_KP_7) == Keyboard.KEY_NUMPAD7, "numeric keypad");
        check(GLFWInput.legacyKey(GLFW_KEY_UNKNOWN) == Keyboard.KEY_NONE, "unknown key");
        for (int key = GLFW_KEY_A; key <= GLFW_KEY_Z; key++)
            check(GLFWInput.legacyKey(key) > 0, "alphabet mapping");
        input.key(GLFW_KEY_A, GLFW_PRESS);
        input.character('ф');
        input.key(GLFW_KEY_A, GLFW_REPEAT);
        input.character('Ф');
        input.key(GLFW_KEY_A, GLFW_RELEASE);
        ByteBuffer keys = ByteBuffer.allocate(Keyboard.EVENT_SIZE * 256);
        input.readKeyboard(keys); keys.flip();
        key(keys, Keyboard.KEY_A, true, 'ф', false);
        key(keys, Keyboard.KEY_A, true, 'Ф', true);
        key(keys, Keyboard.KEY_A, false, 0, false);
        check(!keys.hasRemaining(), "no duplicate character events");
        input.character(0x1F642);
        keys.clear(); input.readKeyboard(keys); keys.flip();
        char[] surrogate = Character.toChars(0x1F642);
        key(keys, 0, true, surrogate[0], false); key(keys, 0, true, surrogate[1], false);
        input.key(GLFW_KEY_LEFT_CONTROL, GLFW_PRESS);
        input.key(GLFW_KEY_RIGHT_SHIFT, GLFW_PRESS);
        input.mouseButton(0, GLFW_PRESS);
        input.releaseAll();
        ByteBuffer states = ByteBuffer.allocate(256);
        input.pollKeyboard(states);
        check(states.position() == 0, "keyboard buffer position retained");
        check(states.get(Keyboard.KEY_LCONTROL) == 0 && states.get(Keyboard.KEY_RSHIFT) == 0, "focus releases held keys");
        ByteBuffer buttons = ByteBuffer.allocate(8);
        IntBuffer coords = IntBuffer.allocate(3);
        input.pollMouse(coords, buttons);
        check(buttons.get(0) == 0, "focus releases held mouse button");
        input.cursorPosition(50, 25);
        input.cursorPosition(60, 35);
        input.scroll(0.5);
        input.pollMouse(coords, buttons);
        check(coords.get(0) == 60 && coords.get(1) == 664, "top-left to bottom-left coordinates");
        check(coords.get(2) == 60, "half wheel notch");
        input.pollMouse(coords, buttons); check(coords.get(2) == 0, "wheel consumed once");
        ByteBuffer events = ByteBuffer.allocate(Mouse.EVENT_SIZE * 256);
        input.readMouse(events); events.clear(); // discard preceding absolute/focus events
        set(input, "grabbed", true);
        input.rebasePosition();
        input.cursorPosition(100, 100);
        input.cursorPosition(100.4, 99.6);
        input.cursorPosition(101.25, 98.75);
        input.pollMouse(coords, buttons);
        check(coords.get(0) == 1 && coords.get(1) == 1, "fractional deltas accumulate");
        input.pollMouse(coords, buttons);
        check(coords.get(0) == 0 && coords.get(1) == 0, "relative deltas consumed once");
        input.cursorPosition(102, 98);
        input.pollMouse(coords, buttons);
        check(coords.get(0) == 1 && coords.get(1) == 1, "fractional residue retained");
        input.mouseButton(1, GLFW_PRESS);
        input.readMouse(events); events.flip();
        int moves = 0, rightClicks = 0;
        while (events.hasRemaining()) {
            int button = events.get(); boolean pressed = events.get() != 0;
            int dx = events.getInt(), dy = events.getInt(), wheel = events.getInt();
            long nanos = events.getLong();
            check(nanos != 0 && wheel == 0, "mouse event timestamp and wheel");
            if (button == -1) { check(dx == 1 && dy == 1, "relative event coordinate format"); moves++; }
            if (button == 1) { check(pressed && dx == 0 && dy == 0, "button event has no phantom movement"); rightClicks++; }
        }
        check(moves == 2 && rightClicks == 1, "movement and right click event count");
        keys.clear(); input.readKeyboard(keys);
        for (int i = 0; i < 1000; i++) input.key(GLFW_KEY_W, (i & 1) == 0 ? GLFW_PRESS : GLFW_RELEASE);
        input.pollKeyboard(states);
        check(states.get(Keyboard.KEY_W) == 0, "queue overflow cannot leave key polled down");
        keys.clear(); input.readKeyboard(keys);
        check(keys.position() <= Keyboard.EVENT_SIZE * 200, "event queue remains bounded");
        System.out.println("GLFW_INPUT_PASS checks=" + checks + " keys/Unicode/repeat/focus/mouse/wheel/bounded-queues");
    }
    private static void key(ByteBuffer events, int key, boolean down, int character, boolean repeat) {
        check(events.remaining() >= Keyboard.EVENT_SIZE, "complete keyboard event");
        check(events.getInt() == key, "key code");
        check((events.get() != 0) == down, "key state");
        check(events.getInt() == character, "character");
        check(events.getLong() != 0, "key timestamp");
        check((events.get() != 0) == repeat, "repeat");
    }
    private static void set(Object value, String name, boolean state) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); field.setBoolean(value, state);
    }
    private static void check(boolean passed, String message) {
        checks++; if (!passed) throw new AssertionError(message);
    }
}
