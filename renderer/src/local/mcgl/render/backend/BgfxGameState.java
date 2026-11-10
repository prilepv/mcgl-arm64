package local.mcgl.render.backend;

import java.nio.*;
import java.util.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;

/** State for legacy game commands consumed by the Metal frame producer, with no driver calls. */
final class BgfxGameState {
    private static final ThreadLocal<BgfxGameState> CURRENT = new ThreadLocal<>();
    private final int[] integers = new int[1 << 16];
    private final float[] floats = new float[1 << 16];
    private final int[] viewport = new int[4], colorMask = {1, 1, 1, 1};
    private final float[] depthRange = {0, 1}, clearColor = new float[4];
    private final int[] textureBindings = new int[32];
    private final Map<Integer, Texture> textures = new HashMap<>();
    private final Map<Integer, Framebuffer> framebuffers = new HashMap<>();
    private final Map<Integer, Integer> bufferSizes = new HashMap<>();
    private final Map<Integer, Integer> bufferBindings = new HashMap<>();
    private final MetalTextureUploads uploads = new MetalTextureUploads(TerrainGLReadback::uploadAlignment);
    private int unit, nextName = 1;
    private boolean reportedReadback;

    BgfxGameState() {
        integers[GL_PACK_ALIGNMENT] = integers[GL_UNPACK_ALIGNMENT] = 4;
        integers[GL_MAX_TEXTURE_SIZE] = 4096;
        integers[GL_MAX_TEXTURE_IMAGE_UNITS] = textureBindings.length;
        integers[GL_MAX_VERTEX_UNIFORM_COMPONENTS] = integers[GL_MAX_FRAGMENT_UNIFORM_COMPONENTS] = 1024;
        integers[GL_MAX_RENDERBUFFER_SIZE] = 4096;
        integers[GL_MAX_COLOR_ATTACHMENTS] = integers[GL_MAX_DRAW_BUFFERS] = 1;
        integers[0x0D56] = 24;
        integers[0x0D57] = 8;
        integers[GL_DEPTH_FUNC] = GL_LESS;
        integers[GL_DEPTH_WRITEMASK] = integers[GL_DITHER] = integers[GL_MULTISAMPLE] = 1;
        integers[GL_CULL_FACE_MODE] = GL_BACK;
        integers[GL_FRONT_FACE] = GL_CCW;
        integers[GL_LOGIC_OP_MODE] = GL_COPY;
        integers[GL_STENCIL_FUNC] = GL_ALWAYS;
        integers[GL_STENCIL_VALUE_MASK] = integers[GL_STENCIL_WRITEMASK] = 255;
        integers[GL_STENCIL_FAIL] = integers[GL_STENCIL_PASS_DEPTH_FAIL] = integers[GL_STENCIL_PASS_DEPTH_PASS] = GL_KEEP;
        integers[GL_BLEND_SRC_RGB] = integers[GL_BLEND_SRC_ALPHA] = GL_ONE;
        integers[GL_BLEND_EQUATION_RGB] = integers[GL_BLEND_EQUATION_ALPHA] = GL_FUNC_ADD;
        floats[GL_DEPTH_CLEAR_VALUE] = floats[GL_LINE_WIDTH] = 1;
    }

    void attach() { CURRENT.set(this); }
    static void detach() { CURRENT.remove(); }
    void close() { uploads.clear(); textures.clear(); framebuffers.clear(); bufferSizes.clear(); bufferBindings.clear(); }
    static MetalTextureUploads textureUploads() { return current().uploads; }
    private static BgfxGameState current() {
        BgfxGameState state = CURRENT.get();
        if (state == null) throw new IllegalStateException("Metal game state is detached");
        return state;
    }
    private static void set(int key, int value) { current().integers[key] = value; }
    private static int name() { return current().nextName++; }
    private static void names(IntBuffer result) {
        for (int i = result.position(); i < result.limit(); i++) result.put(i, name());
    }
    private static void copy(int[] values, IntBuffer result) {
        for (int i = 0; i < values.length; i++) result.put(result.position() + i, values[i]);
    }
    private static void copy(float[] values, FloatBuffer result) {
        for (int i = 0; i < values.length; i++) result.put(result.position() + i, values[i]);
    }

