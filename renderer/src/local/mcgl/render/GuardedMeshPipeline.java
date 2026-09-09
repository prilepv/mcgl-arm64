package local.mcgl.render;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Cached meshes keep the same ownership, attachment and generation checks as their factory. */
final class GuardedMeshPipeline implements MeshPipeline {
    private final RenderContext context;
    private final MeshPipeline delegate;
    GuardedMeshPipeline(RenderContext context, MeshPipeline delegate) { this.context = context; this.delegate = delegate; }
    public Mesh create(String label, MeshData data, Usage usage) {
        context.checkOwner();
        if (label == null || !label.matches("[A-Za-z0-9_./-]{1,128}")) throw new IllegalArgumentException("Invalid mesh label");
        if (data == null || usage == null) throw new NullPointerException("mesh data/usage");
        Mesh mesh = delegate.create(label, data, usage);
        if (mesh == null) throw new IllegalStateException("Backend did not create a mesh");
        try { return new GuardedMesh(mesh); }
        catch (Throwable failure) {
            try { mesh.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public void unbind() { context.checkOwner(); delegate.unbind(); }
    public MeshArena createArena(String label,int vertexCapacity,int indexCapacity,long byteBudget) {
        context.checkOwner();
        if(label==null||!label.matches("[A-Za-z0-9_./-]{1,128}"))throw new IllegalArgumentException("Invalid arena label");
        if(vertexCapacity<1||vertexCapacity>4*1024*1024||indexCapacity<1||indexCapacity>12*1024*1024||byteBudget<1||byteBudget>256L*1024*1024)throw new IllegalArgumentException("Invalid arena bounds");
        MeshArena arena=delegate.createArena(label,vertexCapacity,indexCapacity,byteBudget);
        if(arena==null)throw new IllegalStateException("Backend did not create an arena");
        return new GuardedArena(arena);
    }
    private final class GuardedArena implements MeshArena {
        private final MeshArena target;
        private final List<Mesh> submitted=new ArrayList<Mesh>(MAX_DRAWS);
        GuardedArena(MeshArena target){this.target=target;}
        private void check(){context.checkOwner();if(target.isClosed())throw new IllegalStateException("Mesh arena is closed");}
        private GuardedMesh member(Mesh mesh){
            if(!(mesh instanceof GuardedMesh))throw new IllegalArgumentException("Unowned arena member");
            GuardedMesh owned=(GuardedMesh)mesh;
            if(owned.pipeline()!=GuardedMeshPipeline.this||!target.contains(owned.target))throw new IllegalArgumentException("Foreign arena member");
            owned.check();return owned;
        }
        public Mesh create(String label,MeshData data){
            check();if(label==null||!label.matches("[A-Za-z0-9_./-]{1,128}"))throw new IllegalArgumentException("Invalid member label");
            if(data==null)throw new NullPointerException("arena data");
            for(VertexLayout.Attribute attribute:data.layout().attributes())if(attribute.location==TAG_ATTRIBUTE)throw new IllegalArgumentException("Arena tag attribute is already occupied");
            if(data.layout().stride()>252)return null;
            Mesh mesh=target.create(label,data);return mesh==null?null:new GuardedMesh(mesh);
        }
        public Mesh importMesh(String label,Mesh source,long maximumBytes){
            check();if(label==null||!label.matches("[A-Za-z0-9_./-]{1,128}"))throw new IllegalArgumentException("Invalid imported member label");
            if(maximumBytes<1||maximumBytes>4L*1024*1024)throw new IllegalArgumentException("Invalid arena transfer budget");
            if(!(source instanceof GuardedMesh))throw new IllegalArgumentException("Unowned imported mesh");
            GuardedMesh owned=(GuardedMesh)source;
            if(owned.pipeline()!=GuardedMeshPipeline.this)throw new IllegalArgumentException("Imported mesh belongs to another context");
            owned.check();if(owned.usage()!=Usage.STATIC)throw new IllegalArgumentException("Only immutable meshes may be imported");
            if(owned.layout().stride()>252||(owned.layout().attributeMask()&(1<<TAG_ATTRIBUTE))!=0)return null;
            Mesh mesh=target.importMesh(label,owned.target,maximumBytes);return mesh==null?null:new GuardedMesh(mesh);
        }
        public boolean contains(Mesh mesh){check();return mesh instanceof GuardedMesh&&((GuardedMesh)mesh).pipeline()==GuardedMeshPipeline.this&&!mesh.isClosed()&&target.contains(((GuardedMesh)mesh).target);}
        public int tag(Mesh mesh){check();return target.tag(member(mesh).target);}
        public boolean compatible(Mesh first,Mesh second){check();return target.compatible(member(first).target,member(second).target);}
        public void draw(Mesh.Primitive primitive,List<Mesh> members){
            check();if(primitive==null||members==null)throw new NullPointerException("arena draw");
            if(members.size()>MAX_DRAWS)throw new IllegalArgumentException("Oversized arena draw");
            submitted.clear();
            try{
                for(Mesh mesh:members){GuardedMesh owned=member(mesh);if(owned.indexCount()%primitive.indicesPerPrimitive!=0)throw new IllegalArgumentException("Incomplete arena primitive");
                    if(!submitted.isEmpty()&&!target.compatible(submitted.get(0),owned.target))throw new IllegalArgumentException("Arena draw crosses GPU pages");submitted.add(owned.target);}
                if(!submitted.isEmpty())target.draw(primitive,submitted);
            }finally{submitted.clear();}
        }
        public long residentBytes(){check();return target.residentBytes();}
        public int residentPages(){check();return target.residentPages();}
        public int residentMembers(){check();return target.residentMembers();}
        public long pageCreations(){check();return target.pageCreations();}
        public boolean isClosed(){return context.isClosed()||target.isClosed();}
        public void close(){if(isClosed())return;context.checkOwner();target.close();submitted.clear();}
    }
    public Mesh combine(String label,List<Mesh> sources,IndexData indices) {
        context.checkOwner();
        if(label==null||!label.matches("[A-Za-z0-9_./-]{1,128}"))throw new IllegalArgumentException("Invalid mesh label");
        if(sources==null||indices==null)throw new NullPointerException("combined mesh inputs");
        if(sources.isEmpty())throw new IllegalArgumentException("Combined mesh needs sources");
        List<Mesh> owned=new ArrayList<Mesh>();VertexLayout layout=null;int vertices=0;
        for(Mesh source:sources) {
            if(!(source instanceof GuardedMesh))throw new IllegalArgumentException("Unowned combined mesh source");
            GuardedMesh mesh=(GuardedMesh)source;
            if(mesh.pipeline()!=this)throw new IllegalArgumentException("Combined mesh source belongs to another context");
            mesh.check();if(layout==null)layout=mesh.layout();else if(!layout.equals(mesh.layout()))throw new IllegalArgumentException("Combined mesh layouts differ");
            vertices=Math.addExact(vertices,mesh.vertexCount());owned.add(mesh.target);
        }
        if(indices.maximum()>=vertices)throw new IllegalArgumentException("Combined mesh index outside source vertices");
        Mesh mesh=delegate.combine(label,owned,indices);
        if(mesh==null)throw new IllegalStateException("Backend did not create a combined mesh");
        try{return new GuardedMesh(mesh);}catch(Throwable failure){try{mesh.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private final class GuardedMesh implements Mesh {
        private final Mesh target;
        GuardedMesh(Mesh target) { this.target = target; }
        private GuardedMeshPipeline pipeline(){return GuardedMeshPipeline.this;}
        private void check() {
            context.checkOwner();
            if (target.isClosed()) throw new IllegalStateException("Mesh is closed: " + target.label());
        }
        public String label() { return target.label(); }
        public boolean isClosed() { return context.isClosed() || target.isClosed(); }
        public VertexLayout layout() { return target.layout(); }
        public int vertexCount() { return target.vertexCount(); }
        public int indexCount() { return target.indexCount(); }
        public IndexData.Type indexType() { return target.indexType(); }
        public Usage usage() { return target.usage(); }
        public void draw(Primitive primitive, int first, int count) {
            check();
            if (primitive == null) throw new NullPointerException("primitive");
            if (first < 0 || count < 0 || (long)first + count > indexCount()
                    || count % primitive.indicesPerPrimitive != 0)
                throw new IllegalArgumentException("Invalid indexed draw range");
            if (count != 0) target.draw(primitive, first, count);
        }
        public void updateVertices(int first, ByteBuffer data) {
            check(); mutable();
            int count = MeshData.validateVertices(layout(), data);
            if (first < 0 || (long)first + count > vertexCount()) throw new IllegalArgumentException("Vertex update exceeds allocation");
            if (count != 0) target.updateVertices(first, data);
        }
        public void updateIndices(IndexData data) {
            check(); mutable();
            if (data == null) throw new NullPointerException("indices");
            if (data.count() != indexCount() || data.type() != indexType() || data.maximum() >= vertexCount())
                throw new IllegalArgumentException("Index update changes allocation/type or exceeds vertex count");
            if (data.count() != 0) target.updateIndices(data);
        }
        private void mutable() {
            if (usage() == Usage.STATIC) throw new IllegalStateException("Static mesh cannot be updated");
        }
        public void close() {
            if (isClosed()) return;
            context.checkOwner(); target.close();
        }
    }
}
