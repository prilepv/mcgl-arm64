package local.mcgl.render;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Typed replacement for the original LA effect wrapper; disk assets and effect formulas remain external. */
public final class GameEffect implements AutoCloseable {
    private static final java.util.Map<ShaderProgram,TextureVariant> textureVariants=
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<ShaderProgram,TextureVariant>());
    private static final class TextureVariant {
        final ShaderProgram program;final java.util.Map<String,Integer> samplers=new java.util.HashMap<String,Integer>();
        TextureVariant(ShaderProgram program){this.program=program;}
    }
    private final RenderContext context;
    private final ShaderProgram program;
    private final ShaderProgram chunkProgram;
    private final List<ShaderUniform> uniforms = new ArrayList<ShaderUniform>();
    private final List<ShaderUniform> chunkUniforms = new ArrayList<ShaderUniform>();
    private final FloatBuffer matrix = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    public GameEffect(String fragmentName, String vertexName) {
        context = RenderSystem.current();
        Path fragment = shaderPath(fragmentName, ".frag"), vertex = shaderPath(vertexName, ".vert");
        if (!Files.exists(vertex)) vertex = shaderPath("/shader/default", ".vert");
        String fragmentSource=read(fragment),vertexSource=read(vertex);
        program = context.shaders().create(new ShaderSources("game/effect-" + Integer.toUnsignedString(fragmentName.hashCode()),
                GameShaderSource.migrate(35633, vertexSource), GameShaderSource.migrate(35632, fragmentSource)));
        ShaderProgram candidate=null;
        try{
            if(GameShaderSource.chunkTexturesSupported(fragmentSource)&&!java.util.regex.Pattern.compile("\\bsampler[A-Za-z0-9_]*\\b").matcher(vertexSource).find()){
                candidate=context.shaders().create(new ShaderSources("game/effect-chunk-textures-"+Integer.toUnsignedString(fragmentName.hashCode()),
                        GameShaderSource.migrate(35633,vertexSource,true),GameShaderSource.migrate(35632,fragmentSource,true)));
                TextureVariant variant=new TextureVariant(candidate);
                for(String name:program.uniformNames())if(program.uniform(name).type()==ShaderUniform.Type.SAMPLER_2D)variant.samplers.put(name,0);
                textureVariants.put(program,variant);
            }
        }catch(Throwable failure){if(candidate!=null)try{candidate.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}try{program.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
        chunkProgram=candidate;
    }
    private static Path shaderPath(String name, String extension) {
        if (name == null || name.length() > 512) throw new IllegalArgumentException("Game effect name");
        // The shipped names start with '/shader/'. Preserve the original concatenation and fallback.
        return Paths.get("./bin/media/graph" + name + extension);
    }
    private static String read(Path path) {
        try {
            if (Files.size(path) > 2 * 1024 * 1024) throw new IOException("Game shader exceeds 2 MiB");
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Cannot load game effect: " + path, failure); }
    }
    private void check() { context.checkOwner(); if (program.isClosed()) throw new IllegalStateException("Game effect is closed"); }
    public void begin() { check(); context.game().selectEffect(program); }
    public void end() { check(); context.game().selectEffect(null); }
    public int uniform(String name) { check(); uniforms.add(program.findUniform(name));chunkUniforms.add(chunkProgram==null?null:chunkProgram.findUniform(name));return uniforms.size() - 1; }
    public void set(int index, int value) {
        check();ShaderUniform u=uniforms.get(index),chunk=chunkUniforms.get(index);
        if(u!=null)u.setInt(value);if(chunk!=null)chunk.setInt(value);
        TextureVariant variant=textureVariants.get(program);if(u!=null&&variant!=null&&variant.samplers.containsKey(u.name()))variant.samplers.put(u.name(),value);
    }
    /** Renderer-side eligibility only; a non-primary colorMap sampler keeps ordinary material batches. */
    public static boolean chunkTexturesSupported(ShaderProgram program){
        TextureVariant variant=textureVariants.get(program);if(variant==null)return false;java.util.Map<String,Integer> samplers=variant.samplers;
        if(!Integer.valueOf(0).equals(samplers.get("colorMap")))return false;
        for(java.util.Map.Entry<String,Integer> entry:samplers.entrySet())if(!entry.getKey().equals("colorMap")&&entry.getValue()==0)return false;
        return true;
    }
    public static ShaderProgram chunkProgram(ShaderProgram program){TextureVariant variant=textureVariants.get(program);return variant==null?null:variant.program;}
    public void set(int index, float value) { check(); ShaderUniform u=uniforms.get(index),chunk=chunkUniforms.get(index);if(u!=null)u.setFloat(value);if(chunk!=null)chunk.setFloat(value); }
    public void set(int index, float x, float y, float z) { check(); ShaderUniform u=uniforms.get(index),chunk=chunkUniforms.get(index);if(u!=null)u.setVec3(x,y,z);if(chunk!=null)chunk.setVec3(x,y,z); }
    public void set(int index, float[] rowMajor) {
        check(); if (rowMajor == null || rowMajor.length < 16) throw new IllegalArgumentException("Game effect matrix");
        ShaderUniform u=uniforms.get(index),chunk=chunkUniforms.get(index);if(u==null&&chunk==null)return;
        matrix.clear(); for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) matrix.put(rowMajor[row * 4 + column]);
        matrix.flip();if(u!=null)u.setMatrix4(matrix);if(chunk!=null)chunk.setMatrix4(matrix);
    }
    public void close() {
        if(program.isClosed())return;check();textureVariants.remove(program);
        try{program.close();}finally{if(chunkProgram!=null)chunkProgram.close();uniforms.clear();chunkUniforms.clear();}
    }
}
