package local.mcgl.render.backend;

import java.util.*;
import local.mcgl.render.*;

/** Original world scheduler → immutable chunk batches → stage 9 GPU registry. No game-class reflection. */
final class GameChunks {
    private final GameRenderer game;
    private final ChunkRenderer renderer;
    private final NavigableMap<Integer, Entry> entries=new TreeMap<Integer, Entry>();
    private final Map<ChunkMaterial, Inputs> materials=new IdentityHashMap<ChunkMaterial, Inputs>();
    private final GameChunkTextures textures=new GameChunkTextures();
    private final Object texturePolicy=new Object();
    private Build build;
    private long serial, drawCalls, indexUploads;
    GameChunks(GameRenderer game,RenderContext context){this.game=game;renderer=new ChunkRenderer(context);}
    private static final class Entry {
        final int handle,x,y,z;ChunkRenderer.Ticket ticket;List<ChunkMaterial> materials=Collections.emptyList();
        Entry(int handle,int x,int y,int z){this.handle=handle;this.x=x;this.y=y;this.z=z;}
    }
    private static final class Build {
        final Entry entry;final int passes;final ChunkMeshBuilder builder;
        final Map<ChunkMaterial,Inputs> materials=new IdentityHashMap<ChunkMaterial,Inputs>();int pass=-1;
        Build(Entry entry,int passes){this.entry=entry;this.passes=passes;builder=new ChunkMeshBuilder(entry.x,entry.y,entry.z,4*1024*1024);}
    }
    private static final class Inputs {
        final List<GameRenderer.Operation> prefix;final int mask,groups;
        final BatchKey batchKey;final float[] transform;GameChunkTextures.Lease textureLease;
        Inputs(List<GameRenderer.Operation> prefix,int mask,Entry entry,double[] local,int pass){
            this.prefix=Collections.unmodifiableList(prefix);this.mask=mask;int groups=0,depth=-1,texture=-1;boolean eligible=true;
            Map<Integer,Integer> switches=new TreeMap<Integer,Integer>();
            // Only these typed operations can be collapsed to their final value. Active-unit changes,
            // arbitrary color/effect state, side-effecting switches and non-uniform matrices remain individual draws.
            for(GameRenderer.Operation op:prefix){groups|=op.groups();if(op.matrix()){if(!op.batchSafeMatrix())eligible=false;}else if(op.batchDepthMask()!=-2)depth=op.batchDepthMask();else if(op.batchTexture()!=-1)texture=op.batchTexture();else if(op.batchCapability()!=0)switches.put(Math.abs(op.batchCapability()),op.batchCapability());else eligible=false;}this.groups=groups;
            double scale=local[0];if(scale<=0||local[5]!=scale||local[10]!=scale)eligible=false;
            for(int i=0;i<12;i++)if(i!=0&&i!=5&&i!=10&&local[i]!=0)eligible=false;
            transform=eligible?new float[]{(float)((entry.x&1023)+local[12]),(float)((entry.y&1023)+local[13]),(float)((entry.z&1023)+local[14]),(float)scale,
                    entry.x-(entry.x&1023),entry.y-(entry.y&1023),entry.z-(entry.z&1023)}:null;
            int[] capabilities=new int[switches.size()];int at=0;for(int value:switches.values())capabilities[at++]=value;
            batchKey=eligible?new BatchKey(mask,depth,texture,capabilities):null;
        }
    }
    private static final class BatchKey {
        final int mask,depth,texture;final int[] capabilities;
        BatchKey(int mask,int depth,int texture,int[] capabilities){this.mask=mask;this.depth=depth;this.texture=texture;this.capabilities=capabilities;}
        @Override public boolean equals(Object other){if(!(other instanceof BatchKey))return false;BatchKey key=(BatchKey)other;return mask==key.mask&&depth==key.depth&&texture==key.texture&&Arrays.equals(capabilities,key.capabilities);}
        @Override public int hashCode(){return ((mask*31+depth)*31+texture)*31+Arrays.hashCode(capabilities);}
    }
    boolean building(){return build!=null;}
    void begin(int handle,int x,int y,int z,int passes) {
        if(build!=null||handle<=0||passes<1||passes>3||x%16!=0||y%16!=0||z%16!=0)throw new IllegalArgumentException("Invalid terrain rebuild scope");
        Entry entry=entries.get(handle);
        if(entry!=null&&(entry.x!=x||entry.y!=y||entry.z!=z)){unload(handle,1);entry=null;}
        if(entry==null){entry=new Entry(handle,x,y,z);entries.put(handle,entry);}
        entry.ticket=renderer.request(x,y,z);build=new Build(entry,passes);
    }
    void beginPass(int name){if(build==null||build.pass!=-1||name<build.entry.handle||name>=build.entry.handle+build.passes)throw new IllegalStateException("Terrain pass handle mismatch");build.pass=name-build.entry.handle;}
    void endPass(int name){if(build==null||build.pass!=name-build.entry.handle)throw new IllegalStateException("Terrain pass mismatch");build.pass=-1;}
    int raw(int[] data,int words,int count,int mode,boolean converted,boolean begun,boolean color,boolean texture,boolean light,boolean normals,List<GameRenderer.Operation> prefix) {
        if(build==null||build.pass<0)throw new IllegalStateException("No terrain pass");
        ShaderLibrary.Material shader=light?ShaderLibrary.Material.LIGHTMAPPED:texture?ShaderLibrary.Material.TEXTURED:ShaderLibrary.Material.COLOR;
        ChunkMaterial material=new ChunkMaterial("game/"+(++serial),build.pass,shader,ShaderLibrary.AlphaTest.DISABLED,0);
        double[] transform=game.chunkBoundsTransform(prefix,build.entry.x,build.entry.y,build.entry.z);
        ChunkMeshBuilder.Sink sink=build.builder.sink(material,-1,0,0,0,0,transform);
        int bytes=sink.append(data,words,count,mode,converted,begun,color,texture,light,normals);
        if(count!=0)build.materials.put(material,new Inputs(prefix,1|(color?2:0)|(texture?4:0)|(light?8:0)|(normals?16:0),build.entry,transform,build.pass));
        return bytes;
    }
    void finish() {
        if(build==null||build.pass!=-1)throw new IllegalStateException("Unfinished terrain rebuild");
        Build pending=build;ChunkMeshData data=pending.builder.finish();
        boolean published=false;
        try {
            Map<ChunkMaterial,float[]> transforms=new IdentityHashMap<ChunkMaterial,float[]>();
            for(Map.Entry<ChunkMaterial,Inputs> entry:pending.materials.entrySet()) {
                Inputs inputs=entry.getValue();if(inputs.transform==null)continue;
                BatchKey key=inputs.batchKey;inputs.textureLease=textures.acquire(entry.getKey().pass,key.depth,key.capabilities,key.texture);
                float[] augmented=Arrays.copyOf(inputs.transform,8);augmented[7]=(inputs.mask<<4)|inputs.textureLease.slot;transforms.put(entry.getKey(),augmented);
            }
            if(!transforms.isEmpty())data=GameChunkGeometry.withTransforms(data,transforms);
            if(!renderer.publish(pending.entry.ticket,data))throw new IllegalStateException("Superseded synchronous terrain rebuild");
            published=true;
            for(ChunkMaterial material:pending.entry.materials)release(materials.remove(material));
            pending.entry.materials=new ArrayList<ChunkMaterial>(pending.materials.keySet());materials.putAll(pending.materials);
        }finally{if(!published)for(Inputs inputs:pending.materials.values())release(inputs);build=null;}
    }
    private void release(Inputs inputs){if(inputs!=null&&inputs.textureLease!=null){textures.release(inputs.textureLease);inputs.textureLease=null;}}
    void abort(){if(build!=null){try{build.builder.abort();}finally{build=null;}}}
    void unload(int first,int count) {
        if(build!=null)throw new IllegalStateException("Terrain unload during rebuild");long end=(long)first+count;
        List<Integer> handles=new ArrayList<Integer>();for(Integer handle:entries.tailMap(first,true).keySet()){if(handle>=end)break;handles.add(handle);}
        for(Integer handle:handles){Entry entry=entries.remove(handle);renderer.unload(entry.ticket);for(ChunkMaterial material:entry.materials)release(materials.remove(material));}
    }
    void draw(List<?> selected,int pass,double cameraX,double cameraY,double cameraZ) {
        if(build!=null)throw new IllegalStateException("Terrain draw during rebuild");if(selected==null)throw new NullPointerException("visible chunks");
        List<ChunkRenderer.Ticket> tickets=new ArrayList<ChunkRenderer.Ticket>();
        for(Object object:selected){if(!(object instanceof GameChunkHandle))throw new IllegalArgumentException("Unmigrated world chunk handle");Entry entry=entries.get(((GameChunkHandle)object).mcglChunkHandle());if(entry!=null)tickets.add(entry.ticket);}
        final GameMatrices.Snapshot matrices=game.state.matrices.snapshot();final double[] model=game.state.matrices.modelView();
        final Map<Integer,Runnable> scopes=new HashMap<Integer,Runnable>();
        final boolean textureBatching=game.chunkTexturesSupported();
        ChunkRenderer.DrawStats stats=renderer.drawPass(pass,ChunkFrustum.ALL,cameraX,cameraY,cameraZ,new ChunkRenderer.MaterialBinder(){
            private Runnable restore;
            public void begin(int pass){}
            public boolean batching(){return true;}
            public Object batchPolicy(){return textureBatching?texturePolicy:GameChunks.this;}
            public Object batchKey(ChunkMaterial material,int x,int y,int z){Inputs inputs=materials.get(material);return inputs==null||inputs.batchKey==null?null:textureBatching?inputs.textureLease.table:inputs.batchKey;}
            public void bindBatch(ChunkMaterial material,int x,int y,int z){
                Inputs inputs=materials.get(material);if(inputs==null||inputs.batchKey==null)throw new IllegalStateException("Incompatible terrain batch");
                restore=scopes.get(inputs.groups);if(restore==null){restore=game.restoreInputs(inputs.groups);scopes.put(inputs.groups,restore);}
                try{
                    BatchKey key=inputs.batchKey;
                    double rx=Math.floor(cameraX/1024)*1024,ry=Math.floor(cameraY/1024)*1024,rz=Math.floor(cameraZ/1024)*1024;
                    game.state.matrices.mode(5888);game.state.matrices.load(model);game.state.matrices.translate(rx-cameraX,ry-cameraY,rz-cameraZ);
                    if(key.depth!=-1)game.glDepthMask(key.depth==1);if(!textureBatching&&key.texture!=-1)game.glBindTexture(3553,key.texture);
                    for(int capability:key.capabilities)if(capability>0)game.glEnable(capability);else game.glDisable(-capability);
                    game.bindChunk(textureBatching?1|2048:inputs.mask,(float)rx,(float)ry,(float)rz);
                    if(textureBatching)game.bindChunkTextures(inputs.textureLease.table.textures);
                }catch(Throwable failure){reset();throw failure;}
            }
            public void bind(ChunkMaterial material,int x,int y,int z){
                Inputs inputs=materials.get(material);if(inputs==null)throw new IllegalStateException("Retired terrain material");
                restore=scopes.get(inputs.groups);if(restore==null){restore=game.restoreInputs(inputs.groups);scopes.put(inputs.groups,restore);}
                try {
                    game.state.matrices.mode(5888);game.state.matrices.load(model);
                    // Reproduce the original region-relative camera transform before its per-chunk commands.
                    game.state.matrices.translate((x-(x&1023))-cameraX,-cameraY,(z-(z&1023))-cameraZ);
                    for(GameRenderer.Operation operation:inputs.prefix)operation.draw();game.bind(inputs.mask);
                }catch(Throwable failure){reset();throw failure;}
            }
            private void reset(){try{game.endSamplers();if(restore!=null)restore.run();}finally{matrices.restore(game.state.matrices);restore=null;}}
            public void afterDraw(ChunkMaterial material,int x,int y,int z){reset();}
            public void end(int pass){try{reset();}finally{game.endChunkTextures();}}
        },tickets);
        drawCalls+=stats.drawCalls;indexUploads+=stats.indexUploads;
    }
    int residentChunks(){return renderer.residentChunks();}
    long drawCalls(){return drawCalls;}
    long indexUploads(){return indexUploads;}
    void abandon(){build=null;entries.clear();materials.clear();textures.clear();renderer.close();}
}
