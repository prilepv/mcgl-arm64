package local.mcgl.render;

import java.util.List;

/** Render-owner factory. All VAO/VBO/index names remain private to the backend. */
public interface MeshPipeline {
    enum Usage { STATIC, DYNAMIC, STREAM }
    Mesh create(String label, MeshData data, Usage usage);
    /** Independent immutable views into bounded shared GPU pages. */
    default MeshArena createArena(String label,int vertexCapacity,int indexCapacity,long byteBudget) {
        throw new UnsupportedOperationException("This mesh backend does not support shared arenas");
    }
    /** Snapshot source vertex streams into one independently owned GPU mesh, in list order.
     * Indices address that concatenation; source buffers/names never escape their context. */
    default Mesh combine(String label,List<Mesh> sources,IndexData indices) {
        throw new UnsupportedOperationException("This mesh backend does not support GPU combination");
    }
    void unbind();
}
