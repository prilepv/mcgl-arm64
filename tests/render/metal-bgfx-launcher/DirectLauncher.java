package local.mcgl;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import local.mcgl.render.GameGeometry;
import local.mcgl.render.GameMatrices;
import local.mcgl.render.IndexData;
import local.mcgl.render.MeshData;
import local.mcgl.render.backend.BgfxTerrainReplay;
import local.mcgl.render.tests.OriginalChunkEmitter;
import net.mcgl.MCGLClassLoader;
import org.lwjgl.bgfx.BGFXInit;
import org.lwjgl.bgfx.BGFXVertexLayout;
import static org.lwjgl.bgfx.BGFX.*;

/** Draws original MCGL geometry in Cocoa through bgfx Metal with no legacy graphics bindings. */
public final class DirectLauncher {
    private static final int WIDTH = 360, HEIGHT = 240;
    private static final int CLEAR_COLOR = 0x14213dff;
    private static final int NEAR_COLOR = 0x64c832ff;
    private static final int FAR_COLOR = 0xfa1eaaff;

    private static native long openWindow(int width, int height);
    private static native void closeWindow(long window);

    public static void main(String[] args) throws Exception {
        String capture = System.getenv("MCGL_BGFX_WORLD_CAPTURE");
        BgfxTerrainReplay replay = capture == null ? null : BgfxTerrainReplay.read(Path.of(capture));
        int width = replay == null ? WIDTH : replay.width(), height = replay == null ? HEIGHT : replay.height();
        System.load(Path.of("bgfx-window.dylib").toAbsolutePath().toString());
        long window = openWindow(width, height);
        if (window == 0) throw new AssertionError("Cocoa window unavailable");
        try (BGFXInit init = BGFXInit.calloc()) {
            bgfx_init_ctor(init);
            init.type(BGFX_RENDERER_TYPE_METAL).platformData().nwh(window);
            init.resolution().width(width).height(height).reset(BGFX_RESET_VSYNC);
            if (!bgfx_init(init)) throw new AssertionError("bgfx Metal initialization failed");
            try {
                if (bgfx_get_renderer_type() != BGFX_RENDERER_TYPE_METAL)
                    throw new AssertionError("Unexpected renderer: " + bgfx_get_renderer_name(bgfx_get_renderer_type()));
                if (replay == null) renderGeometryProbe();
                else replay.draw(Path.of("."), "galaxy-bgfx");
            } finally {
                bgfx_shutdown();
            }
            byte[] image = Files.readAllBytes(Path.of("galaxy-bgfx.tga"));
            if (replay == null) verifyPixels(image);
            else verifyTerrain(image, width, height);
        } finally {
            closeWindow(window);
        }
    }

    private static void renderGeometryProbe() throws Exception {
        new MCGLClassLoader("mcgl.jar");
        Field loader = MCGLClassLoader.class.getDeclaredField("classLoader");
        loader.setAccessible(true);
        Thread.currentThread().setContextClassLoader((ClassLoader)loader.get(null));
        render(quad(NEAR_COLOR, .25), quad(FAR_COLOR, .75));
    }

    private static MeshData quad(int color, double depth) throws Exception {
        OriginalChunkEmitter emitter = new OriginalChunkEmitter(new File("bin/mcgl.jar"), 1024);
        emitter.begin();
        emitter.color(color >>> 24, color >>> 16 & 255, color >>> 8 & 255, color & 255);
        emitter.quad(-.65, -.6, .65, .6, depth);
        return GameGeometry.raw(emitter.raw(), 32, 4, 7, false, true, true, true, false, false).mesh;
    }

