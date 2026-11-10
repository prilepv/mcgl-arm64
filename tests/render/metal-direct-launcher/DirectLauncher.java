package local.mcgl;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.Arrays;
import local.mcgl.render.GameGeometry;
import local.mcgl.render.GameMatrices;
import local.mcgl.render.GameRenderState;
import local.mcgl.render.backend.MetalTerrainWindow;
import local.mcgl.render.tests.OriginalChunkEmitter;
import net.mcgl.MCGLClassLoader;

/** The production Cocoa/JVM host runs this entry point without authentication or a game session. */
public final class DirectLauncher {
    public static void main(String[] ignored) throws Exception {
        new MCGLClassLoader("mcgl.jar");
        Field loader = MCGLClassLoader.class.getDeclaredField("classLoader");
        loader.setAccessible(true);
        Thread.currentThread().setContextClassLoader((ClassLoader)loader.get(null));
        GameGeometry near = quad(100, 200, 50, 255, -.5);
        GameGeometry far = quad(250, 30, 170, 255, .5);
        GameGeometry overlay = quad(200, 200, 250, 128, -.75);

        GameRenderState material = new GameRenderState();
        material.enable(3553, true);
        material.activeUnit(1);
        material.enable(3553, true);
        material.activeUnit(0);
        ByteBuffer pixels = ByteBuffer.allocateDirect(8);
        double[] model = GameMatrices.identity();
        try (MetalTerrainWindow window = MetalTerrainWindow.open(Paths.get("direct-metal.dylib"),
                "Minecraft Galaxy · Metal Direct Test", 360, 240, true);
             MetalTerrainWindow.Resource nearMesh = window.geometry(near);
             MetalTerrainWindow.Resource farMesh = window.geometry(far);
             MetalTerrainWindow.Resource overlayMesh = window.geometry(overlay);
             MetalTerrainWindow.Resource atlas = window.texture(1, 1, rgba(255, 128, 255, 255));
             MetalTerrainWindow.Resource light = window.texture(1, 1, rgba(255, 255, 128, 255))) {
            int[] before = window.drawableSize();
            frame(window, material, near, far, overlay, nearMesh, farMesh, overlayMesh, atlas, light, model, pixels);
            distantDepth(window, near, far, nearMesh, farMesh, pixels);
            litItem(window, near, nearMesh, atlas, model, pixels);
            window.resize(440, 300);
            int[] after = window.drawableSize();
            if (after[0] <= before[0] || after[1] <= before[1])
                throw new AssertionError("Drawable did not resize: " + Arrays.toString(before) + " to " + Arrays.toString(after));
            for (int i = 0; i < 4; i++)
                frame(window, material, near, far, overlay, nearMesh, farMesh, overlayMesh, atlas, light, model, pixels);
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (window.presentedFrames() == 0 && System.nanoTime() < deadline) {
                window.begin(0, 0, 0);
                window.pass(material, false, true);
                window.draw(nearMesh, near.attributeMask, model, atlas, light);
                window.present(null);
                Thread.sleep(20);
            }
            long presented = window.presentedFrames();
            if (presented == 0) throw new AssertionError("Metal drawable did not reach the display; visible=" + window.isVisible());
            System.out.println("DIRECT_METAL_WINDOW_PASS testedFrames=5 presented=" + presented
                    + " before=" + before[0] + "x" + before[1] + " after=" + after[0] + "x" + after[1]
                    + " verified=atlas,lightmap,depth,distant-depth,blend,item-lighting,resize");
        }
    }

    private static GameGeometry quad(int r, int g, int b, int alpha, double depth) throws Exception {
        OriginalChunkEmitter emitter = new OriginalChunkEmitter(new File("bin/mcgl.jar"), 1024);
        emitter.begin();
        emitter.color(r, g, b, alpha);
        emitter.lightmap(0);
        emitter.quad(-.6, -.6, .6, .6, depth);
        return GameGeometry.raw(emitter.raw(), 32, 4, 7, false, true, true, true, true, false);
    }

