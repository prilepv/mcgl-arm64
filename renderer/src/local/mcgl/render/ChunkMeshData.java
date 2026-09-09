package local.mcgl.render;

import java.util.*;

/** Immutable CPU handoff. No context, GL name, original accumulator, or world object is retained. */
public final class ChunkMeshData {
    public final int x, y, z, vertexCount;
    private final List<Part> parts;
    ChunkMeshData(int x, int y, int z, List<Part> parts, int vertexCount) {
        this.x = x; this.y = y; this.z = z; this.vertexCount = vertexCount;
        this.parts = Collections.unmodifiableList(new ArrayList<Part>(parts));
    }
    public List<Part> parts() { return parts; }
    public static final class Part {
        public final ChunkMaterial material;
        public final MeshData mesh;
        public final int faceWidth;
        private final double[] centers, bounds;
        Part(ChunkMaterial material, MeshData mesh, int faceWidth, double[] centers, double[] bounds) {
            this.material = material; this.mesh = mesh; this.faceWidth = faceWidth;
            this.centers = centers.clone(); this.bounds = bounds.clone();
        }
        double[] centers() { return centers.clone(); }
        double[] bounds() { return bounds.clone(); }
    }
}
