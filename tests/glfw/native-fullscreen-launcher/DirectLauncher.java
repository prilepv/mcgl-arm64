package local.mcgl;

/** Account-free production-runtime entry for the native Spaces/Core probe. */
public final class DirectLauncher {
    public static void main(String[] args) throws Exception {
        java.net.URL[] urls = {
            new java.io.File("bin/lwjgl.jar").toURI().toURL(),
            new java.io.File("bin/window-test.jar").toURI().toURL()
        };
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(urls, DirectLauncher.class.getClassLoader())) {
            try {
                Class.forName("org.lwjgl.opengl.NativeFullscreenProbe", true, loader)
                        .getMethod("main", String[].class).invoke(null, (Object)new String[0]);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                Throwable cause = failure.getCause(); cause.printStackTrace(); throw new RuntimeException(cause);
            }
        }
    }
}
