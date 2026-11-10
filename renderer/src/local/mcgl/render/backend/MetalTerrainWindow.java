package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import local.mcgl.render.GameGeometry;
import local.mcgl.render.GameRenderState;
import local.mcgl.render.Mesh;
import local.mcgl.render.MeshData;

/** A direct Cocoa/Metal window for ordered terrain passes. All calls belong to its Java render thread. */
public final class MetalTerrainWindow implements AutoCloseable {
    private static final int DRAW_BYTES = 96, MAX_DRAWS = MetalWorldFrame.MAX_DRAWS;
    private static final int PASS_BYTES = GameRenderState.METAL_MATERIAL_FLOATS * 4 + 64;
    private static final int MAX_PASSES = MetalWorldFrame.MAX_PASSES;
    private final Thread owner = Thread.currentThread();
    private final Set<Resource> resources = new HashSet<Resource>();
    private final ByteBuffer draws = direct(MAX_DRAWS * DRAW_BYTES);
    private final ByteBuffer passes = direct(MAX_PASSES * PASS_BYTES);
    private final FloatBuffer uniforms = direct(GameRenderState.METAL_MATERIAL_FLOATS * 4).asFloatBuffer();
    private final int[] flags = new int[14];
    private final boolean validation;
    private long window;
    private int count, passCount, passStart;
    private boolean recording;
    private float red, green, blue;

    public static MetalTerrainWindow open(Path library, String title, int width, int height, boolean validation) {
        System.load(library.toAbsolutePath().toString());
        return new MetalTerrainWindow(title, width, height, validation, false, 0);
    }

    static MetalTerrainWindow openPassive(Path library, String title, int width, int height) {
        System.load(library.toAbsolutePath().toString());
        return new MetalTerrainWindow(title, width, height, false, true, 0);
    }

    static MetalTerrainWindow openAttached(Path library, String title, int width, int height, long cocoaWindow) {
        System.load(library.toAbsolutePath().toString());
        return new MetalTerrainWindow(title, width, height, false, false, cocoaWindow);
    }

    private MetalTerrainWindow(String title, int width, int height, boolean validation, boolean passive, long cocoaWindow) {
        if (title == null) throw new NullPointerException("Metal window title");
        validateSize(width, height);
        this.validation = validation;
        window = openNative(title, width, height, validation, passive, cocoaWindow);
        if (window == 0) throw new IllegalStateException("Metal window unavailable");
    }

    public final class Resource implements AutoCloseable {
        private final boolean texture;
        private long handle;
        private Resource(long handle, boolean texture) {
            if (handle == 0) throw new IllegalStateException("Metal terrain resource unavailable");
            this.handle = handle;
            this.texture = texture;
            resources.add(this);
        }
        @Override public void close() {
            checkOwner();
            if (recording) throw new IllegalStateException("Cannot release a resource referenced by an open frame");
            if (handle != 0) {
                releaseResource(handle);
                handle = 0;
                resources.remove(this);
            }
        }
    }

    public Resource geometry(GameGeometry geometry) {
        checkOpen();
        if (geometry.primitive != Mesh.Primitive.TRIANGLES || (geometry.attributeMask & ~127) != 0)
            throw new IllegalArgumentException("Direct terrain expects original indexed triangles");
        return geometry(GameTerrainFormat.storage(geometry.mesh));
    }

    Resource geometry(MeshData mesh) {
        checkOpen();
        if (!GameTerrainFormat.LAYOUT.equals(mesh.layout()) || mesh.indices().count() == 0)
            throw new IllegalArgumentException("Unsupported terrain vertex layout");
        return new Resource(uploadMesh(window, mesh.vertices().slice(), mesh.indices().bytes().slice(),
                mesh.indices().count(), mesh.indices().type().bytes), false);
    }

    public Resource texture(int width, int height, ByteBuffer rgba) {
        checkOpen();
        if (width < 1 || height < 1 || width > 4096 || height > 4096
                || rgba == null || !rgba.isDirect() || rgba.remaining() != (long)width * height * 4)
            throw new IllegalArgumentException("Expected complete RGBA8 pixels");
        return new Resource(uploadTexture(window, width, height, new ByteBuffer[]{rgba.slice()}, 9728, 9728, 33071, 33071), true);
    }

    Resource texture(MetalWorldFrame.Texture texture) {
        checkOpen();
        return new Resource(uploadTexture(window, texture.width, texture.height, texture.levels,
                texture.min, texture.mag, texture.wrapS, texture.wrapT), true);
    }

