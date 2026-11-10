package local.mcgl.render.backend;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import local.mcgl.render.IndexData;
import local.mcgl.render.MeshData;

/** The HUD is drawn after a sudden jump in visible chunks, with its own transforms. */
public final class BgfxTurnStress {
    private static final int WIDTH = 160, HEIGHT = 120;
    private static final int[] EXPECTED = {0xff0000, 0x00ff00, 0x0000ff, 0x000000};

    public static void verify(Path windowLibrary, Path output) throws Exception {
        try (BgfxTerrainStream stream = new BgfxTerrainStream(windowLibrary, WIDTH, HEIGHT, 0)) {
            // Use the production stream's initialization and the renderer's existing screenshot hook.
            Field field = BgfxTerrainStream.class.getDeclaredField("renderer");
            field.setAccessible(true);
            BgfxTerrainRenderer renderer = (BgfxTerrainRenderer)field.get(stream);
            MetalWorldFrame quiet = frame(500);
            for (int i = 0; i < 5; i++) renderer.draw(quiet, null);
            for (int count : new int[]{1800, 5500, 13000}) {
                Path name = output.resolve("turn-" + count);
                MetalWorldFrame busy = frame(count);
                renderer.draw(busy, name.toString());
                for (int i = 0; i < 4; i++) renderer.draw(busy, null);
                check(name.resolveSibling(name.getFileName() + ".tga"));
                if (count == 1800) {
                    // Allow the default peak window to expire before turning again.
                    for (int i = 0; i < 300; i++) renderer.draw(quiet, null);
                }
            }
        }
        System.out.println("BGFX_TURN_STRESS_PASS world, hand and map kept their coordinates up to 13002 draws");
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

        MetalWorldFrame.Pass world = pass(0, 0, 1);
        world.raster[4] = 1;
        world.raster[5] = 1;
        for (int i = 0; i < count; i++) {
            world.draws.add(rect(.12f, .12f, -.65f + (i % 31) * .0001f, (i % 27) * .0001f));
        }
        frame.passes.add(world);

        MetalWorldFrame.Pass hand = pass(1, 0, 0);
        hand.raster[9] = 1;
        hand.draws.add(rect(.15f, .2f, .25f, .25f));
        frame.passes.add(hand);
        MetalWorldFrame.Pass map = pass(0, 1, 0);
        map.draws.add(rect(.1f, .1f, .67f, .6f));
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

    private static MetalWorldFrame.Draw rect(float sx, float sy, float x, float y) {
        MetalWorldFrame.Draw draw = new MetalWorldFrame.Draw(0, 0, 0, 0);
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
