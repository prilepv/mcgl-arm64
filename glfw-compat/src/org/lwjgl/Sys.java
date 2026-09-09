package org.lwjgl;

import local.mcgl.platform.Platform;

/** Legacy utility entry points without loading a LWJGL 2 native library. */
public final class Sys {
    private Sys() {}
    public static void initialize() {}
    public static String getVersion() { return Version.getVersion(); }
    public static boolean is64Bit() { return true; }
    public static long getTime() { return System.currentTimeMillis(); }
    public static long getTimerResolution() { return 1000L; }
    public static String getClipboard() {
        return Platform.window().clipboard();
    }
    public static void alert(String title, String message) { System.err.println(title + ": " + message); }
    public static boolean openURL(String url) {
        return Platform.host().openURL(url);
    }
}
