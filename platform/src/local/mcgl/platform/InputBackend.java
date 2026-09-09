package local.mcgl.platform;

import java.nio.IntBuffer;

/** Native input operations run on the event thread via HostServices.invoke. */
public interface InputBackend {
    void createMouse(InputEvents events);
    void destroyMouse();
    void createKeyboard(InputEvents events);
    void destroyKeyboard();
    void cursorPosition(double[] x, double[] y);
    boolean cursorInside();
    void grabMouse(boolean grabbed);
    void setCursorPosition(double x, double y);
    void setCursor(Object cursor);
    /** Pixels are ABGR, top row first. The returned handle is opaque to the client. */
    Object createCursor(int width, int height, int hotspotX, int hotspotY, IntBuffer pixels);
    void destroyCursor(Object cursor);
}
