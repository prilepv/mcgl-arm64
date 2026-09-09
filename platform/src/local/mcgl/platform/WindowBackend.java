package local.mcgl.platform;

/** One game window. No legacy LWJGL, AWT, renderer, or game objects cross this contract. */
public interface WindowBackend {
    HostServices host();
    InputBackend input();
    WindowMode desktopMode();
    WindowMode[] availableModes();
    WindowMode mode();
    void setMode(WindowMode mode);
    void setFullscreen(boolean fullscreen);
    boolean isFullscreen();
    void create(ContextRequest request, WindowEvents events);
    void destroy();
    void shutdown();
    boolean isCreated();
    void makeCurrent();
    void releaseContext();
    void checkContextThread();
    boolean isCurrent();
    void swapBuffers();
    void setSwapInterval(int interval);
    void awaitWithoutContextLock(Runnable wait);
    void pollEvents();
    String clipboard();
    void setTitle(String title);
    String title();
    void setLocation(int x, int y);
    void setResizable(boolean resizable);
    boolean isResizable();
    boolean wasResized();
    boolean isActive();
    boolean isVisible();
    boolean isCloseRequested();
    boolean isDirty();
    boolean consumeDirty();
    int width();
    int height();
    int x();
    int y();
    int framebufferWidth();
    int framebufferHeight();
}
