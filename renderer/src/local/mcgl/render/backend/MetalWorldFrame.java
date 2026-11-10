package local.mcgl.render.backend;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import local.mcgl.render.IndexData;
import local.mcgl.render.MeshData;
import local.mcgl.render.GameRenderState;

/** Bounded game command stream. Raw geometry and texels use Apple Silicon's little-endian layout. */
final class MetalWorldFrame {
    static final int MAX_DRAWS = 16384, MAX_PASSES = 4096, MAX_TEXTURES = 128;
    static final long MAX_GEOMETRY = 192L << 20, MAX_TEXTURE_BYTES = 32L << 20;
    private static final int MAGIC = 0x4d434d54, VERSION = 1;
    private static final long MAX_FILE = MAX_GEOMETRY + MAX_TEXTURE_BYTES + (8L << 20);
    int width, height, skipped, sceneSkipped, sceneDraws, textDraws, depthResets, stencilDraws, regionDraws;
    boolean terrainAttempted, menu, worldFromTarget;
    long uploadedTextureLevels, readbackTextureLevels;
    float depthClear;
    final float[] clear = new float[3];
    final List<MeshData> meshes = new ArrayList<MeshData>();
    final BitSet retainedMeshes = new BitSet();
    final List<Texture> textures = new ArrayList<Texture>();
    final List<Pass> passes = new ArrayList<Pass>();

    static final class Texture {
        final int width, height, min, mag, wrapS, wrapT;
        final ByteBuffer[] levels;
        Texture(int width, int height, int min, int mag, int wrapS, int wrapT, ByteBuffer[] levels) {
            this.width = width; this.height = height; this.min = min; this.mag = mag;
            this.wrapS = wrapS; this.wrapT = wrapT; this.levels = levels;
        }
        boolean renderTarget() { return levels.length == 0; }
    }

    static final class Pass {
        final float[] material = new float[GameRenderState.METAL_MATERIAL_FLOATS];
        final int[] raster = new int[14];
        int[] viewport, scissor;
        Texture target;
        int targetClear;
        int targetClearColor;
        float depthClear, polygonFactor, polygonUnits;
        final List<Draw> draws = new ArrayList<Draw>();
    }

    static final class Draw {
        static final int LINE = 512;
        final int mesh, atlas, lightmap, mask;
        final float[] model = new float[16];
        Draw(int mesh, int atlas, int lightmap, int mask) {
            this.mesh = mesh; this.atlas = atlas; this.lightmap = lightmap; this.mask = mask;
        }
    }

    int drawCount() {
        int count = 0;
        for (Pass pass : passes) count += pass.draws.size();
        return count;
    }

