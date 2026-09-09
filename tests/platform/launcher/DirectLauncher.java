package local.mcgl;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/** Test-only bootstrap, no client/account or external actions. */
public final class DirectLauncher {
    public static void main(String[] ignored) throws Exception {
        java.awt.Toolkit.getDefaultToolkit();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {
                new File("bin/lwjgl.jar").toURI().toURL(), new File("bin/platform-test.jar").toURI().toURL()
        }, DirectLauncher.class.getClassLoader())) {
            try {
                Class.forName("local.mcgl.platform.PlatformWindowProbe", true, loader)
                        .getMethod("main", String[].class).invoke(null, (Object)new String[0]);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                failure.getCause().printStackTrace(); System.exit(1);
            }
        }
    }
}
