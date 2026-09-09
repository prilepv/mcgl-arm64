package org.lwjgl.opengl;

import java.util.Map;
import java.util.WeakHashMap;
import local.mcgl.render.RenderContext;
import local.mcgl.render.RenderProfile;
import local.mcgl.render.RenderSystem;
import org.lwjgl.LWJGLException;

/** Historical capabilities ABI; binding/state lifetime is owned by the renderer. */
public final class GLContext {
    private static final Map<Object, ContextCapabilities> contexts = new WeakHashMap<Object, ContextCapabilities>();
    private static final ThreadLocal<ContextCapabilities> current = new ThreadLocal<ContextCapabilities>();
    private GLContext() {}

    public static ContextCapabilities getCapabilities() {
        ContextCapabilities caps = current.get();
        if (caps == null) throw new IllegalStateException("No OpenGL context is current");
        return caps;
    }

    static synchronized ContextCapabilities getCapabilities(Object context) { return contexts.get(context); }

    public static void useContext(Object context) throws LWJGLException { useContext(context, false); }

    public static synchronized void useContext(Object context, boolean forwardCompatible) throws LWJGLException {
        RenderContext existing = context == null ? null : RenderSystem.find(context);
        RenderProfile profile = existing == null ? RenderProfile.COMPATIBILITY_21 : existing.profile();
        useContext(context, profile, forwardCompatible || profile == RenderProfile.CORE_41);
    }

    static synchronized void useContext(Object context, RenderProfile profile, boolean forwardCompatible) throws LWJGLException {
        if (context == null) {
            try { RenderSystem.detach(); } finally { current.remove(); }
            return;
        }
        RenderContext renderer;
        try { renderer = RenderSystem.attach(context, profile, forwardCompatible, Display::mcglRecordDisplayList); }
        catch (RuntimeException failure) { throw new LWJGLException("Could not attach the renderer", failure); }
        ContextCapabilities caps = contexts.get(context);
        if (caps == null) {
            caps = new ContextCapabilities(renderer.capabilities());
            contexts.put(context, caps);
            System.out.println("[MCGL LWJGL3] bindings=" + org.lwjgl.Version.getVersion()
                    + " GL=" + renderer.capabilities().version
                    + " window=" + System.getProperty("mcgl.window.backend", "Cocoa-legacy"));
        }
        current.set(caps);
    }

    public static void loadOpenGLLibrary() throws LWJGLException {
        // GL's initializer loads the system framework once; context creation is separate.
        if (!RenderSystem.loadBindings()) throw new LWJGLException("OpenGL framework unavailable");
    }

    static synchronized void destroyContext(Object context) {
        ContextCapabilities removed = contexts.get(context);
        try { RenderSystem.destroy(context); }
        finally {
            contexts.remove(context);
            if (current.get() == removed) current.remove();
        }
    }

    public static void unloadOpenGLLibrary() {
        // Keep the binding library alive for the classloader lifetime, like LWJGL 3.
        // Native context/window destruction remains the responsibility of the platform.
    }
}
