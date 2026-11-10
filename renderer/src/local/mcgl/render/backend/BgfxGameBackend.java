package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import local.mcgl.render.*;

/** CPU-side inputs for the bgfx renderer. The presenter owns all GPU resources. */
public final class BgfxGameBackend implements RenderBackend {
    private final BgfxGameState state = new BgfxGameState();
    private final RenderCapabilities capabilities = new RenderCapabilities(
            "Apple", "bgfx Metal", "Metal (MCGL game bridge)",
            Collections.<String>emptySet(), RenderProfile.CORE_41, 4, 1, "");
    private final FrameCommands frames = new FrameCommands() {
        public void viewport(int x, int y, int width, int height) { BgfxGameState.glViewport(x, y, width, height); }
        public void clearColor(float r, float g, float b, float a) { BgfxGameState.glClearColor(r, g, b, a); }
        public void clear(boolean color, boolean depth, boolean stencil) {
            BgfxGameState.glClear((color ? 0x4000 : 0) | (depth ? 0x100 : 0) | (stencil ? 0x400 : 0));
        }
    };
    private final ShaderPipeline shaders = new ShaderPipeline() {
        public ShaderProgram create(ShaderSources sources) { return new GameProgram(sources.label); }
        public void unbind() {}
    };
    private final MeshPipeline meshes = new MeshPipeline() {
        public Mesh create(String label, MeshData data, Usage usage) { return new GameMesh(label, data, usage); }
        public void unbind() {}
    };

    public void attach() { state.attach(); }
    public void detach() { BgfxGameState.detach(); }
    public void close() { state.close(); }
    public RenderCapabilities capabilities() { return capabilities; }
    public FrameCommands frameCommands() { return frames; }
    public ShaderPipeline shaders() { return shaders; }
    public MeshPipeline meshes() { return meshes; }
    public LegacyRenderCommands commands() { return null; }
    public boolean insideBeginEnd() { return false; }

    // Legacy GLSL effects are represented as logical handles. bgfx binds its own material programs.
    private static final class GameProgram implements ShaderProgram {
        private final String label;
        private boolean closed;
        GameProgram(String label) { this.label = label; }
        public String label() { return label; }
        public boolean isClosed() { return closed; }
        public Set<String> uniformNames() { return Collections.emptySet(); }
        public ShaderUniform findUniform(String name) { return null; }
        public void bind() {}
        public void close() { closed = true; }
    }

    private static final class GameMesh implements Mesh {
        private final String label;
        private final MeshPipeline.Usage usage;
        private MeshData data;
        GameMesh(String label, MeshData data, MeshPipeline.Usage usage) {
            this.label = label;
            this.data = data;
            this.usage = usage;
        }
        public String label() { return label; }
        public boolean isClosed() { return data == null; }
        public VertexLayout layout() { return data.layout(); }
        public int vertexCount() { return data.vertexCount(); }
        public int indexCount() { return data.indices().count(); }
        public IndexData.Type indexType() { return data.indices().type(); }
        public MeshPipeline.Usage usage() { return usage; }
        public MeshData snapshot(long maximumBytes) {
            long size = (long) vertexCount() * layout().stride() + (long) indexCount() * indexType().bytes;
            return size <= maximumBytes ? data : null;
        }
        // GameRenderer submits geometry to the bgfx frame producer before reaching this legacy hook.
        public void draw(Primitive primitive, int firstIndex, int count) {}
        public void updateVertices(int firstVertex, ByteBuffer vertices) {
            int vertexCount = MeshData.validateVertices(layout(), vertices);
            if (usage == MeshPipeline.Usage.STATIC || firstVertex < 0 || firstVertex > vertexCount() - vertexCount)
                throw new IllegalArgumentException("Invalid bgfx mesh update");
            ByteBuffer updated = ByteBuffer.allocateDirect(data.vertices().remaining()).order(vertices.order());
            if (firstVertex != 0 || vertexCount != vertexCount()) updated.put(data.vertices()).clear();
            updated.position(firstVertex * layout().stride());
            updated.put(vertices.duplicate()).clear();
            data = new MeshData(layout(), updated, data.indices());
        }
        public void updateIndices(IndexData indices) {
            if (indices.type() != indexType() || indices.count() != indexCount())
                throw new IllegalArgumentException("bgfx index layout changed");
            data = new MeshData(layout(), data.vertices(), indices);
        }
        public void close() { data = null; }
    }
}
