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
    default MeshArena createArena(String label,int vertexCapacity,int indexCapacity,long byteBudget,MeshArena.Tags tags) {
        if(tags==null)throw new NullPointerException("arena tags");
        if(tags.count==MeshArena.TAG_COUNT&&tags.packedLayout==null)return createArena(label,vertexCapacity,indexCapacity,byteBudget);
        throw new UnsupportedOperationException("This mesh backend does not support configurable tags");
    }
    /** Snapshot source vertex streams into one independently owned GPU mesh, in list order.
     * Indices address that concatenation; source buffers/names never escape their context. */
    default Mesh combine(String label,List<Mesh> sources,IndexData indices) {
        throw new UnsupportedOperationException("This mesh backend does not support GPU combination");
    }
    void unbind();
}
