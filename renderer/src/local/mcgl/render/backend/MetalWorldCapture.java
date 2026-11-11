package local.mcgl.render.backend;

import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Captures ordered game geometry and commands for Metal, without copying the game framebuffer. */
final class MetalWorldCapture {
    private static final long CACHE_BUDGET = 224L << 20;
    private static final long LIVE_CACHE_BUDGET = 320L << 20, SCENE_CACHE_BUDGET = 512L << 20;
    private static final long LIVE_FRAME_BUDGET = 256L << 20;
    private static final long READBACK_BUDGET = 4L << 20;
    private static final int READBACK_MESHES = 64;
    private static final int WORLD_CLEAR = GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT;
    private final Path target;
    private final MetalWorldLive live;
    private final boolean full;
    private final boolean gameWindow;
    private final MetalTextureUploads textureUploads;
    private final GameRenderState state;
    private final GameRasterState raster;
    private final Set<Integer> renderTargets;
    private final int warmup = Math.max(0, Integer.getInteger("mcgl.metal.capture.warmup", 90));
    private final long interval;
    private final long liveCacheBudget;
    private final boolean offscreen = Boolean.getBoolean("mcgl.metal.capture.offscreen");
    private final Map<Mesh, MeshData> resident = new LinkedHashMap<Mesh, MeshData>(16, .75f, true);
    private final IdentityHashMap<MeshData, Integer> ids = new IdentityHashMap<MeshData, Integer>();
    private final Map<Integer, Integer> textureIds = new HashMap<Integer, Integer>();
    private final Map<Integer, CachedTexture> cachedTextures = new HashMap<Integer, CachedTexture>();
    private final Map<Integer, MetalWorldFrame.Texture> gpuTargets = new HashMap<>();
    private final Set<MetalWorldFrame.Texture> terrainTargets = new HashSet<>();
    private boolean primaryHeader;
    private final Set<String> skippedReasons = new LinkedHashSet<String>();
    private final Set<String> sceneReasons = new LinkedHashSet<String>();
    private final int[] size = new int[2];
    private final double[] model = new double[16];
    private long residentBytes, geometryBytes, textureBytes, cachedTextureBytes, vertices, nextCapture, readbackBytes, sampleStart;
    private long uploadedTextureLevels, readbackTextureLevels;
    private int terrainFrames, draws, blendedDraws, heldFrames, clearMask, readbackMeshes, stencilValue;
    private int menuFrames;
    private boolean seen, sampleThisFrame, finished, opaque, depthReset, stencilReset, reportedRecovery, optionalPass;
    private boolean showingWorld;
    private boolean lateTerrainClear;
    private float firstDepthClear, passDepthClear;
    private MetalWorldFrame frame;

    static MetalWorldCapture open(GameRenderState state, GameRasterState raster, Set<Integer> renderTargets) {
        String name = System.getenv("MCGL_METAL_CAPTURE_FILE");
        String library = System.getenv("MCGL_METAL_LIVE_LIBRARY");
        String bgfxLibrary = System.getenv("MCGL_BGFX_LIVE_LIBRARY");
        try {
            if (bgfxLibrary != null && !bgfxLibrary.isEmpty()) {
                Path bgfx = Paths.get(bgfxLibrary).toAbsolutePath().normalize();
                long window = MetalGameTarget.cocoaWindow();
                if (Files.isRegularFile(bgfx)) return new MetalWorldCapture(null,
                        new MetalWorldLive(bgfx, window != 0, window, true),
                        state, raster, renderTargets, window != 0, window != 0);
            }
            if (MetalGameTarget.independent()) throw new IllegalStateException("Missing bundled bgfx window");
            if (library != null && !library.isEmpty()) {
                Path direct = Paths.get(library).toAbsolutePath().normalize();
                if (!Files.isRegularFile(direct)) return null;
                boolean full = "1".equals(System.getenv("MCGL_METAL_FULL_SCENE"));
                System.out.println("[MCGL Metal live] armed for " + (full ? "the game scene" : "the game's terrain"));
                long cocoaWindow = MetalGameTarget.cocoaWindow();
                return new MetalWorldCapture(null, new MetalWorldLive(direct, full, cocoaWindow, false),
                        state, raster, renderTargets, full, cocoaWindow != 0);
            }
            if (name == null || name.isEmpty()) return null;
            Path path = Paths.get(name).toAbsolutePath().normalize();
            if (!Files.isDirectory(path.getParent()) || Files.exists(path)) return null;
            System.out.println("[MCGL Metal capture] armed for one terrain frame");
            return new MetalWorldCapture(path, null, state, raster, renderTargets, false, false);
        } catch (RuntimeException error) {
            System.out.println("[MCGL Metal capture] unavailable: " + error.getMessage());
            return null;
        }
    }

