package local.mcgl.render;

import static local.mcgl.render.VertexLayout.Storage.*;

/** Packed inputs for ShaderLibrary; color RGBA bytes, lightmap raw shorts, signed normalized normals. */
public final class VertexFormats {
    private VertexFormats() {}
    public static final VertexLayout POSITION_COLOR = layout(false, false, false);
    public static final VertexLayout TEXTURED = layout(true, false, false);
    public static final VertexLayout LIGHTMAPPED = layout(true, true, false);
    public static final VertexLayout LIT_TEXTURED = layout(true, false, true);
    public static final VertexLayout LIT_LIGHTMAPPED = layout(true, true, true);
    private static VertexLayout layout(boolean texture, boolean lightmap, boolean normal) {
        java.util.List<VertexLayout.Attribute> a = new java.util.ArrayList<VertexLayout.Attribute>();
        a.add(new VertexLayout.Attribute(ShaderLibrary.POSITION, 3, FLOAT32, false, 0));
        a.add(new VertexLayout.Attribute(ShaderLibrary.COLOR, 4, UINT8, true, 12));
        int stride = 16;
        if (texture) { a.add(new VertexLayout.Attribute(ShaderLibrary.TEXCOORD, 2, FLOAT32, false, stride)); stride += 8; }
        if (lightmap) { a.add(new VertexLayout.Attribute(ShaderLibrary.LIGHTMAP, 2, INT16, false, stride)); stride += 4; }
        if (normal) { a.add(new VertexLayout.Attribute(ShaderLibrary.NORMAL, 3, INT8, true, stride)); stride += 4; }
        return new VertexLayout(stride, a.toArray(new VertexLayout.Attribute[0]));
    }
}
