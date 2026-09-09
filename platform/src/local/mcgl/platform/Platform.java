package local.mcgl.platform;

import local.mcgl.platform.glfw.GlfwWindow;
import local.mcgl.platform.macos.MacOSHostServices;

/** Explicit, single-backend composition. This is not a claim of Windows/Linux support. */
public final class Platform {
    private static final HostServices HOST = selectHost(System.getProperty("os.name"), System.getProperty("os.arch"));
    private static final WindowBackend WINDOW = new GlfwWindow(HOST);
    private Platform() {}
    public static HostServices host() { return HOST; }
    public static WindowBackend window() { return WINDOW; }
    static HostServices selectHost(String os, String architecture) {
        NativeLibraries.requireSupportedHost(os, architecture);
        return new MacOSHostServices();
    }
}