    private MetalWorldCapture(Path target, MetalWorldLive live, GameRenderState state, GameRasterState raster,
                              Set<Integer> renderTargets, boolean full, boolean gameWindow) {
        this.target = target; this.live = live; this.state = state; this.raster = raster; this.full = full;
        this.gameWindow = gameWindow;
        textureUploads = MetalGameTarget.independent() ? BgfxGameState.textureUploads()
                : gameWindow || live != null && live.usesBgfx()
                    ? new MetalTextureUploads(TerrainGLReadback::uploadAlignment) : null;
        this.renderTargets = renderTargets;
        Integer configured = Integer.getInteger("mcgl.metal.live.interval");
        interval = configured == null ? (gameWindow ? 0 : full ? 22_000_000L : 50_000_000L)
                : Math.max(0, configured) * 1_000_000L;
        // The live bgfx preview can revisit the same large terrain working set
        // as the full Metal scene while the original client streams chunks.
        long budget = full || live != null && live.usesBgfx() ? SCENE_CACHE_BUDGET : LIVE_CACHE_BUDGET;
        liveCacheBudget = Math.max(1, Math.min(budget, Long.getLong("mcgl.metal.live.cacheBytes", budget)));
    }

    private static final class CachedTexture {
        final MetalWorldFrame.Texture image;
        final long bytes;
        CachedTexture(MetalWorldFrame.Texture image, long bytes) { this.image = image; this.bytes = bytes; }
    }

    void remember(Mesh mesh, MeshData data) {
        if (finished || !GameTerrainFormat.LAYOUT.equals(data.layout())) return;
        long bytes = bytes(data);
        long budget = live == null ? CACHE_BUDGET : liveCacheBudget;
        if (bytes > budget) return;
        forget(mesh);
        if (live != null && residentBytes + bytes > budget) {
            Iterator<MeshData> oldest = resident.values().iterator();
            while (residentBytes + bytes > budget) {
                residentBytes -= bytes(oldest.next());
                oldest.remove();
            }
        }
        if (residentBytes + bytes > budget) return;
        resident.put(mesh, data);
        residentBytes += bytes;
    }

    void replace(Mesh old, Mesh replacement) {
        MeshData data = resident.remove(old);
        if (data != null) resident.put(replacement, data);
    }

    void forget(Mesh mesh) {
        MeshData data = resident.remove(mesh);
        if (data != null) residentBytes -= bytes(data);
    }

    private MetalWorldFrame.Texture gpuTarget(int name) {
        return gpuTargets.computeIfAbsent(name, BgfxGameState::renderTarget);
    }

    void textureChanged(int name) {
        if (frame != null) textureIds.remove(name);
        CachedTexture old = cachedTextures.remove(name);
        if (old != null) cachedTextureBytes -= old.bytes;
    }

    void textureUploaded(int name, int level, int internal, int width, int height, int border,
                         int format, int type, ByteBuffer pixels) {
        textureChanged(name);
        MetalWorldFrame.Texture target = gpuTargets.get(name);
        if (target != null && level == 0 && (target.width != width || target.height != height)) gpuTargets.remove(name);
        if (textureUploads != null && !finished && !renderTargets.contains(name))
            textureUploads.upload(name, level, internal, width, height, border, format, type, pixels);
    }

