package local.mcgl.render.tests;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import local.mcgl.render.*;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.MCGLCoreDisplay;
import org.lwjgl.opengl.PixelFormat;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;

/** Real Core/shader/VAO/VBO/EBO tests through the production Cocoa bootstrap. No game or account. */
public final class CoreGeometryProbe {
    private static int checks;
    private static final VertexLayout[] FORMATS = {VertexFormats.POSITION_COLOR, VertexFormats.TEXTURED,
            VertexFormats.LIGHTMAPPED, VertexFormats.LIT_TEXTURED, VertexFormats.LIT_LIGHTMAPPED};
    private static final String VERTEX = "#version 410 core\nlayout(location=0) in vec3 p; void main(){gl_Position=vec4(p,1);}";
    private static final String FRAGMENT = "#version 410 core\nlayout(location=0) out vec4 c; void main(){c=vec4(1);}";
    public static void main(String[] args) throws Exception {
        long generation = 0;
        for (int lifetime = 0; lifetime < 2; lifetime++) {
            Display.setDisplayMode(new DisplayMode(640, 360)); Display.setResizable(true);
            Display.setTitle("MCGL Core 4.1 — geometry validation");
            MCGLCoreDisplay.create(new PixelFormat().withDepthBits(24).withStencilBits(8));
            RenderContext context = RenderSystem.current();
            ShaderLibrary library = context.materials();
            ShaderProgram retainedProgram = null; Mesh retainedMesh = null; ShaderUniform retainedUniform = null;
            try {
                check(context.profile() == RenderProfile.CORE_41 && context.generation() > generation, "new Core generation");
                generation = context.generation();
                int mask = GL11C.glGetInteger(GL32C.GL_CONTEXT_PROFILE_MASK);
                check((mask & GL32C.GL_CONTEXT_CORE_PROFILE_BIT) != 0
                        && (mask & GL32C.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) == 0, "actual Core-only driver profile");
                check((GL11C.glGetInteger(GL30C.GL_CONTEXT_FLAGS) & GL30C.GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) != 0,
                        "actual forward-compatible context");
                System.out.println("CORE_DRIVER version=" + context.capabilities().version + " glsl="
                        + context.capabilities().shadingLanguageVersion + " renderer=" + context.capabilities().renderer);
                rejects(context::commands);
                GL11C.glDisable(GL11C.GL_DEPTH_TEST); GL11C.glDisable(GL11C.GL_BLEND); GL11C.glDisable(GL11C.GL_CULL_FACE);
                GL11C.glDisable(GL11C.GL_DITHER); GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
                context.frameCommands().viewport(0, 0, 64, 64);
                clear(); pixel(32, 32, 0, 0, 0, 255, "Core frame clear");
                // Default-window alpha precision is platform-selected. Test alpha in explicit RGBA8 storage.
                int framebuffer = GL30C.glGenFramebuffers(), renderbuffer = GL30C.glGenRenderbuffers();
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, renderbuffer);
                GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL11C.GL_RGBA8, 64, 64);
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
                GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, renderbuffer);
                check(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) == GL30C.GL_FRAMEBUFFER_COMPLETE, "RGBA8 test target complete");
                shaderFailures(context);
                int tex0 = texture(0, 128, 255, 64, 255), tex1 = texture(1, 255, 128, 255, 255);
                ShaderProgram color = library.program(ShaderLibrary.Material.COLOR); color.bind();
                int colorId = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
                for (ShaderLibrary.Material material : ShaderLibrary.Material.values()) {
                    ShaderProgram program = library.program(material);
                    check(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) == colorId, "material creation preserves current program");
                    check(program == library.program(material), "material cache");
                    check(GL20C.glGetProgrami(programId(program), GL20C.GL_ATTACHED_SHADERS) == 0, "linked shaders detached/deleted");
                    VertexLayout format = FORMATS[material.ordinal()]; format.requireAttributes(material.requiredAttributes());
                    if (material.lightmapped) program.uniform("uLightmapMatrix").setMatrix4(scale(1f / 16, 1f / 16, 1));
                    try (Mesh mesh = context.meshes().create("material/" + material.name(),
                            new MeshData(format, vertices(material, 64, 128, 192, 255), IndexData.quads(4)), MeshPipeline.Usage.STATIC)) {
                        clear(); program.bind(); mesh.draw(Mesh.Primitive.TRIANGLES);
                        int r = material.textured ? 32 : 64;
                        int g = material.lightmapped ? 64 : 128;
                        int b = material.textured ? 48 : 192;
                        pixel(32, 32, r, g, b, 255, material.name() + " packed vertex inputs/texture/lightmap/default lighting");
                        System.out.println("CORE_MATERIAL_PASS " + material.name() + " stride=" + format.stride()
                                + " uniforms=" + program.uniformNames().size());
                    }
                    color.bind();
                }
                uniformTypes(context);
                alphaFogLighting(context, library);
                textureTransforms(context, library, tex0, tex1);
                geometry(context, color);
                combinedGeometry(context,color);
                checks+=MeshArenaProbe.run(context);
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
                screenshot(context, library, lifetime);
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
                GL11C.glDeleteTextures(tex0); GL11C.glDeleteTextures(tex1);
                retainedProgram = library.program(ShaderLibrary.Material.COLOR);
                retainedUniform = retainedProgram.uniform("uColorModulator");
                retainedMesh = context.meshes().create("retained", new MeshData(VertexFormats.POSITION_COLOR,
                        vertices(ShaderLibrary.Material.COLOR, 0, 255, 0, 255), IndexData.quads(4)), MeshPipeline.Usage.DYNAMIC);
                Mesh mesh = retainedMesh; ShaderProgram program = retainedProgram; ShaderUniform uniform = retainedUniform;
                FrameCommands frame = context.frameCommands(); MeshPipeline pipeline = context.meshes();
                foreign(() -> {
                    rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES)); rejects(mesh::close);
                    rejects(() -> mesh.updateVertices(0, vertices(ShaderLibrary.Material.COLOR, 1, 2, 3, 255)));
                    rejects(() -> mesh.updateIndices(IndexData.quads(4))); rejects(pipeline::unbind);
                    rejects(program::bind); rejects(() -> uniform.setVec4(1, 1, 1, 1)); rejects(() -> frame.clear(true, false, false));
                });
                Display.releaseContext();
                rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES)); rejects(mesh::close);
                rejects(program::bind); rejects(() -> uniform.setVec4(1, 1, 1, 1)); rejects(pipeline::unbind);
                Display.makeCurrent();
                check(RenderSystem.current() == context, "reattach retains Core profile/generation");
                clear(); uniform.setVec4(1, 1, 1, 1); program.bind(); mesh.draw(Mesh.Primitive.TRIANGLES);
                context.frameCommands().viewport(0, 0, 64, 64); clear(); mesh.draw(Mesh.Primitive.TRIANGLES);
                pixel(32, 32, 0, 255, 0, 255, "native mesh/program survive reattach");
                long frames = context.completedFrames(); Display.update();
                check(context.completedFrames() == frames + 1 && context.presentedFrames() > 0, "Core presentation uses normal frame lifecycle");
                Display.setDisplayMode(new DisplayMode(680, 400)); Display.update();
                check(RenderSystem.current() == context && context.surfaceWidth() == 680 && context.surfaceHeight() == 400,
                        "Core resize retains generation");
                check(GL11C.glGetError() == GL11C.GL_NO_ERROR, "no GL errors across complete lifetime");
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
                GL30C.glDeleteFramebuffers(framebuffer); GL30C.glDeleteRenderbuffers(renderbuffer);
            } finally { Display.destroy(); }
            check(context.isClosed() && library.isClosed(), "context invalidates material library");
            checks+=MeshArenaProbe.verifyRetired();
            check(retainedMesh != null && retainedMesh.isClosed() && retainedProgram.isClosed(), "context invalidates mesh/program handles");
            Mesh stale = retainedMesh; ShaderUniform staleUniform = retainedUniform;
            rejects(() -> stale.draw(Mesh.Primitive.TRIANGLES)); rejects(() -> staleUniform.setVec4(1, 1, 1, 1));
            stale.close(); retainedProgram.close(); library.close();
            rejects(RenderSystem::current);
        }
        Display.shutdown();
        System.out.println("CORE_GEOMETRY_PASS checks=" + checks + " lifetimes=2 actual-Core/GLSL/material-pixels/VAO/VBO/EBO/ownership/cleanup");
    }
    private static void shaderFailures(RenderContext context) throws Exception {
        Object backend = field(context, "backend"), pipeline = field(backend, "shaders");
        int before = ((Set<?>)field(pipeline, "programs")).size();
        rejects(() -> context.shaders().create(new ShaderSources("bad/vertex", "#version 410 core\ninvalid syntax", FRAGMENT)));
        rejects(() -> context.shaders().create(new ShaderSources("bad/fragment", VERTEX, "#version 410 core\ninvalid syntax")));
        rejects(() -> context.shaders().create(new ShaderSources("bad/link",
                "#version 410 core\nout vec3 mismatch; void main(){mismatch=vec3(1);gl_Position=vec4(0,0,0,1);}",
                "#version 410 core\nin vec4 mismatch; out vec4 c; void main(){c=mismatch;}")));
        rejects(() -> context.shaders().create(new ShaderSources("bad/array", VERTEX,
                "#version 410 core\nuniform vec4 values[2]; out vec4 c; void main(){c=values[int(gl_FragCoord.x)&1];}")));
        rejects(() -> context.shaders().create(new ShaderSources("bad/cube", VERTEX,
                "#version 410 core\nuniform samplerCube value; out vec4 c; void main(){c=texture(value,vec3(1));}")));
        rejects(() -> context.shaders().create(new ShaderSources("bad/block", VERTEX,
                "#version 410 core\nlayout(std140) uniform Values {vec4 value;}; out vec4 c; void main(){c=value;}")));
        check(((Set<?>)field(pipeline, "programs")).size() == before, "failed shader builds publish no resource");
        try (ShaderProgram valid = context.shaders().create(new ShaderSources("valid/no-uniforms", VERTEX, FRAGMENT))) {
            check(valid.uniformNames().isEmpty() && valid.findUniform("optimized") == null, "zero/optimized uniforms");
            rejects(() -> valid.uniform("absent"));
        }
        check(GL11C.glGetError() == GL11C.GL_NO_ERROR, "shader rejection cleanup leaves no GL errors");
    }
    private static void uniformTypes(RenderContext context) throws Exception {
        String fragment = "#version 410 core\nuniform bool uBool; uniform int uInt; uniform float uFloat;"
                + "uniform vec2 uVec2; uniform vec3 uVec3; uniform vec4 uVec4; uniform mat3 uMat3; uniform mat4 uMat4;"
                + "out vec4 c; void main(){c=uVec4+vec4(uVec2,uVec3.x,uFloat)+vec4(uMat3[0][0]+uMat4[0][0]+float(uInt)+(uBool?1:0));}";
        ShaderProgram other = context.materials().program(ShaderLibrary.Material.COLOR); other.bind();
        int current = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        ShaderProgram p = context.shaders().create(new ShaderSources("all/uniform-types", VERTEX, fragment));
        ShaderUniform retained = p.uniform("uFloat");
        p.uniform("uBool").setInt(1); p.uniform("uInt").setInt(7); retained.setFloat(0.25f);
        p.uniform("uVec2").setVec2(2, 3); p.uniform("uVec3").setVec3(4, 5, 6); p.uniform("uVec4").setVec4(7, 8, 9, 10);
        FloatBuffer m4 = buffer(80).asFloatBuffer(); m4.position(2); m4.limit(18);
        for (int i = 0; i < 16; i++) m4.put(2 + i, i % 5 == 0 ? 1 : 0);
        p.uniform("uMat4").setMatrix4(m4); p.uniform("uMat3").setMatrix3(identity(3));
        check(m4.position() == 2 && m4.limit() == 18, "matrix cursor retained");
        check(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) == current, "uniform uploads target unbound program");
        check(Math.abs(GL20C.glGetUniformf(programId(p), GL20C.glGetUniformLocation(programId(p), "uFloat")) - 0.25f) < 0.001,
                "actual unbound uniform value");
        retained.setFloat(0.25f);retained.setFloat(0.75f);retained.setFloat(0.75f);
        check(Math.abs(GL20C.glGetUniformf(programId(p),GL20C.glGetUniformLocation(programId(p),"uFloat"))-.75f)<.001,"repeated scalar cache still publishes changed values");
        m4.put(14,2);p.uniform("uMat4").setMatrix4(m4);p.uniform("uMat4").setMatrix4(m4);
        FloatBuffer actual=buffer(64).asFloatBuffer();GL20C.glGetUniformfv(programId(p),GL20C.glGetUniformLocation(programId(p),"uMat4"),actual);
        check(actual.get(12)==2&&m4.position()==2&&m4.limit()==18,"reused matrix buffer mutation is observed without moving its cursor");
        try(ShaderProgram independent=context.shaders().create(new ShaderSources("uniform/cache-isolation",VERTEX,fragment))) {
            independent.uniform("uFloat").setFloat(.75f);independent.uniform("uInt").setInt(7);
            check(Math.abs(GL20C.glGetUniformf(programId(independent),GL20C.glGetUniformLocation(programId(independent),"uFloat"))-.75f)<.001,"first upload is per-program even when another program has the same value");
            p.uniform("uInt").setInt(7);p.uniform("uInt").setInt(9);p.uniform("uInt").setInt(9);
            check(GL20C.glGetUniformi(programId(p),GL20C.glGetUniformLocation(programId(p),"uInt"))==9&&GL20C.glGetUniformi(programId(independent),GL20C.glGetUniformLocation(programId(independent),"uInt"))==7,"cached integer uniforms remain isolated and mutable");
        }
        rejects(() -> retained.setInt(3)); rejects(() -> p.uniform("uBool").setInt(2));
        rejects(() -> p.uniform("uMat4").setMatrix4(FloatBuffer.allocate(16)));
        rejects(() -> p.uniform("uMat4").setMatrix4(buffer(60).asFloatBuffer()));
        rejects(() -> context.materials().program(ShaderLibrary.Material.TEXTURED).uniform("uTexture").setInt(-1));
        int id = programId(p); p.close(); p.close();
        check(!GL20C.glIsProgram(id) && GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) == current,
                "closing non-current program deletes only itself");
        rejects(() -> retained.setFloat(2));
        try (ShaderProgram bound = context.shaders().create(new ShaderSources("bound/close", VERTEX, FRAGMENT))) {
            bound.bind(); id = programId(bound); bound.close();
            check(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) == 0 && !GL20C.glIsProgram(id), "closing current program unbinds/deletes");
        }
    }
    private static void alphaFogLighting(RenderContext context, ShaderLibrary library) {
        ShaderProgram p = library.program(ShaderLibrary.Material.COLOR);
        try (Mesh mesh = context.meshes().create("effects", new MeshData(VertexFormats.POSITION_COLOR,
                vertices(ShaderLibrary.Material.COLOR, 0, 255, 0, 128), IndexData.quads(4)), MeshPipeline.Usage.STATIC)) {
            p.bind(); p.uniform("uAlphaReference").setFloat(128f / 255);
            for (ShaderLibrary.AlphaTest alpha : ShaderLibrary.AlphaTest.values()) {
                p.uniform("uAlphaFunction").setInt(alpha.id); clear(); mesh.draw(Mesh.Primitive.TRIANGLES);
                boolean pass = alpha == ShaderLibrary.AlphaTest.DISABLED || alpha == ShaderLibrary.AlphaTest.EQUAL
                        || alpha == ShaderLibrary.AlphaTest.LEQUAL || alpha == ShaderLibrary.AlphaTest.GEQUAL || alpha == ShaderLibrary.AlphaTest.ALWAYS;
                pixel(32, 32, 0, pass ? 255 : 0, 0, pass ? 128 : 255, "alpha comparison " + alpha);
            }
            p.uniform("uAlphaFunction").setInt(0); p.uniform("uFogColor").setVec4(1, 0, 0, 0);
            p.uniform("uFogStart").setFloat(0); p.uniform("uFogEnd").setFloat(1); p.uniform("uFogDensity").setFloat(1);
            for (ShaderLibrary.FogMode fog : ShaderLibrary.FogMode.values()) {
                p.uniform("uFogMode").setInt(fog.id); clear(); mesh.draw(Mesh.Primitive.TRIANGLES);
                double factor = fog == ShaderLibrary.FogMode.LINEAR ? 0.5 : fog == ShaderLibrary.FogMode.EXP ? Math.exp(-0.5)
                        : fog == ShaderLibrary.FogMode.EXP2 ? Math.exp(-0.25) : 1;
                pixel(32, 32, (int)Math.round(255 * (1 - factor)), (int)Math.round(255 * factor), 0, 128, "fog " + fog + " preserves alpha");
            }
            p.uniform("uFogDistance").setInt(1); p.uniform("uFogMode").setInt(1);
            clear(); mesh.draw(Mesh.Primitive.TRIANGLES);
            double radius = Math.sqrt(0.25 + 2 * Math.pow(1.0 / 64, 2));
            pixel(32, 32, (int)Math.round(255 * radius), (int)Math.round(255 * (1 - radius)), 0, 128, "radial fog");
            p.uniform("uFogMode").setInt(0); p.uniform("uFogDistance").setInt(0);
        }
        ShaderProgram lit = library.program(ShaderLibrary.Material.LIT_TEXTURED);
        try (Mesh mesh = context.meshes().create("lighting", new MeshData(VertexFormats.LIT_TEXTURED,
                vertices(ShaderLibrary.Material.LIT_TEXTURED, 255, 255, 255, 255), IndexData.quads(4)), MeshPipeline.Usage.STATIC)) {
            lit.uniform("uAmbientLight").setVec3(0.25f, 0.25f, 0.25f);
            lit.uniform("uLightColor0").setVec3(0.5f, 0.5f, 0.5f);
            clear(); lit.bind(); mesh.draw(Mesh.Primitive.TRIANGLES);
            pixel(32, 32, 96, 191, 48, 255, "packed normalized normal and diffuse lighting");
            lit.uniform("uAmbientLight").setVec3(1, 1, 1); lit.uniform("uLightColor0").setVec3(0, 0, 0);
        }
    }
    private static void geometry(RenderContext context, ShaderProgram color) throws Exception {
        MeshPipeline pipeline = context.meshes();
        MeshData data = new MeshData(VertexFormats.POSITION_COLOR, vertices(ShaderLibrary.Material.COLOR, 255, 0, 0, 255), IndexData.quads(4));
        int sentinelVao = GL30C.glGenVertexArrays(), sentinelArray = GL15C.glGenBuffers(), sentinelIndex = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(sentinelVao); GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, sentinelArray);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, sentinelIndex);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, sentinelArray);
        Mesh mesh = pipeline.create("dynamic/quad", data, MeshPipeline.Usage.DYNAMIC);
        check(GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING) == sentinelVao
                && GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING) == sentinelArray
                && GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING) == sentinelIndex, "creation preserves caller VAO/VBO/EBO");
        Object nativeMesh = field(mesh, "target");
        int vao = (Integer)field(nativeMesh, "vao"), vbo = (Integer)field(nativeMesh, "vbo"), ibo = (Integer)field(nativeMesh, "ibo");
        check(GL30C.glIsVertexArray(vao) && GL15C.glIsBuffer(vbo) && GL15C.glIsBuffer(ibo), "real GPU objects allocated");
        color.uniform("uColorModulator").setVec4(1, 1, 1, 1); color.bind(); clear(); mesh.draw(Mesh.Primitive.TRIANGLES);
        pixel(32, 32, 255, 0, 0, 255, "initial VBO contents");
        color.uniform("uProjection").setMatrix4(scale(0.5f, 0.5f, 1));
        clear(); mesh.draw(Mesh.Primitive.TRIANGLES);
        pixel(32, 32, 255, 0, 0, 255, "projection transform retains center");
        pixel(8, 8, 0, 0, 0, 255, "projection transform shrinks geometry");
        color.uniform("uProjection").setMatrix4(identity(4));
        FloatBuffer translated = identity(4); translated.put(12, 1);
        color.uniform("uModelView").setMatrix4(translated); clear(); mesh.draw(Mesh.Primitive.TRIANGLES);
        pixel(16, 32, 0, 0, 0, 255, "model-view translation moves geometry");
        pixel(48, 32, 255, 0, 0, 255, "model-view translation expected coverage");
        color.uniform("uModelView").setMatrix4(identity(4));
        ByteBuffer update = vertices(ShaderLibrary.Material.COLOR, 0, 255, 0, 255);
        int position = update.position(), limit = update.limit(); mesh.updateVertices(0, update);
        check(update.position() == position && update.limit() == limit
                && GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER) == sentinelArray
                && GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING) == vao, "vertex update preserves cursor/copy binding/VAO");
        clear(); mesh.draw(Mesh.Primitive.TRIANGLES); pixel(32, 32, 0, 255, 0, 255, "updated VBO pixels");
        clear(); mesh.draw(Mesh.Primitive.TRIANGLES, 3, 3);
        pixel(16, 48, 0, 255, 0, 255, "16-bit byte offset draws second triangle");
        pixel(48, 16, 0, 0, 0, 255, "first triangle excluded by subrange");
        mesh.updateIndices(IndexData.of(0, 1, 2, 0, 1, 2)); clear(); mesh.draw(Mesh.Primitive.TRIANGLES, 3, 3);
        pixel(16, 48, 0, 0, 0, 255, "index-only update changes covered triangle");
        pixel(48, 16, 0, 255, 0, 255, "index-only update retains vertex data");
        check(GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER)==sentinelArray
                &&GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)==vao
                &&GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING)==ibo,"complete index replacement preserves COPY/VAO/EBO bindings");
        mesh.updateIndices(IndexData.quads(4));clear();mesh.draw(Mesh.Primitive.TRIANGLES,3,3);
        // No finish/readback between these draws: replacing the store must not change queued work.
        mesh.updateIndices(IndexData.of(0,1,2,0,1,2));mesh.draw(Mesh.Primitive.TRIANGLES,3,3);
        pixel(16,48,0,255,0,255,"queued draw retains the previous index store");
        pixel(48,16,0,255,0,255,"following draw uses the replacement index store");
        rejects(() -> mesh.updateIndices(IndexData.of(0, 1, 4, 0, 1, 2)));
        rejects(() -> mesh.updateVertices(1, update)); rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES, 5, 3));
        clear(); mesh.draw(Mesh.Primitive.TRIANGLES); pixel(48, 16, 0, 255, 0, 255, "rejected changes do not corrupt buffers");
        mesh.close(); mesh.close();
        check(GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING) == 0 && !GL30C.glIsVertexArray(vao)
                && !GL15C.glIsBuffer(vbo) && !GL15C.glIsBuffer(ibo), "explicit close deletes VAO/VBO/EBO and unbinds current mesh");
        rejects(() -> mesh.draw(Mesh.Primitive.TRIANGLES));
        IndexData index32 = IndexData.unsignedInt(IntBuffer.wrap(new int[] {0, 1, 2, 0, 2, 3}));
        try (Mesh wide = pipeline.create("stream/uint32", new MeshData(VertexFormats.POSITION_COLOR,
                vertices(ShaderLibrary.Material.COLOR, 0, 0, 255, 255), index32), MeshPipeline.Usage.STREAM)) {
            clear(); wide.draw(Mesh.Primitive.TRIANGLES, 3, 3);
            pixel(16, 48, 0, 0, 255, 255, "32-bit byte offset");
            wide.updateIndices(IndexData.unsignedInt(IntBuffer.wrap(new int[] {0, 1, 2, 0, 1, 2})));
            clear(); wide.draw(Mesh.Primitive.TRIANGLES, 3, 3); pixel(48, 16, 0, 0, 255, 255, "32-bit index update");
            ByteBuffer replacement=vertices(ShaderLibrary.Material.COLOR,200,40,80,255);int replacementPosition=replacement.position();
            int wideVao=GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),wideIndex=GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
            wide.updateVertices(0,replacement);
            check(replacement.position()==replacementPosition&&GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER)==sentinelArray
                    &&GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)==wideVao&&GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING)==wideIndex,
                    "whole STREAM replacement preserves source cursor and caller COPY/VAO/EBO bindings");
            clear();wide.draw(Mesh.Primitive.TRIANGLES,3,3);pixel(48,16,200,40,80,255,"orphaned STREAM storage uses new vertices and retains UINT32 index contents");
            rejects(()->wide.updateVertices(1,replacement));clear();wide.draw(Mesh.Primitive.TRIANGLES,3,3);pixel(48,16,200,40,80,255,"rejected STREAM replacement leaves old storage intact");
            wide.draw(Mesh.Primitive.LINES, 0, 2); wide.draw(Mesh.Primitive.POINTS, 0, 1);
        }
        // An index larger than 65535 proves this is not merely a UINT32 label on 16-bit addressing.
        ByteBuffer large = buffer(65540 * 16); ByteBuffer quad = vertices(ShaderLibrary.Material.COLOR, 255, 255, 0, 255);
        large.position(65536 * 16); large.put(quad); large.clear();
        try (Mesh wide = pipeline.create("large/uint32", new MeshData(VertexFormats.POSITION_COLOR, large,
                IndexData.of(65536, 65537, 65538, 65536, 65538, 65539)), MeshPipeline.Usage.STATIC)) {
            clear(); wide.draw(Mesh.Primitive.TRIANGLES); pixel(32, 32, 255, 255, 0, 255, "indices above 65535");
        }
        try (Mesh empty = pipeline.create("empty", new MeshData(VertexFormats.POSITION_COLOR, buffer(0), IndexData.of()), MeshPipeline.Usage.STATIC)) {
            empty.draw(Mesh.Primitive.TRIANGLES);
        }
        GL30C.glBindVertexArray(sentinelVao);
        check(GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING) == sentinelIndex, "other VAO retains its EBO throughout");
        GL30C.glBindVertexArray(0); GL30C.glDeleteVertexArrays(sentinelVao);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0); GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        GL15C.glDeleteBuffers(sentinelArray); GL15C.glDeleteBuffers(sentinelIndex);
        check(GL11C.glGetError() == GL11C.GL_NO_ERROR, "mesh allocation/update/draw/delete have no GL errors");
    }
    private static void textureTransforms(RenderContext context, ShaderLibrary library, int texture, int lightmap) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0); GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        ByteBuffer colors = buffer(8);
        colors.put(new byte[] {(byte)255, 0, 0, (byte)255, 0, 0, (byte)255, (byte)255}).flip();
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 2, 1, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, colors);
        ShaderProgram p = library.program(ShaderLibrary.Material.TEXTURED); p.bind();
        try (Mesh mesh = context.meshes().create("texture/projective", new MeshData(VertexFormats.TEXTURED,
                vertices(ShaderLibrary.Material.TEXTURED, 255, 255, 255, 255), IndexData.quads(4)), MeshPipeline.Usage.STATIC)) {
            FloatBuffer matrix = identity(4); matrix.put(12, -0.25f); p.uniform("uTextureMatrix").setMatrix4(matrix);
            clear(); mesh.draw(Mesh.Primitive.TRIANGLES); pixel(32, 32, 255, 0, 0, 255, "texture matrix translated UV");
            matrix.put(12, 0.25f); p.uniform("uTextureMatrix").setMatrix4(matrix);
            clear(); mesh.draw(Mesh.Primitive.TRIANGLES); pixel(32, 32, 0, 0, 255, 255, "texture matrix second texel");
            matrix.put(12, 0); matrix.put(15, 2); p.uniform("uTextureMatrix").setMatrix4(matrix);
            clear(); mesh.draw(Mesh.Primitive.TRIANGLES); pixel(32, 32, 255, 0, 0, 255, "projective UV divides by w");
            p.uniform("uTextureMatrix").setMatrix4(identity(4));
        }
        // White base texture isolates the lightmap matrix and raw (not normalized) short attributes.
        ByteBuffer white = buffer(4); white.putInt(-1).flip();
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 1, 1, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, white);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + 1); GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lightmap);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 2, 1, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, colors);
        ShaderProgram light = library.program(ShaderLibrary.Material.LIGHTMAPPED); light.bind();
        try (Mesh mesh = context.meshes().create("lightmap/transform", new MeshData(VertexFormats.LIGHTMAPPED,
                vertices(ShaderLibrary.Material.LIGHTMAPPED, 255, 255, 255, 255), IndexData.quads(4)), MeshPipeline.Usage.STATIC)) {
            light.uniform("uLightmapMatrix").setMatrix4(scale(1f / 32, 1f / 16, 1));
            clear(); mesh.draw(Mesh.Primitive.TRIANGLES); pixel(32, 32, 255, 0, 0, 255, "raw short lightmap matrix first texel");
            light.uniform("uLightmapMatrix").setMatrix4(scale(1f / 16, 1f / 16, 1));
            clear(); mesh.draw(Mesh.Primitive.TRIANGLES); pixel(32, 32, 0, 0, 255, 255, "independent lightmap matrix second texel");
        }
        ByteBuffer original = buffer(4); original.put(new byte[] {(byte)255, (byte)128, (byte)255, (byte)255}).flip();
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 1, 1, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, original);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0); GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        original.clear(); original.put(new byte[] {(byte)128, (byte)255, 64, (byte)255}).flip();
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 1, 1, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, original);
    }
    private static void screenshot(RenderContext context, ShaderLibrary library, int lifetime) throws Exception {
        context.frameCommands().viewport(0, 0, 640, 360); clear();
        for (ShaderLibrary.Material material : ShaderLibrary.Material.values()) {
            ShaderProgram program = library.program(material); program.bind();
            context.frameCommands().viewport(16 + material.ordinal() * 124, 60, 112, 240);
            try (Mesh mesh = context.meshes().create("preview/" + material.name(), new MeshData(FORMATS[material.ordinal()],
                    vertices(material, 180, 220, 255, 255), IndexData.quads(4)), MeshPipeline.Usage.STATIC)) {
                mesh.draw(Mesh.Primitive.TRIANGLES);
            }
        }
        ByteBuffer pixels = buffer(640 * 360 * 4);
        GL11C.glReadPixels(0, 0, 640, 360, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 360; y++) for (int x = 0; x < 640; x++) {
            int i = (y * 640 + x) * 4;
            image.setRGB(x, 359 - y, ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
        }
        check(ImageIO.write(image, "png", new File("core-materials-" + lifetime + ".png")), "GPU preview saved");
        Display.update(); context.frameCommands().viewport(0, 0, 64, 64);
    }
    private static ByteBuffer vertices(ShaderLibrary.Material material, int r, int g, int b, int a) {
        ByteBuffer result = buffer(FORMATS[material.ordinal()].stride() * 4);
        float[] xy = {-0.9f, -0.9f, 0.9f, -0.9f, 0.9f, 0.9f, -0.9f, 0.9f};
        for (int v = 0; v < 4; v++) {
            result.putFloat(xy[v * 2]).putFloat(xy[v * 2 + 1]).putFloat(0.5f);
            result.put((byte)r).put((byte)g).put((byte)b).put((byte)a);
            if (material.textured) result.putFloat(0.5f).putFloat(0.5f);
            if (material.lightmapped) result.putShort((short)8).putShort((short)8);
            if (material.lit) result.put((byte)0).put((byte)0).put((byte)127).put((byte)0);
        }
        result.flip(); return result;
    }
    private static void combinedGeometry(RenderContext context,ShaderProgram color)throws Exception {
        MeshPipeline pipeline=context.meshes();
        Mesh a=pipeline.create("combine/source-a",new MeshData(VertexFormats.POSITION_COLOR,vertices(ShaderLibrary.Material.COLOR,255,0,0,255),IndexData.quads(4)),MeshPipeline.Usage.DYNAMIC);
        Mesh b=pipeline.create("combine/source-b",new MeshData(VertexFormats.POSITION_COLOR,vertices(ShaderLibrary.Material.COLOR,0,0,255,255),IndexData.quads(4)),MeshPipeline.Usage.STATIC);
        int sentinelVao=GL30C.glGenVertexArrays(),sentinel=GL15C.glGenBuffers();GL30C.glBindVertexArray(sentinelVao);GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,sentinel);GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,sentinel);GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER,sentinel);
        Mesh combined=pipeline.combine("combine/result",Arrays.asList(a,b),IndexData.unsignedInt(IntBuffer.wrap(new int[]{0,1,2,0,2,3,4,5,6,4,6,7})));
        check(GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)==sentinelVao&&GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING)==sentinel&&GL11C.glGetInteger(GL31C.GL_COPY_READ_BUFFER)==sentinel&&GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER)==sentinel,"combination preserves caller VAO, array and both copy bindings");
        check(combined.vertexCount()==8&&combined.indexCount()==12&&combined.indexType()==IndexData.Type.UINT32,"combined ownership and wide index metadata");
        color.uniform("uModelView").setMatrix4(identity(4));color.uniform("uProjection").setMatrix4(identity(4));color.uniform("uColorModulator").setVec4(1,1,1,1);color.bind();
        clear();combined.draw(Mesh.Primitive.TRIANGLES,0,6);pixel(32,32,255,0,0,255,"combined first source vertex bytes");
        clear();combined.draw(Mesh.Primitive.TRIANGLES,6,6);pixel(32,32,0,0,255,255,"combined second source vertex offset");
        a.updateVertices(0,vertices(ShaderLibrary.Material.COLOR,0,255,0,255));a.close();b.close();
        clear();combined.draw(Mesh.Primitive.TRIANGLES,0,6);pixel(32,32,255,0,0,255,"combined snapshot survives source mutation and deletion");
        foreign(()->rejects(()->pipeline.combine("combine/foreign",Collections.singletonList(combined),IndexData.of(0,1,2))));
        rejects(()->pipeline.combine("combine/retired",Arrays.asList(a,b),IndexData.of(0,1,2)));
        rejects(()->pipeline.combine("combine/bounds",Collections.singletonList(combined),IndexData.of(0,1,8)));
        int vao=(Integer)field(field(combined,"target"),"vao");combined.close();combined.close();check(!GL30C.glIsVertexArray(vao),"combined GPU ownership closes exactly once");
        GL30C.glBindVertexArray(0);GL30C.glDeleteVertexArrays(sentinelVao);GL15C.glDeleteBuffers(sentinel);
    }
    private static int texture(int unit, int r, int g, int b, int a) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit); int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        ByteBuffer bytes = buffer(4); bytes.put((byte)r).put((byte)g).put((byte)b).put((byte)a).flip();
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 1, 1, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, bytes);
        return texture;
    }
    private static FloatBuffer identity(int dimension) {
        FloatBuffer matrix = buffer(dimension * dimension * 4).asFloatBuffer();
        for (int i = 0; i < dimension; i++) matrix.put(i * (dimension + 1), 1); return matrix;
    }
    private static FloatBuffer scale(float x, float y, float z) {
        FloatBuffer matrix = identity(4); matrix.put(0, x); matrix.put(5, y); matrix.put(10, z); return matrix;
    }
    private static ByteBuffer buffer(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()); }
    private static void clear() { RenderSystem.frameCommands().clearColor(0, 0, 0, 1); RenderSystem.frameCommands().clear(true, true, true); }
    private static void pixel(int x, int y, int r, int g, int b, int a, String label) {
        ByteBuffer pixel = buffer(4); GL11C.glReadPixels(x, y, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixel);
        int[] expected = {r, g, b, a}, actual = new int[4]; boolean equal = true;
        for (int i = 0; i < 4; i++) { actual[i] = pixel.get(i) & 255; equal &= Math.abs(actual[i] - expected[i]) <= 2; }
        check(equal, label + " expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
    }
    private static int programId(ShaderProgram program) throws Exception { return (Integer)field(field(program, "target"), "id"); }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void foreign(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } }, "Core ownership probe");
        thread.start(); thread.join(2500); check(!thread.isAlive() && failure.get() == null, "foreign guards: " + failure.get());
    }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid operation accepted"); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
