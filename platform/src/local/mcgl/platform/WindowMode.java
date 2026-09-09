package local.mcgl.platform;

/** Platform value, with no dependency on the legacy DisplayMode ABI. */
public final class WindowMode {
    public final int width, height, bitsPerPixel, frequency;
    public final boolean fullscreenCapable;
    public WindowMode(int width, int height) { this(width, height, 0, 0, false); }
    public WindowMode(int width, int height, int bitsPerPixel, int frequency) {
        this(width, height, bitsPerPixel, frequency, true);
    }
    private WindowMode(int width, int height, int bitsPerPixel, int frequency, boolean fullscreenCapable) {
        this.width = width; this.height = height;
        this.bitsPerPixel = bitsPerPixel; this.frequency = frequency;
        this.fullscreenCapable = fullscreenCapable;
    }
}