    void texturePixelsChanged(int name, int level) {
        textureChanged(name);
        if (textureUploads != null) textureUploads.invalidateLevel(name, level);
    }

    void textureUpdated(int name, int level, int x, int y, int width, int height,
                        int format, int type, ByteBuffer pixels) {
        textureChanged(name);
        if (textureUploads != null && !finished)
            textureUploads.update(name, level, x, y, width, height, format, type, pixels);
    }

    void textureStorageChanged(int name) {
        textureChanged(name);
        gpuTargets.remove(name);
        if (textureUploads != null) textureUploads.invalidate(name);
    }

    boolean tracksLiveFrames() { return live != null && !finished; }
    boolean fullScene() { return full && !finished; }
    private boolean bgfxWindow() { return gameWindow && live != null && live.usesBgfx(); }

    void rememberScene(Mesh mesh, MeshData data, int mask) {
        if (!fullScene() || (mask & ~127) != 0) return;
        MeshData normalized = MetalSceneGeometry.mesh(data, state, mask);
        if (normalized != null) remember(mesh, normalized);
    }

    void clear(int bits) {
        if ((bits & (WORLD_CLEAR | (full ? GL11C.GL_STENCIL_BUFFER_BIT : 0))) == 0) return;
        if (raster.enabled(GL11C.GL_SCISSOR_TEST)) return;
        if (!primarySurface()) {
            if (MetalGameTarget.independent() && bgfxWindow() && active()) clearTarget(bits);
            return;
        }
        boolean bgfx = bgfxWindow();
        if (bgfx) {
            if (!raster.depthMask()) bits &= ~GL11C.GL_DEPTH_BUFFER_BIT;
            if ((bits & GL11C.GL_COLOR_BUFFER_BIT) != 0 && !writesAnyColor()) bits &= ~GL11C.GL_COLOR_BUFFER_BIT;
        }
        if ((bits & GL11C.GL_COLOR_BUFFER_BIT) != 0 && primaryHeader) {
            if (live != null && live.usesBgfx() && frame.terrainAttempted) lateTerrainClear = true;
            reset();
            clearMask = 0;
        } else if ((bits & GL11C.GL_DEPTH_BUFFER_BIT) != 0 && draws != 0 && raster.depthMask()) {
            depthReset = true;
        }
        if (bgfx && (bits & GL11C.GL_DEPTH_BUFFER_BIT) != 0) {
            float depth = 1 - GL11C.glGetFloat(GL11C.GL_DEPTH_CLEAR_VALUE);
            if (depthReset) passDepthClear = depth;
            else firstDepthClear = depth;
        }
        clearMask |= bits & WORLD_CLEAR;
        if (full && (bits & GL11C.GL_STENCIL_BUFFER_BIT) != 0) {
            stencilReset = true;
            stencilValue = GL11C.glGetInteger(GL11C.GL_STENCIL_CLEAR_VALUE) & 255;
        }
    }