    static int glGetInteger(int key) {
        BgfxGameState state = current();
        if (key == GL_ACTIVE_TEXTURE) return GL_TEXTURE0 + state.unit;
        if (key == GL_TEXTURE_BINDING_2D) return state.textureBindings[state.unit];
        return key >= 0 && key < state.integers.length ? state.integers[key] : 0;
    }
    static void glGetIntegerv(int key, IntBuffer result) {
        BgfxGameState state = current();
        if (key == GL_VIEWPORT) copy(state.viewport, result);
        else if (key == GL_COLOR_WRITEMASK) copy(state.colorMask, result);
        else if (key == GL_MAX_VIEWPORT_DIMS) {
            result.put(result.position(), 4096);
            result.put(result.position() + 1, 4096);
        } else result.put(result.position(), glGetInteger(key));
    }
    static float glGetFloat(int key) { return current().floats[key & 0xffff]; }
    static void glGetFloatv(int key, FloatBuffer result) {
        BgfxGameState state = current();
        if (key == GL_DEPTH_RANGE) copy(state.depthRange, result);
        else if (key == GL_COLOR_CLEAR_VALUE) copy(state.clearColor, result);
        else result.put(result.position(), glGetFloat(key));
    }
    static boolean glIsEnabled(int key) { return glGetInteger(key) != 0; }
    static boolean glGetBoolean(int key) { return glGetInteger(key) != 0; }
    static int glGetError() { return GL_NO_ERROR; }
    static String glGetString(int key) {
        switch (key) {
            case GL_VENDOR: return "Apple";
            case GL_RENDERER: return "bgfx Metal";
            case GL_VERSION: return "4.1 MCGL compatibility interface (Metal)";
            default: return "";
        }
    }
    static String glGetStringi(int key, int index) { return ""; }

    static void glEnable(int key) { set(key, 1); }
    static void glDisable(int key) { set(key, 0); }
    static void glCullFace(int face) { set(GL_CULL_FACE_MODE, face); }
    static void glFrontFace(int face) { set(GL_FRONT_FACE, face); }
    static void glLogicOp(int op) { set(GL_LOGIC_OP_MODE, op); }
    static void glDepthFunc(int function) { set(GL_DEPTH_FUNC, function); }
    static void glDepthMask(boolean write) { set(GL_DEPTH_WRITEMASK, write ? 1 : 0); }
    static void glClearDepth(double depth) { current().floats[GL_DEPTH_CLEAR_VALUE] = (float) depth; }
    static void glDepthRange(double near, double far) {
        float[] range = current().depthRange;
        range[0] = (float)near;
        range[1] = (float)far;
    }
    static void glPolygonOffset(float factor, float units) {
        BgfxGameState state = current();
        state.floats[GL_POLYGON_OFFSET_FACTOR] = factor;
        state.floats[GL_POLYGON_OFFSET_UNITS] = units;
    }
    static void glBlendFunc(int source, int dest) { glBlendFuncSeparate(source, dest, source, dest); }
    static void glBlendFuncSeparate(int source, int dest, int sourceAlpha, int destAlpha) {
        set(GL_BLEND_SRC_RGB, source);
        set(GL_BLEND_DST_RGB, dest);
        set(GL_BLEND_SRC_ALPHA, sourceAlpha);
        set(GL_BLEND_DST_ALPHA, destAlpha);
    }
    static void glBlendEquation(int equation) { glBlendEquationSeparate(equation, equation); }
    static void glBlendEquationSeparate(int rgb, int alpha) {
        set(GL_BLEND_EQUATION_RGB, rgb);
        set(GL_BLEND_EQUATION_ALPHA, alpha);
    }
    static void glStencilFunc(int function, int ref, int mask) {
        set(GL_STENCIL_FUNC, function);
        set(GL_STENCIL_REF, ref);
        set(GL_STENCIL_VALUE_MASK, mask);
    }
    static void glStencilOp(int fail, int depthFail, int pass) {
        set(GL_STENCIL_FAIL, fail);
        set(GL_STENCIL_PASS_DEPTH_FAIL, depthFail);
        set(GL_STENCIL_PASS_DEPTH_PASS, pass);
    }
    static void glClearStencil(int value) { set(GL_STENCIL_CLEAR_VALUE, value); }
    static void glColorMask(boolean r, boolean g, boolean b, boolean a) {
        int[] mask = current().colorMask;
        mask[0] = r ? 1 : 0;
        mask[1] = g ? 1 : 0;
        mask[2] = b ? 1 : 0;
        mask[3] = a ? 1 : 0;
    }
    static void glClearColor(float r, float g, float b, float a) {
        float[] color = current().clearColor;
        color[0] = r;
        color[1] = g;
        color[2] = b;
        color[3] = a;
    }
    static void glViewport(int x, int y, int width, int height) {
        int[] viewport = current().viewport;
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = width;
        viewport[3] = height;
    }

