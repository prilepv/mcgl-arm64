package local.mcgl.render;

import java.nio.*;

/** Immutable, validated CPU index snapshot. Construction does not initialize native rendering. */
public final class IndexData {
    public enum Type {
        UINT16(2), UINT32(4);
        public final int bytes;
        Type(int bytes) { this.bytes = bytes; }
    }
    private final Type type;
    private final ByteBuffer bytes;
    private final int count, maximum;
    private IndexData(Type type, int[] values) {
        this.type = type; this.count = values.length;
        int maximum = -1;
        for (int value : values) {
            if (value < 0 || (type == Type.UINT16 && value > 65535))
                throw new IllegalArgumentException("Index outside supported unsigned range");
            maximum = Math.max(maximum, value);
        }
        this.maximum = maximum;
        ByteBuffer result = ByteBuffer.allocateDirect(Math.multiplyExact(count, type.bytes)).order(ByteOrder.nativeOrder());
        for (int value : values) {
            if (type == Type.UINT16) result.putShort((short)value); else result.putInt(value);
        }
        result.flip(); bytes = result.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }
    public static IndexData of(int... values) {
        if (values == null) throw new NullPointerException("indices");
        int[] copy = values.clone(); int maximum = -1;
        for (int value : copy) maximum = Math.max(maximum, value);
        return new IndexData(maximum <= 65535 ? Type.UINT16 : Type.UINT32, copy);
    }
    public static IndexData unsignedShort(ShortBuffer values) {
        if (values == null) throw new NullPointerException("indices");
        int[] copy = new int[values.remaining()];
        for (int i = 0; i < copy.length; i++) copy[i] = values.get(values.position() + i) & 65535;
        return new IndexData(Type.UINT16, copy);
    }
    public static IndexData unsignedInt(IntBuffer values) {
        if (values == null) throw new NullPointerException("indices");
        int[] copy = new int[values.remaining()];
        for (int i = 0; i < copy.length; i++) copy[i] = values.get(values.position() + i);
        return new IndexData(Type.UINT32, copy);
    }
    /** Each ordered convex quad becomes (0,1,2),(0,2,3); does not sort translucent faces. */
    public static IndexData quads(int vertexCount) {
        if (vertexCount < 0 || vertexCount % 4 != 0) throw new IllegalArgumentException("Quads require groups of four vertices");
        int[] indices = new int[Math.multiplyExact(vertexCount / 4, 6)];
        for (int v = 0, i = 0; v < vertexCount; v += 4) {
            indices[i++] = v; indices[i++] = v + 1; indices[i++] = v + 2;
            indices[i++] = v; indices[i++] = v + 2; indices[i++] = v + 3;
        }
        return new IndexData(vertexCount <= 65536 ? Type.UINT16 : Type.UINT32, indices);
    }
    public Type type() { return type; }
    public int count() { return count; }
    public int maximum() { return maximum; }
    public ByteBuffer bytes() { return bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder()); }
}
