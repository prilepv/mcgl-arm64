package local.mcgl.render.backend;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import local.mcgl.render.IndexData;
import local.mcgl.render.MeshData;
import org.lwjgl.BufferUtils;
import org.lwjgl.bgfx.BGFXMemory;
import org.lwjgl.bgfx.BGFXTransientIndexBuffer;
import org.lwjgl.bgfx.BGFXTransientVertexBuffer;
import org.lwjgl.bgfx.BGFXVertexLayout;
import static org.lwjgl.bgfx.BGFX.*;

/** Consumes immutable MCGL terrain frames on the bgfx API thread. */
final class BgfxTerrainRenderer implements AutoCloseable {
    private static final long GEOMETRY_BUDGET = 320L << 20;
    private static final long PAGE_BYTES = 8L << 20;
    private static final int MAX_RETAINED_PAGES = 256;
    private static final int COMPACTION_HEADROOM = 32;
    private static final int RETAINED_FRAMES = 60;
    private static final long TARGET_PIXEL_BUDGET = 16L << 20;
    private static final int MAX_TARGETS = 32;
    private static final long[] REVERSED_DEPTH = {
            BGFX_STATE_DEPTH_TEST_NEVER, BGFX_STATE_DEPTH_TEST_GREATER, BGFX_STATE_DEPTH_TEST_EQUAL,
            BGFX_STATE_DEPTH_TEST_GEQUAL, BGFX_STATE_DEPTH_TEST_LESS, BGFX_STATE_DEPTH_TEST_NOTEQUAL,
            BGFX_STATE_DEPTH_TEST_LEQUAL, BGFX_STATE_DEPTH_TEST_ALWAYS
    };
    private static final long[] BLEND_FACTORS = {
            BGFX_STATE_BLEND_ZERO, BGFX_STATE_BLEND_ONE, BGFX_STATE_BLEND_SRC_COLOR, BGFX_STATE_BLEND_INV_SRC_COLOR,
            BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_INV_SRC_ALPHA, BGFX_STATE_BLEND_DST_COLOR, BGFX_STATE_BLEND_INV_DST_COLOR,
            BGFX_STATE_BLEND_DST_ALPHA, BGFX_STATE_BLEND_INV_DST_ALPHA, BGFX_STATE_BLEND_SRC_ALPHA_SAT
    };
    private static final long[] BLEND_EQUATIONS = {
            BGFX_STATE_BLEND_EQUATION_ADD, BGFX_STATE_BLEND_EQUATION_SUB, BGFX_STATE_BLEND_EQUATION_REVSUB,
            BGFX_STATE_BLEND_EQUATION_MIN, BGFX_STATE_BLEND_EQUATION_MAX
    };
    private static final int[] STENCIL_TESTS = {
            BGFX_STENCIL_TEST_NEVER, BGFX_STENCIL_TEST_LESS, BGFX_STENCIL_TEST_EQUAL, BGFX_STENCIL_TEST_LEQUAL,
            BGFX_STENCIL_TEST_GREATER, BGFX_STENCIL_TEST_NOTEQUAL, BGFX_STENCIL_TEST_GEQUAL, BGFX_STENCIL_TEST_ALWAYS
    };
    private static final int[] STENCIL_OPS = {
            BGFX_STENCIL_OP_FAIL_S_KEEP >>> BGFX_STENCIL_OP_FAIL_S_SHIFT,
            BGFX_STENCIL_OP_FAIL_S_ZERO >>> BGFX_STENCIL_OP_FAIL_S_SHIFT,
            BGFX_STENCIL_OP_FAIL_S_REPLACE >>> BGFX_STENCIL_OP_FAIL_S_SHIFT,
            BGFX_STENCIL_OP_FAIL_S_INCRSAT >>> BGFX_STENCIL_OP_FAIL_S_SHIFT,
            BGFX_STENCIL_OP_FAIL_S_DECRSAT >>> BGFX_STENCIL_OP_FAIL_S_SHIFT,
            BGFX_STENCIL_OP_FAIL_S_INVERT >>> BGFX_STENCIL_OP_FAIL_S_SHIFT,
            BGFX_STENCIL_OP_FAIL_S_INCR >>> BGFX_STENCIL_OP_FAIL_S_SHIFT,
            BGFX_STENCIL_OP_FAIL_S_DECR >>> BGFX_STENCIL_OP_FAIL_S_SHIFT
    };
    private static final int MATERIAL_VECTORS = 29;
    private int width, height;
    private final boolean gameWindow;
    private final int resetFlags;
    private final BGFXVertexLayout layout = BGFXVertexLayout.calloc();
    private final Map<MeshData, GpuMesh> meshes = new IdentityHashMap<>();
    private final Map<MetalWorldFrame.Texture, Short> textures = new IdentityHashMap<>();
    private final Map<MetalWorldFrame.Texture, GpuTarget> targets = new IdentityHashMap<>();
    private final FloatBuffer mask = BufferUtils.createFloatBuffer(4);
    private final FloatBuffer materialData = BufferUtils.createFloatBuffer(MATERIAL_VECTORS * 4);
    private final FloatBuffer offsetData = BufferUtils.createFloatBuffer(4);
    private final FloatBuffer modelData = BufferUtils.createFloatBuffer(16);
    private final BGFXTransientVertexBuffer[] streamVertices = new BGFXTransientVertexBuffer[2];
    private final BGFXTransientIndexBuffer[] streamIndices = new BGFXTransientIndexBuffer[2];
    private int[] streamFirstVertex, streamFirstIndex;
    int matrixUploads, stateUploads;
    long streamPages;
    private short offsetProgram = BGFX_INVALID_HANDLE, polygonOffset = BGFX_INVALID_HANDLE;
    private short program = BGFX_INVALID_HANDLE, terrain = BGFX_INVALID_HANDLE, drawState = BGFX_INVALID_HANDLE;
    private short atlasSampler = BGFX_INVALID_HANDLE, lightSampler = BGFX_INVALID_HANDLE, white = BGFX_INVALID_HANDLE;
    private MetalWorldFrame previous;
    private GpuMesh[] meshTable;
    private short[] textureTable;
    private float[][] materials, polygonOffsets;
    private long[] states;
    private int[] views, stencils;
    private int[][] clips;
    private long residentGeometry;
    private final List<GeometryPage> geometryPages = new ArrayList<>();
    long geometryRepacks, geometryCompactions;
    private long generation;
    long meshUploads, textureUploads, pageUploads, targetAllocations, retainedUploads;

