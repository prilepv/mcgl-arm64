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
    private IndexData(Type type, int count, int maximum, ByteBuffer bytes) {
        this.type = type;
        this.count = count;
        this.maximum = maximum;
        this.bytes = bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }
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
    /** Non-indexed streams use a shared immutable sequence of vertex indices. */
    public static IndexData sequential(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative index count");
        if (count <= 65536) {
            ByteBuffer prefix = SequentialIndices.SHORTS.asReadOnlyBuffer();
            prefix.limit(count * Type.UINT16.bytes);
            return new IndexData(Type.UINT16, count, count - 1, prefix);
        }
        ByteBuffer data = ByteBuffer.allocateDirect(Math.multiplyExact(count, Type.UINT32.bytes)).order(ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) data.putInt(i);
        data.flip();
        return new IndexData(Type.UINT32, count, count - 1, data);
    }
    private static final class SequentialIndices {
        static final ByteBuffer SHORTS = build();
        private static ByteBuffer build() {
            ByteBuffer data = ByteBuffer.allocateDirect(65536 * Type.UINT16.bytes).order(ByteOrder.nativeOrder());
            for (int i = 0; i < 65536; i++) data.putShort((short)i);
            data.flip();
            return data.asReadOnlyBuffer();
        }
    }
    /** Each ordered convex quad becomes (0,1,2),(0,2,3); does not sort translucent faces. */
    public static IndexData quads(int vertexCount) {
        if (vertexCount < 0 || vertexCount % 4 != 0) throw new IllegalArgumentException("Quads require groups of four vertices");
        int count = Math.multiplyExact(vertexCount / 4, 6);
        if (vertexCount <= 65536) {
            ByteBuffer prefix = QuadIndices.SHORTS.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            prefix.limit(count * Type.UINT16.bytes);
            return new IndexData(Type.UINT16, count, vertexCount - 1, prefix.slice());
        }
        ByteBuffer data = ByteBuffer.allocateDirect(Math.multiplyExact(count, Type.UINT32.bytes)).order(ByteOrder.nativeOrder());
        for (int v = 0; v < vertexCount; v += 4) {
            data.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v).putInt(v + 2).putInt(v + 3);
        }
        data.flip();
        return new IndexData(Type.UINT32, count, vertexCount - 1, data);
    }
    /** Ordinary quad meshes use prefixes of the same immutable 192 KiB index sequence. */
    private static final class QuadIndices {
        static final ByteBuffer SHORTS = build();
        private static ByteBuffer build() {
            ByteBuffer data = ByteBuffer.allocateDirect(65536 / 4 * 6 * Type.UINT16.bytes).order(ByteOrder.nativeOrder());
            for (int v = 0; v < 65536; v += 4) {
                data.putShort((short)v).putShort((short)(v + 1)).putShort((short)(v + 2));
                data.putShort((short)v).putShort((short)(v + 2)).putShort((short)(v + 3));
            }
            data.flip();
            return data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        }
    }
    public Type type() { return type; }
    public int count() { return count; }
    public int maximum() { return maximum; }
    public ByteBuffer bytes() { return bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder()); }
}
