package local.mcgl.render.backend;

import java.nio.*;
import java.util.*;
import java.util.function.Consumer;
import local.mcgl.render.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.*;

/** Shared immutable ranges, not a frame-sized vertex cache. Rebuilds upload only new
 * ranges; visibility and camera changes never repack or read back these GPU pages. */
final class NativeMeshArena implements MeshArena {
    private final NativeMeshPipeline owner;
    private final String label;
    private final int vertexCapacity,indexCapacity;
    private final long byteBudget;
    private final Tags tags;
    private final Consumer<NativeMeshArena> retired;
    private final List<Page> pages=new ArrayList<Page>();
    private final Set<Member> members=Collections.newSetFromMap(new IdentityHashMap<Member,Boolean>());
    private final boolean asyncUploads=Boolean.parseBoolean(System.getProperty("mcgl.terrain.asyncUploads","true"));
    private final List<RetiredRange> pending=new ArrayList<RetiredRange>();
    private final IntBuffer counts=BufferUtils.createIntBuffer(MAX_DRAWS),bases=BufferUtils.createIntBuffer(MAX_DRAWS);
    private final PointerBuffer offsets=BufferUtils.createPointerBuffer(MAX_DRAWS);
    private long bytes,creations;
    private int serial;
    private boolean closed;
    NativeMeshArena(NativeMeshPipeline owner,String label,int vertexCapacity,int indexCapacity,long byteBudget,Tags tags,Consumer<NativeMeshArena> retired){
        this.owner=owner;this.label=label;this.vertexCapacity=vertexCapacity;this.indexCapacity=indexCapacity;this.byteBudget=byteBudget;this.tags=tags;this.retired=retired;
    }
    private void check(){if(closed)throw new IllegalStateException("Mesh arena is closed: "+label);}
    public Mesh create(String name,MeshData data){
        check();Allocation range=reserve(data.layout(),data.vertexCount(),data.indices().count());
        return range==null?null:upload(name,range,data.vertices(),data.indices().type(),data.indices().bytes());
    }
    public Mesh importMesh(String name,Mesh input,long maximumBytes){
        check();
        if(!(input instanceof NativeMeshPipeline.NativeMesh))throw new IllegalArgumentException("Imported mesh is not standalone");
        NativeMeshPipeline.NativeMesh source=(NativeMeshPipeline.NativeMesh)input;
        if(source.owner()!=owner||source.isClosed()||source.usage()!=MeshPipeline.Usage.STATIC)throw new IllegalArgumentException("Foreign, mutable or retired imported mesh");
        long vertexBytes=(long)source.vertexCount()*source.layout().stride(),indexBytes=(long)source.indexCount()*source.indexType().bytes;
        if(vertexBytes+indexBytes>maximumBytes)return null;
        // Reserve first: a full arena never causes a speculative GPU readback.
        Allocation range=reserve(source.layout(),source.vertexCount(),source.indexCount());
        if(range==null)return null;
        ByteBuffer vertices,indices;
        try{
            vertices=BufferUtils.createByteBuffer((int)vertexBytes);indices=BufferUtils.createByteBuffer((int)indexBytes);
            int previous=GL11C.glGetInteger(GL31C.GL_COPY_READ_BUFFER);
            try{source.readVertices(vertices);source.readIndices(indices);}
            finally{GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,previous);}
        }catch(Throwable failure){discard(range);throw failure;}
        return upload(name,range,vertices,source.indexType(),indices);
    }
    private Allocation reserve(VertexLayout source,int vertices,int indices){
        if(vertices==0||indices==0||vertices>vertexCapacity||indices>indexCapacity)return null;
        reclaim();
        Page selected=null;int vertex=-1,index=-1;
        for(Page page:pages)if(page.source.equals(source)){
            vertex=page.vertices.allocate(vertices);if(vertex<0)continue;
            index=page.indices.allocate(indices);
            if(index>=0){selected=page;break;}page.vertices.release(vertex,vertices);vertex=-1;
        }
        if(selected==null){
            int stride=taggedLayout(source).stride();
            long required=(long)stride*vertexCapacity+(long)indexCapacity*4;
            // A spare from another layout must never displace live geometry from
            // the fixed budget. Same-layout spares were already tried above.
            if(required>byteBudget-bytes)for(Page idle:new ArrayList<Page>(pages))if(idle.members==0){retire(idle);if(required<=byteBudget-bytes)break;}
            long available=byteBudget-bytes;
            int pageVertices=vertexCapacity,pageIndices=indexCapacity;
            if(required>available){
                // Use the remaining budget for one proportionally sized final page,
                // instead of abandoning tens of MiB that cannot hold a full page.
                pageVertices=(int)((long)vertexCapacity*available/required);
                pageIndices=(int)((long)indexCapacity*available/required);
                if(pageVertices<vertices||pageIndices<indices)return null;
            }
            selected=new Page(source,pageVertices,pageIndices);pages.add(selected);bytes+=selected.bytes;creations++;
            vertex=selected.vertices.allocate(vertices);index=selected.indices.allocate(indices);
        }
        return new Allocation(selected,vertex,vertices,index,indices);
    }
    private final class Allocation {
        final Page page;final int vertex,vertices,index,indices;
        Allocation(Page page,int vertex,int vertices,int index,int indices){this.page=page;this.vertex=vertex;this.vertices=vertices;this.index=index;this.indices=indices;}
    }
    private void discard(Allocation range){
        Page page=range.page;page.vertices.release(range.vertex,range.vertices);page.indices.release(range.index,range.indices);
        if(page.members==0)retire(page);
    }
    private final class RetiredRange {
        final Page page;final int vertex,vertices,index,indices;final long fence;
        RetiredRange(Member member,long fence){page=member.page;vertex=member.firstVertex;vertices=member.vertices;index=member.firstIndex;indices=member.indices;this.fence=fence;}
    }
    /** Poll only. A busy range remains reserved and normal arena-budget fallback stays available. */
    private void reclaim(){
        for(Iterator<RetiredRange> it=pending.iterator();it.hasNext();){
            RetiredRange range=it.next();int status=GL32C.glClientWaitSync(range.fence,GL32C.GL_SYNC_FLUSH_COMMANDS_BIT,0);
            if(status==GL32C.GL_TIMEOUT_EXPIRED)break;
            if(status!=GL32C.GL_ALREADY_SIGNALED&&status!=GL32C.GL_CONDITION_SATISFIED)throw new IllegalStateException("Cannot check retired terrain range");
            range.page.vertices.release(range.vertex,range.vertices);range.page.indices.release(range.index,range.indices);
            GL32C.glDeleteSync(range.fence);it.remove();
        }
    }
    private void release(Member member){
        if(asyncUploads&&member.submitted){
            long fence=GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            if(fence==0)throw new IllegalStateException("Cannot protect retired terrain range");
            pending.add(new RetiredRange(member,fence));
        }else{member.page.vertices.release(member.firstVertex,member.vertices);member.page.indices.release(member.firstIndex,member.indices);}
    }
    private Mesh upload(String name,Allocation range,ByteBuffer input,IndexData.Type type,ByteBuffer sourceIndices){
        final Page page=range.page;int vertices=range.vertices,indices=range.indices;boolean published=false;
        try{
            int tag=serial++&(tags.count-1),stride=page.layout.stride(),sourceStride=page.source.stride();
            ByteBuffer upload=BufferUtils.createByteBuffer(Math.multiplyExact(vertices,stride));
            if(page.compact){upload.put(input.duplicate());for(int v=0;v<vertices;v++)upload.put(v*stride+tags.packedOffset,(byte)tag);}
            else for(int v=0;v<vertices;v++){
                int at=input.position()+v*sourceStride;for(int b=0;b<sourceStride;b++)upload.put(input.get(at+b));
                while(upload.position()%stride<stride-4&&upload.position()%stride!=0)upload.put((byte)0);upload.putFloat(tag);
            }
            upload.flip();ByteBuffer indexUpload=BufferUtils.createByteBuffer(Math.multiplyExact(indices,4));
            for(int i=0;i<indices;i++)indexUpload.putInt(type==IndexData.Type.UINT16?sourceIndices.getShort()&65535:sourceIndices.getInt());indexUpload.flip();
            int previous=GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER);
            try{
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER,page.vbo);write((long)range.vertex*stride,upload);
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER,page.ibo);write((long)range.index*4,indexUpload);
            }finally{GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER,previous);}
            Member member=new Member(name,page,range.vertex,vertices,range.index,indices,tag);members.add(member);page.members++;published=true;return member;
        }finally{if(!published)discard(range);}
    }
    private void write(long offset,ByteBuffer data){
        if(!asyncUploads){GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER,offset,data);return;}
        // Only fresh or fence-completed ranges reach here. Never map a region still read by queued draws.
        ByteBuffer mapped=GL30C.glMapBufferRange(GL31C.GL_COPY_WRITE_BUFFER,offset,data.remaining(),
                GL30C.GL_MAP_WRITE_BIT|GL30C.GL_MAP_INVALIDATE_RANGE_BIT|GL30C.GL_MAP_UNSYNCHRONIZED_BIT);
        if(mapped==null)throw new IllegalStateException("Cannot map new terrain range");
        try{mapped.put(data.duplicate());}
        finally{if(!GL15C.glUnmapBuffer(GL31C.GL_COPY_WRITE_BUFFER))throw new IllegalStateException("Terrain range mapping lost its contents");}
    }
    private Member member(Mesh mesh){if(!(mesh instanceof NativeMeshArena.Member)||!members.contains(mesh)||mesh.isClosed())throw new IllegalArgumentException("Foreign or retired arena member");return (Member)mesh;}
    public boolean contains(Mesh mesh){check();return members.contains(mesh)&&!mesh.isClosed();}
    public int tag(Mesh mesh){check();return member(mesh).tag;}
    public boolean compatible(Mesh a,Mesh b){check();return member(a).page==member(b).page;}
    public void draw(Mesh.Primitive primitive,List<Mesh> inputs){
        check();if(inputs.isEmpty())return;if(inputs.size()>MAX_DRAWS)throw new IllegalArgumentException("Oversized arena run");
        Page page=member(inputs.get(0)).page;counts.clear();bases.clear();offsets.clear();
        for(Mesh mesh:inputs){Member value=member(mesh);if(value.page!=page)throw new IllegalArgumentException("Arena run crosses pages");counts.put(value.indices);bases.put(value.firstVertex);offsets.put((long)value.firstIndex*4);value.submitted=true;}
        counts.flip();bases.flip();offsets.flip();GL30C.glBindVertexArray(page.vao);
        GL32C.glMultiDrawElementsBaseVertex(mode(primitive),counts,GL11C.GL_UNSIGNED_INT,offsets,bases);
    }
    private static int mode(Mesh.Primitive primitive){return primitive==Mesh.Primitive.TRIANGLES?GL11C.GL_TRIANGLES:primitive==Mesh.Primitive.LINES?GL11C.GL_LINES:GL11C.GL_POINTS;}
    public long residentBytes(){check();return bytes;}
    public int residentPages(){check();return pages.size();}
    public int residentMembers(){check();return members.size();}
    public long pageCreations(){check();return creations;}
    public boolean isClosed(){return closed;}
    public void close(){if(closed)return;for(Member member:new ArrayList<Member>(members))member.close();closed=true;retired.accept(this);}
    /** The native context already owns deletion after detach; no GL operation here. */
    void abandon(){for(Member member:members)member.closed=true;members.clear();pages.clear();pending.clear();bytes=0;closed=true;}
    private void retire(Page page){page.close();pages.remove(page);bytes-=page.bytes;for(Iterator<RetiredRange> it=pending.iterator();it.hasNext();){RetiredRange range=it.next();if(range.page==page){GL32C.glDeleteSync(range.fence);it.remove();}}}
    private void empty(Page page){
        if(members.isEmpty()){
            for(Page unused:new ArrayList<Page>(pages))retire(unused);
        }else{
            // Atomic rebuilds briefly own both old and new geometry. Keep one
            // bounded spare while any members remain, avoiding allocation/deletion
            // of a whole page on alternating rebuilds at a full-page boundary.
            for(Page unused:new ArrayList<Page>(pages))if(unused!=page&&unused.members==0)retire(unused);
        }
    }
    final class Member implements Mesh {
        private final String name;
        private final Page page;
        private final int firstVertex,vertices,firstIndex,indices,tag;
        private boolean closed,submitted;
        Member(String name,Page page,int firstVertex,int vertices,int firstIndex,int indices,int tag){this.name=name;this.page=page;this.firstVertex=firstVertex;this.vertices=vertices;this.firstIndex=firstIndex;this.indices=indices;this.tag=tag;}
        NativeMeshArena owner(){return NativeMeshArena.this;}
        /** Used only by explicit MeshPipeline.combine, never by ordered arena draws. */
        void readVertices(ByteBuffer output){GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,page.vbo);GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER,(long)firstVertex*page.layout.stride(),output);}
        public MeshData snapshot(long maximumBytes) {
            // The production terrain page stores its tag in the padding byte. The Metal layout ignores it.
            if (isClosed() || !page.compact) return null;
            return NativeMeshPipeline.snapshot(page.source, Math.multiplyExact(vertices, page.source.stride()), indices,
                    IndexData.Type.UINT32, maximumBytes, this::readVertices, output -> {
                        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,page.ibo);
                        GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER,(long)firstIndex*4,output);
                    });
        }
        public String label(){return name;}
        public boolean isClosed(){return closed||NativeMeshArena.this.closed;}
        public VertexLayout layout(){return page.layout;}
        public int vertexCount(){return vertices;}
        public int indexCount(){return indices;}
        public IndexData.Type indexType(){return IndexData.Type.UINT32;}
        public MeshPipeline.Usage usage(){return MeshPipeline.Usage.STATIC;}
        public void draw(Primitive primitive,int first,int count){
            submitted=true;
            GL30C.glBindVertexArray(page.vao);GL32C.glDrawElementsBaseVertex(mode(primitive),count,GL11C.GL_UNSIGNED_INT,(long)(firstIndex+first)*4,firstVertex);
        }
        public void updateVertices(int first,ByteBuffer data){throw new IllegalStateException("Arena members are immutable");}
        public void updateIndices(IndexData data){throw new IllegalStateException("Arena members are immutable");}
        public void close(){if(closed)return;release(this);closed=true;members.remove(this);if(--page.members==0)empty(page);}
    }
    private VertexLayout taggedLayout(VertexLayout source){
        boolean compact=source.equals(tags.packedLayout);int offset=compact?tags.packedOffset:(source.stride()+3)/4*4;
        List<VertexLayout.Attribute> attributes=new ArrayList<VertexLayout.Attribute>(source.attributes());
        attributes.add(new VertexLayout.Attribute(TAG_ATTRIBUTE,1,compact?VertexLayout.Storage.UINT8:VertexLayout.Storage.FLOAT32,false,offset));
        return new VertexLayout(compact?source.stride():offset+4,attributes.toArray(new VertexLayout.Attribute[0]));
    }
    private final class Page {
        final VertexLayout source,layout;
        final boolean compact;
        final MeshArenaRanges vertices,indices;
        final int vao,vbo,ibo;
        final long bytes;
        int members;
        Page(VertexLayout source,int vertexCapacity,int indexCapacity){
            vertices=new MeshArenaRanges(vertexCapacity);indices=new MeshArenaRanges(indexCapacity);
            this.source=source;compact=source.equals(tags.packedLayout);layout=taggedLayout(source);bytes=(long)layout.stride()*vertexCapacity+(long)indexCapacity*4;
            int previousVao=GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),previousArray=GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
            int newVao=0,newVbo=0,newIbo=0;boolean published=false;
            try{
                newVao=GL30C.glGenVertexArrays();newVbo=GL15C.glGenBuffers();newIbo=GL15C.glGenBuffers();
                if(newVao==0||newVbo==0||newIbo==0)throw new IllegalStateException("Cannot allocate arena page: "+label);
                GL30C.glBindVertexArray(newVao);allocate(GL15C.GL_ARRAY_BUFFER,newVbo,(long)layout.stride()*vertexCapacity);allocate(GL15C.GL_ELEMENT_ARRAY_BUFFER,newIbo,(long)indexCapacity*4);
                for(VertexLayout.Attribute attribute:layout.attributes()){
                    GL20C.glVertexAttribPointer(attribute.location,attribute.components,NativeMeshPipeline.storage(attribute.storage),attribute.normalized,layout.stride(),(long)attribute.offset);GL20C.glEnableVertexAttribArray(attribute.location);
                }
                published=true;
            }finally{
                GL30C.glBindVertexArray(previousVao);GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,previousArray);
                if(!published){if(newVao!=0)GL30C.glDeleteVertexArrays(newVao);if(newVbo!=0)GL15C.glDeleteBuffers(newVbo);if(newIbo!=0)GL15C.glDeleteBuffers(newIbo);}
            }
            vao=newVao;vbo=newVbo;ibo=newIbo;
        }
        private void allocate(int target,int name,long length){GL15C.glBindBuffer(target,name);GL15C.glBufferData(target,length,GL15C.GL_STATIC_DRAW);if(GL15C.glGetBufferParameteri(target,GL15C.GL_BUFFER_SIZE)!=length)throw new IllegalStateException("Driver did not allocate arena storage");}
        void close(){if(GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)==vao)GL30C.glBindVertexArray(0);GL30C.glDeleteVertexArrays(vao);GL15C.glDeleteBuffers(vbo);GL15C.glDeleteBuffers(ibo);}
    }
}
