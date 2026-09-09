package local.mcgl.render;

/** Operations shared by compatibility and Core contexts, without fixed-function drawing. */
public interface FrameCommands {
    void viewport(int x, int y, int width, int height);
    void clearColor(float red, float green, float blue, float alpha);
    void clear(boolean color, boolean depth, boolean stencil);
}
