package local.mcgl.platform;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pure host selection/value/library-layout tests. Never initializes a native library. */
public final class PlatformContractTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        check(Platform.selectHost("Mac OS X", "aarch64") != null, "macOS aarch64 host");
        check(Platform.selectHost("Mac OS X", "arm64") != null, "macOS arm64 alias");
        for (String[] unsupported : new String[][] {{"Windows 11", "aarch64"}, {"Linux", "aarch64"},
                {"Mac OS X", "x86_64"}, {"Mac OS X", "unknown"}}) {
            try {
                Platform.selectHost(unsupported[0], unsupported[1]);
                throw new AssertionError("Unsupported host silently selected");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("macOS ARM64 only"), "explicit unsupported-host error");
            }
        }
        WindowMode windowed = new WindowMode(900, 700);
        WindowMode monitor = new WindowMode(1920, 1200, 30, 60);
        check(windowed.width == 900 && windowed.height == 700 && !windowed.fullscreenCapable, "windowed value");
        check(monitor.bitsPerPixel == 30 && monitor.frequency == 60 && monitor.fullscreenCapable, "monitor metadata");
        ContextRequest request = new ContextRequest(2, 1, false, false, false, 10, 10, 10, 8, 24, 8, 0);
        check(request.major == 2 && request.minor == 1 && !request.coreProfile && !request.forwardCompatible,
                "unchanged compatibility-context request");
        check(!request.scaleFramebuffer && request.redBits == 10 && request.depthBits == 24
                && request.stencilBits == 8 && request.samples == 0, "format and scale are explicit policy");
        for (Class<?> contract : new Class<?>[] {WindowBackend.class, HostServices.class, WindowEvents.class,
                InputBackend.class, InputEvents.class}) {
            for (Method method : contract.getDeclaredMethods()) {
                checkType(method.getReturnType());
                for (Class<?> parameter : method.getParameterTypes()) checkType(parameter);
            }
        }
        String previous = System.getProperty("org.lwjgl.librarypath");
        Path fixture = Files.createTempDirectory("mcgl-platform-libraries-");
        try {
            System.clearProperty("org.lwjgl.librarypath");
            missing(NativeLibraries.Library.GLFW, "path");
            System.setProperty("org.lwjgl.librarypath", fixture.toString());
            for (NativeLibraries.Library library : NativeLibraries.Library.values()) missing(library, "Missing bundled native library");
            String[] names = {"libglfw.dylib", "openal.dylib", "libmcgl-mainthread.dylib"};
            NativeLibraries.Library[] libraries = NativeLibraries.Library.values();
            for (int i = 0; i < names.length; i++) {
                Files.createFile(fixture.resolve(names[i]));
                check(NativeLibraries.resolve(libraries[i]).toPath().equals(fixture.resolve(names[i]).toAbsolutePath()),
                        "pinned local library name");
            }
            // Accessing the adapter/platform state must not initialize GLFW/JNI.
            check(!Platform.window().isCreated() && Platform.window().width() == 900
                    && Platform.window().height() == 700, "headless state without loading empty native fixtures");
        } finally {
            if (previous == null) System.clearProperty("org.lwjgl.librarypath");
            else System.setProperty("org.lwjgl.librarypath", previous);
            for (String name : new String[] {"libglfw.dylib", "openal.dylib", "libmcgl-mainthread.dylib"})
                Files.deleteIfExists(fixture.resolve(name));
            Files.delete(fixture);
        }
        System.out.println("PLATFORM_CONTRACT_PASS checks=" + checks + " selection/values/pinned-library-paths/no-native-initialization");
    }
    private static void checkType(Class<?> type) {
        while (type.isArray()) type = type.getComponentType();
        check(type.isPrimitive() || type.getName().startsWith("java.lang.") || type.getName().startsWith("java.nio.")
                || type.getName().startsWith("local.mcgl.platform."), "contract type is not legacy/native: " + type);
    }
    private static void missing(NativeLibraries.Library library, String message) {
        try { NativeLibraries.resolve(library); throw new AssertionError("Missing library accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains(message), "missing bundle file rejected"); }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
