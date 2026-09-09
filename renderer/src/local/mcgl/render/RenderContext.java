package local.mcgl.render;

/** One context generation. A retained command facade cannot outlive or change its owner. */
public final class RenderContext {
    private final RenderBackend backend;
    private final LegacyRenderCommands commands;
    private final FrameCommands frames;
    private final ShaderPipeline shaders;
    private final MeshPipeline meshes;
    private ShaderLibrary materials;
    private GameRenderCommands game;
    private final GameRenderDiagnostics gameDiagnostics=new GameRenderDiagnostics();
    private final RenderProfile profile;
    private final Runnable displayListRecorder;
    private final RenderCapabilities capabilities;
    private final long generation;
    private volatile Thread owner;
    private volatile boolean closed;
    private volatile long completedFrames, presentedFrames;
    private volatile int surfaceWidth, surfaceHeight;

    RenderContext(RenderBackend backend, RenderProfile profile, long generation, Runnable displayListRecorder) {
        this.backend = backend; this.generation = generation;
        this.profile = profile;
        this.displayListRecorder = displayListRecorder;
        capabilities = backend.capabilities();
        LegacyRenderCommands legacy = backend.commands();
        FrameCommands frameCommands = backend.frameCommands();
        ShaderPipeline shaderPipeline = backend.shaders();
        MeshPipeline meshPipeline = backend.meshes();
        if (capabilities == null || (profile == RenderProfile.COMPATIBILITY_21 && legacy == null)
                || (profile == RenderProfile.CORE_41 && (frameCommands == null || shaderPipeline == null || meshPipeline == null)))
            throw new NullPointerException("incomplete render backend");
        if (capabilities.profile != profile) throw new IllegalStateException("Requested and actual render profiles differ");
        if (profile == RenderProfile.CORE_41 && legacy != null)
            throw new IllegalStateException("Fixed-function commands must not be exposed by the Core backend");
        commands = legacy == null ? null : new GuardedRenderCommands(this, legacy);
        frames = frameCommands == null ? null : new GuardedFrameCommands(this, frameCommands);
        shaders = shaderPipeline == null ? null : new GuardedShaderPipeline(this, shaderPipeline);
        meshes = meshPipeline == null ? null : new GuardedMeshPipeline(this, meshPipeline);
        owner = Thread.currentThread();
    }
    public long generation() { return generation; }
    public RenderProfile profile() { return profile; }
    public RenderCapabilities capabilities() { return capabilities; }
    public long completedFrames() { return completedFrames; }
    public long presentedFrames() { return presentedFrames; }
    public int surfaceWidth() { return surfaceWidth; }
    public int surfaceHeight() { return surfaceHeight; }
    public boolean isClosed() { return closed; }
    public LegacyRenderCommands commands() {
        if (commands == null) {
            checkOwner();
            throw new IllegalStateException(
                    "Legacy drawing is unavailable in Core 4.1; the shader and geometry migrations are required");
        }
        return commands;
    }
    public FrameCommands frameCommands() {
        checkOwner();
        if (frames == null) throw new IllegalStateException("This backend does not supply frame commands");
        return frames;
    }
    public ShaderPipeline shaders() {
        checkOwner();
        if (shaders == null) throw new IllegalStateException("The modern shader pipeline requires a Core context");
        return shaders;
    }
    public ShaderLibrary materials() {
        checkOwner();
        if (materials == null || materials.isClosed()) materials = new ShaderLibrary(this);
        return materials;
    }
    public MeshPipeline meshes() {
        checkOwner();
        if (meshes == null) throw new IllegalStateException("Modern geometry requires a Core context");
        return meshes;
    }
    /** Game-specific migration boundary, separate from the backend's forbidden fixed-function contract. */
    public GameRenderCommands game() {
        checkOwner();
        if (profile != RenderProfile.CORE_41) throw new IllegalStateException("Migrated game passes require Core 4.1");
        if (game == null) game = new GuardedGameRenderCommands(this, RenderSystem.createGameCommands(this));
        return game;
    }

    void checkOwner() {
        if (closed) throw new IllegalStateException("Render context is closed");
        if (owner != Thread.currentThread()) throw new IllegalStateException("Render context is not attached to this thread");
    }
    void checkAvailable() {
        if (closed) throw new IllegalStateException("Render context is closed");
        if (owner != null && owner != Thread.currentThread())
            throw new IllegalStateException("Render context belongs to another thread");
    }
    void attach() { checkAvailable(); backend.attach(); owner = Thread.currentThread(); }
    void backendDetachAfterFailure() { try { backend.detach(); } finally { owner = null; } }
    void detach() {
        checkOwner();
        try { backend.detach(); } finally { owner = null; }
    }
    void close() {
        checkAvailable();
        if (owner != null) throw new IllegalStateException("Detach render context before closing it");
        try { backend.close(); } finally { closed = true; if (game != null) { game.abandon(); game = null; } }
    }
    void completeFrame(int width, int height, boolean presented) {
        checkOwner();
        if (width < 0 || height < 0) throw new IllegalArgumentException("Negative render surface dimensions");
        surfaceWidth = width; surfaceHeight = height;
        completedFrames++;
        if (presented) presentedFrames++;
        if (game != null) gameDiagnostics.frame(game);
    }
    boolean insideBeginEnd() { checkOwner(); return game != null && game.insideBeginEnd() || backend.insideBeginEnd(); }
    void recordDisplayList() { checkOwner(); displayListRecorder.run(); }
}