    BgfxTerrainRenderer(Path shaders, int width, int height) throws IOException {
        this(shaders, width, height, false, BGFX_RESET_VSYNC);
    }

    BgfxTerrainRenderer(Path shaders, int width, int height, boolean gameWindow, int resetFlags) throws IOException {
        this.width = width;
        this.height = height;
        this.gameWindow = gameWindow;
        this.resetFlags = resetFlags;
        try {
            if (bgfx_get_renderer_type() != BGFX_RENDERER_TYPE_METAL)
                throw new IllegalStateException("Terrain rendering requires bgfx Metal");
            if (gameWindow) {
                for (int slot = 0; slot < streamVertices.length; slot++) {
                    streamVertices[slot] = BGFXTransientVertexBuffer.calloc();
                    streamIndices[slot] = BGFXTransientIndexBuffer.calloc();
                }
            }
            short vs = shader(shaders.resolve("vs_terrain.bin"));
            short fs;
            try { fs = shader(shaders.resolve("fs_terrain.bin")); }
            catch (IOException | RuntimeException error) { bgfx_destroy_shader(vs); throw error; }
            program = valid(bgfx_create_program(vs, fs, true));
            Path offsetShader = shaders.resolve("fs_terrain_offset.bin");
            if (Files.isRegularFile(offsetShader)) {
                short offsetVs = shader(shaders.resolve("vs_terrain.bin"));
                short offsetFs;
                try { offsetFs = shader(offsetShader); }
                catch (IOException | RuntimeException error) { bgfx_destroy_shader(offsetVs); throw error; }
                offsetProgram = valid(bgfx_create_program(offsetVs, offsetFs, true));
                polygonOffset = valid(bgfx_create_uniform("u_polygonOffset", BGFX_UNIFORM_TYPE_VEC4, 1));
            }
            terrain = valid(bgfx_create_uniform("u_terrain", BGFX_UNIFORM_TYPE_VEC4, MATERIAL_VECTORS));
            drawState = valid(bgfx_create_uniform("u_draw", BGFX_UNIFORM_TYPE_VEC4, 1));
            atlasSampler = valid(bgfx_create_uniform("s_atlas", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            lightSampler = valid(bgfx_create_uniform("s_lightmap", BGFX_UNIFORM_TYPE_SAMPLER, 1));
            vertexLayout(layout);
            ByteBuffer pixel = ByteBuffer.allocateDirect(4);
            pixel.putInt(-1).flip();
            white = texture(new MetalWorldFrame.Texture(1, 1, 9728, 9728, 33071, 33071, new ByteBuffer[]{pixel}));
            bgfx_set_view_mode(0, BGFX_VIEW_MODE_SEQUENTIAL);
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }

    static boolean supported(MetalWorldFrame frame) {
        return supported(frame, activeTargets(frame));
    }

    private static boolean supported(MetalWorldFrame frame, Set<MetalWorldFrame.Texture> active) {
        if (frame.skipped != 0 || frame.passes.isEmpty()) return false;
        for (MetalWorldFrame.Pass pass : frame.passes) {
            int[] flags = pass.raster;
            if ((flags[0] & ~15) != 0 || flags[4] < 0 || flags[4] >= REVERSED_DEPTH.length
                    || (flags[9] & ~3) != 0) return false;
            if (flags[10] != 0 && (flags[11] >>> 16) != 255 && (flags[11] >>> 16) != 0) return false;
            int blend = flags[6];
            if (blend != 0 && blend != 1 && ((blend & 1 << 24) == 0 || (blend & 15) > 10
                    || (blend >> 4 & 15) > 10 || (blend >> 8 & 15) > 10 || (blend >> 12 & 15) > 10
                    || (blend >> 16 & 7) > 4 || (blend >> 19 & 7) > 4)) return false;
        }
        for (MetalWorldFrame.Texture texture : frame.textures) {
            if (!supportedWrap(texture.wrapS) || !supportedWrap(texture.wrapT)) return false;
        }
        long pixels = 0;
        for (MetalWorldFrame.Texture target : active) pixels += targetPixels(target);
        return active.size() <= MAX_TARGETS && pixels <= TARGET_PIXEL_BUDGET;
    }

    private static boolean supportedWrap(int mode) { return mode == 33071 || mode == 33648 || mode == 10497; }

    boolean draw(MetalWorldFrame frame, String screenshot) {
        if (frame != previous) {
            Set<MetalWorldFrame.Texture> active = activeTargets(frame);
            if (!supported(frame, active)) return false;
            prepare(frame, active);
        }
        if (gameWindow) {
            streamGeometry(frame, IndexData.Type.UINT16, 0);
            streamGeometry(frame, IndexData.Type.UINT32, 1);
        }
        matrixUploads = stateUploads = 0;
        int discard = BGFX_DISCARD_ALL & ~BGFX_DISCARD_TRANSFORM;
        int keepRun = discard & ~(BGFX_DISCARD_STATE | BGFX_DISCARD_BINDINGS);
        for (int p = 0; p < frame.passes.size(); p++) {
            int[] clip = clips[p];
            List<MetalWorldFrame.Draw> draws = frame.passes.get(p).draws;
            if (draws.isEmpty() || clip != null && (clip[2] <= 0 || clip[3] <= 0)) {
                if (p == 0 || views[p] != views[p - 1]) bgfx_touch(views[p]);
                continue;
            }
            float[] offset = polygonOffsets[p];
            short passProgram = offset == null ? program : offsetProgram;
            materialData.put(materials[p]).flip();
            if (offset != null) offsetData.put(offset).flip();
            float[] lastModel = null;
            int first = 0;
            while (first < draws.size()) {
                MetalWorldFrame.Draw state = draws.get(first);
                int end = first + 1;
                while (end < draws.size()) {
                    MetalWorldFrame.Draw next = draws.get(end);
                    if (next.mask != state.mask || next.atlas != state.atlas || next.lightmap != state.lightmap) break;
                    end++;
                }
                // Sequential draws with matching state can share uniforms and bindings.
                mask.put(0, state.mask);
                mask.put(1, renderTarget(frame, state.atlas) ? 1 : 0);
                mask.put(2, renderTarget(frame, state.lightmap) ? 1 : 0);
                bgfx_set_uniform(terrain, materialData, MATERIAL_VECTORS);
                bgfx_set_uniform(drawState, mask, 1);
                if (offset != null) bgfx_set_uniform(polygonOffset, offsetData, 1);
                bgfx_set_texture(0, atlasSampler, textureTable[state.atlas], -1);
                bgfx_set_texture(1, lightSampler, textureTable[state.lightmap], -1);
                long topology = (state.mask & MetalWorldFrame.Draw.LINE) != 0 ? BGFX_STATE_PT_LINES : 0;
                bgfx_set_state(states[p] | topology, 0);
                if (clip != null) bgfx_set_scissor(clip[0], clip[1], clip[2], clip[3]);
                if (stencils[p] != BGFX_STENCIL_NONE) bgfx_set_stencil(stencils[p], BGFX_STENCIL_NONE);
                stateUploads++;

                for (int i = first; i < end; i++) {
                    MetalWorldFrame.Draw draw = draws.get(i);
                    GpuMesh mesh = meshTable[draw.mesh];
                    if (!Arrays.equals(lastModel, draw.model)) {
                        modelData.put(draw.model).flip();
                        bgfx_set_transform(modelData);
                        modelData.clear();
                        lastModel = draw.model;
                        matrixUploads++;
                    }
                    if (mesh != null) {
                        bgfx_set_vertex_buffer(0, mesh.page.vertices, mesh.firstVertex, mesh.vertexCount);
                        bgfx_set_index_buffer(mesh.page.indices, mesh.firstIndex, mesh.indexCount);
                    } else {
                        MeshData data = frame.meshes.get(draw.mesh);
                        int slot = data.indices().type() == IndexData.Type.UINT16 ? 0 : 1;
                        bgfx_set_transient_vertex_buffer(0, streamVertices[slot], streamFirstVertex[draw.mesh], data.vertexCount());
                        bgfx_set_transient_index_buffer(streamIndices[slot], streamFirstIndex[draw.mesh], data.indices().count());
                    }
                    bgfx_submit(views[p], passProgram, 0, i + 1 == end ? discard : keepRun);
                }
                first = end;
            }
            materialData.clear();
            offsetData.clear();
        }
        if (screenshot != null) bgfx_request_screen_shot(BGFX_INVALID_HANDLE, screenshot);
        bgfx_frame(BGFX_FRAME_NONE);
        return true;
    }

    private static boolean renderTarget(MetalWorldFrame frame, int index) {
        return index != 0 && frame.textures.get(index - 1).renderTarget();
    }

    private void prepare(MetalWorldFrame frame, Set<MetalWorldFrame.Texture> active) {
        generation++;
        retainGeometry(frame);
        retireUnused(textures, frame.textures, org.lwjgl.bgfx.BGFX::bgfx_destroy_texture);
        prepareTargets(active);
        uploadMissing(frame, IndexData.Type.UINT16, true);
        uploadMissing(frame, IndexData.Type.UINT32, true);
        if (!gameWindow) {
            uploadMissing(frame, IndexData.Type.UINT16, false);
            uploadMissing(frame, IndexData.Type.UINT32, false);
        } else if (streamFirstVertex == null || streamFirstVertex.length < frame.meshes.size()) {
            streamFirstVertex = new int[frame.meshes.size()];
            streamFirstIndex = new int[frame.meshes.size()];
        }
        meshTable = new GpuMesh[frame.meshes.size()];
        for (int i = 0; i < meshTable.length; i++) meshTable[i] = meshes.get(frame.meshes.get(i));
        textureTable = new short[frame.textures.size() + 1];
        textureTable[0] = white;
        for (int i = 1; i < textureTable.length; i++) {
            MetalWorldFrame.Texture image = frame.textures.get(i - 1);
            textureTable[i] = image.renderTarget() ? targets.get(image).color : textures.computeIfAbsent(image, key -> {
                short handle = texture(key);
                textureUploads++;
                return handle;
            });
        }
        materials = new float[frame.passes.size()][];
        states = new long[materials.length];
        polygonOffsets = new float[materials.length][];
        views = new int[materials.length];
        stencils = new int[materials.length];
        clips = new int[materials.length][];
        if (gameWindow && (width != frame.width || height != frame.height)) {
            width = frame.width;
            height = frame.height;
            bgfx_reset(width, height, resetFlags, BGFX_TEXTURE_FORMAT_COUNT);
        }
        double scale = Math.min((double)width / frame.width, (double)height / frame.height);
        int w = Math.max(1, (int)Math.round(frame.width * scale)), h = Math.max(1, (int)Math.round(frame.height * scale));
        int clear = channel(frame.clear[0]) << 24 | channel(frame.clear[1]) << 16 | channel(frame.clear[2]) << 8 | 255;
        int view = -1;
        boolean screenStarted = false;
        MetalWorldFrame.Texture previousTarget = null;
        for (int i = 0; i < materials.length; i++) {
            MetalWorldFrame.Pass pass = frame.passes.get(i);
            MetalWorldFrame.Texture target = pass.target;
            int targetWidth = target == null ? frame.width : target.width;
            int targetHeight = target == null ? frame.height : target.height;
            materials[i] = material(pass, targetWidth, targetHeight);
            clips[i] = clip(pass, targetWidth, targetHeight);
            int[] flags = pass.raster;
            states[i] = state(flags);
            if (offsetProgram != BGFX_INVALID_HANDLE && (pass.polygonFactor != 0 || pass.polygonUnits != 0))
                polygonOffsets[i] = new float[]{pass.polygonFactor, pass.polygonUnits, 0, 0};
            stencils[i] = stencil(flags);
            if (i == 0 || target != previousTarget || flags[9] != 0 || pass.targetClear != 0) {
                view++;
                int clearFlags = 0, rgba = clear;
                float depth = frame.depthClear;
                if (target == null) {
                    if (!screenStarted) clearFlags = BGFX_CLEAR_COLOR | BGFX_CLEAR_DEPTH | BGFX_CLEAR_STENCIL;
                    if ((flags[9] & 1) != 0) {
                        clearFlags |= BGFX_CLEAR_DEPTH;
                        depth = pass.depthClear;
                    }
                    if ((flags[9] & 2) != 0) clearFlags |= BGFX_CLEAR_STENCIL;
                    screenStarted = true;
                    bgfx_set_view_frame_buffer(view, BGFX_INVALID_HANDLE);
                    bgfx_set_view_rect(view, (width - w) / 2, (height - h) / 2, w, h);
                } else {
                    if ((pass.targetClear & 0x4000) != 0) clearFlags |= BGFX_CLEAR_COLOR;
                    if ((pass.targetClear & 0x0100) != 0) clearFlags |= BGFX_CLEAR_DEPTH;
                    if ((pass.targetClear & 0x0400) != 0) clearFlags |= BGFX_CLEAR_STENCIL;
                    rgba = pass.targetClearColor;
                    depth = pass.depthClear;
                    bgfx_set_view_frame_buffer(view, targets.get(target).framebuffer);
                    bgfx_set_view_rect(view, 0, 0, targetWidth, targetHeight);
                }
                bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
                bgfx_set_view_clear(view, clearFlags, rgba, depth, flags[13]);
            }
            views[i] = view;
            previousTarget = target;
        }
        previous = frame;
    }

    private static long meshBytes(MeshData mesh) {
        return (long)mesh.vertexCount() * mesh.layout().stride() + (long)mesh.indices().count() * mesh.indices().type().bytes;
    }

    int residentPages() { return geometryPages.size(); }

    private void streamGeometry(MetalWorldFrame frame, IndexData.Type type, int slot) {
        int vertices = 0, indices = 0, count = 0;
        for (int i = 0; i < meshTable.length; i++) {
            if (meshTable[i] != null) continue;
            MeshData data = frame.meshes.get(i);
            if (data.indices().type() != type) continue;
            if (!data.layout().equals(GameTerrainFormat.LAYOUT)) throw new IllegalArgumentException("Unexpected MCGL terrain layout");
            streamFirstVertex[i] = vertices;
            streamFirstIndex[i] = indices;
            vertices += data.vertexCount();
            indices += data.indices().count();
            count++;
        }
        if (count == 0) return;

        BGFXTransientVertexBuffer vertex = streamVertices[slot];
        BGFXTransientIndexBuffer index = streamIndices[slot];
        if (vertices > 0 && indices > 0 && bgfx_alloc_transient_buffers(vertex, layout, vertices, index,
                indices, type == IndexData.Type.UINT32)) {
            ByteBuffer vertexData = vertex.data(), indexData = index.data();
            for (int i = 0; i < meshTable.length; i++) {
                if (meshTable[i] != null) continue;
                MeshData data = frame.meshes.get(i);
                if (data.indices().type() != type) continue;
                vertexData.put(data.vertices());
                indexData.put(data.indices().bytes());
                meshUploads++;
            }
            streamPages++;
            return;
        }

        // Large frames can exceed bgfx's per-frame stream budget. Keep the existing upload path.
        uploadMissing(frame, type, false);
        for (int i = 0; i < meshTable.length; i++) {
            if (meshTable[i] == null && frame.meshes.get(i).indices().type() == type)
                meshTable[i] = meshes.get(frame.meshes.get(i));
        }
    }

    private void retainGeometry(MetalWorldFrame frame) {
        long missing = 0;
        for (MeshData mesh : frame.meshes) {
            GpuMesh gpu = meshes.get(mesh);
            if (gpu == null) missing += meshBytes(mesh);
            else gpu.page.lastUse = generation;
        }
        Iterator<GeometryPage> pages = geometryPages.iterator();
        while (pages.hasNext()) {
            GeometryPage page = pages.next();
            if (page.lastUse != generation && (!page.retained || generation - page.lastUse > RETAINED_FRAMES)) {
                releasePage(page);
                pages.remove();
            }
        }
        long budget = gameWindow ? 512L << 20 : GEOMETRY_BUDGET;
        if (missing == 0 || residentGeometry + missing <= budget && geometryPages.size() < MAX_RETAINED_PAGES) return;

        geometryPages.sort((a, b) -> Long.compare(a.lastUse, b.lastUse));
        pages = geometryPages.iterator();
        while (pages.hasNext() && (residentGeometry + missing > budget || geometryPages.size() >= MAX_RETAINED_PAGES)) {
            GeometryPage page = pages.next();
            if (page.lastUse == generation) break;
            releasePage(page);
            pages.remove();
        }
        if (residentGeometry + missing > budget || geometryPages.size() >= MAX_RETAINED_PAGES)
            compactGeometry(frame, missing, budget);
    }

    private void compactGeometry(MetalWorldFrame frame, long missing, long budget) {
        for (GeometryPage page : geometryPages) page.activeBytes = 0;
        for (MeshData mesh : frame.meshes) {
            GpuMesh gpu = meshes.get(mesh);
            if (gpu != null) gpu.page.activeBytes += meshBytes(mesh);
        }
        // The next upload combines the visible meshes from these sparse pages
        // with newly visible geometry. Leave most of the live cache in place.
        geometryPages.sort((a, b) -> Long.compare(a.activeBytes, b.activeBytes));
        int targetPages = geometryPages.size() >= MAX_RETAINED_PAGES
                ? MAX_RETAINED_PAGES - COMPACTION_HEADROOM : geometryPages.size();
        Iterator<GeometryPage> pages = geometryPages.iterator();
        while (pages.hasNext() && (geometryPages.size() > targetPages || residentGeometry + missing > budget)) {
            GeometryPage page = pages.next();
            missing += page.activeBytes;
            releasePage(page);
            pages.remove();
        }
        if (geometryPages.isEmpty()) geometryRepacks++;
        else geometryCompactions++;
    }

    private void uploadMissing(MetalWorldFrame frame, IndexData.Type type, boolean retained) {
        List<MeshData> pending = new ArrayList<>();
        long bytes = 0;
        for (int i = 0; i < frame.meshes.size(); i++) {
            MeshData mesh = frame.meshes.get(i);
            if (frame.retainedMeshes.get(i) != retained || meshes.containsKey(mesh) || mesh.indices().type() != type) continue;
            long size = meshBytes(mesh);
            if (!pending.isEmpty() && bytes + size > PAGE_BYTES) {
                uploadPage(pending, type, bytes, retained);
                pending.clear();
                bytes = 0;
            }
            pending.add(mesh);
            bytes += size;
        }
        if (!pending.isEmpty()) uploadPage(pending, type, bytes, retained);
    }

    private void uploadPage(List<MeshData> pending, IndexData.Type type, long bytes, boolean retained) {
        int stride = GameTerrainFormat.LAYOUT.stride(), vertexBytes = 0, indexBytes = 0;
        for (MeshData mesh : pending) {
            if (!mesh.layout().equals(GameTerrainFormat.LAYOUT)) throw new IllegalArgumentException("Unexpected MCGL terrain layout");
            vertexBytes += mesh.vertexCount() * stride;
            indexBytes += mesh.indices().count() * type.bytes;
        }
        BGFXMemory vertexMemory = bgfx_alloc(vertexBytes), indexMemory = bgfx_alloc(indexBytes);
        ByteBuffer vertexData = vertexMemory.data(), indexData = indexMemory.data();
        for (MeshData mesh : pending) {
            vertexData.put(mesh.vertices());
            indexData.put(mesh.indices().bytes());
        }
        short vertices = valid(bgfx_create_vertex_buffer(vertexMemory, layout, BGFX_BUFFER_NONE));
        int flags = type == IndexData.Type.UINT32 ? BGFX_BUFFER_INDEX32 : BGFX_BUFFER_NONE;
        short indices;
        try {
            indices = valid(bgfx_create_index_buffer(indexMemory, flags));
        } catch (RuntimeException error) {
            bgfx_destroy_vertex_buffer(vertices);
            throw error;
        }
        GeometryPage page = new GeometryPage(vertices, indices, pending.toArray(new MeshData[0]), bytes, retained, generation);
        residentGeometry += bytes;
        geometryPages.add(page);
        pageUploads++;
        int firstVertex = 0, firstIndex = 0;
        for (MeshData mesh : pending) {
            int vertexCount = mesh.vertexCount(), indexCount = mesh.indices().count();
            meshes.put(mesh, new GpuMesh(page, firstVertex, vertexCount, firstIndex, indexCount));
            firstVertex += vertexCount;
            firstIndex += indexCount;
            meshUploads++;
            if (retained) retainedUploads++;
        }
    }

    private static final class GeometryPage {
        final short vertices, indices;
        final long bytes;
        final boolean retained;
        long lastUse, activeBytes;
        final MeshData[] meshes;
        GeometryPage(short vertices, short indices, MeshData[] meshes, long bytes, boolean retained, long lastUse) {
            this.vertices = vertices; this.indices = indices;
            this.meshes = meshes; this.bytes = bytes;
            this.retained = retained; this.lastUse = lastUse;
        }
        void close() {
            bgfx_destroy_index_buffer(indices);
            bgfx_destroy_vertex_buffer(vertices);
        }
    }

    private static final class GpuMesh {
        final GeometryPage page;
        final int firstVertex, vertexCount, firstIndex, indexCount;
        GpuMesh(GeometryPage page, int firstVertex, int vertexCount, int firstIndex, int indexCount) {
            this.page = page; this.firstVertex = firstVertex; this.vertexCount = vertexCount;
            this.firstIndex = firstIndex; this.indexCount = indexCount;
        }
    }

    private void releasePage(GeometryPage page) {
        for (MeshData data : page.meshes) {
            GpuMesh mesh = meshes.get(data);
            if (mesh != null && mesh.page == page) meshes.remove(data);
        }
        page.close();
        residentGeometry -= page.bytes;
    }

    private void releaseMeshes() {
        for (GeometryPage page : geometryPages) page.close();
        geometryPages.clear();
        meshes.clear();
        residentGeometry = 0;
    }

    private static <K, V> void retireUnused(Map<K, V> cache, List<K> active, java.util.function.Consumer<V> destroy) {
        Set<K> current = Collections.newSetFromMap(new IdentityHashMap<K, Boolean>());
        current.addAll(active);
        Iterator<Map.Entry<K, V>> entries = cache.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<K, V> entry = entries.next();
            if (!current.contains(entry.getKey())) {
                destroy.accept(entry.getValue());
                entries.remove();
            }
        }
    }

    private void prepareTargets(Set<MetalWorldFrame.Texture> active) {
        if (active.isEmpty() && targets.isEmpty()) return;
        long pixels = 0;
        int count = active.size();
        for (MetalWorldFrame.Texture image : active) pixels += targetPixels(image);
        List<MetalWorldFrame.Texture> inactive = new ArrayList<>();
        Iterator<Map.Entry<MetalWorldFrame.Texture, GpuTarget>> entries = targets.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<MetalWorldFrame.Texture, GpuTarget> entry = entries.next();
            if (active.contains(entry.getKey())) continue;
            if (generation - entry.getValue().lastUse > RETAINED_FRAMES) {
                entry.getValue().close();
                entries.remove();
            } else {
                inactive.add(entry.getKey());
                pixels += targetPixels(entry.getKey());
                count++;
            }
        }
        if (pixels > TARGET_PIXEL_BUDGET || count > MAX_TARGETS) {
            inactive.sort((a, b) -> Long.compare(targets.get(a).lastUse, targets.get(b).lastUse));
            for (MetalWorldFrame.Texture image : inactive) {
                if (pixels <= TARGET_PIXEL_BUDGET && count <= MAX_TARGETS) break;
                targets.remove(image).close();
                pixels -= targetPixels(image);
                count--;
            }
        }
        for (MetalWorldFrame.Texture image : active)
            targets.computeIfAbsent(image, this::createTarget).lastUse = generation;
    }

    private static long targetPixels(MetalWorldFrame.Texture image) {
        return (long)image.width * image.height;
    }

    private static Set<MetalWorldFrame.Texture> activeTargets(MetalWorldFrame frame) {
        Set<MetalWorldFrame.Texture> active = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MetalWorldFrame.Pass pass : frame.passes) if (pass.target != null) active.add(pass.target);
        for (MetalWorldFrame.Texture texture : frame.textures) if (texture.renderTarget()) active.add(texture);
        return active;
    }

