package local.mcgl.render;

import java.util.List;

/** Bounded, context-owned storage for independent immutable meshes.
 * A member adds one constant tag at attribute 14. Tags may be reused by
 * different live members: the caller must split a palette run on conflicting tags.
 * Neither allocation nor ordered drawing changes the source topology or order. */
public interface MeshArena extends AutoCloseable {
    int TAG_ATTRIBUTE=14, TAG_COUNT=32, MAX_DRAWS=256;
    /** Explicit palette size and optional unused source byte. Other layouts keep
     * the ordinary appended float tag; existing callers retain 32 float tags. */
    final class Tags {
        public static final Tags DEFAULT=new Tags(TAG_COUNT);
        public final int count,packedOffset;
        public final VertexLayout packedLayout;
        public Tags(int count){this(count,null,-1);}
        public Tags(int count,VertexLayout packedLayout,int packedOffset){
            if(count<1||count>128||(count&(count-1))!=0)throw new IllegalArgumentException("Arena tags require a power of two up to 128");
            if(packedLayout==null){if(packedOffset!=-1)throw new IllegalArgumentException("Packed tag needs a layout");}
            else{
                if(packedOffset<0||packedOffset>=packedLayout.stride()||(packedLayout.attributeMask()&(1<<TAG_ATTRIBUTE))!=0)throw new IllegalArgumentException("Invalid packed tag byte");
                for(VertexLayout.Attribute a:packedLayout.attributes())if(packedOffset>=a.offset&&packedOffset<a.offset+a.components*a.storage.bytes)throw new IllegalArgumentException("Packed tag overlaps a source field");
            }
            this.count=count;this.packedLayout=packedLayout;this.packedOffset=packedOffset;
        }
    }
    /** Returns null when this member cannot fit the configured arena budget.
     * The original CPU streams are copied during upload and are never retained. */
    Mesh create(String label,MeshData data);
    /** Copies an immutable mesh from this context only when a destination range and
     * the explicit transfer budget are available. The source remains owned by its
     * caller. No CPU vertex snapshot is retained after this resource operation. */
    Mesh importMesh(String label,Mesh source,long maximumBytes);
    boolean contains(Mesh member);
    int tag(Mesh member);
    /** True only for members that can be submitted together without rebinding a VAO. */
    boolean compatible(Mesh first,Mesh second);
    /** Full members, in the exact supplied order, including intentional duplicates.
     * Leaves the shared VAO bound, just like Mesh.draw. */
    void draw(Mesh.Primitive primitive,List<Mesh> members);
    long residentBytes();
    int residentPages();
    int residentMembers();
    long pageCreations();
    boolean isClosed();
    @Override void close();
}
