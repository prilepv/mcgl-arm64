package local.mcgl.render.backend;

import java.util.HashSet;
import java.util.Set;
import local.mcgl.render.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/** LWJGL 3 binding implementation for the unchanged compatibility context. */
public final class CompatibilityBackend implements RenderBackend {
    private final boolean forwardCompatible;
    private final CompatibilityState state = new CompatibilityState();
    private final LegacyRenderCommands commands = new CompatibilityCommands(state);
    private final FrameCommands frames = new NativeFrameCommands();
    private GLCapabilities bindings;
    private RenderCapabilities capabilities;
    private boolean closed;

    public CompatibilityBackend(boolean forwardCompatible) { this.forwardCompatible = forwardCompatible; }
    public static boolean loadBindings() { return GL.getFunctionProvider() != null; }
    @Override public void attach() {
        if (closed) throw new IllegalStateException("Compatibility backend is closed");
        if (bindings != null) { GL.setCapabilities(bindings); return; }
        bindings = GL.createCapabilities(forwardCompatible);
        Set<String> features = new HashSet<String>();
        feature(features, "OpenGL13", bindings.OpenGL13);
        feature(features, "OpenGL15", bindings.OpenGL15);
        feature(features, "GL_ARB_fragment_shader", bindings.GL_ARB_fragment_shader);
        feature(features, "GL_ARB_framebuffer_object", bindings.GL_ARB_framebuffer_object);
        feature(features, "GL_ARB_multitexture", bindings.GL_ARB_multitexture);
        feature(features, "GL_ARB_occlusion_query", bindings.GL_ARB_occlusion_query);
        feature(features, "GL_ARB_shader_objects", bindings.GL_ARB_shader_objects);
        feature(features, "GL_ARB_vertex_buffer_object", bindings.GL_ARB_vertex_buffer_object);
        feature(features, "GL_ARB_vertex_shader", bindings.GL_ARB_vertex_shader);
        feature(features, "GL_EXT_framebuffer_object", bindings.GL_EXT_framebuffer_object);
        feature(features, "GL_NV_fog_distance", bindings.GL_NV_fog_distance);
        feature(features, "GL_APPLE_vertex_array_object", bindings.GL_APPLE_vertex_array_object);
        capabilities = new RenderCapabilities(GL11.glGetString(GL11.GL_VENDOR),
                GL11.glGetString(GL11.GL_RENDERER), GL11.glGetString(GL11.GL_VERSION), features);
        System.out.println("[MCGL Renderer] backend=compatibility/lwjgl3 commands=immediate context-local-state=true");
    }
    private static void feature(Set<String> features, String name, boolean supported) { if (supported) features.add(name); }
    @Override public void detach() { GL.setCapabilities(null); }
    @Override public RenderCapabilities capabilities() { return capabilities; }
    @Override public LegacyRenderCommands commands() { return commands; }
    @Override public FrameCommands frameCommands() { return frames; }
    @Override public boolean insideBeginEnd() { return state.begin; }
    @Override public void close() { closed = true; state.clear(); bindings = null; }
}