    private GpuTarget createTarget(MetalWorldFrame.Texture image) {
        short color = valid(bgfx_create_texture_2d(image.width, image.height, false, 1,
                BGFX_TEXTURE_FORMAT_RGBA8, BGFX_TEXTURE_RT | samplerFlags(image), null, 0));
        short depth = BGFX_INVALID_HANDLE;
        try {
            depth = valid(bgfx_create_texture_2d(image.width, image.height, false, 1,
                    BGFX_TEXTURE_FORMAT_D32FS8, BGFX_TEXTURE_RT_WRITE_ONLY, null, 0));
            short framebuffer = valid(bgfx_create_frame_buffer_from_handles(new short[]{color, depth}, true));
            targetAllocations++;
            return new GpuTarget(color, framebuffer);
        } catch (RuntimeException failure) {
            if (depth != BGFX_INVALID_HANDLE) bgfx_destroy_texture(depth);
            bgfx_destroy_texture(color);
            throw failure;
        }
    }

    private static final class GpuTarget {
        final short color, framebuffer;
        long lastUse;
        GpuTarget(short color, short framebuffer) { this.color = color; this.framebuffer = framebuffer; }
        void close() { bgfx_destroy_frame_buffer(framebuffer); }
    }

    @Override public void close() {
        releaseMeshes();
        for (GpuTarget target : targets.values()) target.close();
        targets.clear();
        for (short texture : textures.values()) bgfx_destroy_texture(texture);
        textures.clear();
        if (white != BGFX_INVALID_HANDLE) bgfx_destroy_texture(white);
        if (lightSampler != BGFX_INVALID_HANDLE) bgfx_destroy_uniform(lightSampler);
        if (atlasSampler != BGFX_INVALID_HANDLE) bgfx_destroy_uniform(atlasSampler);
        if (drawState != BGFX_INVALID_HANDLE) bgfx_destroy_uniform(drawState);
        if (terrain != BGFX_INVALID_HANDLE) bgfx_destroy_uniform(terrain);
        if (polygonOffset != BGFX_INVALID_HANDLE) bgfx_destroy_uniform(polygonOffset);
        if (offsetProgram != BGFX_INVALID_HANDLE) bgfx_destroy_program(offsetProgram);
        if (program != BGFX_INVALID_HANDLE) bgfx_destroy_program(program);
        for (int slot = 0; slot < streamVertices.length; slot++) {
            if (streamVertices[slot] != null) streamVertices[slot].close();
            if (streamIndices[slot] != null) streamIndices[slot].close();
        }
        layout.close();
    }

