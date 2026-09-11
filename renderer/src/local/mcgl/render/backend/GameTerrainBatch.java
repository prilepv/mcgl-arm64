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
    // Two simultaneous materials bound fragment work on fill-limited scenes.
    // Wider tables reduce CPU submissions but measurably regress heavy blending.
    private static final int MATERIAL_TEXTURES=2;
    private final GameRenderer renderer;
    private final RenderContext context;
    private final List<Mesh> queued=new ArrayList<Mesh>(MeshArena.MAX_DRAWS);
    private final boolean wideArena=Boolean.parseBoolean(System.getProperty("mcgl.terrain.wideArena","true"));
    // 128 matrices plus input/light uniforms exceed the Core 4.1 minimum.
    // Keep the smaller palette on drivers without the tested uniform capacity.
    private final int tags=wideArena&&org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL20C.GL_MAX_VERTEX_UNIFORM_COMPONENTS)>=4096?128:MeshArena.TAG_COUNT;
    private final boolean[] occupied=new boolean[tags];
    private final FloatBuffer matrices=ByteBuffer.allocateDirect(tags*64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final IntBuffer inputs=ByteBuffer.allocateDirect(tags*4).order(ByteOrder.nativeOrder()).asIntBuffer();
    private final IntBuffer arrayInputs=ByteBuffer.allocateDirect(tags*4).order(ByteOrder.nativeOrder()).asIntBuffer();
    private final int[] arrayLayers=new int[8];
    private final GameTextureArrays arrays;
    private GameTextureArrays.Page arrayPage;
    private ShaderProgram arrayProgram,unlitArrayProgram;
    private boolean arrayTables;
    private final int[] textures=new int[GameChunkTextures.SIZE];
    private final double[] singleMatrix=new double[16];
    private MeshArena arena;
    private ShaderProgram program,materialProgram,unlitProgram,unlitMaterialProgram;
    private Mesh.Primitive primitive;
    private int mask,firstTag,textureCount,firstTexture;
    private boolean materialTablesEnabled,materialBatch,mixedMasks;
    private long vertices,batchedParts,batches;
    private boolean flushing,reported;
    private final LinkedHashSet<Recovery> waiting=new LinkedHashSet<Recovery>();
    private long released,seenRelease,recoveryFrame=-1,recovered,recoveredBytes;
    private int remaining;
    GameTerrainBatch(GameRenderer renderer,RenderContext context){this.renderer=renderer;this.context=context;arrays=renderer.createTextureArrays();}
    Mesh create(GameGeometry geometry,Recovery recovery){
        if(geometry.primitive!=Mesh.Primitive.TRIANGLES||(geometry.attributeMask&~127)!=0||geometry.mesh.layout().stride()>64)return null;
        // A page must cover a useful world-sized working set: tiny pages make the
        // original distance-sorted selection alternate VAOs almost every draw.
        // Raw triangles/fans use the same storage layout as quads, without changing
        // their primitive, topology, attribute mask, or original vertex fields.
        MeshData data=GameTerrainFormat.storage(geometry.mesh);
        // The ordinary 40-byte layout has one unused byte after its normal.
        // Packing only the palette tag there preserves every original field and
        // allows one 276 MiB page to hold a useful world-sized working set. All
        // layouts and spare pages together remain inside the 320 MiB hard cap.
        if(arena==null)arena=wideArena
                ?context.meshes().createArena("game/original-terrain",6*1024*1024,9*1024*1024,320L*1024*1024,new MeshArena.Tags(tags,GameTerrainFormat.LAYOUT,39))
                :context.meshes().createArena("game/original-terrain",2*1024*1024,3*1024*1024,256L*1024*1024);
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
    void materialTables(boolean enabled){
        if(pending())throw new IllegalStateException("Changing terrain material mode inside a pending run");
        materialTablesEnabled=enabled;
        arrayTables=enabled&&Boolean.parseBoolean(System.getProperty("mcgl.terrain.textureArrays","true"))&&arrays.supportedState();
    }
    boolean acceptsTextureBind(int target,int texture){
        return pending()&&materialBatch&&target==3553&&texture>=0&&renderer.originalMaterialsEligible();
    }
    boolean append(Mesh mesh,Mesh.Primitive primitive,int mask){
        if(arena==null||!arena.contains(mesh))return false;
        int tag=arena.tag(mesh);double[] matrix=renderer.state.matrices.modelView();
        boolean materials=materialTablesEnabled&&(mask&~127)==0&&renderer.originalMaterialsEligible();
        int texture=materials?renderer.originalTexture():0;
        if(!queued.isEmpty()&&(queued.size()==MeshArena.MAX_DRAWS||this.primitive!=primitive
                ||materialBatch!=materials||!materials&&this.mask!=mask||!arena.compatible(queued.get(0),mesh)))flush();
        // Only the bounded tables change. The GPU geometry, vertex order, index
        // order and every float matrix stay exactly as supplied by the caller.
        for(;;){
            if(queued.isEmpty()){this.primitive=primitive;this.mask=mask;firstTag=tag;firstTexture=texture;materialBatch=materials;mixedMasks=false;}
            int slot=0;
            if(materials)for(int i=0;i<textureCount;i++)if(textures[i]==texture){slot=i+1;break;}
            int input=(mask<<4)|slot;
            if(materials&&slot==0&&textureCount>=MATERIAL_TEXTURES){
                if(!arrayTables||textureCount==textures.length||(arrayPage=matchingArray(texture))==null){flush();continue;}
            }
            if(occupied[tag]&&(!matches(tag,matrix)||materials&&inputs.get(tag)!=input)){
                flush();continue;
            }
            if(materials&&slot==0){slot=++textureCount;textures[slot-1]=texture;input=(mask<<4)|slot;}
            if(!occupied[tag]){
                for(int i=0;i<16;i++)matrices.put(tag*16+i,(float)matrix[i]);
                inputs.put(tag,input);occupied[tag]=true;
            }
            mixedMasks|=this.mask!=mask;queued.add(mesh);vertices+=mesh.vertexCount();return true;
        }
    }
    private boolean matches(int tag,double[] matrix){for(int i=0;i<16;i++)if(Float.floatToRawIntBits(matrices.get(tag*16+i))!=Float.floatToRawIntBits((float)matrix[i]))return false;return true;}
    private GameTextureArrays.Page matchingArray(int extra){
        GameTextureArrays.Page page=arrayPage;
        for(int i=0;i<textureCount+(extra>=0?1:0);i++){
            GameTextureArrays.Entry entry=arrays.get(i<textureCount?textures[i]:extra,page);if(entry==null)return null;
            if(page==null)page=entry.page;else if(entry.page!=page)return null;
            if(i<arrayLayers.length)arrayLayers[i]=entry.layer+1;
        }
        return page;
    }
    void endArrays(){arrays.end();}
    void textureReplaced(int texture){arrays.remove(texture);}
    void textureDeleted(int texture){arrays.deleted(texture);}
    void textureChanged(int texture,int level,int x,int y,int w,int h){arrays.changed(texture,level,x,y,w,h);}
    void textureRenderTarget(int texture){arrays.renderTarget(texture);}
    void flush(){
        if(queued.isEmpty()||flushing)return;flushing=true;
        try{
            if(queued.size()==1){
                for(int i=0;i<16;i++)singleMatrix[i]=matrices.get(firstTag*16+i);
                if(materialBatch)renderer.drawTerrainSingle(queued.get(0),primitive,mask,singleMatrix,firstTexture);
                else renderer.drawTerrainSingle(queued.get(0),primitive,mask,singleMatrix);
            }else{
                matrices.clear();
                boolean unlit=GameRenderer.unlitShaders();
                GameTextureArrays.Page selectedArray=materialBatch&&arrayTables&&textureCount>1?matchingArray(-1):null;
                if(selectedArray!=null){
                    ShaderProgram selected;
                    if(unlit){if(unlitArrayProgram==null)unlitArrayProgram=context.shaders().create(GameMaterialProgram.originalArraySources(true,tags));selected=unlitArrayProgram;}
                    else{if(arrayProgram==null)arrayProgram=context.shaders().create(GameMaterialProgram.originalArraySources(false,tags));selected=arrayProgram;}
                    arrayInputs.clear();for(int i=0;i<tags;i++){int value=inputs.get(i),slot=value&15;arrayInputs.put(i,occupied[i]?((value>>4)<<8)|arrayLayers[slot-1]:0);}
                    renderer.drawTerrainArray(arena,queued,primitive,selected,matrices,arrayInputs,arrays,selectedArray,vertices);
                }else if(materialBatch&&(mixedMasks||textureCount>1||firstTexture!=renderer.originalTexture())){
                    if(textureCount>2)throw new IllegalStateException("Array group lost its protected textures");
                    ShaderProgram selected;
                    if(unlit){if(unlitMaterialProgram==null)unlitMaterialProgram=context.shaders().create(GameMaterialProgram.originalBatchSources(true,true,tags));selected=unlitMaterialProgram;}
                    else{if(materialProgram==null)materialProgram=context.shaders().create(GameMaterialProgram.originalBatchSources(true,false,tags));selected=materialProgram;}
                    inputs.clear();renderer.drawTerrainMaterials(arena,queued,primitive,selected,matrices,inputs,textures,textureCount,vertices);
                }else{
                    ShaderProgram selected;
                    if(unlit){if(unlitProgram==null)unlitProgram=context.shaders().create(GameMaterialProgram.originalBatchSources(false,true,tags));selected=unlitProgram;}
                    else{if(program==null)program=context.shaders().create(GameMaterialProgram.originalBatchSources(false,false,tags));selected=program;}
                    renderer.drawTerrainBatch(arena,queued,primitive,mask,selected,matrices,vertices);
                }
                batchedParts+=queued.size();batches++;
                if(!reported){reported=true;if(Boolean.getBoolean("mcgl.graphics.profile"))System.out.println("[MCGL Terrain Batch] ordered original model runs; shared GPU ranges; exact matrix palette; original local transparency");}
            }
        }finally{queued.clear();Arrays.fill(occupied,false);Arrays.fill(textures,0);textureCount=0;vertices=0;flushing=false;arrayPage=null;}
    }
    void abandon(){arrays.abandon();arrayPage=null;arrayProgram=null;unlitArrayProgram=null;queued.clear();waiting.clear();remaining=0;Arrays.fill(occupied,false);Arrays.fill(textures,0);textureCount=0;vertices=0;flushing=false;arena=null;program=null;materialProgram=null;unlitProgram=null;unlitMaterialProgram=null;}
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
