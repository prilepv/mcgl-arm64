package local.mcgl.render;

import java.util.*;

/** Immutable interleaved floating-point shader input description; no native enums. */
public final class VertexLayout {
    public enum Storage {
        FLOAT32(4), INT8(1), UINT8(1), INT16(2), UINT16(2);
        public final int bytes;
        Storage(int bytes) { this.bytes = bytes; }
    }
    public static final class Attribute {
        public final int location, components, offset;
        public final Storage storage;
        public final boolean normalized;
        public Attribute(int location, int components, Storage storage, boolean normalized, int offset) {
            if (storage == null) throw new NullPointerException("attribute storage");
            if (location < 0 || location >= 16 || components < 1 || components > 4 || offset < 0
                    || offset % storage.bytes != 0 || (storage == Storage.FLOAT32 && normalized))
                throw new IllegalArgumentException("Invalid vertex attribute");
            this.location = location; this.components = components; this.storage = storage;
            this.normalized = normalized; this.offset = offset;
        }
    }
    private final int stride, mask;
    private final List<Attribute> attributes;
    public VertexLayout(int stride, Attribute... attributes) {
        if (stride <= 0 || stride > 256 || attributes == null || attributes.length == 0)
            throw new IllegalArgumentException("Vertex layout needs a 1..256 byte stride and attributes");
        List<Attribute> copy = new ArrayList<Attribute>();
        int mask = 0;
        for (Attribute a : attributes) {
            if (a == null) throw new NullPointerException("vertex attribute");
            if ((mask & (1 << a.location)) != 0 || stride % a.storage.bytes != 0
                    || (long)a.offset + a.components * a.storage.bytes > stride)
                throw new IllegalArgumentException("Duplicate, unaligned or out-of-stride attribute");
            // Overlapping fields are deliberately unsupported, including differently typed aliases.
            for (Attribute b : copy)
                if (a.offset < b.offset + b.components * b.storage.bytes
                        && b.offset < a.offset + a.components * a.storage.bytes)
                    throw new IllegalArgumentException("Overlapping vertex attributes");
            mask |= 1 << a.location; copy.add(a);
        }
        this.stride = stride; this.mask = mask;
        this.attributes = Collections.unmodifiableList(copy);
    }
    public int stride() { return stride; }
    public int attributeMask() { return mask; }
    public List<Attribute> attributes() { return attributes; }
    @Override public boolean equals(Object other) {
        if(!(other instanceof VertexLayout))return false;VertexLayout layout=(VertexLayout)other;
        if(stride!=layout.stride||attributes.size()!=layout.attributes.size())return false;
        for(int i=0;i<attributes.size();i++){Attribute a=attributes.get(i),b=layout.attributes.get(i);if(a.location!=b.location||a.components!=b.components||a.offset!=b.offset||a.storage!=b.storage||a.normalized!=b.normalized)return false;}
        return true;
    }
    @Override public int hashCode(){int result=stride;for(Attribute a:attributes){result=31*result+a.location;result=31*result+a.components;result=31*result+a.offset;result=31*result+a.storage.ordinal();result=31*result+(a.normalized?1:0);}return result;}
    public void requireAttributes(int requiredMask) {
        if ((mask & requiredMask) != requiredMask)
            throw new IllegalArgumentException("Mesh lacks required shader attributes: " + requiredMask);
    }
}
