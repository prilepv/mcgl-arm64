package local.mcgl.render.backend;

import java.nio.Buffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Existing LWJGL 2 pointer-retention semantics, now owned by one renderer context. */
final class CompatibilityState {
    Map<Integer, Buffer> pointers = new HashMap<Integer, Buffer>();
    final ArrayDeque<Map<Integer, Buffer>> stack = new ArrayDeque<Map<Integer, Buffer>>();
    final ArrayDeque<Integer> units = new ArrayDeque<Integer>();
    int textureUnit = org.lwjgl.opengl.GL13.GL_TEXTURE0;
    boolean begin;

    void pushClientAttributes(int mask) {
        boolean saveArrays = (mask & org.lwjgl.opengl.GL11.GL_CLIENT_VERTEX_ARRAY_BIT) != 0;
        stack.push(saveArrays ? new HashMap<Integer, Buffer>(pointers) : java.util.Collections.<Integer, Buffer>emptyMap());
        units.push(saveArrays ? textureUnit : Integer.MIN_VALUE);
    }
    void popClientAttributes() {
        if (!stack.isEmpty()) {
            Map<Integer, Buffer> previous = stack.pop();
            int unit = units.pop();
            if (unit != Integer.MIN_VALUE) { pointers = previous; textureUnit = unit; }
        }
    }
    void clear() { pointers.clear(); stack.clear(); units.clear(); begin = false; }
}
