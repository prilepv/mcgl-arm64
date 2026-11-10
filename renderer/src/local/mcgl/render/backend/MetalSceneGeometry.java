package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import local.mcgl.render.ChunkMeshBuilder;
import local.mcgl.render.GameRenderState;
import local.mcgl.render.IndexData;
import local.mcgl.render.MeshData;
import local.mcgl.render.VertexLayout;

/** Adapts the game's ordinary model and text streams to the live Metal vertex format. */
final class MetalSceneGeometry {
    private MetalSceneGeometry() {}

    static MeshData mesh(MeshData mesh, GameRenderState state, int mask) {
        if (GameTerrainFormat.LAYOUT.equals(mesh.layout()) || ChunkMeshBuilder.LAYOUT.equals(mesh.layout()))
            return GameTerrainFormat.storage(mesh);
        VertexLayout.Attribute color = attribute(mesh.layout(), 1), flat = attribute(mesh.layout(), 5);
        if (color == null || color.storage != VertexLayout.Storage.FLOAT32) return null;
        ByteBuffer in = mesh.vertices(), out = buffer(mesh.vertexCount());
        boolean perVertex = (mask & 128) != 0;
        VertexLayout.Attribute flatMask = attribute(mesh.layout(), 8);
        VertexLayout.Attribute flatNormal = attribute(mesh.layout(), 6);
        float[] currentColor = perVertex ? state.color() : null;
        float[] currentUv = perVertex ? state.uv(0) : null;
        float[] currentLight = perVertex ? state.uv(1) : null;
        float[] currentNormal = perVertex ? state.normal() : null;
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int at = vertex * mesh.layout().stride();
            int vertexMask = perVertex ? (int)in.getFloat(at + 56) : mask;
            int provoking = perVertex && flatMask != null ? (int)in.getFloat(at + flatMask.offset) : vertexMask;
            out.putFloat(in.getFloat(at)).putFloat(in.getFloat(at + 4)).putFloat(in.getFloat(at + 8));
            pair(out, in, at + 28, currentUv, vertexMask, 4, false);
            color(out, in, at + color.offset, currentColor, vertexMask);
            normal(out, in, at + 44, currentNormal, vertexMask);
            pair(out, in, at + 36, currentLight, vertexMask, 8, true);
            color(out, in, at + (flat == null ? color.offset : flat.offset), currentColor, provoking);
            normal(out, in, at + (flatNormal == null ? 44 : flatNormal.offset), currentNormal, provoking);
        }
        out.flip();
        return new MeshData(GameTerrainFormat.LAYOUT, out, mesh.indices());
    }

    static MeshData lines(MeshData mesh, GameRenderState state, int mask) {
        if (mesh.vertexCount() % 4 != 0) return null;
        MeshData packed = mesh(mesh, state, mask);
        if (packed == null) {
            // The raw tessellator's first 32 bytes remain unchanged by line expansion.
            VertexLayout.Attribute color = attribute(mesh.layout(), 1), flat = attribute(mesh.layout(), 5);
            VertexLayout.Attribute normal = attribute(mesh.layout(), 6);
            if (color == null || color.storage != VertexLayout.Storage.UINT8 || flat == null || normal == null)
                return null;
            ByteBuffer in = mesh.vertices(), out = buffer(mesh.vertexCount());
            for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                int at = vertex * mesh.layout().stride();
                for (int offset = 0; offset < 32; offset += 4) out.putInt(in.getInt(at + offset));
                out.putInt(in.getInt(at + flat.offset));
                out.put(in.get(at + normal.offset)).put(in.get(at + normal.offset + 1))
                        .put(in.get(at + normal.offset + 2)).put((byte)0);
            }
            out.flip();
            packed = new MeshData(GameTerrainFormat.LAYOUT, out, mesh.indices());
        }
        // Four vertices per expanded line, two copies of each endpoint.
        int[] indices = new int[mesh.vertexCount() / 2];
        for (int i = 0; i < indices.length; i++) indices[i] = i * 2;
        return new MeshData(GameTerrainFormat.LAYOUT, packed.vertices(), IndexData.of(indices));
    }

    static MeshData text(ByteBuffer input, int vertices, int stride) {
        ByteBuffer out = buffer(vertices);
        int[] indices = new int[vertices];
        for (int i = 0; i < vertices; i++) {
            int at = i * stride, matrix = at + 60;
            float x = input.getFloat(at), y = input.getFloat(at + 4), z = input.getFloat(at + 8);
            for (int axis = 0; axis < 3; axis++) {
                int row = matrix + axis * 4;
                out.putFloat(input.getFloat(row) * x + input.getFloat(row + 16) * y
                        + input.getFloat(row + 32) * z + input.getFloat(row + 48));
            }
            pair(out, input, at + 28, null, 4, 4, false);
            color(out, input, at + 12, null, 2);
            out.putInt(0);
            pair(out, input, at + 36, null, 8, 8, true);
            color(out, input, at + 44, null, 2);
            out.putInt(0);
            indices[i] = i;
        }
        out.flip();
        return new MeshData(GameTerrainFormat.LAYOUT, out, IndexData.of(indices));
    }

    private static ByteBuffer buffer(int vertices) {
        return ByteBuffer.allocateDirect(Math.multiplyExact(vertices, 40)).order(ByteOrder.nativeOrder());
    }

    private static VertexLayout.Attribute attribute(VertexLayout layout, int location) {
        for (VertexLayout.Attribute value : layout.attributes()) if (value.location == location) return value;
        return null;
    }

    private static void pair(ByteBuffer out, ByteBuffer in, int at, float[] current, int mask, int bit, boolean packed) {
        for (int i = 0; i < 2; i++) {
            float value = current != null && (mask & bit) == 0 ? current[i] : in.getFloat(at + i * 4);
            if (packed) out.putShort((short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value))));
            else out.putFloat(value);
        }
    }

    private static void color(ByteBuffer out, ByteBuffer in, int at, float[] current, int mask) {
        for (int i = 0; i < 4; i++) {
            float value = current != null && (mask & 2) == 0 ? current[i] : in.getFloat(at + i * 4);
            out.put((byte)Math.round(Math.max(0, Math.min(1, value)) * 255));
        }
    }

    private static void normal(ByteBuffer out, ByteBuffer in, int at, float[] current, int mask) {
        for (int i = 0; i < 3; i++) {
            float value = current != null && (mask & 16) == 0 ? current[i] : in.getFloat(at + i * 4);
            out.put((byte)Math.round(Math.max(-1, Math.min(1, value)) * 127));
        }
        out.put((byte)0);
    }
}