    private static final class Texture {
        final Map<Integer, Integer> parameters = new HashMap<>();
        final Map<Integer, Image> levels = new HashMap<>();
    }
    private static final class Image {
        final int format, width, height;
        Image(int format, int width, int height) { this.format = format; this.width = width; this.height = height; }
    }
    private Texture boundTexture() { return textures.get(textureBindings[unit]); }
    static void glActiveTexture(int target) {
        BgfxGameState state = current();
        int unit = target - GL_TEXTURE0;
        if (unit < 0 || unit >= state.textureBindings.length) throw new IllegalArgumentException("Invalid Metal texture unit");
        state.unit = unit;
    }
    static void glBindTexture(int target, int name) {
        BgfxGameState state = current();
        state.textureBindings[state.unit] = name;
        if (name != 0) state.textures.computeIfAbsent(name, ignored -> new Texture());
    }
    static int glGenTextures() { return name(); }
    static void glGenTextures(IntBuffer result) { names(result); }
    static void glDeleteTextures(int name) {
        BgfxGameState state = current();
        state.textures.remove(name);
        state.uploads.invalidate(name);
        for (int i = 0; i < state.textureBindings.length; i++) {
            if (state.textureBindings[i] == name) state.textureBindings[i] = 0;
        }
    }
    static void glTexParameteri(int target, int key, int value) {
        Texture texture = current().boundTexture();
        if (texture != null) texture.parameters.put(key, value);
    }
    static int glGetTexParameteri(int target, int key) {
        Texture texture = current().boundTexture();
        Integer value = texture == null ? null : texture.parameters.get(key);
        if (value != null) return value;
        switch (key) {
            case GL_TEXTURE_MIN_FILTER: return GL_NEAREST_MIPMAP_LINEAR;
            case GL_TEXTURE_MAG_FILTER: return GL_LINEAR;
            case GL_TEXTURE_WRAP_S:
            case GL_TEXTURE_WRAP_T: return GL_REPEAT;
            case GL_TEXTURE_MAX_LEVEL: return 1000;
            default: return 0;
        }
    }
    static void glTexImage2D(int target, int level, int internal, int width, int height, int border,
                             int format, int type, ByteBuffer pixels) {
        Texture texture = current().boundTexture();
        if (texture != null) texture.levels.put(level, new Image(internal, width, height));
    }
    // GameRenderer forwards the same upload and subimage bytes to the shared texture store.
    static void glTexSubImage2D(int target, int level, int x, int y, int width, int height,
                                int format, int type, ByteBuffer pixels) {}
    static int glGetTexLevelParameteri(int target, int level, int key) {
        Texture texture = current().boundTexture();
        Image image = texture == null ? null : texture.levels.get(level);
        if (image == null) return 0;
        switch (key) {
            case GL_TEXTURE_INTERNAL_FORMAT: return image.format;
            case GL_TEXTURE_WIDTH: return image.width;
            case GL_TEXTURE_HEIGHT: return image.height;
            default: return 0;
        }
    }
    static void glPixelStorei(int key, int value) { set(key, value); }

