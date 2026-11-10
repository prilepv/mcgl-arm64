package local.mcgl.render.backend;

import java.nio.*;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Opt-in Metal terrain diagnostics and shared-surface presentation. */
final class MetalTerrainPreview {
    private static final long MESH_BUDGET = 160L * 1024 * 1024, TEXTURE_BUDGET = 32L * 1024 * 1024;
    private static final int DRAW_BYTES = 96, MAX_SAMPLES = 12;
    private final RenderContext context;
    private final GameRenderState state;
    private final GameRasterState raster;
    private final IdentityHashMap<Mesh, CachedMesh> meshes = new IdentityHashMap<Mesh, CachedMesh>();
    private final Map<Integer, CachedTexture> textures = new HashMap<Integer, CachedTexture>();
    private final Set<Integer> renderTargets = new HashSet<Integer>();
    private final ByteBuffer draws = direct(MeshArena.MAX_DRAWS * DRAW_BYTES);
    private final FloatBuffer uniform = direct(64 * 4).asFloatBuffer();
    private final int[] flags = new int[10], size = new int[2];
    private final boolean validate = Boolean.getBoolean("mcgl.metal.terrain.validate");
    private final boolean visible = "1".equals(System.getenv("MCGL_METAL_TERRAIN_VISIBLE"));
    private final long minVertices = Math.max(1, Long.getLong("mcgl.metal.terrain.minVertices", 4096));
    private final int interval = Math.max(1, Integer.getInteger("mcgl.metal.terrain.interval", 180));
    private long nextFrame = Math.max(0, Integer.getInteger("mcgl.metal.terrain.warmup", 180));
    private long scene, meshBytes, textureBytes, vertices, started, visibleFrame = -1;
    private long visibleFrames, visibleNanos, visibleVertices;
    private int query, width, height, parts, samples;
    boolean drawVisible(MeshArena arena, List<Mesh> meshesInOrder, int mask, FloatBuffer matrices, IntBuffer inputs,
                        int[] table, boolean materials, long vertexCount) {
        long frame = context.completedFrames();
        if (!visible || scene == 0 || frame == visibleFrame || meshesInOrder.size() < 4 || vertexCount < minVertices) return false;
        if (raster.enabled(GL11C.GL_BLEND) || !raster.depthMask() || !raster.enabled(GL11C.GL_DEPTH_TEST)) return false;
        // Bound synchronization to one original batch per presented frame, keeping later passes on their original path.
        visibleFrame = frame;
        long start = System.nanoTime();
        try {
            if (!prepare(arena, meshesInOrder, mask, matrices, inputs, table, materials, vertexCount)) return false;
            if ((flags[4] != 1 && flags[4] != 3) || flags[5] == 0 || flags[6] != 0
                    || GL11C.glGetInteger(GL13C.GL_SAMPLE_BUFFERS) != 0
                    || GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER1) != GL11C.GL_NONE) return skip("presentation target");
            double[] times = replay(scene, width, height, draws, parts, uniform, flags, null, true);
            if (times == null) return false;
            visibleNanos += System.nanoTime() - start;
            visibleVertices += vertexCount;
            if (++visibleFrames == 1 || validate || visibleFrames % 180 == 0) {
                System.out.println(String.format(Locale.ROOT,
                        "[MCGL Metal terrain] visibleFrames=%d frame=%d parts=%d vertices=%d resolution=%dx%d hybridAverageMs=%.3f metalGpuMs=%.3f",
                        visibleFrames, frame, parts, visibleVertices, width, height, visibleNanos / 1e6 / visibleFrames, times[1]));
            }
            return true;
        } catch (RuntimeException | LinkageError error) {
            disable(error);
            return false;
        }
    }

    private boolean prepare(MeshArena arena, List<Mesh> meshesInOrder, int mask, FloatBuffer matrices,
                            IntBuffer inputs, int[] table, boolean materials, long vertexCount) {
        if (!TerrainGLReadback.raster(raster, flags, size) || state.constantTextureCombine() || state.activeUnit() != 0
                || !TerrainGLReadback.usesTextureSamplers()) return skip("raster/material");
        width = size[0]; height = size[1];
        state.writeTerrainPreview(uniform, flags);
        for (Mesh mesh : meshesInOrder) if (!meshes.containsKey(mesh)) return skip("mesh cache");
        long lightmap = (flags[0] & 2) != 0 ? texture(raster.previewTexture(1)) : 0;
        if (lightmap < 0) return skip("lightmap");
        draws.clear();
        for (Mesh mesh : meshesInOrder) {
            int tag = arena.tag(mesh), entry = materials ? inputs.get(tag) : 0;
            int materialMask = materials ? entry >> 4 : mask;
            int texture = materials ? table[(entry & 15) - 1] : raster.previewTexture(0);
            long atlas = (flags[0] & 1) != 0 ? texture(texture) : 0;
            if (atlas < 0) return skip("atlas");
            draws.putLong(meshes.get(mesh).handle).putLong(atlas).putInt(materialMask).putInt(0).putLong(lightmap);
            for (int i = 0; i < 16; i++) draws.putFloat(matrices.get(tag * 16 + i));
        }
        draws.flip();
        parts = meshesInOrder.size();
        vertices = vertexCount;
        return true;
    }

    private boolean skipped;

    static MetalTerrainPreview open(RenderContext context, GameRenderState state, GameRasterState raster) {
        if (System.getenv("MCGL_METAL_CAPTURE_FILE") != null || System.getenv("MCGL_METAL_LIVE_LIBRARY") != null) return null;
        String library = System.getenv("MCGL_METAL_TERRAIN_LIBRARY");
        if (library == null || library.isEmpty()) return null;
        try {
            System.load(library);
            return new MetalTerrainPreview(context, state, raster);
        } catch (RuntimeException | LinkageError error) {
            System.out.println("[MCGL Metal terrain] unavailable: " + error.getMessage());
            return null;
        }
    }

    private MetalTerrainPreview(RenderContext context, GameRenderState state, GameRasterState raster) {
        this.context = context;
        this.state = state;
        this.raster = raster;
        scene = create(visible);
        if (scene == 0) throw new IllegalStateException("No Metal terrain context");
        System.out.println("[MCGL Metal terrain] " + (visible ? "visible opaque batch enabled" : "live shadow enabled; offscreen diagnostic"));
    }

    private static final class CachedMesh {
        final long handle, bytes;
        CachedMesh(long handle, long bytes) { this.handle = handle; this.bytes = bytes; }
    }

    private static final class CachedTexture {
        final long handle, bytes;
        final int base;
        final int[] parameters;
        CachedTexture(long handle, long bytes, int base, int[] parameters) {
            this.handle = handle; this.bytes = bytes; this.base = base; this.parameters = parameters;
        }
    }

    void remember(Mesh mesh, MeshData data) {
        if (scene == 0 || samples >= MAX_SAMPLES) return;
        if (!GameTerrainFormat.LAYOUT.equals(data.layout())) return;
        ByteBuffer vertexData = data.vertices().slice(), indexData = data.indices().bytes().slice();
        long bytes = (long)vertexData.remaining() + indexData.remaining();
        if (meshBytes + bytes > MESH_BUDGET) return;
        try {
            long handle = uploadMesh(scene, vertexData, indexData, data.indices().count(), data.indices().type().bytes);
            if (handle == 0) throw new IllegalStateException("Metal mesh upload failed");
            meshes.put(mesh, new CachedMesh(handle, bytes));
            meshBytes += bytes;
        } catch (RuntimeException | LinkageError error) { disable(error); }
    }

    void replace(Mesh old, Mesh replacement) {
        CachedMesh cached = meshes.remove(old);
        if (cached != null) meshes.put(replacement, cached);
    }

    void forget(Mesh mesh) {
        CachedMesh cached = meshes.remove(mesh);
        if (cached != null) {
            meshBytes -= cached.bytes;
            releaseResource(cached.handle);
        }
    }

    boolean begin(MeshArena arena, List<Mesh> meshesInOrder, int mask, FloatBuffer matrices, IntBuffer inputs,
                  int[] table, boolean materials, long vertexCount) {
        long frame = context.completedFrames();
        if (visible || scene == 0 || samples >= MAX_SAMPLES || frame < nextFrame
                || meshesInOrder.size() < 4 || vertexCount < minVertices) return false;
        nextFrame = frame + interval;
        try {
            if (GL15C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL15C.GL_CURRENT_QUERY) != 0) return skip("GPU timer already in use");
            if (!prepare(arena, meshesInOrder, mask, matrices, inputs, table, materials, vertexCount)) return false;
            query = GL15C.glGenQueries();
            if (query == 0) return skip("GPU timer unavailable");
            GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, query);
            started = System.nanoTime();
            return true;
        } catch (RuntimeException | LinkageError error) {
            disable(error);
            return false;
        } finally {
            if (query == 0) releaseTextures();
        }
    }

    private boolean skip(String reason) {
        if (!skipped) {
            skipped = true;
            System.out.println("[MCGL Metal terrain] waiting for compatible batch: " + reason);
        }
        return false;
    }

    void finish(boolean drawn) {
        long glCpuWall = System.nanoTime() - started;
        GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        try {
            if (!drawn) return;
            long glNanos = GL33C.glGetQueryObjectui64(query, GL15C.GL_QUERY_RESULT);
            ByteBuffer result = validate ? direct(Math.multiplyExact(width * height, 8)) : null;
            double[] metal = replay(scene, width, height, draws, parts, uniform, flags, result, false);
            if (result != null) compare(result);
            samples++;
            System.out.println(String.format(Locale.ROOT,
                    "[MCGL Metal terrain] sample=%d frame=%d parts=%d vertices=%d resolution=%dx%d blend=%b glSubmitWallMs=%.3f glGpuMs=%.3f metalSubmitWallMs=%.3f metalGpuMs=%.3f",
                    samples, context.completedFrames(), parts, vertices, width, height, flags[6] != 0,
                    glCpuWall / 1e6, glNanos == 0 ? Double.NaN : glNanos / 1e6, metal[0], metal[1]));
            if (samples == MAX_SAMPLES) close();
        } catch (RuntimeException | LinkageError error) {
            disable(error);
        } finally {
            GL15C.glDeleteQueries(query);
            query = 0;
            releaseTextures();
        }
    }

    private void compare(ByteBuffer metal) {
        int plane = width * height * 4;
        ByteBuffer color = direct(plane), depth = direct(plane);
        GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, color);
        GL11C.glReadPixels(0, 0, width, height, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, depth);
        int worst = 0, coverage = 0;
        float depthWorst = 0;
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
            int gl = ((height - 1 - row) * width + col) * 4, mt = (row * width + col) * 4;
            for (int channel = 0; channel < 4; channel++) {
                worst = Math.max(worst, Math.abs(Byte.toUnsignedInt(color.get(gl + channel))
                        - Byte.toUnsignedInt(metal.get(mt + channel))));
            }
            float z = depth.getFloat(gl);
            depthWorst = Math.max(depthWorst, Math.abs(z - metal.getFloat(plane + mt)));
            if (z < 1 || color.getInt(gl) != 0) coverage++;
        }
        if (coverage == 0 || worst > 2 || !(depthWorst <= 0.000001f))
            throw new IllegalStateException("terrain parity failed: coverage=" + coverage + " color=" + worst + " depth=" + depthWorst);
        System.out.printf(Locale.ROOT, "[MCGL Metal terrain] parity pixels=%d colorMax=%d depthMax=%.8f%n",
                coverage, worst, depthWorst);
    }

    private long texture(int name) {
        if (name == 0) return 0; // The existing renderer substitutes opaque white.
        if (renderTargets.contains(name)) return -1;
        CachedTexture previous = textures.get(name);
        if (previous != null) {
            int[] parameters = raster.originalTextureParameters(name);
            if (parameters == null || Arrays.equals(parameters, previous.parameters)) return previous.handle;
            textureInvalidated(name);
        }
        TerrainGLReadback.Texture data = TerrainGLReadback.texture(name, TEXTURE_BUDGET - textureBytes);
        if (data == null) return -1;
        long handle = uploadTexture(scene, data.width, data.height, data.levels, data.min, data.mag, data.wrapS, data.wrapT);
        if (handle == 0) return -1;
        textures.put(name, new CachedTexture(handle, data.bytes, data.base, new int[]{data.min, data.mag, data.wrapS, data.wrapT}));
        textureBytes += data.bytes;
        return handle;
    }

    void textureInvalidated(int name) {
        CachedTexture old = textures.remove(name);
        if (old == null) return;
        textureBytes -= old.bytes;
        releaseResource(old.handle);
    }

    void textureDeleted(int name) {
        renderTargets.remove(name);
        textureInvalidated(name);
    }

    void textureRenderTarget(int name) {
        if (!visible) return;
        renderTargets.add(name);
        textureInvalidated(name);
    }

    void textureChanged(int name, int level, int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        CachedTexture cached = textures.get(name);
        if (cached == null) return;
        boolean bgra = format == GL12C.GL_BGRA;
        int alignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        if (pixels != null && ((format == GL11C.GL_RGBA || bgra) && type == GL11C.GL_UNSIGNED_BYTE
                || bgra && type == GL12C.GL_UNSIGNED_INT_8_8_8_8_REV)
                && GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING) == 0
                && GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH) == 0
                && GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS) == 0
                && GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS) == 0
                && GL11C.glGetInteger(GL11C.GL_UNPACK_SWAP_BYTES) == 0
                && (alignment == 1 || alignment == 2 || alignment == 4 || width % 2 == 0)) {
            try {
                if (updateTexture(cached.handle, level - cached.base, x, y, width, height, pixels.slice(), bgra)) return;
            } catch (RuntimeException | LinkageError error) {
                disable(error);
                return;
            }
        }
        textureInvalidated(name);
    }

    private void releaseTextures() {
        for (CachedTexture texture : textures.values()) releaseResource(texture.handle);
        textures.clear();
        textureBytes = 0;
    }

    private void disable(Throwable error) {
        System.out.println("[MCGL Metal terrain] preview disabled: " + error.getMessage());
        close();
    }

    void close() {
        if (scene == 0) return;
        releaseTextures();
        for (CachedMesh mesh : meshes.values()) releaseResource(mesh.handle);
        meshes.clear();
        meshBytes = 0;
        releaseResource(scene);
        scene = 0;
    }

    private static ByteBuffer direct(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()); }
    private static native long create(boolean visible);
    private static native long uploadMesh(long scene, ByteBuffer vertices, ByteBuffer indices, int count, int indexBytes);
    private static native long uploadTexture(long scene, int width, int height, ByteBuffer[] levels,
                                             int min, int mag, int wrapS, int wrapT);
    private static native boolean updateTexture(long texture, int level, int x, int y, int width,
                                                int height, ByteBuffer pixels, boolean bgra);
    private static native double[] replay(long scene, int width, int height, ByteBuffer draws, int parts,
                                          FloatBuffer uniform, int[] flags, ByteBuffer readback, boolean visible);
    private static native void releaseResource(long resource);
}
