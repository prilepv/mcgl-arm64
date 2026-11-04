package org.lwjgl.opengl;

import local.mcgl.render.RenderProfile;

/** Test-only real macOS Core context for GPU checks when no monitor is exposed to GLFW. */
public final class OffscreenCoreContext {
    private static Object key;
    private static long context;
    private static native long create();
    private static native void release(long context);
    public static void open() throws Exception {
        if (context != 0) throw new IllegalStateException("Context already open");
        System.load(System.getProperty("mcgl.test.offscreenLibrary"));
        context = create();
        if (context == 0) throw new IllegalStateException("Cannot create CGL Core 4.1 test context");
        key = new Object();
        try { GLContext.useContext(key, RenderProfile.CORE_41, true); }
        catch (Throwable failure) { release(context); context = 0; key = null; throw failure; }
    }
    public static void close() {
        try { GLContext.destroyContext(key); }
        finally { release(context); context = 0; key = null; }
    }
}
