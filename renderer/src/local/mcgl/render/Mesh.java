package local.mcgl.render;

import java.nio.ByteBuffer;

public interface Mesh extends AutoCloseable {
    enum Primitive {
        POINTS(1), LINES(2), TRIANGLES(3);
        public final int indicesPerPrimitive;
        Primitive(int count) { indicesPerPrimitive = count; }
    }
    String label();
    boolean isClosed();
    VertexLayout layout();
    int vertexCount();
    int indexCount();
    IndexData.Type indexType();
    MeshPipeline.Usage usage();
    /** Binds this VAO and leaves it bound; caller supplies program, textures and render state. */
    void draw(Primitive primitive, int firstIndex, int indexCount);
    default void draw(Primitive primitive) { draw(primitive, 0, indexCount()); }
    /** Complete vertex records, fixed allocation, non-static meshes only; input cursor unchanged. */
    void updateVertices(int firstVertex, ByteBuffer vertices);
    /** Same count and index type; validates all indices before upload. Useful for sorted face order. */
    void updateIndices(IndexData indices);
    @Override void close();
}