    private static void vertexLayout(BGFXVertexLayout layout) {
        bgfx_vertex_layout_begin(layout, BGFX_RENDERER_TYPE_METAL);
        bgfx_vertex_layout_add(layout, BGFX_ATTRIB_POSITION, 3, BGFX_ATTRIB_TYPE_FLOAT, false, false);
        bgfx_vertex_layout_add(layout, BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT, false, false);
        bgfx_vertex_layout_add(layout, BGFX_ATTRIB_COLOR0, 4, BGFX_ATTRIB_TYPE_UINT8, true, false);
        bgfx_vertex_layout_add(layout, BGFX_ATTRIB_NORMAL, 4, BGFX_ATTRIB_TYPE_INT8, true, false);
        bgfx_vertex_layout_add(layout, BGFX_ATTRIB_TEXCOORD1, 2, BGFX_ATTRIB_TYPE_INT16, false, false);
        bgfx_vertex_layout_add(layout, BGFX_ATTRIB_COLOR1, 4, BGFX_ATTRIB_TYPE_UINT8, true, false);
        bgfx_vertex_layout_add(layout, BGFX_ATTRIB_TANGENT, 4, BGFX_ATTRIB_TYPE_INT8, true, false);
        bgfx_vertex_layout_end(layout);
        if (layout.stride() != GameTerrainFormat.LAYOUT.stride())
            throw new IllegalStateException("bgfx terrain stride does not match MCGL");
    }

