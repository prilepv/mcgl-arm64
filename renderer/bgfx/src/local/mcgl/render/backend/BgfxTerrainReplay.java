package local.mcgl.render.backend;

import java.io.IOException;
import java.nio.file.Path;

/** Drives the bgfx terrain renderer with a saved frame for pixel comparison. */
public final class BgfxTerrainReplay {
    private final MetalWorldFrame frame;

    private BgfxTerrainReplay(MetalWorldFrame frame) {
        if (!BgfxTerrainRenderer.supported(frame)) throw new IllegalArgumentException("Unsupported capture");
        this.frame = frame;
    }

    public static BgfxTerrainReplay read(Path capture) throws IOException {
        return new BgfxTerrainReplay(MetalWorldFrame.read(capture));
    }

    public int width() { return frame.width; }
    public int height() { return frame.height; }

    public void draw(Path shaders, String screenshot) throws IOException, InterruptedException {
        try (BgfxTerrainRenderer renderer = new BgfxTerrainRenderer(shaders, width(), height())) {
            for (int i = 0; i < 120; i++) {
                renderer.draw(frame, i == 20 ? screenshot : null);
                Thread.sleep(15);
            }
            System.out.println("BGFX_METAL_TERRAIN draws=" + frame.drawCount() + " passes=" + frame.passes.size()
                    + " meshes=" + renderer.meshUploads + " textures=" + renderer.textureUploads
                    + " pages=" + renderer.pageUploads
                    + " size=" + width() + "x" + height());
        }
    }
}
