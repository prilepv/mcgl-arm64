package local.mcgl.render;

import java.util.List;

/** Bounded, context-owned storage for independent immutable meshes.
 * A member adds one constant float tag at attribute 14. Tags may be reused by
 * different live members: the caller must split a palette run on conflicting tags.
 * Neither allocation nor ordered drawing changes the source topology or order. */
public interface MeshArena extends AutoCloseable {
    int TAG_ATTRIBUTE=14, TAG_COUNT=32, MAX_DRAWS=256;
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
