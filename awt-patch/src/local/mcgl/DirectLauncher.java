package local.mcgl;

import java.applet.Applet;
import java.applet.AppletContext;
import java.applet.AppletStub;
import java.awt.Canvas;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;

import net.mcgl.MCGLClassLoader;

/**
 * Starts the MCGL game loop on Java's first thread and lets LWJGL create its
 * own Cocoa window.  The original launcher embeds LWJGL into an AWT Canvas;
 * that produces a permanently black CALayer on current macOS versions.
 */
public final class DirectLauncher {
    private static final int WIDTH = 900;
    private static final int HEIGHT = 700;

    private DirectLauncher() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: DirectLauncher <login>");
            System.exit(2);
        }

        final String login = args[0];
        final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        final String password = input.readLine();
        if (password == null || password.length() == 0) {
            System.err.println("Empty password from stdin.");
            System.exit(3);
        }

        Thread.currentThread().setName("MCGL direct Cocoa thread");
        prepareHeadlessAppletShell();
        System.out.println("Direct Cocoa mode: creating MCGL Applet without an AWT window.");

        MCGLClassLoader loader = new MCGLClassLoader("mcgl.jar");
        Applet applet = createPeerlessApplet(loader);
        applet.setStub(new LocalStub(applet, login, password));
        applet.setSize(WIDTH, HEIGHT);
        applet.init();
        applet.setSize(WIDTH, HEIGHT);
        applet.doLayout();
        sizeCanvases(applet);
        applet.start();

        Runnable minecraft = findMinecraftRunnable(applet);
        createStandaloneDisplay(applet.getClass().getClassLoader());
        startDisplayMonitor(applet.getClass().getClassLoader());
        requestJavaForeground("after direct LWJGL window creation");
        System.out.println("Direct Cocoa mode: starting the game loop on the first JVM thread.");
        minecraft.run();
        System.out.println("Direct Cocoa mode: game loop finished.");
    }

    /**
     * The Applet hierarchy is used only as a logical container.  Loading the
     * native Java 8 Cocoa toolkit corrupts the application menu on macOS 15,
     * while LWJGL creates and owns the real NSWindow itself.  Initialize the
     * safe headless toolkit, then permit construction of the peerless Applet.
     */
    private static void prepareHeadlessAppletShell() throws Exception {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!GraphicsEnvironment.isHeadless())
            throw new IllegalStateException("The safe headless AWT toolkit was not selected.");
        System.out.println("AWT compatibility mode: " + toolkit.getClass().getName() +
                " with a peerless MCGL Applet shell.");
    }

    private static Applet createPeerlessApplet(MCGLClassLoader loader) throws Exception {
        setHeadlessState(false);
        try {
            return loader.createApplet();
        } finally {
            setHeadlessState(true);
            System.out.println("AWT compatibility mode: restored headless rendering after Applet construction.");
        }
    }

    private static void setHeadlessState(boolean headless) throws Exception {
        System.setProperty("java.awt.headless", Boolean.toString(headless));
        for (String fieldName : new String[] {"headless", "defaultHeadless"}) {
            Field field = GraphicsEnvironment.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, Boolean.valueOf(headless));
        }
    }

    private static void createStandaloneDisplay(ClassLoader gameLoader) throws Exception {
        // The normal AWT toolkit creates NSApplication as a side effect.  Our
        // peerless Applet deliberately uses HeadlessToolkit, so initialize
        // AppKit explicitly on the JVM/main thread before asking macOS for an
        // NSOpenGLPixelFormat.  Without this, Apple Silicon returns nil even
        // for a valid legacy OpenGL 2.1 format.
        int preexistingWindows = CocoaWindowBridge.activateAndRaise();
        if (preexistingWindows < 0)
            throw new IllegalStateException("Cocoa bridge was not called on the JVM main thread.");
        System.out.println("Cocoa application initialized before OpenGL; existing windows: " +
                preexistingWindows + ".");

        Class<?> displayModeClass = Class.forName("org.lwjgl.opengl.DisplayMode", true, gameLoader);
        Object displayMode = displayModeClass.getConstructor(Integer.TYPE, Integer.TYPE)
                .newInstance(Integer.valueOf(WIDTH), Integer.valueOf(HEIGHT));

        Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display", true, gameLoader);
        displayClass.getMethod("setDisplayMode", displayModeClass).invoke(null, displayMode);
        displayClass.getMethod("setLocation", Integer.TYPE, Integer.TYPE)
                .invoke(null, Integer.valueOf(80), Integer.valueOf(80));
        displayClass.getMethod("setTitle", String.class).invoke(null, "Minecraft Galaxy");

        Class<?> pixelFormatClass = Class.forName("org.lwjgl.opengl.PixelFormat", true, gameLoader);
        Object pixelFormat = pixelFormatClass.getConstructor().newInstance();
        pixelFormat = pixelFormatClass.getMethod("withDepthBits", Integer.TYPE)
                .invoke(pixelFormat, Integer.valueOf(24));
        pixelFormat = pixelFormatClass.getMethod("withStencilBits", Integer.TYPE)
                .invoke(pixelFormat, Integer.valueOf(8));
        displayClass.getMethod("create", pixelFormatClass).invoke(null, pixelFormat);

        int raised = CocoaWindowBridge.activateAndRaise();
        if (raised < 1)
            throw new IllegalStateException("Cocoa bridge found no visible LWJGL window (result " + raised + ").");
        System.out.println("Direct Cocoa bridge raised " + raised + " window(s) on the JVM main thread.");
    }

    private static void requestJavaForeground(String stage) {
        try {
            Class<?> applicationClass = Class.forName("com.apple.eawt.Application");
            Object application = applicationClass.getMethod("getApplication").invoke(null);
            applicationClass.getMethod("requestForeground", Boolean.TYPE)
                    .invoke(application, Boolean.TRUE);
            System.out.println("Java macOS foreground requested " + stage + ".");
        } catch (Throwable error) {
            System.err.println("Java macOS foreground request failed " + stage + ": " + error);
        }
    }

    private static void startDisplayMonitor(final ClassLoader gameLoader) {
        Thread monitor = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(2500L);
                    Class<?> display = Class.forName("org.lwjgl.opengl.Display", true, gameLoader);
                    Method isCreated = display.getMethod("isCreated");
                    for (int attempt = 0; attempt < 20; attempt++) {
                        if (((Boolean)isCreated.invoke(null)).booleanValue()) {
                            requestJavaForeground("after LWJGL window creation");
                            display.getMethod("setLocation", Integer.TYPE, Integer.TYPE)
                                    .invoke(null, Integer.valueOf(80), Integer.valueOf(80));
                            boolean visible = ((Boolean)display.getMethod("isVisible").invoke(null)).booleanValue();
                            boolean active = ((Boolean)display.getMethod("isActive").invoke(null)).booleanValue();
                            int width = ((Integer)display.getMethod("getWidth").invoke(null)).intValue();
                            int height = ((Integer)display.getMethod("getHeight").invoke(null)).intValue();
                            int x = ((Integer)display.getMethod("getX").invoke(null)).intValue();
                            int y = ((Integer)display.getMethod("getY").invoke(null)).intValue();
                            System.out.println("LWJGL window: created=true visible=" + visible +
                                    " active=" + active + " bounds=" + width + "x" + height +
                                    "+" + x + "+" + y);
                            return;
                        }
                        Thread.sleep(500L);
                    }
                    System.err.println("LWJGL window monitor: Display was not created within 12 seconds.");
                } catch (Throwable error) {
                    System.err.println("LWJGL window monitor failed: " + error);
                }
            }
        }, "MCGL LWJGL window monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private static Runnable findMinecraftRunnable(Applet applet) throws Exception {
        for (Field field : applet.getClass().getDeclaredFields()) {
            if (Runnable.class.isAssignableFrom(field.getType()) &&
                    field.getType().getName().equals("net.minecraft.client.Minecraft")) {
                field.setAccessible(true);
                Object value = field.get(applet);
                if (value instanceof Runnable)
                    return (Runnable)value;
            }
        }
        throw new IllegalStateException("Minecraft Runnable field was not found.");
    }

    private static void sizeCanvases(Component component) {
        if (component instanceof Canvas)
            component.setBounds(0, 0, WIDTH, HEIGHT);
        if (component instanceof Container) {
            for (Component child : ((Container)component).getComponents())
                sizeCanvases(child);
        }
    }

    private static final class LocalStub implements AppletStub {
        private final Applet applet;
        private final String login;
        private final String password;

        LocalStub(Applet applet, String login, String password) {
            this.applet = applet;
            this.login = login;
            this.password = password;
        }

        public boolean isActive() {
            return true;
        }

        public URL getDocumentBase() {
            try {
                return new URL("http://mcgl.ru/");
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }

        public URL getCodeBase() {
            return null;
        }

        public String getParameter(String name) {
            if ("login".equals(name)) return login;
            if ("password".equals(name)) return password;
            if ("key".equals(name)) return "";
            return null;
        }

        public AppletContext getAppletContext() {
            return null;
        }

        public void appletResize(int width, int height) {
            applet.setSize(width, height);
        }
    }
}