    void write(Path target) throws IOException {
        for (Pass pass : passes) if (pass.target != null) throw new IOException("Render targets require live playback");
        for (Texture texture : textures) if (texture.renderTarget()) throw new IOException("Render targets require live playback");
        Path temporary = Files.createTempFile(target.getParent(), ".mcgl-metal-", ".tmp");
        try {
            byte[] scratch = new byte[65536];
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(width); out.writeInt(height); out.writeInt(skipped);
                for (float value : clear) out.writeFloat(value);
                out.writeInt(meshes.size()); out.writeInt(textures.size()); out.writeInt(passes.size());
                for (MeshData mesh : meshes) {
                    out.writeInt(mesh.vertexCount());
                    out.writeInt(mesh.indices().count());
                    out.writeInt(mesh.indices().type().bytes);
                    writeBytes(out, mesh.vertices(), scratch);
                    writeBytes(out, mesh.indices().bytes(), scratch);
                }
                for (Texture texture : textures) {
                    out.writeInt(texture.width); out.writeInt(texture.height);
                    out.writeInt(texture.min); out.writeInt(texture.mag);
                    out.writeInt(texture.wrapS); out.writeInt(texture.wrapT);
                    out.writeInt(texture.levels.length);
                    for (ByteBuffer level : texture.levels) writeBytes(out, level, scratch);
                }
                for (Pass pass : passes) {
                    for (int i = 0; i < 64; i++) out.writeFloat(pass.material[i]);
                    for (int i = 0; i < 10; i++) out.writeInt(pass.raster[i]);
                    out.writeInt(pass.draws.size());
                    for (Draw draw : pass.draws) {
                        out.writeInt(draw.mesh); out.writeInt(draw.atlas); out.writeInt(draw.lightmap); out.writeInt(draw.mask);
                        for (float value : draw.model) out.writeFloat(value);
                    }
                }
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static MetalWorldFrame read(Path path) throws IOException {
        if (Files.size(path) > MAX_FILE) throw new IOException("Oversized terrain capture");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported terrain capture");
            MetalWorldFrame frame = new MetalWorldFrame();
            frame.width = bound(in.readInt(), 1, 5120, "width");
            frame.height = bound(in.readInt(), 1, 5120, "height");
            frame.skipped = bound(in.readInt(), 0, Integer.MAX_VALUE, "skipped");
            floats(in, frame.clear);
            int meshes = bound(in.readInt(), 1, MAX_DRAWS, "meshes");
            int textures = bound(in.readInt(), 0, MAX_TEXTURES, "textures");
            int passes = bound(in.readInt(), 1, MAX_PASSES, "passes");
            byte[] scratch = new byte[65536];
            long remaining = MAX_GEOMETRY;
            for (int i = 0; i < meshes; i++) {
                int vertices = bound(in.readInt(), 1, 4 * 1024 * 1024, "vertices");
                int indices = bound(in.readInt(), 3, 12 * 1024 * 1024, "indices");
                int indexBytes = in.readInt();
                if ((indexBytes != 2 && indexBytes != 4) || indices % 3 != 0) throw new IOException("Invalid index format");
                int vertexBytes = vertices * GameTerrainFormat.LAYOUT.stride();
                remaining -= vertexBytes + (long)indices * indexBytes;
                if (remaining < 0) throw new IOException("Terrain geometry budget exceeded");
                ByteBuffer vertexData = readBytes(in, vertexBytes, scratch);
                ByteBuffer indexData = readBytes(in, indices * indexBytes, scratch);
                IndexData index = indexBytes == 2 ? IndexData.unsignedShort(indexData.asShortBuffer())
                        : IndexData.unsignedInt(indexData.asIntBuffer());
                frame.meshes.add(new MeshData(GameTerrainFormat.LAYOUT, vertexData, index));
            }
            remaining = MAX_TEXTURE_BYTES;
            for (int i = 0; i < textures; i++) {
                int w = bound(in.readInt(), 1, 4096, "texture width");
                int h = bound(in.readInt(), 1, 4096, "texture height");
                int min = in.readInt(), mag = in.readInt(), wrapS = in.readInt(), wrapT = in.readInt();
                int count = bound(in.readInt(), 1, 32 - Integer.numberOfLeadingZeros(Math.max(w, h)), "mip levels");
                ByteBuffer[] levels = new ByteBuffer[count];
                for (int level = 0; level < count; level++) {
                    int bytes = Math.max(1, w >> level) * Math.max(1, h >> level) * 4;
                    remaining -= bytes;
                    if (remaining < 0) throw new IOException("Terrain texture budget exceeded");
                    levels[level] = readBytes(in, bytes, scratch);
                }
                frame.textures.add(new Texture(w, h, min, mag, wrapS, wrapT, levels));
            }
            int draws = 0;
            for (int i = 0; i < passes; i++) {
                Pass pass = new Pass();
                floats(in, pass.material, 64);
                for (int j = 0; j < 10; j++) pass.raster[j] = in.readInt();
                int count = bound(in.readInt(), 1, MAX_DRAWS - draws, "draws");
                draws += count;
                for (int j = 0; j < count; j++) {
                    Draw draw = new Draw(bound(in.readInt(), 0, meshes - 1, "mesh"),
                            bound(in.readInt(), 0, textures, "atlas"), bound(in.readInt(), 0, textures, "lightmap"),
                            bound(in.readInt(), 0, 127, "mask"));
                    floats(in, draw.model);
                    pass.draws.add(draw);
                }
                frame.passes.add(pass);
            }
            if (in.read() != -1) throw new IOException("Unexpected trailing terrain data");
            return frame;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid terrain mesh", error);
        }
    }

    private static int bound(int value, int min, int max, String label) throws IOException {
        if (value < min || value > max) throw new IOException("Invalid " + label + ": " + value);
        return value;
    }

    private static void floats(DataInputStream in, float[] values) throws IOException {
        floats(in, values, values.length);
    }

    private static void floats(DataInputStream in, float[] values, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            float value = in.readFloat();
            if (!Float.isFinite(value)) throw new IOException("Non-finite terrain input");
            values[i] = value;
        }
    }

    private static void writeBytes(DataOutputStream out, ByteBuffer input, byte[] scratch) throws IOException {
        ByteBuffer data = input.duplicate();
        while (data.hasRemaining()) {
            int size = Math.min(data.remaining(), scratch.length);
            data.get(scratch, 0, size);
            out.write(scratch, 0, size);
        }
    }

    private static ByteBuffer readBytes(DataInputStream in, int size, byte[] scratch) throws IOException {
        ByteBuffer data = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        while (data.hasRemaining()) {
            int count = Math.min(data.remaining(), scratch.length);
            in.readFully(scratch, 0, count);
            data.put(scratch, 0, count);
        }
        data.flip();
        return data;
    }
}
