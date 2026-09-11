package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import local.mcgl.render.GameGeometry;
import local.mcgl.render.IndexData;
import local.mcgl.render.MeshData;

/** Exact CPU equivalence and hard compilation bounds; no OpenGL context required. */
public final class GameModelBatchTest {
    private static int checks;
    public static void main(String[] args) {
        Random random = new Random(194);
        for (int mode = 0; mode <= 9; mode++) for (int trial = 0; trial < 24; trial++) {
            GameModelBatch batch = new GameModelBatch();
            List<MeshData> sources = new ArrayList<MeshData>();
            for (int i = 0; i < 6; i++) {
                int count = 12 + 4 * random.nextInt(8);
                GameGeometry geometry = geometry(count, mode, 31, random);
                check(batch.append(geometry), "compatible topology run " + mode);
                sources.add(geometry.mesh);
            }
            equivalent(batch.meshData(), sources);
        }
        GameGeometry quad = geometry(4, 7, 31, random);
        GameModelBatch single = new GameModelBatch();
        check(single.append(quad) && single.meshData() == quad.mesh, "single part needs no extra copy");
        check(!single.append(geometry(4, 7, 1, random)), "attribute mask is a barrier");
        check(!single.append(geometry(4, 0, 31, random)), "primitive is a barrier");
        ByteBuffer immediate = ByteBuffer.allocateDirect(4 * 60).order(ByteOrder.nativeOrder());
        check(!single.append(GameGeometry.floats(immediate, 7, 31)), "vertex layout is a barrier even with the same mask");
        equivalent(single.meshData(), java.util.Collections.singletonList(quad.mesh));
        GameModelBatch countBound = new GameModelBatch();
        for (int i = 0; i < GameModelBatch.MAX_PARTS; i++) check(countBound.append(quad), "part bound accepts prefix");
        check(!countBound.append(quad), "part bound stops the next run");
        GameGeometry large = geometry(10000, 0, 31, random);
        GameModelBatch byteBound = new GameModelBatch();
        check(byteBound.append(large) && byteBound.append(large), "bounded large inputs");
        check(!byteBound.append(large), "byte bound stops before allocation growth");
        equivalent(byteBound.meshData(), java.util.Arrays.asList(large.mesh, large.mesh));
        check(!GameModelBatch.eligible(geometry(70000, 0, 31, random)), "oversized geometry keeps the existing path");
        check(!GameModelBatch.eligible(geometry(0, 7, 31, random)), "empty geometry does not form a run");
        boolean empty = false;
        try { new GameModelBatch().meshData(); } catch (IllegalStateException expected) { empty = true; }
        check(empty, "empty builder cannot publish");
        System.out.println("GAME_MODEL_BATCH_CPU_PASS checks=" + checks);
    }
    private static GameGeometry geometry(int count, int mode, int mask, Random random) {
        int[] raw = new int[count * 8];
        for (int v = 0; v < count; v++) {
            for (int j = 0; j < 5; j++) raw[v * 8 + j] = Float.floatToRawIntBits((random.nextFloat() - .5f) * 64);
            for (int j = 5; j < 8; j++) raw[v * 8 + j] = random.nextInt();
        }
        return GameGeometry.raw(raw, raw.length, count, mode, false, true,
                (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0, (mask & 16) != 0);
    }
    private static void equivalent(MeshData joined, List<MeshData> sources) {
        ByteBuffer vertices = joined.vertices(), indices = joined.indices().bytes();
        int base = 0;
        for (MeshData source : sources) {
            check(joined.layout().equals(source.layout()), "layout retained");
            ByteBuffer expectedVertices = source.vertices();
            while (expectedVertices.hasRemaining()) check(vertices.get() == expectedVertices.get(), "vertex bits and order retained");
            ByteBuffer expectedIndices = source.indices().bytes();
            while (expectedIndices.hasRemaining()) check(index(indices, joined.indices().type())
                    == base + index(expectedIndices, source.indices().type()), "triangle topology and provoking vertex retained");
            base += source.vertexCount();
        }
        check(!vertices.hasRemaining() && !indices.hasRemaining() && joined.vertexCount() == base, "no omitted or duplicate data");
    }
    private static int index(ByteBuffer bytes, IndexData.Type type) { return type == IndexData.Type.UINT16 ? bytes.getShort() & 65535 : bytes.getInt(); }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
}
