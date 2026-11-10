package local.mcgl.render.backend;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.*;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Pure Metal playback of a bounded terrain capture, with no game or OpenGL classes loaded. */
public final class MetalWorldReplay {
    private MetalWorldReplay() {}

    public static void show(Path input, Path library, Path image, int seconds) throws Exception {
        if (seconds < 1 || seconds > 300) throw new IllegalArgumentException("Expected 1–300 seconds");
        MetalWorldFrame frame = MetalWorldFrame.read(input);
        double scale = Math.min(1, Math.min(1000.0 / frame.width, 800.0 / frame.height));
        int width = Math.max(1, (int)Math.round(frame.width * scale));
        int height = Math.max(1, (int)Math.round(frame.height * scale));
        try (MetalTerrainWindow window = MetalTerrainWindow.open(library, "Minecraft Galaxy · Metal World", width, height, true)) {
            MetalWorldPlayback player = new MetalWorldPlayback(window);
            int[] size = window.drawableSize();
            ByteBuffer pixels = ByteBuffer.allocateDirect(Math.multiplyExact(size[0] * size[1], 4));
            boolean captured = false;
            int changed = 0;
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (captured) {
                    if (!player.draw(frame, null)) break;
                } else if (player.draw(frame, pixels)) {
                    changed = saveImage(pixels, size[0], size[1], frame.clear, image);
                    captured = true;
                }
                Thread.sleep(32);
            }
            long presented = window.presentedFrames();
            if (!captured || changed == 0 || presented == 0)
                throw new IllegalStateException("No visible terrain in direct Metal replay");
            System.out.println("DIRECT_METAL_WORLD_PASS draws=" + frame.drawCount() + " passes=" + frame.passes.size()
                    + " meshes=" + frame.meshes.size() + " textures=" + frame.textures.size() + " skipped=" + frame.skipped
                    + " presented=" + presented + " changedPixels=" + changed + " size=" + size[0] + "x" + size[1]);
        }
    }

    private static int saveImage(ByteBuffer pixels, int width, int height, float[] clear, Path path) throws IOException {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        int red = Math.round(clear[0] * 255), green = Math.round(clear[1] * 255), blue = Math.round(clear[2] * 255);
        int changed = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int b = Byte.toUnsignedInt(pixels.get(offset));
                int g = Byte.toUnsignedInt(pixels.get(offset + 1));
                int r = Byte.toUnsignedInt(pixels.get(offset + 2));
                row[x] = (r << 16) | (g << 8) | b;
                if (Math.abs(r - red) > 1 || Math.abs(g - green) > 1 || Math.abs(b - blue) > 1) changed++;
            }
            output.setRGB(0, y, width, 1, row, 0, width);
        }
        if (!ImageIO.write(output, "png", path.toFile())) throw new IOException("PNG writer unavailable");
        return changed;
    }
}
