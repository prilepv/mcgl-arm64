package local.mcgl.platform.macos;

import local.mcgl.platform.HostServices;

/** Cocoa/CGL services, kept outside the window and legacy input adapters. */
public final class MacOSHostServices implements HostServices {
    @Override public boolean isMainThread() { return MacOSMainThread.isMainThread(); }
    @Override public void invoke(Runnable action) { MacOSMainThread.invoke(action); }
    @Override public void lockContext() { MacOSMainThread.lockContext(); }
    @Override public void unlockContext() { MacOSMainThread.unlockContext(); }
    @Override public boolean isContextLocked() { return MacOSMainThread.isContextLocked(); }
    @Override public void configureWindowing() {
        org.lwjgl.glfw.GLFW.glfwInitHint(org.lwjgl.glfw.GLFW.GLFW_COCOA_CHDIR_RESOURCES,
                org.lwjgl.glfw.GLFW.GLFW_FALSE);
    }
    @Override public boolean usesNativeFullscreen() { return true; }
    private long cocoaWindow(long window) { return org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(window); }
    @Override public void attachNativeFullscreen(long window) { MacOSMainThread.attachFullscreen(cocoaWindow(window)); }
    @Override public void requestNativeFullscreen(long window, boolean fullscreen) {
        MacOSMainThread.requestFullscreen(cocoaWindow(window), fullscreen);
    }
    @Override public int nativeFullscreenState(long window, boolean reconcile) {
        return MacOSMainThread.fullscreenState(cocoaWindow(window), reconcile);
    }
    @Override public void detachNativeFullscreen(long window) { MacOSMainThread.detachFullscreen(cocoaWindow(window)); }
    @Override public String description() { return "macOS-main render=game-thread context-lock=CGL/nsgl-lock1 fullscreen=Spaces/native1"; }
    @Override public boolean openURL(String url) {
        try { new ProcessBuilder("/usr/bin/open", url).start(); return true; }
        catch (java.io.IOException failed) { return false; }
    }
}
