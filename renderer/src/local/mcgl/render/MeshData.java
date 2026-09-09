package local.mcgl.render;

import java.nio.*;

/** Immutable CPU mesh; safe to prepare before attaching a context. Upload does not retain this data. */
public final class MeshData {
    private final VertexLayout layout;
    private final ByteBuffer vertices;
    private final IndexData indices;
    private final int vertexCount;
    public MeshData(VertexLayout layout, ByteBuffer vertices, IndexData indices) {
        this.vertexCount = validateVertices(layout, vertices);
        if (indices == null) throw new NullPointerException("mesh indices");
        if (indices.maximum() >= vertexCount) throw new IllegalArgumentException("Index exceeds vertex count");
        ByteBuffer copy = ByteBuffer.allocateDirect(vertices.remaining()).order(ByteOrder.nativeOrder());
        copy.put(vertices.duplicate()); copy.flip();
        this.vertices = copy.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        this.layout = layout; this.indices = indices;
    }
    public static int validateVertices(VertexLayout layout, ByteBuffer data) {
        if (layout == null || data == null) throw new NullPointerException("vertex layout/data");
        if (!data.isDirect() || data.order() != ByteOrder.nativeOrder() || data.remaining() % layout.stride() != 0)
            throw new IllegalArgumentException("Vertices require complete strides in a native-order direct buffer");
        return data.remaining() / layout.stride();
    }
    public VertexLayout layout() { return layout; }
    public int vertexCount() { return vertexCount; }
    public ByteBuffer vertices() { return vertices.asReadOnlyBuffer().order(ByteOrder.nativeOrder()); }
    public IndexData indices() { return indices; }
}
