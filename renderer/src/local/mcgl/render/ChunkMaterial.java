package local.mcgl.render;

/** Logical material, not a borrowed native texture/program name. The frame binder resolves resources. */
public final class ChunkMaterial {
    public final String key;
    public final int pass;
    public final ShaderLibrary.Material shader;
    public final ShaderLibrary.AlphaTest alphaTest;
    public final float alphaReference;

    /** Original terrain passes: zero writes opaque/cutout geometry; passes one and two are sorted. */
    public ChunkMaterial(String key, int pass, ShaderLibrary.Material shader,
                         ShaderLibrary.AlphaTest alphaTest, float alphaReference) {
        if (key == null || shader == null || alphaTest == null) throw new NullPointerException("chunk material");
        if (!key.matches("[A-Za-z0-9_./-]{1,128}") || pass < 0 || pass > 2
                || !Float.isFinite(alphaReference) || alphaReference < 0 || alphaReference > 1)
            throw new IllegalArgumentException("Invalid chunk material");
        this.key = key; this.pass = pass; this.shader = shader;
        this.alphaTest = alphaTest; this.alphaReference = alphaReference;
    }
    public boolean translucent() { return pass != 0; }
}