    private static float[] material(MetalWorldFrame.Pass pass, int width, int height) {
        float[] material = Arrays.copyOf(pass.material, MATERIAL_VECTORS * 4);
        // Convert the projection before multiplying vertices to preserve far-depth precision.
        for (int column = 0; column < 16; column += 4)
            material[column + 2] = (material[column + 3] - material[column + 2]) * .5f;
        // Map the original viewport into the full drawable. Per-draw scissoring
        // clips it without opening another Metal render pass.
        if (pass.viewport != null) {
            int[] viewport = pass.viewport;
            float sx = (float)viewport[2] / width, sy = (float)viewport[3] / height;
            float tx = (2f * viewport[0] + viewport[2]) / width - 1;
            float ty = (2f * viewport[1] + viewport[3]) / height - 1;
            for (int column = 0; column < 16; column += 4) {
                float w = material[column + 3];
                material[column] = sx * material[column] + tx * w;
                material[column + 1] = sy * material[column + 1] + ty * w;
            }
        }
        for (int i = 0; i < 4; i++) material[112 + i] = pass.raster[i];
        return material;
    }

    private static int[] clip(MetalWorldFrame.Pass pass, int width, int height) {
        if (pass.viewport == null && pass.scissor == null) return null;
        long left = 0, bottom = 0, right = width, top = height;
        if (pass.viewport != null) {
            int[] box = pass.viewport;
            left = Math.max(left, box[0]); bottom = Math.max(bottom, box[1]);
            right = Math.min(right, (long)box[0] + box[2]); top = Math.min(top, (long)box[1] + box[3]);
        }
        if (pass.scissor != null) {
            int[] box = pass.scissor;
            left = Math.max(left, box[0]); bottom = Math.max(bottom, box[1]);
            right = Math.min(right, (long)box[0] + box[2]); top = Math.min(top, (long)box[1] + box[3]);
        }
        if (left >= right || bottom >= top) return new int[4];
        return new int[]{(int)left, height - (int)top, (int)(right - left), (int)(top - bottom)};
    }