    public void begin(float red, float green, float blue) {
        checkOpen();
        if (recording) throw new IllegalStateException("Metal frame already open");
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue))
            throw new IllegalArgumentException("Non-finite clear color");
        this.red = red; this.green = green; this.blue = blue;
        draws.clear();
        passes.clear();
        count = passCount = passStart = 0;
        recording = true;
    }

    public void pass(GameRenderState material, boolean blend, boolean writeDepth) {
        material.writeMetalMaterial(uniforms, flags);
        flags[4] = 3; // Less-or-equal depth for original terrain.
        flags[5] = writeDepth ? 1 : 0;
        flags[6] = blend ? 1 : 0;
        flags[8] = 1;
        pass(uniforms, flags);
    }

    void pass(FloatBuffer material, int[] raster) {
        checkOpen();
        if (!recording || passCount == MAX_PASSES) throw new IllegalStateException("No space for a Metal terrain pass");
        sealPass();
        for (int i = 0; i < GameRenderState.METAL_MATERIAL_FLOATS; i++) passes.putFloat(material.get(material.position() + i));
        for (int flag : raster) passes.putInt(flag);
        passes.putInt(count).putInt(0);
        passStart = count;
        passCount++;
    }

    private void sealPass() {
        if (passCount != 0) passes.putInt(passCount * PASS_BYTES - Integer.BYTES, count - passStart);
    }

    public void draw(Resource geometry, int mask, double[] model, Resource atlas, Resource lightmap) {
        checkOpen();
        if (!recording || passCount == 0 || count == MAX_DRAWS)
            throw new IllegalStateException("No space in the current Metal terrain pass");
        if (model == null || model.length != 16 || (mask & ~127) != 0)
            throw new IllegalArgumentException("Invalid Metal terrain transform or mask");
        for (double value : model) if (!Float.isFinite((float)value))
            throw new IllegalArgumentException("Non-finite Metal terrain transform");
        long mesh = handle(geometry, false), color = handle(atlas, true), lighting = handle(lightmap, true);
        draws.putLong(mesh).putLong(color).putInt(mask).putInt(0).putLong(lighting);
        for (double value : model) draws.putFloat((float)value);
        count++;
    }

    /** Optional validation returns BGRA pixels at the drawable center and top-left. */
    public boolean present(ByteBuffer pixels) {
        return present(pixels, false);
    }

    boolean presentImage(ByteBuffer pixels) {
        return present(pixels, true);
    }

    private boolean present(ByteBuffer pixels, boolean image) {
        checkOpen();
        if (!recording) throw new IllegalStateException("No Metal frame to present");
        if (pixels != null && (!validation || !pixels.isDirect() || pixels.isReadOnly() || pixels.remaining() < 8))
            throw new IllegalArgumentException("Expected an eight-byte writable validation buffer");
        recording = false;
        sealPass();
        draws.flip();
        passes.flip();
        return presentNative(window, draws, count, passes, passCount, red, green, blue,
                pixels == null ? null : pixels.slice(), image);
    }

    public long presentedFrames() {
        checkOpen();
        return presentedNative(window);
    }

    void title(String title) {
        checkOpen();
        titleNative(window, title);
    }

    int presentationPolicy() {
        checkOpen();
        return presentationPolicyNative(window);
    }

    public boolean isVisible() {
        checkOpen();
        return isVisibleNative(window);
    }

    boolean isClosed() {
        checkOpen();
        return isClosedNative(window);
    }

    public void resize(int width, int height) {
        checkOpen();
        validateSize(width, height);
        if (recording) throw new IllegalStateException("Cannot resize during a Metal frame");
        resizeNative(window, width, height);
    }

    /** Backing-pixel size, width followed by height. */
    public int[] drawableSize() {
        checkOpen();
        long size = sizeNative(window);
        return new int[]{(int)(size >>> 32), (int)size};
    }

    private static void validateSize(int width, int height) {
        if (width < 1 || height < 1 || width > 4096 || height > 4096)
            throw new IllegalArgumentException("Invalid Metal window size");
    }

    private long handle(Resource resource, boolean texture) {
        if (resource == null && texture) return 0;
        if (resource == null || !resources.contains(resource) || resource.texture != texture)
            throw new IllegalArgumentException("Resource does not belong to this Metal window");
        return resource.handle;
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Metal window belongs to another render thread");
    }
    private void checkOpen() {
        checkOwner();
        if (window == 0) throw new IllegalStateException("Metal window closed");
    }

    @Override public void close() {
        checkOwner();
        if (window == 0) return;
        recording = false;
        for (Resource resource : resources.toArray(new Resource[0])) resource.close();
        closeNative(window);
        window = 0;
    }

    private static ByteBuffer direct(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()); }
    private static native long openNative(String title, int width, int height, boolean validation, boolean passive, long cocoaWindow);
    private static native long uploadMesh(long handle, ByteBuffer vertices, ByteBuffer indices, int count, int indexBytes);
    private static native long uploadTexture(long handle, int width, int height, ByteBuffer[] levels,
                                             int min, int mag, int wrapS, int wrapT);
    private static native boolean presentNative(long handle, ByteBuffer draws, int count, ByteBuffer passes, int passCount,
                                                float red, float green, float blue, ByteBuffer pixels, boolean image);
    private static native void titleNative(long handle, String title);
    private static native int presentationPolicyNative(long handle);
    private static native long presentedNative(long handle);
    private static native boolean isVisibleNative(long handle);
    private static native boolean isClosedNative(long handle);
    private static native void resizeNative(long handle, int width, int height);
    private static native long sizeNative(long handle);
    private static native void releaseResource(long handle);
    private static native void closeNative(long handle);
}
