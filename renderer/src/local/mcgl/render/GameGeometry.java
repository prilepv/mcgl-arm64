package local.mcgl.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** CPU geometry for UI, entities, particles, sky and cached models. Driver receives indexed primitives only. */
public final class GameGeometry {
    public static final VertexLayout FLOAT_LAYOUT = new VertexLayout(60,
            a(0, 3, VertexLayout.Storage.FLOAT32, false, 0), a(1, 4, VertexLayout.Storage.FLOAT32, false, 12),
            a(2, 2, VertexLayout.Storage.FLOAT32, false, 28), a(3, 2, VertexLayout.Storage.FLOAT32, false, 36),
            a(4, 3, VertexLayout.Storage.FLOAT32, false, 44), a(7, 1, VertexLayout.Storage.FLOAT32, false, 56));
    public final MeshData mesh;
    public final Mesh.Primitive primitive;
    public final int attributeMask;
    private GameGeometry(VertexLayout layout, ByteBuffer data, int vertices, int mode, int attributes) {
        int[] indices = indices(mode, vertices);
        boolean lines = mode >= 1 && mode <= 3;
        primitive = mode == 0 ? Mesh.Primitive.POINTS : Mesh.Primitive.TRIANGLES;
        // Quad triangulation keeps the original diagonal. Extra flat inputs carry the fourth vertex's
        // color/normal to BOTH triangles without changing interpolated UVs or smooth AO colors.
        if (mode == 7 || mode == 8 || lines) {
            VertexLayout.Attribute color = null, normal = null;
            for (VertexLayout.Attribute attr : layout.attributes()) {
                if (attr.location == 1) color = attr; if (attr.location == 4) normal = attr;
            }
            int colorBytes = color.components * color.storage.bytes, normalBytes = normal.components * normal.storage.bytes;
            int baseStride = layout.stride() + (lines ? 20 : 0);
            int normalOffset = baseStride + colorBytes;
            int flatMaskOffset = (normalOffset + normalBytes + 3) / 4 * 4;
            boolean vertexMasks = (attributes & 128) != 0;
            int stride = flatMaskOffset + (vertexMasks ? 4 : 0);
            List<VertexLayout.Attribute> fields = new ArrayList<VertexLayout.Attribute>(layout.attributes());
            if (lines) {
                // Core line corners and their flat last-endpoint inputs are expanded together.
                // The shader still applies the original pixel width after projection.
                fields.add(a(9, 3, VertexLayout.Storage.FLOAT32, false, layout.stride()));
                fields.add(a(10, 2, VertexLayout.Storage.FLOAT32, false, layout.stride() + 12));
            }
            fields.add(a(5, color.components, color.storage, color.normalized, baseStride));
            fields.add(a(6, normal.components, normal.storage, normal.normalized, normalOffset));
            if (vertexMasks) fields.add(a(8, 1, VertexLayout.Storage.FLOAT32, false, flatMaskOffset));
            // QUAD_STRIP shares vertices across faces: expand the quad sequence before attaching flat values.
            int count = lines ? Math.multiplyExact(indices.length, 2)
                    : mode == 7 ? vertices : Math.max(0, (vertices / 2 - 1) * 4);
            ByteBuffer expanded = ByteBuffer.allocateDirect(Math.multiplyExact(count, stride)).order(ByteOrder.nativeOrder());
            for (int v = 0; v < count; v++) {
                int face = v / 4, corner = v % 4;
                int end = corner < 2 ? 0 : 1;
                int original = lines ? indices[face * 2 + end]
                        : mode == 7 ? v : face * 2 + (corner == 0 ? 0 : corner == 1 ? 1 : corner == 2 ? 3 : 2);
                int provoking = lines ? indices[face * 2 + 1] : mode == 7 ? face * 4 + 3 : face * 2 + 3;
                copy(data, original * layout.stride(), expanded, layout.stride());
                if (lines) {
                    copy(data, indices[face * 2 + 1 - end] * layout.stride(), expanded, 12);
                    expanded.putFloat(corner == 0 || corner == 3 ? 1 : -1).putFloat(end == 0 ? 1 : -1);
                }
                copy(data, provoking * layout.stride() + color.offset, expanded, colorBytes);
                copy(data, provoking * layout.stride() + normal.offset, expanded, normalBytes);
                while (expanded.position() % stride < flatMaskOffset && expanded.position() % stride != 0) expanded.put((byte)0);
                if (vertexMasks) copy(data, provoking * layout.stride() + 56, expanded, 4);
                while (expanded.position() % stride != 0) expanded.put((byte)0);
            }
            expanded.flip(); data = expanded; layout = new VertexLayout(stride, fields.toArray(new VertexLayout.Attribute[0]));
            indices = indices(7, count); attributes |= 32 | 64 | (vertexMasks ? 256 : 0) | (lines ? 512 : 0);
        }
        attributeMask = attributes; mesh = new MeshData(layout, data, IndexData.of(indices));
    }
    private static void copy(ByteBuffer source, int offset, ByteBuffer target, int count) {
        // Both public producers require native-order buffers. Word copies preserve every
        // byte (including packed normals/colors) without allocating per-vertex views.
        int at = source.position() + offset, end = at + count;
        for (; at <= end - 8; at += 8) target.putLong(source.getLong(at));
        if (at <= end - 4) { target.putInt(source.getInt(at)); at += 4; }
        for (; at < end; at++) target.put(source.get(at));
    }
    private static VertexLayout.Attribute a(int location, int count, VertexLayout.Storage storage, boolean normalized, int offset) {
        return new VertexLayout.Attribute(location, count, storage, normalized, offset);
    }
    public static GameGeometry raw(int[] raw, int words, int vertices, int mode, boolean convertedQuads,
                                   boolean begun, boolean color, boolean texture, boolean lighting, boolean normals) {
        if (!begun) throw new IllegalStateException("Game accumulator has not begun");
        if (convertedQuads) throw new IllegalArgumentException("Historical converted-quad accumulator is unsupported");
        if (raw == null || vertices < 0 || vertices > 4 * 1024 * 1024 || words != (long)vertices * 8 || words > raw.length)
            throw new IllegalArgumentException("Invalid game accumulator size");
        ByteBuffer bytes = ByteBuffer.allocateDirect(Math.multiplyExact(words, 4)).order(ByteOrder.nativeOrder());
        for (int v = 0; v < vertices; v++) {
            for (int n = 0; n < 8; n++) {
                int value = raw[v * 8 + n];
                if ((n < 3 || texture && (n == 3 || n == 4)) && !Float.isFinite(Float.intBitsToFloat(value)))
                    throw new IllegalArgumentException("Non-finite game vertex");
                bytes.putInt(value);
            }
        }
        bytes.flip();
        return new GameGeometry(ChunkMeshBuilder.LAYOUT, bytes, vertices, mode,
                1 | (color ? 2 : 0) | (texture ? 4 : 0) | (lighting ? 8 : 0) | (normals ? 16 : 0));
    }
    /** Absolute NIO reads preserve the producer's position and limit. */
    public static GameGeometry floats(ByteBuffer bytes, int mode, int attributes) {
        int vertices = MeshData.validateVertices(FLOAT_LAYOUT, bytes);
        if (vertices > 4 * 1024 * 1024) throw new IllegalArgumentException("Oversized game geometry");
        for (int offset = bytes.position(); offset < bytes.limit(); offset += 4)
            if (!Float.isFinite(bytes.getFloat(offset))) throw new IllegalArgumentException("Non-finite game vertex");
        return new GameGeometry(FLOAT_LAYOUT, bytes, vertices, mode, attributes);
    }
    public static int[] indices(int mode, int vertices) {
        if (mode < 0 || mode > 9 || vertices < 0 || vertices > 4 * 1024 * 1024) throw new IllegalArgumentException("Game topology");
        int faces, count;
        switch (mode) {
            case 0: count = vertices; break;
            case 1: count = vertices / 2 * 2; break;
            case 2: count = vertices < 2 ? 0 : vertices * 2; break;
            case 3: count = Math.max(0, vertices - 1) * 2; break;
            case 4: count = vertices / 3 * 3; break;
            case 5: case 6: case 9: count = Math.max(0, vertices - 2) * 3; break;
            case 7: if (vertices % 4 != 0) throw new IllegalArgumentException("Incomplete game quad"); count = vertices / 4 * 6; break;
            case 8: if (vertices % 2 != 0) throw new IllegalArgumentException("Incomplete game quad strip"); count = Math.max(0, vertices / 2 - 1) * 6; break;
            default: throw new AssertionError();
        }
        int[] result = new int[count]; int at = 0;
        if (mode == 0 || mode == 1 || mode == 4) { for (int i = 0; i < count; i++) result[i] = i; return result; }
        if (mode == 2 || mode == 3) {
            for (int i = 0; i < count / 2; i++) { result[at++] = i; result[at++] = (i + 1) % vertices; } return result;
        }
        if (mode == 7 || mode == 8) {
            faces = count / 6;
            for (int i = 0; i < faces; i++) {
                int a = i * (mode == 7 ? 4 : 2), b = a + 1, c = a + (mode == 7 ? 2 : 3), d = a + (mode == 7 ? 3 : 2);
                result[at++] = a; result[at++] = b; result[at++] = c; result[at++] = a; result[at++] = c; result[at++] = d;
            }
        } else for (int i = 0; i < count / 3; i++) {
            result[at++] = mode == 5 ? i + (i % 2) : 0;
            result[at++] = mode == 5 ? i + 1 - (i % 2) : i + 1; result[at++] = i + 2;
        }
        return result;
    }
}
