package local.mcgl.platform;

/** Host-only services. Rendering remains on the existing game/context owner. */
public interface HostServices {
    boolean isMainThread();
    void invoke(Runnable action);
    void lockContext();
    void unlockContext();
    boolean isContextLocked();
    /** Host initialization hints for the bundled window backend; event thread only. */
    void configureWindowing();
    /** Optional host-native Spaces fullscreen. All handle operations run on the event thread. */
    default boolean usesNativeFullscreen() { return false; }
    default void attachNativeFullscreen(long window) { throw new UnsupportedOperationException(); }
    default void requestNativeFullscreen(long window, boolean fullscreen) { throw new UnsupportedOperationException(); }
    /** Bits: 1 requested (including a queued reversal), 2 actual, 4 transition in flight. */
    default int nativeFullscreenState(long window, boolean reconcile) { throw new UnsupportedOperationException(); }
    default void detachNativeFullscreen(long window) { throw new UnsupportedOperationException(); }
    boolean openURL(String url);
    String description();
}
