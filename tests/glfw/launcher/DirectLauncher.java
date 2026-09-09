package local.mcgl;

/** Account-free entry point for the production native runtime's lifecycle test. */
public final class DirectLauncher {
    public static void main(String[] args) throws Exception {
        java.net.URL[] urls = {
            new java.io.File("bin/lwjgl.jar").toURI().toURL(),
            new java.io.File("bin/window-test.jar").toURI().toURL()
        };
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(urls, DirectLauncher.class.getClassLoader())) {
            try {
                Class.forName("org.lwjgl.opengl.GLFWWindowProbe", true, loader)
                        .getMethod("main", String[].class).invoke(null, (Object)new String[0]);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                cause.printStackTrace();
                throw new RuntimeException(cause);
            }
        }
    }
}
