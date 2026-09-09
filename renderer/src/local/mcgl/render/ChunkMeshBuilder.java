package local.mcgl.render;

import java.nio.*;
import java.util.*;

/** Single-use, CPU-only snapshot of the ORIGINAL MCGL accumulator, including its capacity flushes. */
public final class ChunkMeshBuilder {
    // Native-order original 8-word vertex: xyz, uv, packed RGBA, packed normal, packed lightmap.
    public static final VertexLayout LAYOUT = new VertexLayout(32,
            new VertexLayout.Attribute(0, 3, VertexLayout.Storage.FLOAT32, false, 0),
            new VertexLayout.Attribute(1, 4, VertexLayout.Storage.UINT8, true, 20),
            new VertexLayout.Attribute(2, 2, VertexLayout.Storage.FLOAT32, false, 12),
            new VertexLayout.Attribute(3, 2, VertexLayout.Storage.INT16, false, 28),
            new VertexLayout.Attribute(4, 3, VertexLayout.Storage.INT8, true, 24));
    private final Thread owner = Thread.currentThread();
    private final int x, y, z, maximumVertices;
    private final List<ChunkMeshData.Part> parts = new ArrayList<ChunkMeshData.Part>();
    private int vertexCount;
    private boolean finished;

    /** Origins are block coordinates, not chunk indices; a budget bounds each rebuild's CPU allocation. */
    public ChunkMeshBuilder(int x, int y, int z, int maximumVertices) {
        if (x % 16 != 0 || y % 16 != 0 || z % 16 != 0 || maximumVertices < 0 || maximumVertices > 4 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid chunk origin or vertex budget");
        this.x = x; this.y = y; this.z = z; this.maximumVertices = maximumVertices;
    }
    /** Disabled vertex arrays use explicit caller-supplied current attributes, never stale raw words. */
    public Sink sink(ChunkMaterial material, int rgba, float u, float v, int normal, int lightmap) {
        return sink(material, rgba, u, v, normal, lightmap, GameMatrices.identity());
    }
    /** Bounds/sort transform only: vertex bytes and shader object-space inputs remain unchanged. */
    public Sink sink(ChunkMaterial material, int rgba, float u, float v, int normal, int lightmap, double[] localFromVertex) {
        check();
        if (material == null) throw new NullPointerException("material");
        if (!Float.isFinite(u) || !Float.isFinite(v)) throw new IllegalArgumentException("Non-finite default UV");
        if (localFromVertex == null || localFromVertex.length != 16 || localFromVertex[3] != 0
                || localFromVertex[7] != 0 || localFromVertex[11] != 0 || localFromVertex[15] != 1)
            throw new IllegalArgumentException("Chunk bounds require an affine transform");
        for (double value : localFromVertex) if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite chunk bounds transform");
        return new Sink(material, rgba, u, v, normal, lightmap, localFromVertex.clone());
    }
    public ChunkMeshData finish() {
        check();
        ChunkMeshData result = new ChunkMeshData(x, y, z, parts, vertexCount);
        finished = true; parts.clear();
        return result;
    }
    public void abort() { check(); parts.clear(); finished = true; }
    private void check() {
        if (Thread.currentThread() != owner || finished) throw new IllegalStateException("Chunk build is finished or belongs to another thread");
    }

    public final class Sink {
        private final ChunkMaterial material;
        private final int rgba, normal, lightmap;
        private final float u, v;
        private final double[] localFromVertex;
        private Sink(ChunkMaterial material, int rgba, float u, float v, int normal, int lightmap, double[] localFromVertex) {
            this.material = material; this.rgba = rgba; this.u = u; this.v = v;
            this.normal = normal; this.lightmap = lightmap;
            this.localFromVertex = localFromVertex;
        }
        /** Adapter ABI: original quads, triangles, triangle strips and fans. No GL enum escapes to a backend. */
        public int append(int[] raw, int words, int vertices, int mode, boolean convertedQuads,
                          boolean begun, boolean color, boolean texture, boolean lighting, boolean normals) {
            check();
            if (!begun) throw new IllegalStateException("Accumulator has not begun");
            if (raw == null) throw new NullPointerException("accumulator words");
            if (vertices < 0 || words < 0 || words > raw.length || words != (long)vertices * 8)
                throw new IllegalArgumentException("Invalid or oversized chunk batch");
            // The historical convert-quads path duplicates vertices with missing normal words.
            // Refuse it explicitly instead of quietly changing lighting; production defaults it off.
            if (convertedQuads || mode < 4 || mode > 7)
                throw new IllegalArgumentException("Unsupported chunk topology: mode=" + mode + ", converted=" + convertedQuads);
            int width = mode == 7 ? 4 : 3;
            boolean shared = mode == 5 || mode == 6;
            if (!shared && vertices % width != 0) throw new IllegalArgumentException("Incomplete chunk face");
            // The original slope-block emitter flushes a quad batch, emits a TRIANGLE_FAN,
            // then resumes quads. Expand shared vertices into independent sortable faces;
            // every copied vertex retains all eight original words and the input is untouched.
            int captured = shared ? Math.multiplyExact(Math.max(0, vertices - 2), 3) : vertices;
            if (captured > maximumVertices - vertexCount) throw new IllegalArgumentException("Oversized expanded chunk batch");
            int bytes = Math.multiplyExact(words, 4);
            if (captured == 0) return bytes;
            ByteBuffer data = ByteBuffer.allocateDirect(Math.multiplyExact(captured, 32)).order(ByteOrder.nativeOrder());
            double[] centers = material.translucent() ? new double[captured / width * 3] : new double[0];
            double[] bounds = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int i = 0; i < captured; i++) {
                int source = i;
                if (shared) {
                    int face = i / 3, corner = i % 3;
                    source = corner == 2 ? face + 2 : mode == 6 ? (corner == 0 ? 0 : face + 1)
                            : face + (corner == 0 ? face % 2 : 1 - face % 2);
                }
                int offset = source * 8;
                for (int axis = 0; axis < 3; axis++) {
                    float position = Float.intBitsToFloat(raw[offset + axis]);
                    if (!Float.isFinite(position)) throw new IllegalArgumentException("Non-finite chunk position");
                    data.putFloat(position);
                }
                for (int axis = 0; axis < 3; axis++) {
                    double position = localFromVertex[12 + axis];
                    for (int k = 0; k < 3; k++) position += localFromVertex[k * 4 + axis] * Float.intBitsToFloat(raw[offset + k]);
                    if (!Double.isFinite(position)) throw new IllegalArgumentException("Non-finite transformed chunk position");
                    bounds[axis] = Math.min(bounds[axis], position); bounds[axis + 3] = Math.max(bounds[axis + 3], position);
                    if (material.translucent()) centers[i / width * 3 + axis] += position / width;
                }
                float tu = texture ? Float.intBitsToFloat(raw[offset + 3]) : u;
                float tv = texture ? Float.intBitsToFloat(raw[offset + 4]) : v;
                if (!Float.isFinite(tu) || !Float.isFinite(tv)) throw new IllegalArgumentException("Non-finite chunk UV");
                data.putFloat(tu).putFloat(tv).putInt(color ? raw[offset + 5] : rgba)
                        .putInt(normals ? raw[offset + 6] : normal).putInt(lighting ? raw[offset + 7] : lightmap);
            }
            data.flip();
            IndexData indices;
            if (width == 4) indices = IndexData.quads(captured);
            else { int[] sequence = new int[captured]; for (int i = 0; i < captured; i++) sequence[i] = i; indices = IndexData.of(sequence); }
            MeshData mesh = new MeshData(LAYOUT, data, indices);
            // Publish only after the entire input has been validated and snapshotted.
            parts.add(new ChunkMeshData.Part(material, mesh, width, centers, bounds));
            vertexCount += captured;
            return bytes;
        }
    }
}
