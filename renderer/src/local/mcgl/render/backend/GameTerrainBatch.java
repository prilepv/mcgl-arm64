package local.mcgl.render.backend;

import java.nio.*;
import java.util.*;
import local.mcgl.render.*;

/** Only consecutive compatible original model draws are queued. All scheduler
 * selection, per-chunk sorting, duplicates and pass replay remain the caller's. */
final class GameTerrainBatch {
    interface Recovery { Mesh source(); void replace(Mesh replacement); }
    private static final long RECOVERY_BYTES=4L*1024*1024;
    private static final int RECOVERY_MESHES=8;
    private final GameRenderer renderer;
    private final RenderContext context;
    private final List<Mesh> queued=new ArrayList<Mesh>(MeshArena.MAX_DRAWS);
    private final boolean[] occupied=new boolean[MeshArena.TAG_COUNT];
    private final FloatBuffer matrices=ByteBuffer.allocateDirect(MeshArena.TAG_COUNT*64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final double[] singleMatrix=new double[16];
    private MeshArena arena;
    private ShaderProgram program;
    private Mesh.Primitive primitive;
    private int mask,firstTag;
    private long vertices,batchedParts,batches;
    private boolean flushing,reported;
    private final LinkedHashSet<Recovery> waiting=new LinkedHashSet<Recovery>();
    private long released,seenRelease,recoveryFrame=-1,recovered,recoveredBytes;
    private int remaining;
    GameTerrainBatch(GameRenderer renderer,RenderContext context){this.renderer=renderer;this.context=context;}
    Mesh create(GameGeometry geometry,Recovery recovery){
        if(geometry.primitive!=Mesh.Primitive.TRIANGLES||(geometry.attributeMask&~127)!=0||geometry.mesh.layout().stride()>64)return null;
        // A page must cover a useful world-sized working set: tiny pages make the
        // original distance-sorted selection alternate VAOs almost every draw.
        // Raw triangles/fans use the same storage layout as quads, without changing
        // their primitive, topology, attribute mask, or original vertex fields.
        MeshData data=GameTerrainFormat.storage(geometry.mesh);
        // Reserve GPU-only storage (100 MiB for the usual 44-byte tagged format);
        // all layouts/pages together still share the hard 256 MiB arena limit.
        if(arena==null)arena=context.meshes().createArena("game/original-terrain",2*1024*1024,3*1024*1024,256L*1024*1024);
        Mesh mesh=arena.create("game/original-member",data);
        if(mesh!=null)return mesh;
        mesh=context.meshes().create("game/original-fallback",data,MeshPipeline.Usage.STATIC);
        if(data.vertexCount()<=2*1024*1024&&data.indices().count()<=3*1024*1024
                &&transferBytes(mesh)<=RECOVERY_BYTES)waiting.add(recovery);
        return mesh;
    }
    private static long transferBytes(Mesh mesh){return (long)mesh.vertexCount()*mesh.layout().stride()+(long)mesh.indexCount()*mesh.indexType().bytes;}
    void retiring(Recovery recovery){
        waiting.remove(recovery);remaining=Math.min(remaining,waiting.size());
        if(arena!=null&&arena.contains(recovery.source()))released++;
    }
    /** Resource recovery only after cached geometry releases space. At most eight
     * meshes / 4 MiB per completed frame; no persistent CPU geometry, polling
     * readback, world rebuild request, or camera/order-dependent repacking. */
    void recover(){
        if(waiting.isEmpty()||recoveryFrame==context.completedFrames())return;
        recoveryFrame=context.completedFrames();
        if(seenRelease!=released){seenRelease=released;remaining=waiting.size();}
        if(remaining==0)return;
        flush();long available=RECOVERY_BYTES;
        for(int attempt=0;attempt<RECOVERY_MESHES&&remaining>0&&!waiting.isEmpty();attempt++){
            Iterator<Recovery> iterator=waiting.iterator();Recovery recovery=iterator.next();iterator.remove();remaining--;
            Mesh source=recovery.source();if(source.isClosed())continue;
            long bytes=transferBytes(source);
            if(bytes>available){waiting.add(recovery);remaining++;break;}
            Mesh replacement;
            try{replacement=arena.importMesh("game/original-recovered",source,available);}
            catch(Throwable failure){waiting.add(recovery);remaining++;throw failure;}
            if(replacement==null){waiting.add(recovery);continue;}
            try{recovery.replace(replacement);}
            catch(Throwable failure){replacement.close();throw failure;}
            available-=bytes;recovered++;recoveredBytes+=bytes;
        }
    }
    boolean pending(){return !queued.isEmpty();}
    boolean append(Mesh mesh,Mesh.Primitive primitive,int mask){
        if(arena==null||!arena.contains(mesh))return false;
        int tag=arena.tag(mesh);double[] matrix=renderer.state.matrices.modelView();
        if(!queued.isEmpty()&&(queued.size()==MeshArena.MAX_DRAWS||this.primitive!=primitive||this.mask!=mask||!arena.compatible(queued.get(0),mesh)||occupied[tag]&&!matches(tag,matrix)))flush();
        if(queued.isEmpty()){this.primitive=primitive;this.mask=mask;firstTag=tag;}
        if(!occupied[tag]){for(int i=0;i<16;i++)matrices.put(tag*16+i,(float)matrix[i]);occupied[tag]=true;}
        queued.add(mesh);vertices+=mesh.vertexCount();return true;
    }
    private boolean matches(int tag,double[] matrix){for(int i=0;i<16;i++)if(Float.floatToRawIntBits(matrices.get(tag*16+i))!=Float.floatToRawIntBits((float)matrix[i]))return false;return true;}
    void flush(){
        if(queued.isEmpty()||flushing)return;flushing=true;
        try{
            if(queued.size()==1){
                for(int i=0;i<16;i++)singleMatrix[i]=matrices.get(firstTag*16+i);
                renderer.drawTerrainSingle(queued.get(0),primitive,mask,singleMatrix);
            }else{
                if(program==null)program=context.shaders().create(GameMaterialProgram.originalBatchSources());
                matrices.clear();renderer.drawTerrainBatch(arena,queued,primitive,mask,program,matrices,vertices);batchedParts+=queued.size();batches++;
                if(!reported){reported=true;if(Boolean.getBoolean("mcgl.graphics.profile"))System.out.println("[MCGL Terrain Batch] ordered original model runs; shared GPU ranges; exact matrix palette; original local transparency");}
            }
        }finally{queued.clear();Arrays.fill(occupied,false);vertices=0;flushing=false;}
    }
    void abandon(){queued.clear();waiting.clear();remaining=0;Arrays.fill(occupied,false);vertices=0;flushing=false;arena=null;program=null;}
    long parts(){return batchedParts;}
    long batches(){return batches;}
    long bytes(){return arena==null?0:arena.residentBytes();}
    int pages(){return arena==null?0:arena.residentPages();}
    int members(){return arena==null?0:arena.residentMembers();}
    long creations(){return arena==null?0:arena.pageCreations();}
    int fallbackMeshes(){return waiting.size();}
    long recoveries(){return recovered;}
    long recoveryBytes(){return recoveredBytes;}
}
