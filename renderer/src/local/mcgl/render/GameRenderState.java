package local.mcgl.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Set;

/** Explicit material inputs for the migrated game. Native raster/texture state is separate. */
public final class GameRenderState {
    public final GameMatrices matrices = new GameMatrices();
    private final Set<Integer> enabled = new HashSet<Integer>();
    private final boolean[] textured = new boolean[4];
    private final float[][] uv = new float[4][2];
    private float[] color = {1, 1, 1, 1}, normal = {0, 0, 1}, fogColor = {0, 0, 0, 0};
    private float[] ambient = {.2f, .2f, .2f, 1}, materialAmbient = {.2f, .2f, .2f, 1};
    private float[] materialDiffuse = {.8f, .8f, .8f, 1};
    private Light[] lights = { new Light(true), new Light(false) };
    private int activeUnit, alphaFunction = 519, fogMode = 2048, fogDistance = 0, shade = 7425;
    private int colorMaterialFace = 1032, colorMaterialMode = 5634;
    private float alphaReference, fogStart, fogEnd = 1, fogDensity = 1;
    private final FloatBuffer matrixBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private static final class Light {
        float[] position = {0, 0, 1, 0}, diffuse, ambient = {0, 0, 0, 1}, specular;
        Light(boolean first) { diffuse = first ? new float[] {1, 1, 1, 1} : new float[] {0, 0, 0, 1}; specular = diffuse.clone(); }
        Light copy() { Light result = new Light(false); result.position = position.clone(); result.diffuse = diffuse.clone();
            result.ambient = ambient.clone(); result.specular = specular.clone(); return result; }
    }
    /** Capability handled by explicit material inputs, never forwarded to native fixed-function GL. */
    public static boolean materialCapability(int capability) {
        return capability == 3553 || capability == 2896 || capability == 16384 || capability == 16385
                || capability == 2903 || capability == 2977 || capability == 32826 || capability == 2912 || capability == 3008
                || capability == 34913; // Point coordinates are shader inputs in Core, not a native enable.
    }
    public void enable(int capability, boolean value) {
        if (!materialCapability(capability)) throw new IllegalArgumentException("Not a material capability: " + capability);
        if (capability == 3553) textured[activeUnit] = value;
        else if (value) enabled.add(capability); else enabled.remove(capability);
        if (capability == 2903 && value) trackColorMaterial();
    }
    public boolean enabled(int capability) { return capability == 3553 ? textured[activeUnit] : enabled.contains(capability); }
    public int activeUnit() { return activeUnit; }
    public void activeUnit(int unit) {
        if (unit < 0 || unit >= 4) throw new IllegalArgumentException("Game texture unit: " + unit);
        activeUnit = unit; matrices.textureUnit(unit);
    }
    public float[] color() { return color.clone(); }
    public float[] normal() { return normal.clone(); }
    public float[] uv(int unit) { return uv[unit].clone(); }
    public void color(float r, float g, float b, float a) {
        color = new float[] {clamp(r), clamp(g), clamp(b), clamp(a)};
        if (enabled(2903)) trackColorMaterial();
    }
    public void normal(float x, float y, float z) { normal = finite(x, y, z); }
    public void uv(int unit, float u, float v) {
        if (unit < 0 || unit >= 4) throw new IllegalArgumentException("Game texture coordinate unit");
        float[] values = finite(u, v); uv[unit][0] = values[0]; uv[unit][1] = values[1];
    }
    public void alpha(int function, float reference) {
        if (function < 512 || function > 519) throw new IllegalArgumentException("Alpha function: " + function);
        alphaFunction = function; alphaReference = clamp(reference);
    }
    public void shade(int mode) {
        if (mode != 7424 && mode != 7425) throw new IllegalArgumentException("Shade mode: " + mode); shade = mode;
    }
    public int shade() { return shade; }
    public void fog(int parameter, float value) {
        finite(value);
        if (parameter == 2915) fogStart = value;
        else if (parameter == 2916) fogEnd = value;
        else if (parameter == 2914 && value >= 0) fogDensity = value;
        else if (parameter == 2917 && (value == 2048 || value == 2049 || value == 9729)) fogMode = (int)value;
        else if (parameter == 34138 && (value == 34139 || value == 34140)) fogDistance = value == 34139 ? 1 : 0;
        else throw new IllegalArgumentException("Unsupported game fog parameter: " + parameter + "=" + value);
    }
    public void fog(int parameter, float[] value) {
        if (parameter != 2918 || value.length != 4) throw new IllegalArgumentException("Expected fog color"); fogColor = finite(value).clone();
    }
    public void light(int index, int parameter, float[] value) {
        if (index < 0 || index >= lights.length || value.length != 4) throw new IllegalArgumentException("Game light");
        finite(value); Light light = lights[index];
        if (parameter == 4611) light.position = GameMatrices.transform(matrices.modelView(), value);
        else if (parameter == 4608) light.ambient = value.clone();
        else if (parameter == 4609) light.diffuse = value.clone();
        else if (parameter == 4610) light.specular = value.clone();
        else throw new IllegalArgumentException("Unsupported game light parameter: " + parameter);
    }
    public void lightModel(int parameter, float[] value) {
        if (parameter != 2899 || value.length != 4) throw new IllegalArgumentException("Expected light model ambient"); ambient = finite(value).clone();
    }
    public void colorMaterial(int face, int mode) {
        if ((face != 1028 && face != 1032) || (mode != 4608 && mode != 4609 && mode != 5634))
            throw new IllegalArgumentException("Unsupported game color material: " + face + "/" + mode);
        colorMaterialFace = face; colorMaterialMode = mode; if (enabled(2903)) trackColorMaterial();
    }
    private void trackColorMaterial() {
        if (colorMaterialMode == 4608 || colorMaterialMode == 5634) materialAmbient = color.clone();
        if (colorMaterialMode == 4609 || colorMaterialMode == 5634) materialDiffuse = color.clone();
    }
    public static float[] read(FloatBuffer buffer, int count) {
        if (buffer == null || buffer.remaining() < count) throw new IllegalArgumentException("Insufficient game parameter buffer");
        float[] result = new float[count]; for (int i = 0; i < count; i++) result[i] = buffer.get(buffer.position() + i);
        return finite(result);
    }
    public void bind(ShaderProgram program, int attributeMask) {
        bind(program,attributeMask,null);
    }
    /** Optional original draw matrix without mutating the logical matrix stack. */
    public void bind(ShaderProgram program,int attributeMask,double[] originalModel) {
        if(originalModel!=null&&originalModel.length!=16)throw new IllegalArgumentException("Original model matrix");
        double[] model = originalModel==null?matrices.modelView():originalModel; matrix4(program, "uModelView", model); matrix4(program, "uProjection", matrices.projection());
        matrix3(program, "uNormalMatrix", GameMatrices.normal(model));
        matrix4(program, "uTextureMatrix", matrices.texture(0)); matrix4(program, "uLightmapMatrix", matrices.texture(1));
        integer(program, "uGameAttributeMask", attributeMask); vector(program, "uGameColor", color); vector(program, "uGameNormal", normal);
        vector(program, "uGameUv", uv[0]); vector(program, "uGameLightmap", uv[1]);
        integer(program, "uTextured", textured[0] ? 1 : 0); integer(program, "uLightmapped", textured[1] ? 1 : 0);
        integer(program, "uTexture", 0); integer(program, "uLightmap", 1);
        integer(program, "uLighting", enabled(2896) ? 1 : 0); integer(program, "uFlat", shade == 7424 ? 1 : 0);
        integer(program, "uNormalize", enabled(2977) || enabled(32826) ? 1 : 0);
        integer(program, "uColorMaterial", enabled(2903) ? colorMaterialMode : 0);
        integer(program, "uAlphaFunction", enabled(3008) ? alphaFunction - 511 : 0);
        scalar(program, "uAlphaReference", alphaReference);
        integer(program, "uFogMode", !enabled(2912) ? 0 : fogMode == 9729 ? 1 : fogMode == 2048 ? 2 : 3);
        integer(program, "uFogDistance", fogDistance); vector(program, "uGameFog.color", fogColor);
        scalar(program, "uGameFog.start", fogStart); scalar(program, "uGameFog.end", fogEnd); scalar(program, "uGameFog.density", fogDensity);
        vector(program, "uGameLightModel.ambient", ambient); vector(program, "uGameMaterial.ambient", materialAmbient);
        vector(program, "uGameMaterial.diffuse", materialDiffuse);
        for (int i = 0; i < lights.length; i++) {
            Light light = lights[i]; String prefix = "uGameLight" + i + ".";
            integer(program, "uLightEnabled" + i, enabled(16384 + i) ? 1 : 0);
            vector(program, prefix + "position", light.position); vector(program, prefix + "ambient", light.ambient);
            vector(program, prefix + "diffuse", light.diffuse); vector(program, prefix + "specular", light.specular);
            double n = Math.sqrt(light.position[0]*light.position[0] + light.position[1]*light.position[1] + light.position[2]*light.position[2]);
            float x = n == 0 ? 0 : (float)(light.position[0]/n), y = n == 0 ? 0 : (float)(light.position[1]/n);
            float z = n == 0 ? 1 : (float)(light.position[2]/n) + 1;
            double hn = Math.sqrt(x*x+y*y+z*z);
            vector(program, prefix + "halfVector", hn == 0 ? new float[4] : new float[] {(float)(x/hn), (float)(y/hn), (float)(z/hn), 0});
        }
    }
    private void matrix4(ShaderProgram program, String name, double[] value) {
        ShaderUniform uniform = program.findUniform(name); if (uniform == null) return;
        matrixBuffer.clear(); for (double number : value) matrixBuffer.put((float)number); matrixBuffer.flip(); uniform.setMatrix4(matrixBuffer);
    }
    private void matrix3(ShaderProgram program, String name, float[] value) {
        ShaderUniform uniform = program.findUniform(name); if (uniform == null) return;
        matrixBuffer.clear(); matrixBuffer.put(value).flip(); uniform.setMatrix3(matrixBuffer);
    }
    private static void integer(ShaderProgram p, String name, int value) { ShaderUniform u = p.findUniform(name); if (u != null) u.setInt(value); }
    private static void scalar(ShaderProgram p, String name, float value) { ShaderUniform u = p.findUniform(name); if (u != null) u.setFloat(value); }
    private static void vector(ShaderProgram p, String name, float[] v) {
        ShaderUniform u = p.findUniform(name); if (u == null) return;
        if (v.length == 2) u.setVec2(v[0], v[1]); else if (v.length == 3) u.setVec3(v[0], v[1], v[2]); else u.setVec4(v[0], v[1], v[2], v[3]);
    }
    private static float clamp(float value) { finite(value); return Math.max(0, Math.min(1, value)); }
    private static float[] finite(float... values) {
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite game material input"); return values;
    }
    public Snapshot snapshot(int mask) { return new Snapshot(mask, this); }
    /** Group-aware attribute scopes; matrices are intentionally NOT included. */
    public static final class Snapshot {
        private final int mask;
        private final GameRenderState copy = new GameRenderState();
        private Snapshot(int mask, GameRenderState state) { this.mask = mask; copy.copyGroups(state, mask); }
        public void restore(GameRenderState state) { state.copyGroups(copy, mask); }
    }
    private void copyGroups(GameRenderState source, int mask) {
        if ((mask & 1) != 0) {
            color = source.color.clone(); normal = source.normal.clone();
            for (int i = 0; i < uv.length; i++) System.arraycopy(source.uv[i], 0, uv[i], 0, 2);
            if (enabled(2903)) trackColorMaterial();
        }
        if ((mask & 0x2000) != 0) {
            enabled.clear(); enabled.addAll(source.enabled); System.arraycopy(source.textured, 0, textured, 0, textured.length);
        }
        if ((mask & 0x40000) != 0) { activeUnit(source.activeUnit); System.arraycopy(source.textured, 0, textured, 0, textured.length); }
        if ((mask & 0x4000) != 0) {
            alphaFunction = source.alphaFunction; alphaReference = source.alphaReference; copyEnable(source, 3008);
        }
        if ((mask & 0x80) != 0) {
            fogColor = source.fogColor.clone(); fogStart = source.fogStart; fogEnd = source.fogEnd;
            fogDensity = source.fogDensity; fogMode = source.fogMode; fogDistance = source.fogDistance; copyEnable(source, 2912);
        }
        if ((mask & 0x1000) != 0) { copyEnable(source, 2977); copyEnable(source, 32826); matrices.mode(source.matrices.mode()); }
        if ((mask & 2) != 0) copyEnable(source, 34913);
        if ((mask & 0x40) != 0) {
            ambient = source.ambient.clone(); materialAmbient = source.materialAmbient.clone(); materialDiffuse = source.materialDiffuse.clone();
            lights = new Light[] {source.lights[0].copy(), source.lights[1].copy()};
            shade = source.shade; colorMaterialFace = source.colorMaterialFace; colorMaterialMode = source.colorMaterialMode;
            copyEnable(source, 2896); copyEnable(source, 2903); copyEnable(source, 16384); copyEnable(source, 16385);
        }
    }
    private void copyEnable(GameRenderState source, int capability) { if (source.enabled.contains(capability)) enabled.add(capability); else enabled.remove(capability); }
}
