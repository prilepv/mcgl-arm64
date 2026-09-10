package local.mcgl.render;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded source migration for the game's disk-loaded effects; never edits user shader files. */
public final class GameShaderSource {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    private GameShaderSource() {}
    public static String migrate(int stage, CharSequence input) {
        return migrate(stage,input,false);
    }
    public static String migrate(int stage, CharSequence input,boolean textureTables) {
        boolean vertex = stage == 35633;
        if (!vertex && stage != 35632) throw new IllegalArgumentException("Unsupported game shader stage: " + stage);
        if (input == null) throw new NullPointerException("shader source");
        String source = stripComments(input.toString()).replaceAll("(?m)^\\s*#version[^\\r\\n]*", "");
        if(textureTables&&!vertex&&chunkTexturesSupported(source))
            source=source.replaceAll("\\btexture2D\\s*\\(\\s*colorMap\\s*,","mcglTexture2D(colorMap,");
        source = source.replaceAll("\\bgl_LightSource\\s*\\[\\s*0\\s*\\]", "uGameLight0")
                .replaceAll("\\bgl_LightSource\\s*\\[\\s*1\\s*\\]", "uGameLight1");
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("varying", vertex ? "out" : "in"); names.put("texture2D", "texture");
        names.put("texture2DProj", "textureProj"); names.put("gl_FragColor", "mcglOutColor");
        names.put("gl_Vertex", "vec4(mcglInputPosition, 1.0)"); names.put("gl_Color", "mcglVertexColor()");
        names.put("gl_Normal", "mcglVertexNormal()"); names.put("gl_MultiTexCoord0", "vec4(mcglVertexUv(), 0.0, 1.0)");
        names.put("gl_MultiTexCoord1", "vec4(mcglVertexLightmap(), 0.0, 1.0)");
        names.put("gl_ModelViewMatrix", vertex?"mcglModelViewMatrix()":"uModelView"); names.put("gl_ProjectionMatrix", "uProjection");
        names.put("gl_ModelViewProjectionMatrix", vertex?"(uProjection * mcglModelViewMatrix())":"(uProjection * uModelView)"); names.put("gl_NormalMatrix", vertex?"mcglNormalMatrix()":"uNormalMatrix");
        names.put("gl_Fog", "uGameFog");
        names.put("gl_FrontMaterial", "uGameMaterial"); names.put("gl_LightModel", "uGameLightModel");
        Matcher matcher = TOKEN.matcher(source); StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(), replacement = names.get(token);
            if (token.startsWith("gl_") && replacement == null && !token.equals("gl_Position")
                    && !token.equals("gl_PointSize") && !token.equals("gl_PointCoord")
                    && !token.equals("gl_FragCoord") && !token.equals("gl_FrontFacing") && !token.equals("gl_FragDepth"))
                throw new IllegalArgumentException("Unmigrated game shader input: " + token);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? token : replacement));
        }
        matcher.appendTail(result);
        String migrated = result.toString();
        {
            Pattern main = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
            Matcher entry = main.matcher(migrated);
            if (!entry.find()) throw new IllegalArgumentException("Missing game shader entry point");
            migrated = entry.replaceFirst("void mcglEffectMain()");
            migrated += vertex ? "\nvoid main() { mcglChunkInputs(); if ((uGameAttributeMask & 512) != 0) { mcglInputPosition=aLineOther; mcglEffectMain(); vec4 other=gl_Position; mcglInputPosition=aPosition; mcglEffectMain(); gl_Position=mcglExpandLine(gl_Position,other); } else { mcglInputPosition=aPosition; mcglEffectMain(); } }\n"
                    : "\nvoid main() { mcglEffectMain(); if (!mcglAlphaPass(mcglOutColor.a)) discard; }\n";
        }
        return "#version 410 core\n" + declarations(vertex,textureTables) + "\n#line 1\n" + migrated;
    }
    static String declarations(boolean vertex) {
        return declarations(vertex,false);
    }
    static String declarations(boolean vertex,boolean textureTables) {
        return declarations(vertex,textureTables,false);
    }
    static String declarations(boolean vertex,boolean textureTables,boolean originalPalette) {
        String shared = "uniform mat4 uModelView, uProjection;\nuniform mat3 uNormalMatrix;\n"
                + "struct GameFog { vec4 color; float start; float end; float density; };\nuniform GameFog uGameFog;\n"
                + lightingDeclarations();
        if (!vertex) return shared + (textureTables?textureDeclarations():"vec4 mcglTextureProj(sampler2D original,vec3 uv){return textureProj(original,uv);}\n") + "layout(location=0) out vec4 mcglOutColor;\n"
                + "uniform int uAlphaFunction;\nuniform float uAlphaReference;\n"
                + "bool mcglAlphaPass(float a) { float r = uAlphaReference;\n"
                + "if (uAlphaFunction == 0 || uAlphaFunction == 8) return true;\n"
                + "if (uAlphaFunction == 1) return false; if (uAlphaFunction == 2) return a < r;\n"
                + "if (uAlphaFunction == 3) return a == r; if (uAlphaFunction == 4) return a <= r;\n"
                + "if (uAlphaFunction == 5) return a > r; if (uAlphaFunction == 6) return a != r; return a >= r; }\n";
        return shared + "layout(location=0) in vec3 aPosition;\nlayout(location=1) in vec4 aColor;\n"
                + "layout(location=2) in vec2 aTexCoord;\nlayout(location=3) in vec2 aLightmap;\n"
                + "layout(location=4) in vec3 aNormal;\nlayout(location=7) in float aGameVertexMask;\nuniform int uGameAttributeMask;\n"
                + "layout(location=9) in vec3 aLineOther;\nlayout(location=10) in vec2 aLineCorner;\n"
                + "layout(location=11) in vec4 aChunkTransform;\nlayout(location=12) in vec3 aChunkRegion;\nuniform vec3 uGameChunkRegion;\n"
                + "layout(location=13) in float aChunkInputs;\n"
                + (textureTables?"flat out int vGameChunkTexture;\nvoid mcglChunkInputs(){vGameChunkTexture=(uGameAttributeMask & 2048)!=0 ? (int(aChunkInputs)&15) : 0;}\n":"void mcglChunkInputs(){}\n")
                + (originalPalette?"layout(location=14) in float aOriginalModelTag;\nuniform mat4 uOriginalModelPalette[32];\nmat4 mcglModelViewMatrix(){return uOriginalModelPalette[int(aOriginalModelTag)];}\n"
                    :"mat4 mcglModelViewMatrix() { if((uGameAttributeMask & 1024)==0) return uModelView; mat4 m=uModelView; vec3 offset=(aChunkRegion-uGameChunkRegion)+aChunkTransform.xyz; m[3]=uModelView*vec4(offset,1.0); m[0]*=aChunkTransform.w; m[1]*=aChunkTransform.w; m[2]*=aChunkTransform.w; return m; }\n")
                + "mat3 mcglNormalMatrix() { return (uGameAttributeMask & 1024)==0 ? uNormalMatrix : uNormalMatrix/aChunkTransform.w; }\n"
                + "uniform float uGameLineWidth;\nuniform vec2 uGameViewport;\nvec3 mcglInputPosition;\n"
                + "vec4 mcglExpandLine(vec4 clip, vec4 other) { vec4 a=clip,b=other; float da=a.z+a.w,db=b.z+b.w;\n"
                + "if(da<0.0 && db>=0.0) a=mix(clip,other,da/(da-db)); if(db<0.0 && da>=0.0) b=mix(other,clip,db/(db-da));\n"
                + "vec2 delta=(b.xy/max(abs(b.w),1e-6)-a.xy/max(abs(a.w),1e-6))*uGameViewport*aLineCorner.y; float len=length(delta);\n"
                + "if(len>1e-6) clip.xy+=vec2(-delta.y,delta.x)/len*aLineCorner.x*uGameLineWidth/max(uGameViewport,vec2(1.0))*clip.w; return clip; }\n"
                + "uniform vec4 uGameColor;\nuniform vec3 uGameNormal;\nuniform vec2 uGameUv, uGameLightmap;\n"
                + "int mcglVertexMask() { if((uGameAttributeMask & 2048)!=0)return int(aChunkInputs)>>4; return (uGameAttributeMask & 128) != 0 ? int(aGameVertexMask) : uGameAttributeMask; }\n"
                + "vec4 mcglVertexColor() { return (mcglVertexMask() & 2) != 0 ? aColor : uGameColor; }\n"
                + "vec3 mcglVertexNormal() { return (mcglVertexMask() & 16) != 0 ? aNormal : uGameNormal; }\n"
                + "vec2 mcglVertexUv() { return (mcglVertexMask() & 4) != 0 ? aTexCoord : uGameUv; }\n"
                + "vec2 mcglVertexLightmap() { return (mcglVertexMask() & 8) != 0 ? aLightmap : uGameLightmap; }\n";
    }
    /** Shared material/light contract; text does not import terrain or line vertex attributes. */
    static String lightingDeclarations() {
        return "struct GameLight { vec4 position; vec4 halfVector; vec4 ambient; vec4 diffuse; vec4 specular; };\n"
                + "uniform GameLight uGameLight0, uGameLight1;\nstruct GameMaterial { vec4 ambient; vec4 diffuse; };\n"
                + "uniform GameMaterial uGameMaterial;\nstruct GameLightModel { vec4 ambient; };\n"
                + "uniform GameLightModel uGameLightModel;\n";
    }
    /** Only the shipped, two-argument colorMap lookup can use a chunk's primary texture override.
     * Other sampler names, units, projective/bias calls and arbitrary sampler expressions retain their ordinary path. */
    public static boolean chunkTexturesSupported(CharSequence input) {
        String source=stripComments(input.toString());
        if(!Pattern.compile("\\buniform\\s+sampler2D\\s+colorMap\\s*;").matcher(source).find())return false;
        Matcher calls=Pattern.compile("\\btexture2D\\s*\\(\\s*colorMap\\s*,").matcher(source);int lookups=0;
        while(calls.find()) {
            int depth=1;boolean closed=false;
            for(int i=calls.end();i<source.length();i++){char c=source.charAt(i);if(c=='(')depth++;else if(c==')'){if(--depth==0){closed=true;break;}}else if(c==','&&depth==1)return false;}
            if(!closed)return false;lookups++;
        }
        Matcher names=Pattern.compile("\\bcolorMap\\b").matcher(source);int references=0;while(names.find())references++;
        return lookups>0&&references==lookups+1;
    }
    private static String textureDeclarations() {
        StringBuilder text=new StringBuilder("flat in int vGameChunkTexture;\nuniform int uGameChunkTextureRouting;\n");
        for(int i=1;i<=8;i++)text.append("uniform sampler2D uGameChunkTexture").append(i).append(";\n");
        // GLSL 4.10 §§4.1.7/8.7: no varying sampler-array indices or implicit derivatives in a divergent branch.
        text.append("vec4 mcglTexture2D(sampler2D original,vec2 uv){\n")
            .append("if(uGameChunkTextureRouting==0)return texture(original,uv);\n")
            .append("vec2 dx=dFdx(uv),dy=dFdy(uv);\n");
        for(int i=1;i<=8;i++)text.append("if(vGameChunkTexture==").append(i).append(")return textureGrad(uGameChunkTexture").append(i).append(",uv,dx,dy);\n");
        return text.append("return textureGrad(original,uv,dx,dy);}\n")
            .append("vec4 mcglTextureProj(sampler2D original,vec3 uv){if(uGameChunkTextureRouting==0)return textureProj(original,uv);return mcglTexture2D(original,uv.xy/uv.z);}\n").toString();
    }
    /** Preserve line numbers and token separation; comments must not accidentally become migrated code. */
    private static String stripComments(String text) {
        StringBuilder result = new StringBuilder(text.length()); boolean line = false, block = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (!line && !block && c == '/' && (next == '/' || next == '*')) {
                line = next == '/'; block = next == '*'; result.append("  "); i++; continue;
            }
            if (block && c == '*' && next == '/') { block = false; result.append("  "); i++; continue; }
            if (c == '\n' || c == '\r') { line = false; result.append(c); }
            else result.append(line || block ? ' ' : c);
        }
        if (block) throw new IllegalArgumentException("Unterminated shader comment");
        return result.toString();
    }
}