    private static void frame(MetalTerrainWindow window, GameRenderState material, GameGeometry near, GameGeometry far,
                              GameGeometry overlay,
                              MetalTerrainWindow.Resource nearMesh, MetalTerrainWindow.Resource farMesh,
                              MetalTerrainWindow.Resource overlayMesh,
                              MetalTerrainWindow.Resource atlas, MetalTerrainWindow.Resource light, double[] model,
                              ByteBuffer pixels) throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            window.begin(0, 0, 0);
            window.pass(material, false, true);
            window.draw(nearMesh, near.attributeMask, model, atlas, light);
            window.draw(farMesh, far.attributeMask, model, atlas, light);
            window.pass(material, true, false);
            window.draw(overlayMesh, overlay.attributeMask, model, atlas, light);
            window.draw(farMesh, far.attributeMask, model, atlas, light);
            if (window.present(pixels)) {
                int[] expected = {75, 100, 150, 191, 0, 0, 0, 255};
                int[] actual = new int[8];
                for (int i = 0; i < actual.length; i++) actual[i] = Byte.toUnsignedInt(pixels.get(i));
                for (int i = 0; i < actual.length; i++) {
                    if (Math.abs(actual[i] - expected[i]) > (i < 4 ? 1 : 0))
                        throw new AssertionError("Drawable BGRA mismatch: " + Arrays.toString(actual));
                }
                return;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("No Metal drawable available");
    }

    private static void distantDepth(MetalTerrainWindow window, GameGeometry near, GameGeometry far,
                                     MetalTerrainWindow.Resource nearMesh, MetalTerrainWindow.Resource farMesh,
                                     ByteBuffer pixels) throws Exception {
        GameRenderState material = new GameRenderState();
        double nearClip = .05, farClip = 512;
        material.matrices.mode(5889);
        material.matrices.load(new double[]{
                1, 0, 0, 0, 0, 1, 0, 0,
                0, 0, -(farClip + nearClip) / (farClip - nearClip), -1,
                0, 0, -2 * farClip * nearClip / (farClip - nearClip), 0
        });
        double[] model = GameMatrices.identity();
        model[0] = model[5] = 128;
        model[10] = -.01;
        model[14] = -256;
        for (int attempt = 0; attempt < 4; attempt++) {
            window.begin(0, 0, 0);
            window.pass(material, false, true);
            window.draw(nearMesh, near.attributeMask, model, null, null);
            window.draw(farMesh, far.attributeMask, model, null, null);
            if (window.present(pixels)) {
                int[] bgra = new int[4];
                for (int i = 0; i < bgra.length; i++) bgra[i] = Byte.toUnsignedInt(pixels.get(i));
                if (!Arrays.equals(bgra, new int[]{50, 200, 100, 255}))
                    throw new AssertionError("Distant depth picked the wrong surface: " + Arrays.toString(bgra));
                return;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("No drawable for distant depth test");
    }

    private static ByteBuffer rgba(int r, int g, int b, int a) {
        return ByteBuffer.allocateDirect(4).put((byte)r).put((byte)g).put((byte)b).put((byte)a).flip();
    }

    private static void litItem(MetalTerrainWindow window, GameGeometry geometry, MetalTerrainWindow.Resource mesh,
                                MetalTerrainWindow.Resource atlas, double[] model, ByteBuffer pixels) {
        GameRenderState material = new GameRenderState();
        material.enable(3553, true);
        material.enable(2896, true);
        material.enable(2903, true);
        material.enable(16384, true);
        material.lightModel(2899, new float[]{0, 0, 0, 1});
        material.light(0, 4609, new float[]{.5f, .5f, .5f, 1});
        window.begin(0, 0, 0);
        window.pass(material, false, true);
        window.draw(mesh, geometry.attributeMask, model, atlas, null);
        if (!window.present(pixels)) throw new AssertionError("No drawable for lit item test");
        int[] expected = {25, 50, 50, 255};
        for (int i = 0; i < expected.length; i++)
            if (Math.abs(Byte.toUnsignedInt(pixels.get(i)) - expected[i]) > 1)
                throw new AssertionError("Incorrect lighting or texture on item channel " + i);
    }
}
