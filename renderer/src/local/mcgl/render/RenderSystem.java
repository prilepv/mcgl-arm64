package local.mcgl.render;

import local.mcgl.render.backend.CompatibilityBackend;
import local.mcgl.render.backend.Core41Backend;

/** Production composition root. Core contexts are explicitly requested, never a legacy fallback. */
public final class RenderSystem {
    private static final RenderDevice DEVICE = new RenderDevice((profile, forward) ->
            profile == RenderProfile.CORE_41 ? new Core41Backend() : new CompatibilityBackend(forward));
    private RenderSystem() {}
    public static RenderContext attach(Object key, boolean forwardCompatible, Runnable displayListRecorder) {
        return DEVICE.attach(key, forwardCompatible, displayListRecorder);
    }
    public static RenderContext attach(Object key, RenderProfile profile, boolean forwardCompatible,
                                        Runnable displayListRecorder) {
        return DEVICE.attach(key, profile, forwardCompatible, displayListRecorder);
    }
    public static void detach() { DEVICE.detach(); }
    public static void destroy(Object key) { DEVICE.destroy(key); }
    public static RenderContext current() { return DEVICE.current(); }
    public static RenderContext find(Object key) { return DEVICE.find(key); }
    public static LegacyRenderCommands commands() { return DEVICE.current().commands(); }
    public static FrameCommands frameCommands() { return DEVICE.current().frameCommands(); }
    public static ShaderPipeline shaders() { return DEVICE.current().shaders(); }
    public static ShaderLibrary materials() { return DEVICE.current().materials(); }
    public static MeshPipeline meshes() { return DEVICE.current().meshes(); }
    public static GameRenderCommands game() { return DEVICE.current().game(); }
    static GameRenderCommands createGameCommands(RenderContext context) { return new local.mcgl.render.backend.GameCommands(context); }
    public static void completeFrame(int width, int height, boolean presented) { DEVICE.completeFrame(width, height, presented); }
    public static boolean insideBeginEnd() { return DEVICE.insideBeginEnd(); }
    public static void recordDisplayList() { DEVICE.recordDisplayList(); }
    public static boolean loadBindings() { return CompatibilityBackend.loadBindings(); }
}
