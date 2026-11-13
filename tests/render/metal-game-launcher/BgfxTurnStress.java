package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import local.mcgl.render.IndexData;
import local.mcgl.render.MeshData;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_NONE;

/** The HUD is drawn after a sudden jump in visible chunks, with its own transforms. */
public final class BgfxTurnStress {
    private static final int WIDTH = 160, HEIGHT = 120;
    private static final int[] EXPECTED = {0xff0000, 0x00ff00, 0x0000ff, 0x000000};

    public static void verify(Path windowLibrary, Path output) throws Exception {
        try (BgfxTerrainStream stream = new BgfxTerrainStream(windowLibrary, WIDTH, HEIGHT, 0);
             BgfxTerrainRenderer renderer = new BgfxTerrainRenderer(windowLibrary.getParent(), WIDTH, HEIGHT, true, BGFX_RESET_NONE)) {
            // Use a preview window to verify pixels from the game renderer's streamed and retained buffers.
            verifyCachePressure(renderer);
            MetalWorldFrame quiet = frame(500);
            for (int i = 0; i < 5; i++) renderer.draw(quiet, null);
            for (int count : new int[]{1800, 5500, 13000}) {
                Path name = output.resolve("turn-" + count);
                MetalWorldFrame busy = frame(count);
                long before = renderer.streamPages;
                renderer.draw(busy, name.toString());
                if (renderer.streamPages - before != 2)
                    throw new AssertionError("Expected 16-bit and 32-bit transient pages");
                if (renderer.matrixUploads != (count + 7) / 8 + 2)
                    throw new AssertionError("Repeated matrices were uploaded again");
                if (renderer.stateUploads != 4)
                    throw new AssertionError("Repeated state was uploaded again");
                for (int i = 0; i < 4; i++) renderer.draw(busy, null);
                check(name.resolveSibling(name.getFileName() + ".tga"));
                if (count == 1800) {
                    // Advance cache generations while leaving the busy scene out of view.
                    MetalWorldFrame alternateQuiet = frame(500);
                    for (int i = 0; i < 300; i++) renderer.draw(i % 2 == 0 ? quiet : alternateQuiet, null);
                    if (renderer.residentPages() != 2) throw new AssertionError("Unused geometry page was retained");
                }
            }
        }
        System.out.println("BGFX_TURN_STRESS_PASS world, hand and map kept their coordinates up to 13002 draws");
    }

    private static void verifyCachePressure(BgfxTerrainRenderer renderer) {
        MeshData source = frame(0).meshes.get(0);
        List<MeshData> visible = new ArrayList<>();
        long uploads = renderer.retainedUploads;
        for (int i = 0; i <= 256; i++) {
            visible.add(new MeshData(source.layout(), source.vertices(), source.indices()));
            MetalWorldFrame frame = new MetalWorldFrame();
            frame.width = WIDTH;
            frame.height = HEIGHT;
            frame.meshes.addAll(visible);
            frame.retainedMeshes.set(0, visible.size());
            MetalWorldFrame.Pass pass = pass(1, 0, 0);
            pass.draws.add(rect(i, .25f, .25f, 0, 0, 0));
            frame.passes.add(pass);
            if (!renderer.draw(frame, null)) throw new AssertionError("Cache pressure frame was rejected");
        }
        if (renderer.geometryRepacks != 0 || renderer.geometryCompactions != 1
                || renderer.retainedUploads - uploads != 289 || renderer.residentPages() != 225)
            throw new AssertionError("A full geometry cache did not compact selectively");
        System.out.println("BGFX_GEOMETRY_COMPACTION_PASS 32 existing pages compacted; 224 kept");
    }

    private static MetalWorldFrame frame(int count) {
        MetalWorldFrame frame = new MetalWorldFrame();
        frame.width = WIDTH;
        frame.height = HEIGHT;
        ByteBuffer vertices = ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) {
            vertices.putFloat(i == 0 || i == 3 ? -1 : 1);
            vertices.putFloat(i < 2 ? -1 : 1);
            vertices.putFloat(.5f);
            for (int j = 0; j < 7; j++) vertices.putInt(0);
        }
        vertices.flip();
        frame.meshes.add(new MeshData(GameTerrainFormat.LAYOUT, vertices, IndexData.of(new int[]{0, 1, 2, 0, 2, 3})));
        frame.meshes.add(new MeshData(GameTerrainFormat.LAYOUT, vertices,
                IndexData.unsignedInt(IntBuffer.wrap(new int[]{0, 1, 2, 0, 2, 3}))));
        frame.meshes.add(new MeshData(GameTerrainFormat.LAYOUT, vertices, IndexData.of(new int[]{0, 1, 2, 0, 2, 3})));
        frame.retainedMeshes.set(2);

        MetalWorldFrame.Pass world = pass(0, 0, 1);
        world.raster[4] = 1;
        world.raster[5] = 1;
        for (int i = 0; i < count; i++) {
            int group = i / 8;
            world.draws.add(rect(0, .12f, .12f, -.65f + (group % 31) * .0001f, (group % 27) * .0001f,
                    i < count / 2 ? 0 : 4));
        }
        frame.passes.add(world);

        MetalWorldFrame.Pass hand = pass(1, 0, 0);
        hand.raster[9] = 1;
        hand.draws.add(rect(2, .15f, .2f, .25f, .25f, 0));
        frame.passes.add(hand);
        MetalWorldFrame.Pass map = pass(0, 1, 0);
        map.draws.add(rect(1, .1f, .1f, .67f, .6f, 0));
        frame.passes.add(map);
        return frame;
    }

    private static MetalWorldFrame.Pass pass(float red, float green, float blue) {
        MetalWorldFrame.Pass pass = new MetalWorldFrame.Pass();
        for (int i = 0; i < 16; i += 5) pass.material[i] = 1;
        pass.material[48] = red;
        pass.material[49] = green;
        pass.material[50] = blue;
        pass.material[51] = 1;
        pass.raster[4] = 7;
        return pass;
    }

    private static MetalWorldFrame.Draw rect(int mesh, float sx, float sy, float x, float y, int mask) {
        MetalWorldFrame.Draw draw = new MetalWorldFrame.Draw(mesh, 0, 0, mask);
        draw.model[0] = sx;
        draw.model[5] = sy;
        draw.model[10] = draw.model[15] = 1;
        draw.model[12] = x;
        draw.model[13] = y;
        return draw;
    }

    private static void check(Path screenshot) throws Exception {
        byte[] tga = Files.readAllBytes(screenshot);
        int width = (tga[12] & 255) | (tga[13] & 255) << 8;
        int height = (tga[14] & 255) | (tga[15] & 255) << 8;
        if (tga[2] != 2 || width != WIDTH || height != HEIGHT) throw new AssertionError("Unexpected TGA");
        int[][] positions = {{100, 45}, {133, 24}, {28, 60}, {80, 60}};
        for (int i = 0; i < positions.length; i++) {
            int y = positions[i][1];
            if ((tga[17] & 32) == 0) y = HEIGHT - 1 - y;
            int offset = 18 + (tga[0] & 255) + (y * WIDTH + positions[i][0]) * (tga[16] & 255) / 8;
            int color = (tga[offset + 2] & 255) << 16 | (tga[offset + 1] & 255) << 8 | (tga[offset] & 255);
            if (color != EXPECTED[i]) throw new AssertionError(screenshot.getFileName()
                    + " misplaced layer " + i + ", pixel=" + Integer.toHexString(color));
        }
    }
}