    private void clearTarget(int bits) {
        if (frame.passes.size() >= MetalWorldFrame.MAX_PASSES) return;
        MetalWorldFrame.Pass pass = new MetalWorldFrame.Pass();
        pass.target = gpuTarget(BgfxGameState.drawTargetTexture());
        if (pass.target == null) return;
        if ((bits & GL11C.GL_COLOR_BUFFER_BIT) != 0 && writesAnyColor()) {
            pass.targetClear |= GL11C.GL_COLOR_BUFFER_BIT;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer rgba = stack.mallocFloat(4);
                GL11C.glGetFloatv(GL11C.GL_COLOR_CLEAR_VALUE, rgba);
                for (int i = 0; i < 4; i++)
                    pass.targetClearColor = pass.targetClearColor << 8 | Math.round(Math.max(0, Math.min(1, rgba.get(i))) * 255);
            }
        }
        if ((bits & GL11C.GL_DEPTH_BUFFER_BIT) != 0 && raster.depthMask()) {
            pass.targetClear |= GL11C.GL_DEPTH_BUFFER_BIT;
            pass.depthClear = 1 - GL11C.glGetFloat(GL11C.GL_DEPTH_CLEAR_VALUE);
        }
        if ((bits & GL11C.GL_STENCIL_BUFFER_BIT) != 0) {
            pass.targetClear |= GL11C.GL_STENCIL_BUFFER_BIT;
            pass.raster[13] = GL11C.glGetInteger(GL11C.GL_STENCIL_CLEAR_VALUE) & 255;
        }
        if ((pass.targetClear & GL11C.GL_COLOR_BUFFER_BIT) != 0) terrainTargets.remove(pass.target);
        if (pass.targetClear != 0) frame.passes.add(pass);
    }

    private static boolean writesAnyColor() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer mask = stack.mallocInt(4);
            GL11C.glGetIntegerv(GL11C.GL_COLOR_WRITEMASK, mask);
            return (mask.get(0) | mask.get(1) | mask.get(2) | mask.get(3)) != 0;
        }
    }

    void batch(MeshArena arena, List<Mesh> meshes, int mask, FloatBuffer matrices, IntBuffer inputs,
               int[] table, boolean materials) {
        if (!active()) return;
        frame.terrainAttempted = true;
        try {
            MetalWorldFrame.Pass pass = pass(meshes.size(), true);
            if (pass == null) return;
            int light = texture(1, pass, raster.previewTexture(1));
            for (Mesh mesh : meshes) {
                int tag = arena.tag(mesh), input = materials ? inputs.get(tag) : 0;
                int atlas = materials ? table[(input & 15) - 1] : raster.previewTexture(0);
                MetalWorldFrame.Draw draw = draw(pass, mesh, materials ? input >> 4 : mask, atlas, light);
                if (draw != null) for (int i = 0; i < 16; i++) draw.model[i] = matrices.get(tag * 16 + i);
            }
            add(pass);
        } catch (RuntimeException | LinkageError error) { disable(error); }
    }

    void single(Mesh mesh, MeshData source, int mask, boolean supported) {
        if (!active()) return;
        if (primarySurface()) frame.terrainAttempted = true;
        try {
            MetalWorldFrame.Pass pass = pass(1, supported);
            if (pass == null) return;
            int atlas = raster.previewTexture(0), light = texture(1, pass, raster.previewTexture(1));
            MetalWorldFrame.Draw draw = MetalGameTarget.independent() && source != null
                    && (mask & ~127) == 0 && GameTerrainFormat.LAYOUT.equals(source.layout())
                    ? drawData(pass, source, mask, atlas, light, true)
                    : draw(pass, mesh, mask, atlas, light);
            if (draw != null) {
                state.matrices.copyModelView(model);
                for (int i = 0; i < 16; i++) draw.model[i] = (float)model[i];
                if (pass.target != null && pass.raster[5] != 0 && pass.raster[6] == 0) terrainTargets.add(pass.target);
            }
            add(pass);
        } catch (RuntimeException | LinkageError error) { disable(error); }
    }

    void scene(Mesh mesh, MeshData source, int mask, boolean supported) {
        if (!fullScene() || !active()) return;
        optionalPass = true;
        try {
            boolean line = (mask & MetalWorldFrame.Draw.LINE) != 0 && bgfxWindow();
            MetalWorldFrame.Pass pass = pass(1, supported && (mask & ~(line ? 1023 : 511)) == 0);
            if (pass == null) return;
            MeshData data;
            if (line) {
                MeshData vertices = source == null ? mesh.snapshot(READBACK_BUDGET) : source;
                data = vertices == null ? null : MetalSceneGeometry.lines(vertices, state, mask);
                pass.raster[7] = 0;
                pass.polygonFactor = pass.polygonUnits = 0;
            } else {
                data = source == null ? sceneMesh(mesh, mask) : MetalSceneGeometry.mesh(source, state, mask);
            }
            if ((mask & 128) != 0) mask |= 2 | 4 | 8 | 16;
            appendScene(pass, data, (mask & 127) | (line ? MetalWorldFrame.Draw.LINE : 0), false, !line && source == null);
        } catch (RuntimeException | LinkageError error) { disable(error); }
        finally { optionalPass = false; }
    }

    MeshData sceneMesh(Mesh mesh, int mask) {
        MeshData packed = resident.get(mesh);
        if (packed != null || !MetalGameTarget.independent()) return packed;
        // The independent backend still owns the immutable CPU mesh when the packed
        // copy is evicted by chunk streaming. No GPU readback is needed.
        MeshData source = mesh.snapshot(READBACK_BUDGET);
        if (source == null) return null;
        packed = MetalSceneGeometry.mesh(source, state, mask);
        if (packed != null && (mask & ~127) == 0) remember(mesh, packed);
        return packed;
    }

    void text(ByteBuffer input, int vertices, int stride) {
        if (!fullScene() || !active()) return;
        optionalPass = true;
        try {
            MetalWorldFrame.Pass pass = pass(1, true);
            if (pass != null) {
                pass.material[64] = 0; // Glyph positions are pretransformed; model lighting is a separate path.
                appendScene(pass, MetalSceneGeometry.text(input, vertices, stride), 2 | 4 | 8 | 32, true, false);
            }
        } catch (RuntimeException | LinkageError error) { disable(error); }
        finally { optionalPass = false; }
    }

    private void appendScene(MetalWorldFrame.Pass pass, MeshData data, int mask, boolean text, boolean retained) {
        MetalWorldFrame.Draw draw = drawData(pass, data, mask, raster.previewTexture(0),
                texture(1, pass, raster.previewTexture(1)), retained);
        if (draw == null) return;
        if (text) {
            for (int i = 0; i < 16; i += 5) draw.model[i] = 1;
            if (pass.target == null) frame.textDraws++;
        } else {
            state.matrices.copyModelView(model);
            for (int i = 0; i < 16; i++) draw.model[i] = (float)model[i];
        }
        if (pass.target == null) frame.sceneDraws++;
        add(pass);
    }

    private boolean active() {
        if (finished || seen && !sampleThisFrame) return false;
        if (!seen && live != null && !live.acceptsFrames()) {
            seen = true;
            sampleThisFrame = false;
            return false;
        }
        if (!primarySurface() && !(MetalGameTarget.independent() && bgfxWindow()
                && gpuTarget(BgfxGameState.drawTargetTexture()) != null)) return false;
        if (!seen) {
            seen = true;
            // A sampling deadline may pass between terrain batches in the same source frame.
            sampleStart = System.nanoTime();
            sampleThisFrame = terrainFrames >= warmup && (live == null || sampleStart >= nextCapture);
        }
        if (!sampleThisFrame) return false;
        if (frame == null) frame = new MetalWorldFrame();
        return true;
    }

    private boolean primarySurface() {
        return offscreen || MetalGameTarget.logicalFramebuffer(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)) == 0;
    }

    private MetalWorldFrame.Pass pass(int count, boolean supported) {
        if (depthReset && !full) {
            skip(count, "depth cleared inside terrain");
            return null;
        }
        if (frame.passes.size() >= MetalWorldFrame.MAX_PASSES || draws + count > MetalWorldFrame.MAX_DRAWS) {
            skip(count, "command budget");
            return null;
        }
        MetalWorldFrame.Pass pass = new MetalWorldFrame.Pass();
        boolean bgfx = bgfxWindow();
        if (bgfx && MetalGameTarget.independent() && !primarySurface())
            pass.target = gpuTarget(BgfxGameState.drawTargetTexture());
        if (!supported || (!optionalPass && state.enabled(2896)) || (!full && state.constantTextureCombine())) {
            skip(count, "geometry or material");
            return null;
        }
        if (!TerrainGLReadback.raster(raster, pass.raster, size, full, bgfx ? pass : null)
                || !TerrainGLReadback.usesTextureSamplers()) {
            skip(count, "raster or sampler");
            return null;
        }
        if (frame.width == 0) {
            frame.width = bgfx ? MetalGameTarget.drawableWidth() : size[0];
            frame.height = bgfx ? MetalGameTarget.drawableHeight() : size[1];
        }
        if (pass.target != null && pass.viewport == null
                && (size[0] != pass.target.width || size[1] != pass.target.height))
            pass.viewport = new int[]{0, 0, size[0], size[1]};
        if (pass.target == null && !primaryHeader) {
            primaryHeader = true;
            frame.depthClear = firstDepthClear;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer clear = stack.mallocFloat(4);
                GL11C.glGetFloatv(GL11C.GL_COLOR_CLEAR_VALUE, clear);
                clear.get(frame.clear);
            }
        } else if (!bgfx && (size[0] != frame.width || size[1] != frame.height)) {
            skip(count, "viewport changed");
            return null;
        }
        if (full) state.writeMetalMaterial(FloatBuffer.wrap(pass.material), pass.raster);
        else state.writeTerrainPreview(FloatBuffer.wrap(pass.material), pass.raster);
        return pass;
    }

    private MetalWorldFrame.Draw draw(MetalWorldFrame.Pass pass, Mesh mesh, int mask, int atlasName, int light) {
        if ((mask & ~127) != 0) { skip(1, "mesh format"); return null; }
        MeshData data = resident.get(mesh);
        if (data == null && live != null && readbackMeshes < READBACK_MESHES && readbackBytes < READBACK_BUDGET) {
            readbackMeshes++;
            data = mesh.snapshot(READBACK_BUDGET - readbackBytes);
            if (data != null) {
                readbackBytes += bytes(data);
                if (GameTerrainFormat.LAYOUT.equals(data.layout())) {
                    remember(mesh, data);
                    if (!reportedRecovery) {
                        System.out.println("[MCGL Metal live] restored evicted terrain geometry");
                        reportedRecovery = true;
                    }
                } else data = null;
            }
        }
        return drawData(pass, data, mask, atlasName, light, true);
    }

    private MetalWorldFrame.Draw drawData(MetalWorldFrame.Pass pass, MeshData data, int mask, int atlasName, int light,
                                          boolean retained) {
        if (data == null) { skip(1, "mesh unavailable"); return null; }
        int atlas = texture(0, pass, atlasName);
        if (light < 0 || atlas < 0) { skip(1, "texture format/cache"); return null; }
        Integer id = ids.get(data);
        if (id == null) {
            long bytes = bytes(data);
            long budget = live == null ? MetalWorldFrame.MAX_GEOMETRY
                    : gameWindow ? Math.max(LIVE_FRAME_BUDGET, liveCacheBudget) : LIVE_FRAME_BUDGET;
            if (geometryBytes + bytes > budget) { skip(1, "geometry budget"); return null; }
            id = frame.meshes.size();
            frame.meshes.add(data);
            ids.put(data, id);
            geometryBytes += bytes;
        }
        if (retained) frame.retainedMeshes.set(id);
        if (pass.target == null && atlas > 0 && terrainTargets.contains(frame.textures.get(atlas - 1))) {
            frame.terrainAttempted = true;
            frame.worldFromTarget = true;
            opaque = true;
        }
        MetalWorldFrame.Draw draw = new MetalWorldFrame.Draw(id, atlas, light, mask);
        pass.draws.add(draw);
        vertices += data.vertexCount();
        if (pass.raster[6] != 0) blendedDraws++;
        return draw;
    }

    private int texture(int unit, MetalWorldFrame.Pass pass, int name) {
        if ((pass.raster[0] & (1 << unit)) == 0 || name == 0) return 0;
        Integer old = textureIds.get(name);
        if (old != null) return frame.textures.get(old - 1) == pass.target ? -1 : old;
        if (frame.textures.size() >= MetalWorldFrame.MAX_TEXTURES) return -1;
        if (MetalGameTarget.independent() && renderTargets.contains(name)) {
            MetalWorldFrame.Texture target = gpuTarget(name);
            if (target == null || target == pass.target) return -1;
            frame.textures.add(target);
            int id = frame.textures.size();
            textureIds.put(name, id);
            return id;
        }
        if (full && renderTargets.contains(name)) textureChanged(name);
        CachedTexture cached = cachedTextures.get(name);
        int[] parameters = raster.originalTextureParameters(name);
        if (cached != null && parameters != null && !matches(cached.image, parameters)) {
            textureChanged(name);
            cached = null;
        }
        if (cached == null) {
            TerrainGLReadback.Texture data = TerrainGLReadback.texture(name, MetalWorldFrame.MAX_TEXTURE_BYTES - textureBytes,
                    renderTargets.contains(name) ? null : textureUploads);
            if (data == null) return -1;
            uploadedTextureLevels += data.uploadedLevels;
            readbackTextureLevels += data.levels.length - data.uploadedLevels;
            cached = new CachedTexture(new MetalWorldFrame.Texture(data.width, data.height, data.min, data.mag,
                    data.wrapS, data.wrapT, data.levels), data.bytes);
            if (live != null) {
                if (cachedTextureBytes + cached.bytes > MetalWorldFrame.MAX_TEXTURE_BYTES) {
                    cachedTextures.clear();
                    cachedTextureBytes = 0;
                }
                cachedTextures.put(name, cached);
                cachedTextureBytes += cached.bytes;
            }
        }
        if (textureBytes + cached.bytes > MetalWorldFrame.MAX_TEXTURE_BYTES) return -1;
        frame.textures.add(cached.image);
        int id = frame.textures.size();
        textureIds.put(name, id);
        textureBytes += cached.bytes;
        return id;
    }

    private static boolean matches(MetalWorldFrame.Texture image, int[] parameters) {
        return parameters[0] == image.min && parameters[1] == image.mag
                && parameters[2] == image.wrapS && parameters[3] == image.wrapT;
    }

    private void add(MetalWorldFrame.Pass pass) {
        if (pass.draws.isEmpty()) return;
        if (pass.target == null && full && depthReset) {
            pass.raster[9] |= 1;
            pass.depthClear = passDepthClear;
            frame.depthResets++;
            depthReset = false;
        }
        if (pass.target == null && full && stencilReset) {
            pass.raster[9] |= 2;
            pass.raster[13] = stencilValue;
            stencilReset = false;
        }
        MetalWorldFrame.Pass previous = frame.passes.isEmpty() ? null : frame.passes.get(frame.passes.size() - 1);
        if (gameWindow && previous != null && previous.targetClear == 0
                && pass.target == previous.target && pass.raster[9] == 0
                && previous.polygonFactor == pass.polygonFactor && previous.polygonUnits == pass.polygonUnits
                && Arrays.equals(previous.raster, pass.raster) && Arrays.equals(previous.material, pass.material)
                && Arrays.equals(previous.viewport, pass.viewport) && Arrays.equals(previous.scissor, pass.scissor)) {
            previous.draws.addAll(pass.draws);
        } else frame.passes.add(pass);
        draws += pass.draws.size();
        opaque |= pass.target == null && !optionalPass && pass.raster[5] != 0 && pass.raster[6] == 0 && pass.raster[12] == 0;
        if (pass.raster[10] != 0) frame.stencilDraws += pass.draws.size();
        if (pass.viewport != null || pass.scissor != null) frame.regionDraws += pass.draws.size();
    }

    private void skip(int count, String reason) {
        if (optionalPass || (MetalGameTarget.independent() && !primarySurface())) {
            frame.sceneSkipped += count;
            sceneReasons.add(reason);
            return;
        }
        frame.skipped += count;
        skippedReasons.add(reason);
    }

    void completeFrame(boolean presented) {
        if (finished) return;
        if (live != null && live.isClosed()) { close(); return; }
        if (seen) { terrainFrames++; seen = false; }
        boolean cleared = clearMask == WORLD_CLEAR;
        clearMask = 0;
        boolean discardedTerrain = lateTerrainClear;
        lateTerrainClear = false;
        firstDepthClear = 0;
        readbackBytes = 0;
        readbackMeshes = 0;
        if (frame == null) return;
        if (!primaryHeader) { reset(); return; }
        if (live != null) {
            if (discardedTerrain) skippedReasons.add("primary color cleared after terrain");
            if (!presented && !offscreen) skippedReasons.add("source frame not presented");
            frame.menu = full && !frame.terrainAttempted && frame.sceneDraws >= 2 && frame.textDraws > 0;
            if (frame.menu) {
                // A short gap in world submissions can leave only the HUD. Wait for a
                // sequence of menu frames when returning from a previously visible world.
                if (showingWorld && ++menuFrames < 3) skippedReasons.add("waiting for menu frames");
            } else {
                menuFrames = 0;
                if (!cleared) skippedReasons.add("no primary clear");
                if (!opaque && frame.skipped == 0) skippedReasons.add("no opaque terrain");
            }
            if (skippedReasons.isEmpty()) {
                showingWorld = !frame.menu;
                frame.uploadedTextureLevels = uploadedTextureLevels;
                frame.readbackTextureLevels = readbackTextureLevels;
                if (full && frame.sceneSkipped != 0 && terrainFrames % 120 == 0)
                    System.out.println("[MCGL Metal scene] unsupported draws=" + frame.sceneSkipped + " reasons=" + sceneReasons);
                live.offer(frame);
            } else if (++heldFrames <= 4 || heldFrames % 30 == 0) {
                System.out.println("[MCGL Metal live] held=" + heldFrames + " skipped=" + frame.skipped
                        + " reasons=" + skippedReasons + " cachedMeshes=" + resident.size()
                        + " cachedMiB=" + (residentBytes >> 20));
            }
            long period = Math.max(interval, live.minimumInterval());
            long start = full ? sampleStart : System.nanoTime();
            if (gameWindow && nextCapture != 0 && sampleStart - nextCapture < period) start = nextCapture;
            nextCapture = start + period;
            reset();
            return;
        }
        if (draws == 0) {
            if (!skippedReasons.isEmpty()) System.out.println("[MCGL Metal capture] no usable draws: " + skippedReasons);
            reset();
            return;
        }
        MetalWorldFrame captured = frame;
        String summary = "draws=" + draws + " blended=" + blendedDraws + " passes=" + captured.passes.size()
                + " vertices=" + vertices + " textures=" + captured.textures.size() + " skipped=" + captured.skipped
                + " reasons=" + skippedReasons + " size=" + captured.width + "x" + captured.height;
        close();
        Thread writer = new Thread(() -> {
            try {
                captured.write(target);
                System.out.println("[MCGL Metal capture] saved " + summary);
            } catch (IOException | RuntimeException error) {
                System.out.println("[MCGL Metal capture] save failed: " + error.getMessage());
            }
        }, "MCGL terrain capture writer");
        writer.start();
    }

    private void reset() {
        frame = null;
        ids.clear(); textureIds.clear(); skippedReasons.clear(); sceneReasons.clear(); terrainTargets.clear();
        geometryBytes = textureBytes = vertices = 0;
        draws = blendedDraws = 0;
        opaque = depthReset = stencilReset = primaryHeader = false;
    }

    void close() {
        finished = true;
        resident.clear();
        cachedTextures.clear();
        gpuTargets.clear();
        if (textureUploads != null) textureUploads.clear();
        residentBytes = cachedTextureBytes = 0;
        reset();
        if (live != null) live.close();
    }

    private void disable(Throwable error) {
        System.out.println("[MCGL Metal capture] disabled: " + error.getMessage());
        close();
    }

    private static long bytes(MeshData data) {
        return (long)data.vertexCount() * data.layout().stride() + (long)data.indices().count() * data.indices().type().bytes;
    }
}
