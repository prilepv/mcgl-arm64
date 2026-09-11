package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import local.mcgl.render.GameGeometry;
import local.mcgl.render.IndexData;
import local.mcgl.render.Mesh;
import local.mcgl.render.MeshData;
import local.mcgl.render.VertexLayout;

/** Compile-time concatenation of an adjacent, identical-state model run.
 * Vertices and already-triangulated indices keep their original order and bits.
 * No world geometry, per-frame repacking, GPU readback or retained CPU shadow. */
final class GameModelBatch {
    static final int MAX_PARTS = 256, MAX_BYTES = 1024 * 1024;
    private final List<MeshData> parts = new ArrayList<MeshData>();
    private VertexLayout layout;
    private Mesh.Primitive primitive;
    private int mask, vertices, indices;

    static boolean eligible(GameGeometry geometry) {
        return geometry.mesh.indices().count() > 0 && size(geometry.mesh) <= MAX_BYTES;
    }
    private static long size(MeshData data) {
        // Reserve the worst-case 32-bit rebased indices, not just each input's type.
        return (long)data.vertexCount() * data.layout().stride() + (long)data.indices().count() * 4;
    }
    boolean append(GameGeometry geometry) {
        MeshData data = geometry.mesh;
        if (!eligible(geometry) || parts.size() >= MAX_PARTS) return false;
        if (!parts.isEmpty() && (primitive != geometry.primitive || mask != geometry.attributeMask
                || !layout.equals(data.layout())
                || (long)vertices * layout.stride() + (long)indices * 4 + size(data) > MAX_BYTES)) return false;
        if (parts.isEmpty()) { layout = data.layout(); primitive = geometry.primitive; mask = geometry.attributeMask; }
        parts.add(data); vertices += data.vertexCount(); indices += data.indices().count();
        return true;
    }
    Mesh.Primitive primitive() { return primitive; }
    int mask() { return mask; }
    MeshData meshData() {
        if (parts.isEmpty()) throw new IllegalStateException("Empty model geometry run");
        if (parts.size() == 1) return parts.get(0);
        ByteBuffer joined = ByteBuffer.allocateDirect(Math.multiplyExact(vertices, layout.stride())).order(ByteOrder.nativeOrder());
        int[] rebased = new int[indices];
        int base = 0, at = 0;
        for (MeshData part : parts) {
            joined.put(part.vertices());
            IndexData indexData = part.indices();
            ByteBuffer source = indexData.bytes();
            while (source.hasRemaining()) {
                int index = indexData.type() == IndexData.Type.UINT16 ? source.getShort() & 65535 : source.getInt();
                rebased[at++] = base + index;
            }
            base += part.vertexCount();
        }
        joined.flip();
        return new MeshData(layout, joined, IndexData.of(rebased));
    }
}
