package local.mcgl.render.backend;

import java.nio.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Read-only bridge for explicit Metal diagnostics on a current OpenGL context. */
final class TerrainGLReadback {
    private TerrainGLReadback() {}

    static final class Texture {
        final int width, height, base, min, mag, wrapS, wrapT;
        final ByteBuffer[] levels;
        final long bytes;
        final int uploadedLevels;
        Texture(int width, int height, int base, int min, int mag, int wrapS, int wrapT, ByteBuffer[] levels, long bytes,
                int uploadedLevels) {
            this.width = width; this.height = height; this.base = base;
            this.min = min; this.mag = mag; this.wrapS = wrapS; this.wrapT = wrapT;
            this.levels = levels; this.bytes = bytes;
            this.uploadedLevels = uploadedLevels;
        }
    }

    static boolean raster(GameRasterState raster, int[] flags, int[] size) {
        return raster(raster, flags, size, false);
    }

    static boolean raster(GameRasterState raster, int[] flags, int[] size, boolean full) {
        return raster(raster, flags, size, full, null);
    }

    static boolean raster(GameRasterState raster, int[] flags, int[] size, boolean full, MetalWorldFrame.Pass regions) {
        boolean scissored = raster.enabled(GL11C.GL_SCISSOR_TEST);
        if ((!full && raster.enabled(GL11C.GL_STENCIL_TEST)) || (regions == null && scissored)
                || (regions == null && raster.enabled(GL11C.GL_POLYGON_OFFSET_FILL)) || raster.enabled(GL11C.GL_COLOR_LOGIC_OP)
                || GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB)
                || GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING) != 0
                || GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH) != 0
                || GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS) != 0
                || GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS) != 0) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4), colorMask = stack.mallocInt(4);
            FloatBuffer depth = stack.mallocFloat(2);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            GL11C.glGetIntegerv(GL11C.GL_COLOR_WRITEMASK, colorMask);
            GL11C.glGetFloatv(GL11C.GL_DEPTH_RANGE, depth);
            int width = viewport.get(2), height = viewport.get(3);
            if (width < 1 || height < 1
                    || width > 5120 || height > 5120 || depth.get(0) != 0 || depth.get(1) != 1) return false;
            if (regions == null && (viewport.get(0) != 0 || viewport.get(1) != 0)) return false;
            if (regions != null && (viewport.get(0) != 0 || viewport.get(1) != 0
                    || width != MetalGameTarget.drawableWidth() || height != MetalGameTarget.drawableHeight())) {
                regions.viewport = new int[]{viewport.get(0), viewport.get(1), width, height};
            }
            if (regions != null && scissored) {
                GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, viewport);
                regions.scissor = new int[]{viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3)};
            }
            int mask = 0;
            for (int i = 0; i < 4; i++) if (colorMask.get(i) != 0) mask |= 8 >> i;
            if (mask != 15 && !full) return false;
            if (full && mask != 15) flags[12] = 16 | mask;
            size[0] = width; size[1] = height;
        }
        if (regions != null && raster.enabled(GL11C.GL_POLYGON_OFFSET_FILL)) {
            regions.polygonFactor = GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_FACTOR);
            regions.polygonUnits = GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_UNITS);
        }
        boolean depthTest = raster.enabled(GL11C.GL_DEPTH_TEST);
        flags[4] = depthTest ? GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC) - GL11C.GL_NEVER : 7;
        flags[5] = depthTest && raster.depthMask() ? 1 : 0;
        flags[6] = raster.enabled(GL11C.GL_BLEND) ? blend() : 0;
        if (flags[6] < 0 || (!full && flags[6] > 1)) return false;
        flags[7] = 0;
        if (raster.enabled(GL11C.GL_CULL_FACE)) {
            int face = GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE);
            if (face == GL11C.GL_FRONT_AND_BACK) return false;
            flags[7] = face == GL11C.GL_FRONT ? 1 : 2;
        }
        flags[8] = GL11C.glGetInteger(GL11C.GL_FRONT_FACE) == GL11C.GL_CCW ? 1 : 0;
        if (full && raster.enabled(GL11C.GL_STENCIL_TEST)) {
            int function = GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC) - GL11C.GL_NEVER;
            int fail = stencilOp(GL11C.glGetInteger(GL11C.GL_STENCIL_FAIL));
            int depthFail = stencilOp(GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_FAIL));
            int pass = stencilOp(GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_PASS));
            if (function < 0 || function > 7 || fail < 0 || depthFail < 0 || pass < 0) return false;
            flags[10] = 1 << 12 | function | fail << 3 | depthFail << 6 | pass << 9;
            int reference = GL11C.glGetInteger(GL11C.GL_STENCIL_REF) & 255;
            int read = GL11C.glGetInteger(GL11C.GL_STENCIL_VALUE_MASK) & 255;
            int write = GL11C.glGetInteger(GL11C.GL_STENCIL_WRITEMASK) & 255;
            flags[11] = reference | read << 8 | write << 16;
        }
        return true;
    }

    private static int stencilOp(int value) {
        switch (value) {
            case GL11C.GL_KEEP: return 0;
            case GL11C.GL_ZERO: return 1;
            case GL11C.GL_REPLACE: return 2;
            case GL11C.GL_INCR: return 3;
            case GL11C.GL_DECR: return 4;
            case GL11C.GL_INVERT: return 5;
            case GL14C.GL_INCR_WRAP: return 6;
            case GL14C.GL_DECR_WRAP: return 7;
            default: return -1;
        }
    }

    private static int blend() {
        int src = blendFactor(GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB));
        int dst = blendFactor(GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB));
        int srcAlpha = alphaFactor(blendFactor(GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA)));
        int dstAlpha = alphaFactor(blendFactor(GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA)));
        int op = blendOperation(GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB));
        int alphaOp = blendOperation(GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA));
        if (src < 0 || dst < 0 || srcAlpha < 0 || dstAlpha < 0 || op < 0 || alphaOp < 0) return -1;
        if (src == 4 && dst == 5 && srcAlpha == 4 && dstAlpha == 5 && op == 0 && alphaOp == 0) return 1;
        return 1 << 24 | src | dst << 4 | srcAlpha << 8 | dstAlpha << 12 | op << 16 | alphaOp << 19;
    }

    private static int alphaFactor(int factor) {
        if (factor == 2 || factor == 3) return factor + 2;
        if (factor == 6 || factor == 7) return factor + 2;
        return factor;
    }

    private static int blendFactor(int value) {
        switch (value) {
            case GL11C.GL_ZERO: return 0;
            case GL11C.GL_ONE: return 1;
            case GL11C.GL_SRC_COLOR: return 2;
            case GL11C.GL_ONE_MINUS_SRC_COLOR: return 3;
            case GL11C.GL_SRC_ALPHA: return 4;
            case GL11C.GL_ONE_MINUS_SRC_ALPHA: return 5;
            case GL11C.GL_DST_COLOR: return 6;
            case GL11C.GL_ONE_MINUS_DST_COLOR: return 7;
            case GL11C.GL_DST_ALPHA: return 8;
            case GL11C.GL_ONE_MINUS_DST_ALPHA: return 9;
            case GL11C.GL_SRC_ALPHA_SATURATE: return 10;
            default: return -1;
        }
    }

    private static int blendOperation(int value) {
        switch (value) {
            case GL14C.GL_FUNC_ADD: return 0;
            case GL14C.GL_FUNC_SUBTRACT: return 1;
            case GL14C.GL_FUNC_REVERSE_SUBTRACT: return 2;
            case GL14C.GL_MIN: return 3;
            case GL14C.GL_MAX: return 4;
            default: return -1;
        }
    }

    static boolean usesTextureSamplers() {
        int unit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        try {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            if (GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) != 0) return false;
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            return GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) == 0;
        } finally { GL13C.glActiveTexture(unit); }
    }

    static int uploadAlignment() {
        if (GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING) != 0
                || GL11C.glGetInteger(GL11C.GL_UNPACK_SWAP_BYTES) != 0
                || GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH) != 0
                || GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS) != 0
                || GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS) != 0) return 0;
        return GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
    }

    static Texture texture(int name, long budget) {
        return texture(name, budget, null);
    }

    static Texture texture(int name, long budget, MetalTextureUploads uploads) {
        int saved = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int alignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, name);
        GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
        try {
            int target = GL11C.GL_TEXTURE_2D;
            int min = GL11C.glGetTexParameteri(target, GL11C.GL_TEXTURE_MIN_FILTER);
            int mag = GL11C.glGetTexParameteri(target, GL11C.GL_TEXTURE_MAG_FILTER);
            int s = GL11C.glGetTexParameteri(target, GL11C.GL_TEXTURE_WRAP_S);
            int t = GL11C.glGetTexParameteri(target, GL11C.GL_TEXTURE_WRAP_T);
            int base = GL11C.glGetTexParameteri(target, GL12C.GL_TEXTURE_BASE_LEVEL);
            int max = GL11C.glGetTexParameteri(target, GL12C.GL_TEXTURE_MAX_LEVEL);
            int w = GL11C.glGetTexLevelParameteri(target, base, GL11C.GL_TEXTURE_WIDTH);
            int h = GL11C.glGetTexLevelParameteri(target, base, GL11C.GL_TEXTURE_HEIGHT);
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096 || max < base) return null;
            int levels = min == GL11C.GL_NEAREST || min == GL11C.GL_LINEAR ? 1
                    : Math.min(max - base + 1, 32 - Integer.numberOfLeadingZeros(Math.max(w, h)));
            ByteBuffer[] pixels = new ByteBuffer[levels];
            long allocated = 0;
            int uploaded = 0;
            for (int i = 0; i < levels; i++) {
                int level = base + i;
                int lw = Math.max(1, w >> i), lh = Math.max(1, h >> i);
                int format = GL11C.glGetTexLevelParameteri(target, level, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
                if (format != GL11C.GL_RGBA8 && format != GL11C.GL_RGB8 && format != GL11C.GL_RGBA && format != GL11C.GL_RGB
                        || lw != GL11C.glGetTexLevelParameteri(target, level, GL11C.GL_TEXTURE_WIDTH)
                        || lh != GL11C.glGetTexLevelParameteri(target, level, GL11C.GL_TEXTURE_HEIGHT)) return null;
                int bytes = lw * lh * 4;
                allocated += bytes;
                if (allocated > budget) return null;
                pixels[i] = uploads == null ? null : uploads.pixels(name, level, lw, lh);
                if (pixels[i] == null) {
                    pixels[i] = direct(bytes);
                    GL11C.glGetTexImage(target, level, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels[i]);
                } else uploaded++;
            }
            return new Texture(w, h, base, min, mag, s, t, pixels, allocated, uploaded);
        } finally {
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, alignment);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, saved);
        }
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }
}
