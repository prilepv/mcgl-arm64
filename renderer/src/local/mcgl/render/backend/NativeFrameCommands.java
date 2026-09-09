package local.mcgl.render.backend;

import local.mcgl.render.FrameCommands;
import org.lwjgl.opengl.GL11C;

/** Only functions present in Core GL; no matrix stacks, client arrays or implicit drawing. */
final class NativeFrameCommands implements FrameCommands {
    public void viewport(int x, int y, int width, int height) { GL11C.glViewport(x, y, width, height); }
    public void clearColor(float red, float green, float blue, float alpha) { GL11C.glClearColor(red, green, blue, alpha); }
    public void clear(boolean color, boolean depth, boolean stencil) {
        int mask = (color ? GL11C.GL_COLOR_BUFFER_BIT : 0)
                | (depth ? GL11C.GL_DEPTH_BUFFER_BIT : 0)
                | (stencil ? GL11C.GL_STENCIL_BUFFER_BIT : 0);
        GL11C.glClear(mask);
    }
}
