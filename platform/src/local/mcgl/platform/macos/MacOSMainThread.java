package local.mcgl.platform.macos;

import local.mcgl.platform.NativeLibraries;

/** Executes GLFW's window/event operations on the existing macOS first thread. */
final class MacOSMainThread {
    static { System.load(NativeLibraries.resolve(NativeLibraries.Library.MAIN_THREAD).getAbsolutePath()); }
    private MacOSMainThread() {}
    public static native boolean isMainThread();
    public static native void invoke(Runnable action);
    public static native void lockContext();
    public static native void unlockContext();
    public static native boolean isContextLocked();
    public static native void attachFullscreen(long window);
    public static native void requestFullscreen(long window, boolean value);
    public static native int fullscreenState(long window, boolean reconcile);
    public static native void detachFullscreen(long window);
}
