package local.mcgl;
import java.io.File;
import java.lang.reflect.Field;
import net.mcgl.MCGLClassLoader;
public final class DirectLauncher {
    public static void main(String[] args) throws Exception {
        new MCGLClassLoader("mcgl.jar");
        Field field = MCGLClassLoader.class.getDeclaredField("classLoader");
        field.setAccessible(true);
        ClassLoader loader = (ClassLoader)field.get(null);
        Thread.currentThread().setContextClassLoader(loader);
        System.setProperty("mcgl.test.offscreenLibrary", new File("offscreen.dylib").getAbsolutePath());
        System.setProperty("mcgl.metal.capture.warmup", "0");
        System.setProperty("mcgl.metal.capture.offscreen", "true");
        System.setProperty("mcgl.metal.live.interval", "250");
        if (System.getenv("MCGL_BGFX_LIVE_LIBRARY") == null)
            System.setProperty("mcgl.metal.live.cacheBytes", "1536");
        System.setProperty("mcgl.metal.live.fixture", "true");
        Class.forName("local.mcgl.render.tests.MetalTerrainLiveProbe", true, loader)
            .getMethod("main", String[].class).invoke(null, (Object)new String[]{"bin/mcgl.jar"});
    }
}
