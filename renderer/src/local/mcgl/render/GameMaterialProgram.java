package local.mcgl.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Material program for original game passes, with all inputs supplied explicitly by GameRenderState. */
public final class GameMaterialProgram {
    private GameMaterialProgram() {}
    public static ShaderSources sources() {
        return sources(false);
    }
    public static ShaderSources sources(boolean textureTables) {
        return new ShaderSources("mcgl/game-material"+(textureTables?"-chunk-textures":""), source(true, "game.vert",textureTables), source(false, "game.frag",textureTables));
    }
    /** Equivalent built-in variant selected only while logical lighting is off.
     * Imported effects and the lit material retain their original shader bodies. */
    public static ShaderSources unlitSources(boolean textureTables) {
        return new ShaderSources("mcgl/game-material-unlit"+(textureTables?"-chunk-textures":""),
                "#version 410 core\n"+GameShaderSource.declarations(true,textureTables)+unlit()+"\n#line 1\n"+resource("game.vert"),source(false,"game.frag",textureTables));
    }
    private static String unlit(){return "vec4 mcglLitColor(vec4 color,vec3 inputNormal,vec3 eye,mat3 normalMatrix){return color;}\n";}
    public static ShaderSources textSources() {
        return textSources(false);
    }
    /** Lit cached glyphs keep the ordinary material math, with each glyph's original normal matrix. */
    public static ShaderSources textSources(boolean lighting) {
        String inputs=lighting?"#define MCGL_TEXT_LIGHTING\n"+GameShaderSource.lightingDeclarations()+resource("lighting.glsl"):"";
        return new ShaderSources("mcgl/game-text"+(lighting?"-lit":""), "#version 410 core\n"+inputs+resource("text.vert"), source(false,"game.frag",false));
    }
    /** Exact original per-draw float matrices; ordinary and imported effects stay unchanged. */
    public static ShaderSources originalBatchSources(){
        return originalBatchSources(false);
    }
    /** Per-member primary texture and attribute mask; matrices and geometry are unchanged. */
    public static ShaderSources originalBatchSources(boolean materials){
        return originalBatchSources(materials,false);
    }
    public static ShaderSources originalBatchSources(boolean materials,boolean unlit){
        return originalBatchSources(materials,unlit,MeshArena.TAG_COUNT);
    }
    public static ShaderSources originalBatchSources(boolean materials,boolean unlit,int tags){
        String vertex=resource("game.vert");
        if(materials)vertex=vertex.replace("uGameAttributeMask","mcglVertexMask()");
        String declarations=GameShaderSource.declarations(true,materials,true,tags);
        return new ShaderSources("mcgl/game-original-batch"+(materials?"-materials":"")+(unlit?"-unlit":""),"#version 410 core\n"+declarations+(unlit?unlit():resource("lighting.glsl"))+"\n#line 1\n"+vertex,
                "#version 410 core\n"+GameShaderSource.declarations(false,materials,true)+"\n#line 1\n"+resource("game.frag"));
    }
    public static ShaderSources originalArraySources(boolean unlit,int tags){
        ShaderSources source=originalBatchSources(true,unlit,tags);
        String fragment=source.fragment.replace("vec4 mcglTextureProj(sampler2D original,vec3 uv)","vec4 mcglUnusedTextureProj(sampler2D original,vec3 uv)");
        int body=fragment.indexOf("#line 1");
        fragment=fragment.substring(0,body)+"uniform sampler2DArray uOriginalTextureArray;\nvec4 mcglTextureProj(sampler2D original,vec3 uv){return texture(uOriginalTextureArray,vec3(uv.xy/uv.z,float(vGameChunkTexture-1)));}\n"+fragment.substring(body);
        String vertex=source.vertex.replace("mcglOriginalInputs()&15","mcglOriginalInputs()&255").replace("mcglOriginalInputs()>>4","mcglOriginalInputs()>>8");
        return new ShaderSources(source.label+"-array",vertex,fragment);
    }
    private static String source(boolean vertex, String name,boolean textureTables) {
        return "#version 410 core\n" + GameShaderSource.declarations(vertex,textureTables)
                + (vertex?resource("lighting.glsl"):"") + "\n#line 1\n" + resource(name);
    }
    private static String resource(String name) {
        String path = "/local/mcgl/render/shaders/" + name;
        try (InputStream input = GameMaterialProgram.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing game shader: " + path);
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[4096];
            for (int count; (count = input.read(bytes)) != -1;) output.write(bytes, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Cannot read game shader: " + path, failure); }
    }
}
