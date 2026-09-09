package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;

/** Core 4.1 indexed geometry. GL queries occur during resource changes, never per draw. */
final class NativeMeshPipeline implements MeshPipeline {
    private final Set<NativeMesh> meshes = new HashSet<NativeMesh>();
    private final Set<NativeMeshArena> arenas=new HashSet<NativeMeshArena>();
    private boolean abandoned;
    public MeshArena createArena(String label,int vertexCapacity,int indexCapacity,long byteBudget){
        if(abandoned)throw new IllegalStateException("Mesh pipeline is closed");
        NativeMeshArena arena=new NativeMeshArena(this,label,vertexCapacity,indexCapacity,byteBudget,arenas::remove);arenas.add(arena);return arena;
    }
    public Mesh create(String label, MeshData data, Usage usage) {
        if (abandoned) throw new IllegalStateException("Mesh pipeline is closed");
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousArray = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        int vao = 0, vbo = 0, ibo = 0;
        boolean published = false;
        try {
            vao = GL30C.glGenVertexArrays();
            vbo = GL15C.glGenBuffers(); ibo = GL15C.glGenBuffers();
            if (vao == 0 || vbo == 0 || ibo == 0) throw new IllegalStateException("Cannot allocate mesh: " + label);
            GL30C.glBindVertexArray(vao);
            allocate(GL15C.GL_ARRAY_BUFFER, vbo, data.vertices(), usage);
            allocate(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibo, data.indices().bytes(), usage);
            for (VertexLayout.Attribute a : data.layout().attributes()) {
                GL20C.glVertexAttribPointer(a.location, a.components, storage(a.storage), a.normalized,
                        data.layout().stride(), (long)a.offset);
                GL20C.glEnableVertexAttribArray(a.location);
            }
            NativeMesh mesh = new NativeMesh(label, data, usage, vao, vbo, ibo);
            meshes.add(mesh); published = true; return mesh;
        } finally {
            // EBO is VAO state. Never unbind GL_ELEMENT_ARRAY_BUFFER on the caller's VAO (or VAO 0).
            GL30C.glBindVertexArray(previousVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousArray);
            if (!published) {
                if (vao != 0) GL30C.glDeleteVertexArrays(vao);
                if (vbo != 0) GL15C.glDeleteBuffers(vbo);
                if (ibo != 0) GL15C.glDeleteBuffers(ibo);
            }
        }
    }
    private static void allocate(int target, int name, ByteBuffer bytes, Usage usage) {
        GL15C.glBindBuffer(target, name);
        GL15C.glBufferData(target, bytes, usage == Usage.STATIC ? GL15C.GL_STATIC_DRAW
                : usage == Usage.DYNAMIC ? GL15C.GL_DYNAMIC_DRAW : GL15C.GL_STREAM_DRAW);
        if (GL15C.glGetBufferParameteri(target, GL15C.GL_BUFFER_SIZE) != bytes.remaining())
            throw new IllegalStateException("Driver did not allocate the requested mesh storage");
    }
    public Mesh combine(String label,List<Mesh> sources,IndexData indices) {
        if(abandoned)throw new IllegalStateException("Mesh pipeline is closed");
        List<Mesh> inputs=new ArrayList<Mesh>();VertexLayout layout=null;int vertices=0;
        for(Mesh source:sources){if(!owns(source)||source.isClosed())throw new IllegalArgumentException("Foreign or retired combined mesh source");if(layout==null)layout=source.layout();else if(!layout.equals(source.layout()))throw new IllegalArgumentException("Combined mesh format mismatch");vertices=Math.addExact(vertices,source.vertexCount());inputs.add(source);}
        if(layout==null||indices.maximum()>=vertices)throw new IllegalArgumentException("Invalid combined mesh geometry");
        int previousVao=GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),previousArray=GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        int previousRead=GL11C.glGetInteger(GL31C.GL_COPY_READ_BUFFER);
        int vao=0,vbo=0,ibo=0;boolean published=false;
        try {
            vao=GL30C.glGenVertexArrays();vbo=GL15C.glGenBuffers();ibo=GL15C.glGenBuffers();
            if(vao==0||vbo==0||ibo==0)throw new IllegalStateException("Cannot allocate combined mesh: "+label);
            GL30C.glBindVertexArray(vao);
            // Apple's Metal-backed OpenGL reports gldCopyBufferSubData as unimplemented.
            // Repack only when group membership changes, using a temporary readback buffer.
            // No vertex copy is retained on the CPU and camera sorting updates only the EBO.
            ByteBuffer bytes=ByteBuffer.allocateDirect(Math.multiplyExact(vertices,layout.stride())).order(ByteOrder.nativeOrder());
            int offset=0;for(Mesh input:inputs){int length=Math.multiplyExact(input.vertexCount(),layout.stride());ByteBuffer range=bytes.duplicate().order(ByteOrder.nativeOrder());range.position(offset);range.limit(offset+length);if(input instanceof NativeMesh){GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,((NativeMesh)input).vbo);GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER,0,range);}else ((NativeMeshArena.Member)input).readVertices(range);offset+=length;}
            allocate(GL15C.GL_ARRAY_BUFFER,vbo,bytes,Usage.STATIC);
            allocate(GL15C.GL_ELEMENT_ARRAY_BUFFER,ibo,indices.bytes(),Usage.DYNAMIC);
            for(VertexLayout.Attribute a:layout.attributes()){GL20C.glVertexAttribPointer(a.location,a.components,storage(a.storage),a.normalized,layout.stride(),(long)a.offset);GL20C.glEnableVertexAttribArray(a.location);}
            NativeMesh result=new NativeMesh(label,layout,vertices,indices.count(),indices.type(),Usage.DYNAMIC,vao,vbo,ibo);
            meshes.add(result);published=true;return result;
        }finally {
            GL30C.glBindVertexArray(previousVao);GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,previousArray);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,previousRead);
            if(!published){if(vao!=0)GL30C.glDeleteVertexArrays(vao);if(vbo!=0)GL15C.glDeleteBuffers(vbo);if(ibo!=0)GL15C.glDeleteBuffers(ibo);}
        }
    }
    private boolean owns(Mesh mesh){return meshes.contains(mesh)||mesh instanceof NativeMeshArena.Member&&arenas.contains(((NativeMeshArena.Member)mesh).owner())&&((NativeMeshArena.Member)mesh).owner().contains(mesh);}
    static int storage(VertexLayout.Storage storage) {
        switch (storage) {
            case FLOAT32: return GL11C.GL_FLOAT;
            case INT8: return GL11C.GL_BYTE;
            case UINT8: return GL11C.GL_UNSIGNED_BYTE;
            case INT16: return GL11C.GL_SHORT;
            case UINT16: return GL11C.GL_UNSIGNED_SHORT;
            default: throw new AssertionError(storage);
        }
    }
    public void unbind() { GL30C.glBindVertexArray(0); }
    /** Called after native detach. Context destruction owns native deletion; no GL calls here. */
    void abandon() {
        abandoned = true;
        for (NativeMesh mesh : meshes) mesh.closed = true;
        meshes.clear();
        for(NativeMeshArena arena:arenas)arena.abandon();arenas.clear();
    }
    final class NativeMesh implements Mesh {
        private final String label;
        private final VertexLayout layout;
        private final int vertices, indices, vao, vbo, ibo;
        private final IndexData.Type type;
        private final Usage usage;
        private volatile boolean closed;
        NativeMesh(String label, MeshData data, Usage usage, int vao, int vbo, int ibo) {
            this(label,data.layout(),data.vertexCount(),data.indices().count(),data.indices().type(),usage,vao,vbo,ibo);
        }
        NativeMesh(String label,VertexLayout layout,int vertices,int indices,IndexData.Type type,Usage usage,int vao,int vbo,int ibo) {
            this.label=label;this.layout=layout;this.vertices=vertices;this.indices=indices;this.type=type;this.usage=usage;
            this.vao = vao; this.vbo = vbo; this.ibo = ibo;
        }
        public String label() { return label; }
        public boolean isClosed() { return closed; }
        public VertexLayout layout() { return layout; }
        public int vertexCount() { return vertices; }
        public int indexCount() { return indices; }
        public IndexData.Type indexType() { return type; }
        public Usage usage() { return usage; }
        NativeMeshPipeline owner(){return NativeMeshPipeline.this;}
        void readVertices(ByteBuffer output){GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,vbo);GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER,0,output);}
        void readIndices(ByteBuffer output){GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER,ibo);GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER,0,output);}
        public void draw(Primitive primitive, int first, int count) {
            int mode = primitive == Primitive.TRIANGLES ? GL11C.GL_TRIANGLES
                    : primitive == Primitive.LINES ? GL11C.GL_LINES : GL11C.GL_POINTS;
            GL30C.glBindVertexArray(vao);
            GL11C.glDrawElements(mode, count, type == IndexData.Type.UINT16 ? GL11C.GL_UNSIGNED_SHORT
                    : GL11C.GL_UNSIGNED_INT, (long)first * type.bytes);
        }
        public void updateVertices(int first, ByteBuffer data) {
            if(usage==Usage.STREAM&&first==0&&data.remaining()==(long)vertices*layout.stride())replaceStream(vbo,data);
            else update(vbo, (long)first * layout.stride(), data);
        }
        public void updateIndices(IndexData data) { replaceStream(ibo, data.bytes()); }
        private void replaceStream(int name,ByteBuffer data) {
            // Complete vertex/index replacement orphans storage still referenced by queued draws.
            // Callers have validated unchanged allocation size/type before this native operation.
            // SubData on reused buffers stalls Apple's driver even when pixels are correct.
            int previous=GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER);
            try{GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER,name);GL15C.glBufferData(GL31C.GL_COPY_WRITE_BUFFER,data,GL15C.GL_STREAM_DRAW);}
            finally{GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER,previous);}
        }
        private void update(int name, long offset, ByteBuffer data) {
            // Copy-write binding is independent of VAO/EBO and of GL_ARRAY_BUFFER.
            int previous = GL11C.glGetInteger(GL31C.GL_COPY_WRITE_BUFFER);
            try {
                GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, name);
                GL15C.glBufferSubData(GL31C.GL_COPY_WRITE_BUFFER, offset, data);
            } finally { GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, previous); }
        }
        public void close() {
            if (closed) return;
            if (GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING) == vao) GL30C.glBindVertexArray(0);
            GL30C.glDeleteVertexArrays(vao);
            GL15C.glDeleteBuffers(vbo); GL15C.glDeleteBuffers(ibo);
            closed = true; meshes.remove(this);
        }
    }
}