    private static long state(int[] flags) {
        long state = REVERSED_DEPTH[flags[4]];
        int color = flags[12] == 0 ? 15 : flags[12] & 15;
        if ((color & 8) != 0) state |= BGFX_STATE_WRITE_R;
        if ((color & 4) != 0) state |= BGFX_STATE_WRITE_G;
        if ((color & 2) != 0) state |= BGFX_STATE_WRITE_B;
        if ((color & 1) != 0) state |= BGFX_STATE_WRITE_A;
        if (flags[5] != 0) state |= BGFX_STATE_WRITE_Z;
        int blend = flags[6];
        if (blend == 1) state |= BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_INV_SRC_ALPHA);
        else if (blend != 0) {
            state |= BGFX_STATE_BLEND_FUNC_SEPARATE(BLEND_FACTORS[blend & 15], BLEND_FACTORS[blend >> 4 & 15],
                    BLEND_FACTORS[blend >> 8 & 15], BLEND_FACTORS[blend >> 12 & 15]);
            state |= BGFX_STATE_BLEND_EQUATION_SEPARATE(BLEND_EQUATIONS[blend >> 16 & 7], BLEND_EQUATIONS[blend >> 19 & 7]);
        }
        if (flags[7] != 0) {
            boolean cullCCW = (flags[7] == 1) == (flags[8] != 0);
            state |= cullCCW ? BGFX_STATE_CULL_CCW : BGFX_STATE_CULL_CW;
        }
        return state;
    }

    private static int stencil(int[] flags) {
        int mode = flags[10];
        if (mode == 0) return BGFX_STENCIL_NONE;
        int state = STENCIL_TESTS[mode & 7] | BGFX_STENCIL_FUNC_REF(flags[11] & 255)
                | BGFX_STENCIL_FUNC_RMASK(flags[11] >> 8 & 255);
        if ((flags[11] >>> 16) == 0) return state | BGFX_STENCIL_OP_FAIL_S_KEEP | BGFX_STENCIL_OP_FAIL_Z_KEEP
                | BGFX_STENCIL_OP_PASS_Z_KEEP;
        return state | STENCIL_OPS[mode >> 3 & 7] << BGFX_STENCIL_OP_FAIL_S_SHIFT
                | STENCIL_OPS[mode >> 6 & 7] << BGFX_STENCIL_OP_FAIL_Z_SHIFT
                | STENCIL_OPS[mode >> 9 & 7] << BGFX_STENCIL_OP_PASS_Z_SHIFT;
    }

    private static long samplerFlags(MetalWorldFrame.Texture texture) {
        long flags = wrap(texture.wrapS, BGFX_SAMPLER_U_CLAMP, BGFX_SAMPLER_U_MIRROR)
                | wrap(texture.wrapT, BGFX_SAMPLER_V_CLAMP, BGFX_SAMPLER_V_MIRROR);
        if (texture.min == 9728 || texture.min == 9984 || texture.min == 9986) flags |= BGFX_SAMPLER_MIN_POINT;
        if (texture.mag == 9728) flags |= BGFX_SAMPLER_MAG_POINT;
        if (texture.min == 9984 || texture.min == 9985) flags |= BGFX_SAMPLER_MIP_POINT;
        return flags;
    }

    private static short texture(MetalWorldFrame.Texture texture) {
        int levels = texture.levels.length;
        int full = 32 - Integer.numberOfLeadingZeros(Math.max(texture.width, texture.height));
        long flags = samplerFlags(texture);
        int bytes = 0;
        for (ByteBuffer level : texture.levels) bytes += level.remaining();
        if (levels != 1 && levels != full) {
            // KTX 1 records the actual mip count, which create_texture_2d only accepts as a boolean.
            BGFXMemory ktx = bgfx_alloc(64 + levels * Integer.BYTES + bytes);
            ByteBuffer out = ktx.data().order(ByteOrder.LITTLE_ENDIAN);
            out.put(new byte[]{(byte)0xab, 'K', 'T', 'X', ' ', '1', '1', (byte)0xbb, 13, 10, 26, 10});
            out.putInt(0x04030201).putInt(0x1401).putInt(1).putInt(0x1908).putInt(0x8058).putInt(0x1908);
            out.putInt(texture.width).putInt(texture.height).putInt(0).putInt(0).putInt(1).putInt(levels).putInt(0);
            for (ByteBuffer level : texture.levels) out.putInt(level.remaining()).put(level.duplicate());
            return valid(bgfx_create_texture(ktx, flags, 0, null));
        }
        BGFXMemory pixels = bgfx_alloc(bytes);
        ByteBuffer storage = pixels.data();
        for (ByteBuffer level : texture.levels) storage.put(level.duplicate());
        return valid(bgfx_create_texture_2d(texture.width, texture.height, levels > 1, 1,
                BGFX_TEXTURE_FORMAT_RGBA8, flags, pixels, 0));
    }

    private static int wrap(int mode, int clamp, int mirror) {
        if (mode == 33071) return clamp;
        if (mode == 33648) return mirror;
        if (mode == 10497) return 0;
        throw new IllegalArgumentException("Unsupported capture wrap mode " + mode);
    }

    private static int channel(float value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 255);
    }

    private static short shader(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        return valid(bgfx_create_shader(bgfx_copy(buffer)));
    }

    private static short valid(short handle) {
        if (handle == BGFX_INVALID_HANDLE) throw new IllegalStateException("bgfx resource creation failed");
        return handle;
    }
}
