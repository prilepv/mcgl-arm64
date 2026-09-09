package local.mcgl.render.backend;

import java.util.Arrays;
import java.util.HashSet;
import local.mcgl.render.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GLCapabilities;

/** Real Core context/binding lifecycle. Legacy game drawing is intentionally not emulated. */
public final class Core41Backend implements RenderBackend {
    private final FrameCommands frames = new NativeFrameCommands();
    private final NativeShaderPipeline shaders = new NativeShaderPipeline();
    private final NativeMeshPipeline meshes = new NativeMeshPipeline();
    private GLCapabilities bindings;
    private RenderCapabilities capabilities;
    private boolean closed;

    public void attach() {
        if (closed) throw new IllegalStateException("Core 4.1 backend is closed");
        if (bindings != null) { GL.setCapabilities(bindings); return; }
        GLCapabilities candidate = GL.createCapabilities(true);
        // Check before querying profile/flags: those enums are invalid on a 2.1 fallback.
        if (!candidate.OpenGL41)
            throw new IllegalStateException("OpenGL 4.1 Core is required; no compatibility fallback is permitted");
        int major = GL11C.glGetInteger(GL30C.GL_MAJOR_VERSION);
        int minor = GL11C.glGetInteger(GL30C.GL_MINOR_VERSION);
        int mask = GL11C.glGetInteger(GL32C.GL_CONTEXT_PROFILE_MASK);
        int flags = GL11C.glGetInteger(GL30C.GL_CONTEXT_FLAGS);
        if (major < 4 || (major == 4 && minor < 1)
                || (mask & GL32C.GL_CONTEXT_CORE_PROFILE_BIT) == 0
                || (mask & GL32C.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0
                || (flags & GL30C.GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) == 0)
            throw new IllegalStateException("Driver did not provide the requested forward-compatible Core 4.1 context");
        RenderCapabilities snapshot = new RenderCapabilities(GL11C.glGetString(GL11C.GL_VENDOR),
                GL11C.glGetString(GL11C.GL_RENDERER), GL11C.glGetString(GL11C.GL_VERSION),
                new HashSet<String>(Arrays.asList("OpenGL41", "core-profile", "forward-compatible")),
                RenderProfile.CORE_41, major, minor, GL11C.glGetString(GL20C.GL_SHADING_LANGUAGE_VERSION));
        capabilities = snapshot;
        bindings = candidate;
        System.out.println("[MCGL Renderer] backend=core41/lwjgl3 GL=" + snapshot.version
                + " GLSL=" + snapshot.shadingLanguageVersion + " fixed-function=false");
    }
    public void detach() { GL.setCapabilities(null); }
    public RenderCapabilities capabilities() { return capabilities; }
    public FrameCommands frameCommands() { return frames; }
    public ShaderPipeline shaders() { return shaders; }
    public MeshPipeline meshes() { return meshes; }
    public LegacyRenderCommands commands() { return null; }
    public boolean insideBeginEnd() { return false; }
    public void close() { closed = true; meshes.abandon(); shaders.abandon(); bindings = null; }
}
