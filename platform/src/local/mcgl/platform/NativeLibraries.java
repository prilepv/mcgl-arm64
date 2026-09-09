package local.mcgl.platform;

import java.io.File;

/** Pinned bundle layout; deliberately no system-library or unsupported-host fallback. */
public final class NativeLibraries {
    public enum Library { GLFW, OPENAL_SOFT, MAIN_THREAD }
    private NativeLibraries() {}

    public static void requireSupportedHost(String os, String architecture) {
        if (!"Mac OS X".equals(os) || !("aarch64".equals(architecture) || "arm64".equals(architecture)))
            throw new IllegalStateException("Unsupported platform: " + os + "/" + architecture
                    + "; this build supports macOS ARM64 only");
    }

    public static File resolve(Library library) {
        requireSupportedHost(System.getProperty("os.name"), System.getProperty("os.arch"));
        String directory = System.getProperty("org.lwjgl.librarypath");
        if (directory == null) throw new IllegalStateException("Missing bundled native library path");
        String name;
        switch (library) {
            case GLFW: name = "libglfw.dylib"; break;
            case OPENAL_SOFT: name = "openal.dylib"; break;
            case MAIN_THREAD: name = "libmcgl-mainthread.dylib"; break;
            default: throw new IllegalArgumentException("Unknown native library: " + library);
        }
        File file = new File(directory, name).getAbsoluteFile();
        if (!file.isFile()) throw new IllegalStateException("Missing bundled native library: " + name);
        return file;
    }
}