    private static final class Framebuffer {
        final Map<Integer, Integer> attachments = new HashMap<>();
        final byte[] clear = new byte[4];
    }
    static void glBindFramebuffer(int target, int name) {
        if (target != GL_READ_FRAMEBUFFER) set(GL_DRAW_FRAMEBUFFER_BINDING, name);
        if (target != GL_DRAW_FRAMEBUFFER) set(GL_READ_FRAMEBUFFER_BINDING, name);
        if (name != 0) current().framebuffers.computeIfAbsent(name, ignored -> new Framebuffer());
    }
    static int glGenFramebuffers() { return name(); }
    static void glGenFramebuffers(IntBuffer result) { names(result); }
    static int drawTargetTexture() {
        Framebuffer buffer = framebuffer(GL_DRAW_FRAMEBUFFER);
        return buffer == null ? 0 : Math.max(0, buffer.attachments.getOrDefault(GL_COLOR_ATTACHMENT0, 0));
    }
    static MetalWorldFrame.Texture renderTarget(int name) {
        Texture texture = current().textures.get(name);
        Image image = texture == null ? null : texture.levels.get(0);
        if (image == null || image.width < 1 || image.height < 1 || image.width > 4096 || image.height > 4096
                || (long)image.width * image.height * 4 > MetalWorldFrame.MAX_TEXTURE_BYTES) return null;
        return new MetalWorldFrame.Texture(image.width, image.height,
                texture.parameters.getOrDefault(GL_TEXTURE_MIN_FILTER, GL_LINEAR),
                texture.parameters.getOrDefault(GL_TEXTURE_MAG_FILTER, GL_LINEAR),
                texture.parameters.getOrDefault(GL_TEXTURE_WRAP_S, GL_REPEAT),
                texture.parameters.getOrDefault(GL_TEXTURE_WRAP_T, GL_REPEAT), new ByteBuffer[0]);
    }
    static void glDeleteFramebuffers(int name) {
        current().framebuffers.remove(name);
        if (glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == name) set(GL_DRAW_FRAMEBUFFER_BINDING, 0);
        if (glGetInteger(GL_READ_FRAMEBUFFER_BINDING) == name) set(GL_READ_FRAMEBUFFER_BINDING, 0);
    }
    static void glDeleteFramebuffers(IntBuffer names) {
        for (int i = names.position(); i < names.limit(); i++) glDeleteFramebuffers(names.get(i));
    }
    static int glCheckFramebufferStatus(int target) {
        Framebuffer buffer = framebuffer(target);
        return buffer == null || !buffer.attachments.isEmpty() ? GL_FRAMEBUFFER_COMPLETE : GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT;
    }
    private static Framebuffer framebuffer(int target) {
        return current().framebuffers.get(glGetInteger(target == GL_READ_FRAMEBUFFER ? GL_READ_FRAMEBUFFER_BINDING : GL_DRAW_FRAMEBUFFER_BINDING));
    }
    static void glFramebufferTexture2D(int target, int attachment, int textureTarget, int texture, int level) {
        attach(target, attachment, texture);
    }
    static void glFramebufferRenderbuffer(int target, int attachment, int renderbufferTarget, int renderbuffer) {
        attach(target, attachment, -renderbuffer);
    }
    private static void attach(int target, int attachment, int name) {
        Framebuffer buffer = framebuffer(target);
        if (buffer == null) return;
        if (name == 0) buffer.attachments.remove(attachment);
        else buffer.attachments.put(attachment, name);
    }
    static void glBindRenderbuffer(int target, int name) { set(GL_RENDERBUFFER_BINDING, name); }
    static void glGenRenderbuffers(IntBuffer result) { names(result); }
    static void glDeleteRenderbuffers(IntBuffer names) {
        int bound = glGetInteger(GL_RENDERBUFFER_BINDING);
        for (int i = names.position(); i < names.limit(); i++) {
            if (names.get(i) == bound) set(GL_RENDERBUFFER_BINDING, 0);
        }
    }
    static void glRenderbufferStorage(int target, int internal, int width, int height) {}

