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
        return new ShaderSources("mcgl/game-original-batch","#version 410 core\n"+GameShaderSource.declarations(true,false,true)+resource("lighting.glsl")+"\n#line 1\n"+resource("game.vert"),source(false,"game.frag",false));
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