    private static void render(MeshData near, MeshData far) throws Exception {
        try (BGFXVertexLayout layout = BGFXVertexLayout.calloc()) {
            bgfx_vertex_layout_begin(layout, BGFX_RENDERER_TYPE_METAL);
            bgfx_vertex_layout_add(layout, BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
            bgfx_vertex_layout_skip(layout, 8);
            bgfx_vertex_layout_add(layout, BGFX_ATTRIB_COLOR0, 4, BGFX_ATTRIB_TYPE_UINT8, true, false);
            bgfx_vertex_layout_skip(layout, near.layout().stride() - 24);
            bgfx_vertex_layout_end(layout);
            if (layout.stride() != near.layout().stride()) throw new AssertionError("MCGL vertex stride changed");

            short nearVertices = valid(bgfx_create_vertex_buffer(bgfx_copy(near.vertices()), layout, BGFX_BUFFER_NONE));
            short farVertices = valid(bgfx_create_vertex_buffer(bgfx_copy(far.vertices()), layout, BGFX_BUFFER_NONE));
            IndexData indices = near.indices();
            short indexBuffer = valid(bgfx_create_index_buffer(bgfx_copy(indices.bytes()), BGFX_BUFFER_NONE));
            short program = valid(bgfx_create_program(shader("vs_cubes.bin"), shader("fs_cubes.bin"), true));
            try {
                bgfx_set_view_rect(0, 0, 0, WIDTH, HEIGHT);
                bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
                bgfx_set_view_clear(0, BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH, CLEAR_COLOR, 1, 0);
                double[] identity = GameMatrices.identity();
                float[] model = new float[16];
                for (int i = 0; i < model.length; i++) model[i] = (float)identity[i];
                for (int i = 0; i < 30; i++) {
                    submit(nearVertices, indexBuffer, program, model);
                    submit(farVertices, indexBuffer, program, model);
                    if (i == 20) bgfx_request_screen_shot(BGFX_INVALID_HANDLE, "galaxy-bgfx");
                    bgfx_frame(BGFX_FRAME_NONE);
                    Thread.sleep(15);
                }
            } finally {
                bgfx_destroy_program(program);
                bgfx_destroy_index_buffer(indexBuffer);
                bgfx_destroy_vertex_buffer(farVertices);
                bgfx_destroy_vertex_buffer(nearVertices);
            }
        }
    }

    private static void submit(short vertices, short indices, short program, float[] model) {
        bgfx_set_transform(model);
        bgfx_set_vertex_buffer(0, vertices, 0, 4);
        bgfx_set_index_buffer(indices, 0, 6);
        bgfx_set_state(BGFX_STATE_WRITE_RGB | BGFX_STATE_WRITE_A | BGFX_STATE_WRITE_Z | BGFX_STATE_DEPTH_TEST_LESS, 0);
        bgfx_submit(0, program, 0, BGFX_DISCARD_ALL);
    }

    private static short shader(String name) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(name));
        ByteBuffer source = ByteBuffer.allocateDirect(bytes.length);
        source.put(bytes).flip();
        return valid(bgfx_create_shader(bgfx_copy(source)));
    }

    private static short valid(short handle) {
        if (handle == BGFX_INVALID_HANDLE) throw new AssertionError("bgfx resource creation failed");
        return handle;
    }

    private static void verifyPixels(byte[] image) {
        int width = u16(image, 12), height = u16(image, 14), stride = Byte.toUnsignedInt(image[16]) / 8;
        int offset = 18 + Byte.toUnsignedInt(image[0]);
        if (image[2] != 2 || width != WIDTH || height != HEIGHT || stride != 4
                || image.length < offset + width * height * stride) throw new AssertionError("Unexpected bgfx TGA");
        verifyColor(image, offset + ((height / 2) * width + width / 2) * stride, NEAR_COLOR);
        verifyColor(image, offset, CLEAR_COLOR);
        System.out.println("BGFX_METAL_PASS backend=Metal geometry=MCGL depth=verified pixels=verified");
    }

    private static void verifyTerrain(byte[] image, int width, int height) {
        int offset = 18 + Byte.toUnsignedInt(image[0]);
        if (image[2] != 2 || u16(image, 12) != width || u16(image, 14) != height || image[16] != 32
                || image.length < offset + width * height * 4) throw new AssertionError("Unexpected terrain screenshot");
        int colors = 0;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int at = offset; at < offset + width * height * 4 && seen.size() < 64; at += 4) {
            int color = Byte.toUnsignedInt(image[at]) | Byte.toUnsignedInt(image[at + 1]) << 8
                    | Byte.toUnsignedInt(image[at + 2]) << 16;
            if (seen.add(color)) colors++;
        }
        if (colors < 8) throw new AssertionError("Terrain screenshot has too few distinct colors: " + colors);
        System.out.println("BGFX_METAL_PASS backend=Metal geometry=MCGL terrain=verified uniqueColorsAtLeast=" + colors);
    }

    private static int u16(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static void verifyColor(byte[] image, int offset, int expected) {
        int actual = Byte.toUnsignedInt(image[offset + 2]) << 24 | Byte.toUnsignedInt(image[offset + 1]) << 16
                | Byte.toUnsignedInt(image[offset]) << 8 | Byte.toUnsignedInt(image[offset + 3]);
        if (actual != expected)
            throw new AssertionError("BGFX pixel " + Integer.toHexString(actual) + ", expected " + Integer.toHexString(expected));
    }
}
