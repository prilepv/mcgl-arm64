package local.mcgl.render;

/** Cached frame operations obey the same generation/owner checks as legacy commands. */
final class GuardedFrameCommands implements FrameCommands {
    private final RenderContext context;
    private final FrameCommands delegate;
    GuardedFrameCommands(RenderContext context, FrameCommands delegate) {
        this.context = context; this.delegate = delegate;
    }
    public void viewport(int x, int y, int width, int height) {
        context.checkOwner();
        if (width < 0 || height < 0) throw new IllegalArgumentException("Negative viewport dimensions");
        delegate.viewport(x, y, width, height);
    }
    public void clearColor(float red, float green, float blue, float alpha) {
        context.checkOwner(); delegate.clearColor(red, green, blue, alpha);
    }
    public void clear(boolean color, boolean depth, boolean stencil) {
        context.checkOwner(); delegate.clear(color, depth, stencil);
    }
}
