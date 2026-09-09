package local.mcgl.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;

/** Lazy, context-owned GLSL 4.10 material programs. Mesh/texture binding is a separate layer. */
public final class ShaderLibrary implements AutoCloseable {
    // Stable vertex input contract for the following VBO/VAO migration.
    public static final int POSITION = 0, COLOR = 1, TEXCOORD = 2, LIGHTMAP = 3, NORMAL = 4;
    public static final int TEXTURE_UNIT = 0, LIGHTMAP_UNIT = 1;
    public enum Material {
        COLOR(false, false, false), TEXTURED(true, false, false), LIGHTMAPPED(true, true, false),
        LIT_TEXTURED(true, false, true), LIT_LIGHTMAPPED(true, true, true);
        public final boolean textured, lightmapped, lit;
        Material(boolean textured, boolean lightmapped, boolean lit) {
            this.textured = textured; this.lightmapped = lightmapped; this.lit = lit;
        }
        public int requiredAttributes() {
            return (1 << POSITION) | (1 << ShaderLibrary.COLOR) | (textured ? 1 << TEXCOORD : 0)
                    | (lightmapped ? 1 << LIGHTMAP : 0) | (lit ? 1 << NORMAL : 0);
        }
    }
    /** Values match the explicit fragment-shader contract, not OpenGL enum numbers. */
    public enum AlphaTest {
        DISABLED(0), NEVER(1), LESS(2), EQUAL(3), LEQUAL(4), GREATER(5), NOTEQUAL(6), GEQUAL(7), ALWAYS(8);
        public final int id;
        AlphaTest(int id) { this.id = id; }
    }
    public enum FogMode {
        DISABLED(0), LINEAR(1), EXP(2), EXP2(3);
        public final int id;
        FogMode(int id) { this.id = id; }
    }
    public enum FogDistance {
        EYE_Z(0), RADIAL(1);
        public final int id;
        FogDistance(int id) { this.id = id; }
    }

    private final RenderContext context;
    private final ShaderPipeline pipeline;
    private final Map<Material, ShaderProgram> programs = new EnumMap<Material, ShaderProgram>(Material.class);
    private final FloatBuffer identity4 = identity(4), identity3 = identity(3);
    private volatile boolean closed;
    ShaderLibrary(RenderContext context) {
        this.context = context; this.pipeline = context.shaders();
    }
    public boolean isClosed() { return closed || context.isClosed(); }
    public ShaderProgram program(Material material) {
        context.checkOwner();
        if (closed) throw new IllegalStateException("Shader library is closed");
        if (material == null) throw new NullPointerException("material");
        ShaderProgram cached = programs.get(material);
        if (cached != null && !cached.isClosed()) return cached;
        String defines = "#define MCGL_TEXTURED " + (material.textured ? 1 : 0)
                + "\n#define MCGL_LIGHTMAP " + (material.lightmapped ? 1 : 0)
                + "\n#define MCGL_LIT " + (material.lit ? 1 : 0) + "\n";
        ShaderProgram program = pipeline.create(new ShaderSources("mcgl/" + material.name(),
                resource("material.vert", defines), resource("material.frag", defines)));
        try {
            defaults(program, material);
            programs.put(material, program);
            return program;
        } catch (Throwable failure) {
            try { program.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private void defaults(ShaderProgram program, Material material) {
        program.uniform("uModelView").setMatrix4(identity4);
        program.uniform("uProjection").setMatrix4(identity4);
        program.uniform("uColorModulator").setVec4(1, 1, 1, 1);
        program.uniform("uAlphaFunction").setInt(AlphaTest.DISABLED.id);
        program.uniform("uAlphaReference").setFloat(0);
        program.uniform("uFogMode").setInt(FogMode.DISABLED.id);
        program.uniform("uFogDistance").setInt(FogDistance.EYE_Z.id);
        program.uniform("uFogStart").setFloat(0);
        program.uniform("uFogEnd").setFloat(1);
        program.uniform("uFogDensity").setFloat(0);
        program.uniform("uFogColor").setVec4(0, 0, 0, 1);
        if (material.textured) {
            program.uniform("uTexture").setInt(TEXTURE_UNIT);
            program.uniform("uTextureMatrix").setMatrix4(identity4);
        }
        if (material.lightmapped) {
            program.uniform("uLightmap").setInt(LIGHTMAP_UNIT);
            program.uniform("uLightmapMatrix").setMatrix4(identity4);
        }
        if (material.lit) {
            program.uniform("uNormalMatrix").setMatrix3(identity3);
            program.uniform("uAmbientLight").setVec3(1, 1, 1);
            program.uniform("uLightDirection0").setVec3(0, 0, 1);
            program.uniform("uLightDirection1").setVec3(0, 0, 1);
            program.uniform("uLightColor0").setVec3(0, 0, 0);
            program.uniform("uLightColor1").setVec3(0, 0, 0);
        }
    }
    private static FloatBuffer identity(int size) {
        FloatBuffer matrix = ByteBuffer.allocateDirect(size * size * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i < size; i++) matrix.put(i * (size + 1), 1);
        return matrix.asReadOnlyBuffer();
    }
    private static String resource(String name, String defines) {
        String path = "/local/mcgl/render/shaders/" + name;
        try (InputStream input = ShaderLibrary.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing bundled shader: " + path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) {
                if (output.size() + count > 1024 * 1024) throw new IOException("Oversized shader resource");
                output.write(bytes, 0, count);
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            String version = "#version 410 core\n";
            if (!text.startsWith(version)) throw new IllegalStateException("Shader must declare GLSL 4.10 Core: " + path);
            return version + defines + "#line 2\n" + text.substring(version.length());
        } catch (IOException failure) { throw new IllegalStateException("Could not read bundled shader: " + path, failure); }
    }
    public void close() {
        if (closed) return;
        if (context.isClosed()) { programs.clear(); closed = true; return; }
        context.checkOwner();
        Throwable failure = null;
        for (Iterator<ShaderProgram> entries = programs.values().iterator(); entries.hasNext();) {
            try { entries.next().close(); entries.remove(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem; else failure.addSuppressed(problem);
            }
        }
        closed = programs.isEmpty();
        if (failure instanceof RuntimeException) throw (RuntimeException)failure;
        if (failure instanceof Error) throw (Error)failure;
        if (failure != null) throw new IllegalStateException("Could not close shader library", failure);
    }
}
