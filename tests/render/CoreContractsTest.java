package local.mcgl.render;

import java.nio.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** CPU data and actual Core facade guards with an injected backend; must load no native library. */
public final class CoreContractsTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        data(); lifecycle();
        System.out.println("CORE_CONTRACTS_PASS checks=" + checks + " layouts/indices/snapshots/core-profiles/resource-guards/no-native");
    }
    private static void data() {
        VertexLayout.Attribute position = new VertexLayout.Attribute(0, 3, VertexLayout.Storage.FLOAT32, false, 0);
        rejects(() -> new VertexLayout(0, position));
        rejects(() -> new VertexLayout(8, position));
        rejects(() -> new VertexLayout(16, position, position));
        rejects(() -> new VertexLayout(13, position));
        rejects(() -> new VertexLayout.Attribute(16, 3, VertexLayout.Storage.FLOAT32, false, 0));
        rejects(() -> new VertexLayout.Attribute(0, 5, VertexLayout.Storage.FLOAT32, false, 0));
        rejects(() -> new VertexLayout.Attribute(0, 3, VertexLayout.Storage.FLOAT32, true, 0));
        rejects(() -> new VertexLayout.Attribute(0, 3, VertexLayout.Storage.FLOAT32, false, 1));
        rejects(() -> new VertexLayout(16, position, new VertexLayout.Attribute(1, 4, VertexLayout.Storage.UINT8, true, 8)));
        VertexLayout.Attribute[] original = {position};
        VertexLayout layout = new VertexLayout(12, original); original[0] = null;
        check(layout.attributes().get(0) == position, "layout snapshots caller array");
        try { layout.attributes().clear(); throw new AssertionError("mutable layout"); }
        catch (UnsupportedOperationException expected) { checks++; }
        VertexLayout[] formats = {VertexFormats.POSITION_COLOR, VertexFormats.TEXTURED, VertexFormats.LIGHTMAPPED,
                VertexFormats.LIT_TEXTURED, VertexFormats.LIT_LIGHTMAPPED};
        int[] strides = {16, 24, 28, 28, 32};
        for (int i = 0; i < formats.length; i++) {
            check(formats[i].stride() == strides[i], "packed format stride");
            formats[i].requireAttributes(ShaderLibrary.Material.values()[i].requiredAttributes());
        }
        rejects(() -> VertexFormats.POSITION_COLOR.requireAttributes(ShaderLibrary.Material.LIGHTMAPPED.requiredAttributes()));
        int[] array = {0, 1, 2}; IndexData indices = IndexData.of(array); array[2] = 50;
        check(indices.maximum() == 2 && indices.count() == 3 && indices.bytes().getShort(4) == 2, "indices snapshot caller data");
        check(IndexData.of(65535).type() == IndexData.Type.UINT16, "16-bit inclusive bound");
        check(IndexData.of(65536).type() == IndexData.Type.UINT32, "automatic 32-bit promotion");
        rejects(() -> IndexData.of(-1)); rejects(() -> IndexData.unsignedInt(IntBuffer.wrap(new int[] {-1})));
        ShortBuffer shorts = ShortBuffer.wrap(new short[] {12, -1, 9}); shorts.position(1); shorts.limit(2);
        check(IndexData.unsignedShort(shorts).maximum() == 65535 && shorts.position() == 1 && shorts.limit() == 2,
                "unsigned short view and caller cursor");
        IntBuffer ints = IntBuffer.wrap(new int[] {99, 0, 1, 2, 99}); ints.position(1); ints.limit(4);
        check(IndexData.unsignedInt(ints).type() == IndexData.Type.UINT32 && ints.position() == 1 && ints.limit() == 4,
                "explicit 32-bit indices preserve cursor");
        for (int count = 0; count < 100; count++) {
            IndexData quads = IndexData.quads(count * 4); ByteBuffer b = quads.bytes();
            check(quads.count() == count * 6 && quads.maximum() == (count == 0 ? -1 : count * 4 - 1), "quad bounds");
            for (int q = 0; q < count; q++) {
                int[] expected = {q * 4, q * 4 + 1, q * 4 + 2, q * 4, q * 4 + 2, q * 4 + 3};
                for (int index : expected) check((b.getShort() & 65535) == index, "winding and quad order");
            }
        }
        check(IndexData.quads(65536).type() == IndexData.Type.UINT16, "last 16-bit quad");
        check(IndexData.quads(65540).type() == IndexData.Type.UINT32, "first 32-bit quad");
        rejects(() -> IndexData.quads(3)); rejects(() -> IndexData.quads(-4));
        ByteBuffer vertices = buffer(60); vertices.position(12); vertices.limit(48); vertices.putFloat(12, 3);
        MeshData mesh = new MeshData(layout, vertices, indices); vertices.putFloat(12, 77);
        check(vertices.position() == 12 && vertices.limit() == 48 && mesh.vertexCount() == 3
                && mesh.vertices().getFloat(0) == 3, "vertex snapshot honors remaining range without changing input");
        mesh.vertices().position(12); check(mesh.vertices().position() == 0, "independent returned cursors");
        try { mesh.vertices().put(0, (byte)1); throw new AssertionError("mutable snapshot"); }
        catch (ReadOnlyBufferException expected) { checks++; }
        rejects(() -> new MeshData(layout, vertices, IndexData.of(3)));
        rejects(() -> new MeshData(layout, ByteBuffer.allocate(36), indices));
        rejects(() -> new MeshData(layout, buffer(35), indices));
        rejects(() -> new MeshData(layout, buffer(36).order(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN), indices));
        check(new MeshData(layout, buffer(0), IndexData.of()).vertexCount() == 0, "empty meshes supported");
    }
    private static void lifecycle() throws Exception {
        FakeBackend backend = new FakeBackend();
        RenderDevice device = new RenderDevice((profile, forward) -> backend);
        Object key = new Object();
        RenderContext context = device.attach(key, RenderProfile.CORE_41, true, () -> {});
        check(context.profile() == RenderProfile.CORE_41, "Core profile selected");
        rejects(context::commands);
        rejects(() -> device.attach(key, RenderProfile.COMPATIBILITY_21, false, () -> {}));
        MeshPipeline pipeline = context.meshes();
        MeshData data = new MeshData(VertexFormats.POSITION_COLOR, buffer(64), IndexData.quads(4));
        rejects(() -> pipeline.create("bad label", data, MeshPipeline.Usage.STATIC));
        Mesh mesh = pipeline.create("test/quad", data, MeshPipeline.Usage.DYNAMIC);
        ShaderProgram program = context.shaders().create(new ShaderSources("test/program", "void main(){}", "void main(){}"));
        ShaderUniform uniform = program.uniform("uValue");
        FrameCommands frame = context.frameCommands();
        mesh.draw(Mesh.Primitive.TRIANGLES); check(backend.draws == 1, "guard dispatches typed indexed draw");
        rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES, 0, 4));
        rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES, -1, 3));
        rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES, Integer.MAX_VALUE, 3));
        rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES, 4, 3));
        mesh.draw(Mesh.Primitive.TRIANGLES, 6, 0); check(backend.draws == 1, "empty draw is no-op");
        ByteBuffer update = buffer(96); update.position(16); update.limit(80);
        mesh.updateVertices(0, update); mesh.updateIndices(IndexData.of(0, 2, 3, 0, 1, 2));
        check(backend.updates == 2 && update.position() == 16 && update.limit() == 80, "validated updates preserve cursor");
        rejects(() -> mesh.updateVertices(1, update));
        rejects(() -> mesh.updateVertices(-1, buffer(16)));
        rejects(() -> mesh.updateVertices(0, buffer(17)));
        rejects(() -> mesh.updateIndices(IndexData.of(0, 1, 2)));
        rejects(() -> mesh.updateIndices(IndexData.of(0, 1, 4, 0, 2, 3)));
        rejects(() -> mesh.updateIndices(IndexData.unsignedInt(IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 3}))));
        Mesh immutable = pipeline.create("static", data, MeshPipeline.Usage.STATIC);
        rejects(() -> immutable.updateVertices(0, buffer(16)));
        rejects(() -> immutable.updateIndices(data.indices()));
        foreign(() -> {
            rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES)); rejects(mesh::close);
            rejects(() -> mesh.updateVertices(0, buffer(16))); rejects(() -> mesh.updateIndices(data.indices()));
            rejects(() -> pipeline.create("foreign", data, MeshPipeline.Usage.STATIC)); rejects(pipeline::unbind);
            rejects(program::bind); rejects(() -> uniform.setFloat(2)); rejects(() -> frame.clear(true, false, false));
        });
        device.detach();
        rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES)); rejects(mesh::close);
        rejects(() -> uniform.setFloat(2)); rejects(program::bind); rejects(context::materials);
        check(device.attach(key, RenderProfile.CORE_41, true, () -> {}) == context, "reattachment retains generation");
        mesh.draw(Mesh.Primitive.TRIANGLES); uniform.setFloat(2); program.bind();
        check(backend.draws == 2 && backend.uniformWrites == 1, "reattached cached handles still work");
        immutable.close(); immutable.close(); rejects(() -> immutable.draw(Mesh.Primitive.TRIANGLES));
        program.close(); rejects(() -> uniform.setFloat(3));
        device.destroy(key);
        check(mesh.isClosed(), "destroy invalidates cached mesh"); mesh.close();
        rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES)); rejects(pipeline::unbind);
        rejects(() -> frame.clear(true, true, false));
        check(backend.draws == 2 && backend.updates == 2 && backend.uniformWrites == 1, "rejections never dispatch to backend");
    }
    private static final class FakeBackend implements RenderBackend {
        int draws, updates, uniformWrites;
        public void attach() {} public void detach() {} public void close() {}
        public boolean insideBeginEnd() { return false; }
        public LegacyRenderCommands commands() { return null; }
        public RenderCapabilities capabilities() {
            return new RenderCapabilities("test", "test", "4.1", Collections.singleton("OpenGL41"), RenderProfile.CORE_41, 4, 1, "4.10");
        }
        public FrameCommands frameCommands() { return new FrameCommands() {
            public void viewport(int x, int y, int w, int h) {} public void clearColor(float r, float g, float b, float a) {}
            public void clear(boolean color, boolean depth, boolean stencil) {}
        }; }
        public ShaderPipeline shaders() { return new ShaderPipeline() {
            public void unbind() {}
            public ShaderProgram create(ShaderSources source) {
                boolean[] closed = {false};
                ShaderUniform uniform = (ShaderUniform)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ShaderUniform.class},
                        (p, m, a) -> { if (m.getName().equals("name")) return "uValue";
                            if (m.getName().equals("type")) return ShaderUniform.Type.FLOAT; uniformWrites++; return null; });
                return (ShaderProgram)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ShaderProgram.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "label": return "test/program"; case "isClosed": return closed[0];
                        case "findUniform": return uniform; case "uniformNames": return Collections.singleton("uValue");
                        case "close": closed[0] = true; return null; case "bind": return null;
                        default: throw new AssertionError(m.getName());
                    }
                });
            }
        }; }
        public MeshPipeline meshes() { return new MeshPipeline() {
            public void unbind() {}
            public Mesh create(String label, MeshData data, Usage usage) { return new Mesh() {
                boolean closed;
                public String label() { return label; } public boolean isClosed() { return closed; }
                public VertexLayout layout() { return data.layout(); } public int vertexCount() { return data.vertexCount(); }
                public int indexCount() { return data.indices().count(); } public IndexData.Type indexType() { return data.indices().type(); }
                public Usage usage() { return usage; }
                public void draw(Primitive p, int first, int count) { draws++; }
                public void updateVertices(int first, ByteBuffer b) { updates++; }
                public void updateIndices(IndexData b) { updates++; } public void close() { closed = true; }
            }; }
        }; }
    }
    private static ByteBuffer buffer(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()); }
    private static void foreign(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread t = new Thread(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } });
        t.start(); t.join(2500); check(!t.isAlive() && failure.get() == null, "foreign checks: " + failure.get());
    }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid operation accepted"); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