    static void glClear(int bits) {
        Framebuffer buffer = framebuffer(GL_DRAW_FRAMEBUFFER);
        if (buffer == null || (bits & GL_COLOR_BUFFER_BIT) == 0) return;
        BgfxGameState state = current();
        for (int i = 0; i < 4; i++) {
            if (state.colorMask[i] != 0) buffer.clear[i] = (byte)Math.round(Math.max(0, Math.min(1, state.clearColor[i])) * 255);
        }
    }
    static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        current().reportReadback();
    }
    static void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer result) {
        BgfxGameState state = current();
        state.reportReadback();
        Framebuffer buffer = framebuffer(GL_READ_FRAMEBUFFER);
        byte[] color = buffer == null ? new byte[4] : buffer.clear;
        int components = format == GL_RGB ? 3 : 4;
        int alignment = glGetInteger(GL_PACK_ALIGNMENT);
        long row = (long)width * components;
        long pitch = (row + alignment - 1) & -alignment;
        if (width < 1 || height < 1 || (height - 1) * pitch + row > result.remaining()) return;
        for (int iy = 0; iy < height; iy++) {
            int position = result.position() + (int)(iy * pitch);
            for (int ix = 0; ix < width; ix++) {
                for (int c = 0; c < components; c++)
                    result.put(position++, color[format == GL_BGRA && c < 3 ? 2 - c : c]);
            }
        }
    }
    static void glGetTexImage(int target, int level, int format, int type, ByteBuffer result) {
        // The frame producer uses the shared upload store. A framebuffer texture has
        // no CPU pixel copy, so the zero-filled fallback represents an empty target.
    }
    private void reportReadback() {
        if (reportedReadback) return;
        reportedReadback = true;
        System.out.println("[MCGL bgfx] GPU framebuffer readback is unavailable in the independent preview");
    }

    static int glGenBuffers() { return name(); }
    static void glGenBuffers(IntBuffer result) { names(result); }
    static void glBindBuffer(int target, int name) {
        current().bufferBindings.put(target, name);
        if (target == 0x88EB) set(0x88ED, name); // Pixel pack buffer and binding
        if (target == 0x88EC) set(0x88EF, name); // Pixel unpack buffer and binding
    }
    static void glBufferData(int target, ByteBuffer data, int usage) { bufferData(target, data.remaining()); }
    static void glBufferData(int target, FloatBuffer data, int usage) { bufferData(target, data.remaining() * 4); }
    private static void bufferData(int target, int size) {
        BgfxGameState state = current();
        int name = state.bufferBindings.getOrDefault(target, 0);
        if (name != 0) state.bufferSizes.put(name, size);
    }
    static int glGetBufferParameteri(int target, int key) {
        BgfxGameState state = current();
        return key == GL_BUFFER_SIZE ? state.bufferSizes.getOrDefault(state.bufferBindings.get(target), 0) : 0;
    }
    static void glDeleteBuffers(int name) {
        BgfxGameState state = current();
        state.bufferSizes.remove(name);
        for (Map.Entry<Integer, Integer> binding : state.bufferBindings.entrySet()) {
            if (binding.getValue() == name) glBindBuffer(binding.getKey(), 0);
        }
    }
    static int glGenVertexArrays() { return name(); }
    static void glBindVertexArray(int name) { set(GL_VERTEX_ARRAY_BINDING, name); }
    static void glDeleteVertexArrays(int name) {
        if (glGetInteger(GL_VERTEX_ARRAY_BINDING) == name) set(GL_VERTEX_ARRAY_BINDING, 0);
    }
    static void glGenQueries(IntBuffer result) { names(result); }
    static void glBeginQuery(int target, int name) {}
    static void glEndQuery(int target) {}
    // Until Metal supplies occlusion results, keep the geometry visible.
    static void glGetQueryObjectuiv(int name, int key, IntBuffer result) { result.put(result.position(), 1); }
}
