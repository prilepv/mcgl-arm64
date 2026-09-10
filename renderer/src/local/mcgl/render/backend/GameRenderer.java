package local.mcgl.render.backend;

import java.nio.*;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Finite game-call migration boundary. Geometry is CPU data or owned Core meshes; no native fixed function. */
public class GameRenderer {
    protected final RenderContext context;
    public final GameRenderState state = new GameRenderState();
    private final GameRasterState raster = new GameRasterState();
    private final NavigableMap<Integer, Model> models = new TreeMap<Integer, Model>();
    private final Deque<Runnable> attributes = new ArrayDeque<Runnable>();
    private final Set<Integer> fontGlyphNames=new HashSet<Integer>();
    private Model compiling;
    private OriginalChunk originalChunk;
    private int compilingName, nextName = 1, callDepth, clientTexture;
    private int terrainReplayDepth;
    private Immediate immediate;
    private ShaderProgram material,chunkMaterial,effect,boundProgram;
    private final Set<ShaderProgram> chunkTexturePrograms=Collections.newSetFromMap(new WeakHashMap<ShaderProgram,Boolean>());
    private GameChunks chunks;
    private final GameDynamicMeshes dynamicMeshes;
    private GameTextBatch text;
    private final boolean terrainBatchEnabled=!"false".equals(System.getProperty("mcgl.terrain.batch"));
    private GameTerrainBatch terrainBatch;
    private int terrainBatchDepth;
    private int textDepth;
    private ByteBuffer textImmediate;
    private boolean replayingMatrices;
    private boolean originalChunkReported;
    private long draws, vertices, modelDraws, rawBatches, originalChunkDraws;

    protected GameRenderer(RenderContext context) {
        if (context.profile() != RenderProfile.CORE_41) throw new IllegalArgumentException("Game renderer requires Core 4.1");
        this.context = context;
        dynamicMeshes=new GameDynamicMeshes(context.meshes());
    }
    public final void check() {
        if (context.isClosed() || RenderSystem.current() != context) throw new IllegalStateException("Game renderer context is not current");
    }
    public final boolean insideBeginEnd() { return immediate != null; }
    public final long drawCalls() { return draws; }
    public final long rawBatches() { return rawBatches; }
    public final long submittedVertices() { return vertices; }
    public final int cachedModels() { return models.size(); }
    public final long cachedModelDraws() { return modelDraws; }
    public final long transientMeshCreations(){return dynamicMeshes.creations()+(text==null?0:text.creations());}
    public final int transientMeshCount(){return dynamicMeshes.residentMeshes()+(text==null?0:text.meshes());}
    public final long transientMeshBytes(){return dynamicMeshes.residentBytes()+(text==null?0:text.bytes());}
    public final long textGlyphs(){return text==null?0:text.glyphs();}
    public final long textDraws(){return text==null?0:text.submissions();}
    public final long terrainBatchParts(){return terrainBatch==null?0:terrainBatch.parts();}
    public final long terrainBatches(){return terrainBatch==null?0:terrainBatch.batches();}
    public final long terrainArenaBytes(){return terrainBatch==null?0:terrainBatch.bytes();}
    public final int terrainArenaPages(){return terrainBatch==null?0:terrainBatch.pages();}
    public final int terrainArenaMembers(){return terrainBatch==null?0:terrainBatch.members();}
    public final long terrainArenaCreations(){return terrainBatch==null?0:terrainBatch.creations();}
    public final int terrainPendingRecoveries(){return terrainBatch==null?0:terrainBatch.fallbackMeshes();}
    public final long terrainRecoveries(){return terrainBatch==null?0:terrainBatch.recoveries();}
    public final long terrainRecoveryBytes(){return terrainBatch==null?0:terrainBatch.recoveryBytes();}
    /** Called after native context destruction. No native operation or producer data retained. */
    public final void abandon() { if(chunks!=null)chunks.abandon();chunks=null;dynamicMeshes.abandon();if(text!=null)text.abandon();text=null;textDepth=0;textImmediate=null;if(terrainBatch!=null)terrainBatch.abandon();terrainBatch=null;terrainBatchDepth=0;fontGlyphNames.clear();models.clear(); attributes.clear();chunkTexturePrograms.clear(); compiling=null;originalChunk=null;immediate=null;material=null;chunkMaterial=null;effect=null;boundProgram=null; }
    public final void selectEffect(ShaderProgram value) { check(); outsideBegin();flushText(); effect=value; }
    protected final boolean record(Runnable operation) {
        flushText();
        return record(operation, false, -1);
    }
    private boolean recordInput(Runnable operation){return record(operation,false,-1);}
    private boolean recordMatrix(Runnable operation) { flushText();return record(operation, true, 0, false); }
    private boolean recordModelTransform(Runnable operation){if(state.matrices.mode()!=5888)flushText();return record(operation,true,0,true);}
    private boolean record(Runnable operation, boolean matrix, int groups) {
        return record(operation,matrix,groups,false);
    }
    private boolean record(Runnable operation, boolean matrix, int groups,boolean batchSafeMatrix) {
        outsideBegin(); if (compiling == null || replayingMatrices) return false;
        if (compiling.operations.size() >= 1024 * 1024) throw new IllegalStateException("Oversized game model command group");
        compiling.operations.add(new Action(operation, matrix, groups,batchSafeMatrix)); return true;
    }
    private void outsideBegin() { if (immediate != null) throw new IllegalStateException("State operation inside a begun game primitive"); }
    interface Operation { void draw(); void close(); default boolean matrix() { return false; } default boolean batchSafeMatrix(){return false;} default int groups() { return -1; } default int batchDepthMask(){return -2;} default int batchTexture(){return -1;} default int batchCapability(){return 0;} }
    private static final class Action implements Operation {
        final Runnable action; final boolean matrix,batchSafeMatrix; final int groups;
        Action(Runnable action,boolean matrix,int groups,boolean batchSafeMatrix){this.action=action;this.matrix=matrix;this.groups=groups;this.batchSafeMatrix=batchSafeMatrix;}
        public void draw(){action.run();}public void close(){}public boolean matrix(){return matrix;}public int groups(){return groups;}public boolean batchSafeMatrix(){return batchSafeMatrix;}
    }
    private final class Draw implements Operation,GameTerrainBatch.Recovery {
        Mesh mesh; final Mesh.Primitive primitive; final int mask;
        Draw(GameGeometry geometry, MeshPipeline.Usage usage) {
            Mesh shared=terrainBatchEnabled&&compiling!=null&&compiling.terrainOwner!=0?terrainBatch().create(geometry,this):null;
            mesh=shared==null?context.meshes().create("game/geometry",geometry.mesh,usage):shared;primitive=geometry.primitive;mask=geometry.attributeMask;
        }
        public void draw() { drawMesh(mesh,primitive,mask); }
        public Mesh source(){return mesh;}
        public void replace(Mesh replacement){Mesh previous=mesh;previous.close();mesh=replacement;}
        public void close(){if(terrainBatch!=null)terrainBatch.retiring(this);mesh.close();}
    }
    private static final class Model {
        final List<Operation> operations=new ArrayList<Operation>();
        int terrainOwner;boolean fontGlyph;
        void close(){for(Operation op:operations)op.close();operations.clear();}
    }
    /** A declared original font glyph keeps only its logical xy/z/uv description, not a world/model vertex shadow.
     * Its standalone GPU representation is allocated only if an incompatible text effect needs it. */
    private final class Glyph implements Operation {
        final float[] positionsAndUvs=new float[20];
        Draw fallback;
        Glyph(int[] raw){for(int v=0;v<4;v++)for(int i=0;i<5;i++)positionsAndUvs[v*5+i]=Float.intBitsToFloat(raw[v*8+i]);}
        public void draw(){
            if(textScopeEligible()){text().glyph(positionsAndUvs);return;}
            flushText();
            if(fallback==null){int[] raw=new int[32];for(int v=0;v<4;v++)for(int i=0;i<5;i++)raw[v*8+i]=Float.floatToRawIntBits(positionsAndUvs[v*5+i]);fallback=new Draw(GameGeometry.raw(raw,32,4,7,false,true,false,true,false,false),MeshPipeline.Usage.STATIC);}
            fallback.draw();
        }
        public void close(){if(fallback!=null)fallback.close();}
    }
    public void defineFontGlyphs(int first,int count) {
        check();outsideBegin();flushText();
        if(compiling!=null||originalChunk!=null||first<=0||count<1||count>256||(long)first+count>nextName)throw new IllegalArgumentException("Invalid original font glyph range");
        for(int name=first;name<first+count;name++)if(fontGlyphNames.contains(name)||models.containsKey(name))throw new IllegalArgumentException("Font glyph range is already in use");
        for(int name=first;name<first+count;name++)fontGlyphNames.add(name);
    }
    public int beginText() {
        check();outsideBegin();
        // Text compiled inside an unrelated model retains the ordinary cached-model path.
        if(compiling!=null||(chunks!=null&&chunks.building()))return 0;
        if(textDepth==64)throw new IllegalStateException("Text scope overflow");
        return ++textDepth;
    }
    public void endText(int scope) {
        check();outsideBegin();if(scope==0)return;
        if(scope!=textDepth)throw new IllegalStateException("Mismatched text scope");
        if(scope==1)flushText();textDepth--;
    }
    public void abortText(int scope) {
        check();if(scope==0)return;
        if(scope!=textDepth)throw new IllegalStateException("Mismatched failed text scope");
        immediate=null;
        // Completed glyphs preceded the failing original operation. Keep that prefix visible.
        try{flushText();}finally{if(text!=null)text.discard();textDepth--;}
    }
    protected final void flushText(){flushTerrain();if(text!=null)text.flush();}
    private void flushTerrain(){if(terrainBatch!=null)terrainBatch.flush();}
    private boolean pendingTerrain(){return terrainBatch!=null&&terrainBatch.pending();}
    private GameTerrainBatch terrainBatch(){if(terrainBatch==null)terrainBatch=new GameTerrainBatch(this,context);return terrainBatch;}
    private boolean textScopeEligible(){return textDepth>0&&compiling==null&&effect==null;}
    // Immediate lit geometry can vary its normal within a quad; it keeps the ordinary path.
    private boolean textEligible(){return textScopeEligible()&&!state.enabled(2896);}
    private GameTextBatch text(){if(text==null)text=new GameTextBatch(this,context);return text;}
    void drawText(Mesh mesh,int count,ShaderProgram program) {
        state.bind(program,0);program.bind();boundProgram=program;raster.beginSamplers();
        try{mesh.draw(Mesh.Primitive.TRIANGLES,0,count);draws++;vertices+=count;}
        finally{raster.endSamplers();}
    }
    void drawTerrainSingle(Mesh mesh,Mesh.Primitive primitive,int mask,double[] matrix){
        bind(mask,matrix);try{mesh.draw(primitive,0,mesh.indexCount());originalChunkDraws++;vertices+=mesh.vertexCount();}finally{endSamplers();}
    }
    void drawTerrainBatch(MeshArena arena,List<Mesh> meshes,Mesh.Primitive primitive,int mask,ShaderProgram program,FloatBuffer matrices,long count){
        state.bind(program,mask);program.uniform("uOriginalModelPalette").setMatrix4Array(matrices);program.bind();boundProgram=program;raster.beginSamplers();
        try{arena.draw(primitive,meshes);originalChunkDraws++;vertices+=count;}finally{raster.endSamplers();}
    }
    /** The original chunk algorithms compile independently owned Core model ranges.
     * Replacement is atomic across the original passes; no global face plan or camera repacking. */
    private final class OriginalChunk {
        final int first,count;
        final Map<Integer,Model> pending=new LinkedHashMap<Integer,Model>();
        final GameMatrices.Snapshot matrices=state.matrices.snapshot();
        final Runnable inputs=restoreInputs(-1);
        final Deque<Runnable> savedAttributes=new ArrayDeque<Runnable>(attributes);
        final ShaderProgram savedEffect=effect;
        OriginalChunk(int first,int count){this.first=first;this.count=count;}
        boolean contains(int name){return name>=first&&(long)name<(long)first+count;}
        void restore(){inputs.run();matrices.restore(state.matrices);attributes.clear();attributes.addAll(savedAttributes);effect=savedEffect;}
    }
    void bind(int mask) {
        bind(mask,null);
    }
    private void bind(int mask,double[] originalModel) {
        ShaderProgram program=effect;
        if((mask&2048)!=0){
            if(program==null){if(chunkMaterial==null||chunkMaterial.isClosed())chunkMaterial=context.shaders().create(GameMaterialProgram.sources(true));program=chunkMaterial;}
            else{program=GameEffect.chunkProgram(program);if(program==null)throw new IllegalStateException("Effect has no chunk texture variant");}
        }else if(program==null){if(material==null||material.isClosed())material=context.shaders().create(GameMaterialProgram.sources());program=material;}
        state.bind(program,mask,originalModel);
        if((mask&512)!=0) {
            ShaderUniform width=program.findUniform("uGameLineWidth"),viewport=program.findUniform("uGameViewport");
            if(width!=null)width.setFloat(Math.max(1,Math.round(raster.lineWidth())));
            if(viewport!=null)viewport.setVec2(raster.viewportWidth(),raster.viewportHeight());
        }
        program.bind();boundProgram=program;if((mask&512)!=0)raster.beginLines();raster.beginSamplers();
    }
    void endSamplers(){try{raster.endSamplers();}finally{raster.endLines();}}
    void bindChunk(int mask,float regionX,float regionY,float regionZ) {
        bind(mask|1024);
        ShaderUniform region=boundProgram.findUniform("uGameChunkRegion");
        if(region!=null)region.setVec3(regionX,regionY,regionZ);
    }
    boolean chunkTexturesSupported(){return state.activeUnit()==0&&(effect==null||GameEffect.chunkTexturesSupported(effect));}
    void bindChunkTextures(int[] textures){
        raster.chunkTextures(textures);
        ShaderProgram program=boundProgram;
        if(chunkTexturePrograms.add(program)){
            for(int i=1;i<=GameChunkTextures.SIZE;i++)uniformInt(program,"uGameChunkTexture"+i,3+i);
            uniformInt(program,"uGameChunkTextureRouting",1);
        }
    }
    void endChunkTextures(){raster.endChunkTextures();}
    private static void uniformInt(ShaderProgram program,String name,int value){ShaderUniform uniform=program.findUniform(name);if(uniform!=null)uniform.setInt(value);}
    private void submit(GameGeometry geometry) {
        if(chunks!=null&&chunks.building())throw new IllegalStateException("Unexpected immediate/model geometry inside a terrain rebuild");
        if(geometry.mesh.indices().count()==0)return;
        if(compiling==null){Mesh reused=dynamicMeshes.acquire(geometry.mesh);if(reused!=null){drawMesh(reused,geometry.primitive,geometry.attributeMask);return;}}
        Draw draw=new Draw(geometry,compiling==null?MeshPipeline.Usage.STREAM:MeshPipeline.Usage.STATIC);
        if(compiling!=null)compiling.operations.add(draw);
        else try{draw.draw();}finally{draw.close();}
    }
    private void drawMesh(Mesh mesh,Mesh.Primitive primitive,int mask) {
        if(terrainBatchDepth>0&&terrainReplayDepth>0&&effect==null&&!state.enabled(2896)&&terrainBatch!=null&&terrainBatch.append(mesh,primitive,mask))return;
        flushText();
        bind(mask);try{mesh.draw(primitive,0,mesh.indexCount());if(terrainReplayDepth==0)draws++;else originalChunkDraws++;vertices+=mesh.vertexCount();}finally{endSamplers();}
    }
    public int raw(int[] data,int words,int count,int mode,boolean converted,boolean begun,boolean color,boolean texture,boolean light,boolean normals) {
        check();outsideBegin();
        if(chunks!=null&&chunks.building()) {
            if(compiling==null)throw new IllegalStateException("Terrain batch outside its pass");
            int bytes=chunks.raw(data,words,count,mode,converted,begun,color,texture,light,normals,new ArrayList<Operation>(compiling.operations));
            rawBatches++;return bytes;
        }
        if(compiling!=null&&compiling.fontGlyph&&words==32&&count==4&&mode==7&&!converted&&begun&&!color&&texture&&!light&&!normals) {
            if(data==null||data.length<32)throw new IllegalArgumentException("Incomplete font glyph");
            for(int v=0;v<4;v++)for(int i=0;i<5;i++)if(!Float.isFinite(Float.intBitsToFloat(data[v*8+i])))throw new IllegalArgumentException("Non-finite font glyph");
            compiling.operations.add(new Glyph(data));rawBatches++;return 128;
        }
        flushText();GameGeometry geometry=GameGeometry.raw(data,words,count,mode,converted,begun,color,texture,light,normals);
        submit(geometry);rawBatches++;return Math.multiplyExact(words,4);
    }
    public int glGenLists(int count) {
        outsideBegin();if(count<0)throw new IllegalArgumentException("Negative game model count");if(count==0)return 0;
        int first=nextName;nextName=Math.addExact(nextName,count);if(nextName<=0)throw new IllegalStateException("Game model id space exhausted");return first;
    }
    public void glNewList(int name,int mode){glNewList(name,mode,false);}
    public void glNewList(int name,int mode,boolean terrain) {
        outsideBegin();flushText();if(compiling!=null||name<=0||mode!=4864)throw new IllegalArgumentException("Invalid game model compilation");
        if(originalChunk!=null&&!originalChunk.contains(name))throw new IllegalArgumentException("Model outside original chunk transaction");
        if(chunks!=null&&chunks.building())chunks.beginPass(name);
        compilingName=name;compiling=new Model();compiling.fontGlyph=fontGlyphNames.contains(name);
        if(originalChunk!=null)compiling.terrainOwner=originalChunk.first;
    }
    public void glEndList() {
        outsideBegin();if(compiling==null)throw new IllegalStateException("No game model is being compiled");
        if(chunks!=null&&chunks.building()) { chunks.endPass(compilingName);compiling=null;compilingName=0;return; }
        if(originalChunk!=null){Model old=originalChunk.pending.put(compilingName,compiling);compiling=null;compilingName=0;if(old!=null)old.close();return;}
        Model old=compiling.operations.isEmpty()?models.remove(compilingName):models.put(compilingName,compiling);compiling=null;compilingName=0;if(old!=null)old.close();
    }
    public void abortModel() {
        check();immediate=null;if(compiling!=null){compiling.close();compiling=null;compilingName=0;}
    }
    public void glCallList(int name) {
        if(recordInput(()->glCallList(name)))return;
        Model model=models.get(name);if(model==null)return;
        if(callDepth>=64)throw new IllegalStateException("Recursive game model cache");
        boolean terrain=model.terrainOwner!=0;
        if(!terrain)flushTerrain();
        callDepth++;if(terrain)terrainReplayDepth++;
        try{for(Operation op:model.operations)op.draw();modelDraws++;}finally{if(terrain)terrainReplayDepth--;callDepth--;}
    }
    public void glCallLists(IntBuffer names) {
        if(names==null)throw new NullPointerException("model handles");
        if(compiling!=null||!terrainBatchEnabled){for(int i=names.position();i<names.limit();i++)glCallList(names.get(i));return;}
        if(terrainBatchDepth==64)throw new IllegalStateException("Terrain list scope overflow");
        if(terrainBatchDepth==0&&callDepth==0&&originalChunk==null&&terrainBatch!=null)terrainBatch.recover();
        terrainBatchDepth++;
        try{for(int i=names.position();i<names.limit();i++)glCallList(names.get(i));}
        finally{try{if(terrainBatchDepth==1)flushTerrain();}finally{terrainBatchDepth--;}}
    }
    public void glDeleteLists(int first,int count) {
        outsideBegin();flushText();if(count<0)throw new IllegalArgumentException("Negative model range");if(count==0)return;
        if(originalChunk!=null)throw new IllegalStateException("Model deletion during original chunk transaction");
        long end=(long)first+count;List<Integer> selected=new ArrayList<Integer>();
        if(chunks!=null)chunks.unload(first,count);
        for(Integer name:models.tailMap(first,true).keySet()){if(name>=end)break;selected.add(name);}
        for(Integer name:selected)models.remove(name).close();
        fontGlyphNames.removeIf(name->name>=first&&name<end);
    }
    private static final class Immediate {
        ByteBuffer data;
        final int mode; final boolean recording;
        float[] color,normal,uv,light;int mask,changed;
        Immediate(int mode,boolean recording,GameRenderState state,ByteBuffer scratch){this.mode=mode;this.recording=recording;data=scratch==null?ByteBuffer.allocateDirect(60*256).order(ByteOrder.nativeOrder()):scratch;data.clear();color=state.color();normal=state.normal();uv=state.uv(0);light=state.uv(1);mask=recording?1:31;}
        void vertex(float x,float y,float z) {
            if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))throw new IllegalArgumentException("Non-finite immediate vertex");
            if(data.remaining()<60){if(data.capacity()>=60*1024*1024)throw new IllegalArgumentException("Oversized immediate primitive");ByteBuffer larger=ByteBuffer.allocateDirect(Math.multiplyExact(data.capacity(),2)).order(ByteOrder.nativeOrder());data.flip();larger.put(data);data=larger;}
            data.putFloat(x).putFloat(y).putFloat(z);for(float v:color)data.putFloat(v);for(float v:uv)data.putFloat(v);for(float v:light)data.putFloat(v);for(float v:normal)data.putFloat(v);data.putFloat(mask);
        }
    }
    public void glBegin(int mode) {
        if(immediate!=null)throw new IllegalStateException("Nested game primitive");GameGeometry.indices(mode,0);
        ByteBuffer scratch=null;if(textEligible()){if(textImmediate==null)textImmediate=ByteBuffer.allocateDirect(240).order(ByteOrder.nativeOrder());scratch=textImmediate;}
        immediate=new Immediate(mode,compiling!=null,state,scratch);
    }
    public void glEnd() {
        if(immediate==null)throw new IllegalStateException("No game primitive");Immediate batch=immediate;immediate=null;
        batch.data.flip();if(!textEligible()||!text().immediate(batch.data,batch.mode)){flushText();submit(GameGeometry.floats(batch.data,batch.mode,1|128));}
        // Current attributes changed within a cached primitive take effect after its execution too.
        if((batch.changed&2)!=0)glColor4f(batch.color[0],batch.color[1],batch.color[2],batch.color[3]);
        if((batch.changed&16)!=0)glNormal3f(batch.normal[0],batch.normal[1],batch.normal[2]);
        if((batch.changed&4)!=0)glTexCoord2f(batch.uv[0],batch.uv[1]);
        if((batch.changed&8)!=0)glMultiTexCoord2f(33985,batch.light[0],batch.light[1]);
    }
    public void glVertex3f(float x,float y,float z){if(immediate==null)throw new IllegalStateException("Vertex outside game primitive");immediate.vertex(x,y,z);}
    public void glVertex3d(double x,double y,double z){glVertex3f((float)x,(float)y,(float)z);}
    public void glVertex2f(float x,float y){glVertex3f(x,y,0);}
    public void glColor4f(float r,float g,float b,float a) {
        if(immediate!=null){immediate.color=new float[]{clamp(r),clamp(g),clamp(b),clamp(a)};immediate.mask|=2;immediate.changed|=2;return;}
        if(recordInput(()->glColor4f(r,g,b,a)))return;flushTerrain();state.color(r,g,b,a);
    }
    private static float clamp(float value){if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite game color");return Math.max(0,Math.min(1,value));}
    public void glColor3f(float r,float g,float b){glColor4f(r,g,b,1);}
    public void glColor4ub(byte r,byte g,byte b,byte a){glColor4f((r&255)/255f,(g&255)/255f,(b&255)/255f,(a&255)/255f);}
    public void glColor3ub(byte r,byte g,byte b){glColor4ub(r,g,b,(byte)255);}
    public void glColor3b(byte r,byte g,byte b){glColor3f(Math.max(-1,r/127f),Math.max(-1,g/127f),Math.max(-1,b/127f));}
    public void glNormal3f(float x,float y,float z) {
        if(immediate!=null){immediate.normal=new float[]{x,y,z};immediate.mask|=16;immediate.changed|=16;return;}
        if(recordInput(()->glNormal3f(x,y,z)))return;flushTerrain();state.normal(x,y,z);
    }
    public void glNormal3d(double x,double y,double z){glNormal3f((float)x,(float)y,(float)z);}
    public void glTexCoord2f(float u,float v){glMultiTexCoord2f(33984,u,v);}
    public void glMultiTexCoord2f(int unit,float u,float v) {
        int index=unit-33984;if(index<0||index>=4)throw new IllegalArgumentException("Game texture coordinate unit");
        if(immediate!=null){if(index>1)throw new IllegalArgumentException("Immediate auxiliary texture coordinates");if(index==0){immediate.uv=new float[]{u,v};immediate.mask|=4;immediate.changed|=4;}else{immediate.light=new float[]{u,v};immediate.mask|=8;immediate.changed|=8;}return;}
        if(recordInput(()->glMultiTexCoord2f(unit,u,v)))return;flushTerrain();state.uv(index,u,v);
    }
    public void glActiveTexture(int unit){if(record(()->glActiveTexture(unit)))return;state.activeUnit(unit-33984);raster.activeTexture(unit-33984);}
    public void glClientActiveTexture(int unit){outsideBegin();if(unit<33984||unit>=33988)throw new IllegalArgumentException("Client texture unit");clientTexture=unit-33984;}
    public void glMatrixMode(int mode){if(recordMatrix(()->glMatrixMode(mode)))return;state.matrices.mode(mode);}
    public void glLoadIdentity(){if(recordMatrix(()->glLoadIdentity()))return;state.matrices.loadIdentity();}
    public void glPushMatrix(){if(recordModelTransform(()->glPushMatrix()))return;state.matrices.push();}
    public void glPopMatrix(){if(recordModelTransform(()->glPopMatrix()))return;state.matrices.pop();}
    public void glLoadMatrix(FloatBuffer value){matrix(GameMatrices.read(value),true);}
    public void glMultMatrix(FloatBuffer value){matrix(GameMatrices.read(value),false);}
    private void matrix(double[] value,boolean load){if(recordMatrix(()->matrix(value,load)))return;if(load)state.matrices.load(value);else state.matrices.multiply(value);}
    public void glTranslatef(float x,float y,float z){glTranslated(x,y,z);}
    public void glTranslated(double x,double y,double z){if(recordModelTransform(()->glTranslated(x,y,z)))return;state.matrices.translate(x,y,z);}
    public void glScalef(float x,float y,float z){glScaled(x,y,z);}
    public void glScaled(double x,double y,double z){if(recordModelTransform(()->glScaled(x,y,z)))return;state.matrices.scale(x,y,z);}
    public void glRotatef(float angle,float x,float y,float z){if(recordMatrix(()->glRotatef(angle,x,y,z)))return;state.matrices.rotate(angle,x,y,z);}
    public void glOrtho(double l,double r,double b,double t,double n,double f){if(recordMatrix(()->glOrtho(l,r,b,t,n,f)))return;state.matrices.ortho(l,r,b,t,n,f);}
    public void glEnable(int capability){enable(capability,true);}
    public void glDisable(int capability){enable(capability,false);}
    private void enable(int capability,boolean on){
        outsideBegin();final int groups=batchCapabilityGroup(capability);
        if(compiling!=null&&!replayingMatrices&&groups!=-1){
            if(compiling.operations.size()>=1024*1024)throw new IllegalStateException("Oversized game model command group");
            compiling.operations.add(new Operation(){public void draw(){enable(capability,on);}public void close(){}public int groups(){return groups;}public int batchCapability(){return on?capability:-capability;}});return;
        }
        if(pendingTerrain()&&capability!=2903&&(GameRenderState.materialCapability(capability)?state.enabled(capability):raster.enabled(capability))==on){if(GameRenderState.materialCapability(capability))state.enable(capability,on);else raster.enable(capability,on);return;}
        if(record(()->enable(capability,on)))return;if(GameRenderState.materialCapability(capability))state.enable(capability,on);else raster.enable(capability,on);
    }
    /** Only independent boolean switches commute. Color-material enable also copies current
     * color into material parameters, so it deliberately retains its ordered individual path. */
    private static int batchCapabilityGroup(int capability){
        switch(capability){
            case 3553:return 0x40000;
            case 2896:case 16384:case 16385:return 0x40;
            case 2977:case 32826:return 0x1000;
            case 2912:return 0x80;case 3008:return 0x4000;case 34913:return 2;
            case 2903:return -1;
            default:try{return GameRasterState.enableGroup(capability);}catch(IllegalArgumentException unsupported){return -1;}
        }
    }
    public boolean glIsEnabled(int capability){outsideBegin();if(GameRenderState.materialCapability(capability))return state.enabled(capability);flushText();return GL11C.glIsEnabled(capability);}
    public boolean glGetBoolean(int parameter){outsideBegin();if(GameRenderState.materialCapability(parameter))return state.enabled(parameter);flushText();return GL11C.glGetBoolean(parameter);}
    public void glAlphaFunc(int function,float reference){if(record(()->glAlphaFunc(function,reference)))return;state.alpha(function,reference);}
    public void glShadeModel(int mode){if(record(()->glShadeModel(mode)))return;state.shade(mode);}
    public void glColorMaterial(int face,int mode){if(record(()->glColorMaterial(face,mode)))return;state.colorMaterial(face,mode);}
    public void glFogf(int parameter,float value){if(record(()->glFogf(parameter,value)))return;state.fog(parameter,value);}
    public void glFogi(int parameter,int value){glFogf(parameter,value);}
    public void glFog(int parameter,FloatBuffer value){fog(parameter,GameRenderState.read(value,parameter==2918?4:1));}
    private void fog(int parameter,float[] value){if(record(()->fog(parameter,value)))return;if(value.length==4)state.fog(parameter,value);else state.fog(parameter,value[0]);}
    public void glLight(int light,int parameter,FloatBuffer value){light(light,parameter,GameRenderState.read(value,4));}
    private void light(int light,int parameter,float[] value){if(record(()->light(light,parameter,value)))return;state.light(light-16384,parameter,value);}
    public void glLightModel(int parameter,FloatBuffer value){lightModel(parameter,GameRenderState.read(value,4));}
    private void lightModel(int parameter,float[] value){if(record(()->lightModel(parameter,value)))return;state.lightModel(parameter,value);}
    public void glPushAttrib(int mask) {
        if(record(()->glPushAttrib(mask)))return;if(attributes.size()>=64)throw new IllegalStateException("Game attribute stack overflow");
        GameRenderState.Snapshot material=state.snapshot(mask);Runnable nativeState=raster.snapshot(mask);
        attributes.push(()->{material.restore(state);nativeState.run();});
    }
    public void glPopAttrib(){if(record(()->glPopAttrib()))return;if(attributes.isEmpty())throw new IllegalStateException("Game attribute stack underflow");attributes.pop().run();}
    public void glBindTexture(int target,int texture){
        outsideBegin();
        if(compiling!=null&&!replayingMatrices){
            if(compiling.operations.size()>=1024*1024)throw new IllegalStateException("Oversized game model command group");
            compiling.operations.add(new Operation(){public void draw(){glBindTexture(target,texture);}public void close(){}public int groups(){return 0x40000;}public int batchTexture(){return target==3553&&texture>=0?texture:-1;}});return;
        }
        if(!pendingTerrain()||target!=3553||raster.texture()!=texture)flushText();raster.bindTexture(target,texture);
    }
    public int glGenTextures(){outsideBegin();return GL11C.glGenTextures();}
    public void glGenTextures(IntBuffer textures){outsideBegin();GL11C.glGenTextures(textures);}
    public void glDeleteTextures(int texture){outsideBegin();flushText();raster.deleteTexture(texture);}
    public void glDeleteTextures(IntBuffer textures){outsideBegin();flushText();for(int i=textures.position();i<textures.limit();i++)raster.deleteTexture(textures.get(i));}
    public void glTexParameteri(int target,int parameter,int value){if(record(()->glTexParameteri(target,parameter,value)))return;raster.textureParameter(target,parameter,value);}
    public void glTexEnvi(int target,int parameter,int value){if(record(()->glTexEnvi(target,parameter,value)))return;if(target!=8960||parameter!=8704||value!=8448)throw new IllegalArgumentException("Unsupported texture combine operation");}
    public void glTexImage2D(int target,int level,int internal,int width,int height,int border,int format,int type,ByteBuffer pixels) {
        if(compiling!=null){final ByteBuffer copy=copy(pixels);if(record(()->glTexImage2D(target,level,internal,width,height,border,format,type,copy)))return;}
        outsideBegin();flushText();GL11C.glTexImage2D(target,level,internal==3?GL11C.GL_RGB8:internal==4?GL11C.GL_RGBA8:internal,width,height,border,format,type,pixels);
    }
    public void glTexSubImage2D(int target,int level,int x,int y,int width,int height,int format,int type,ByteBuffer pixels) {
        if(compiling!=null){final ByteBuffer copy=copy(pixels);if(record(()->glTexSubImage2D(target,level,x,y,width,height,format,type,copy)))return;}
        outsideBegin();flushText();GL11C.glTexSubImage2D(target,level,x,y,width,height,format,type,pixels);
    }
    private static ByteBuffer copy(ByteBuffer bytes){if(bytes==null)return null;ByteBuffer copy=ByteBuffer.allocateDirect(bytes.remaining()).order(bytes.order());copy.put(bytes.duplicate()).flip();return copy;}
    public void glCopyTexSubImage2D(int target,int level,int xoffset,int yoffset,int x,int y,int w,int h){if(record(()->glCopyTexSubImage2D(target,level,xoffset,yoffset,x,y,w,h)))return;GL11C.glCopyTexSubImage2D(target,level,xoffset,yoffset,x,y,w,h);}
    public void glPixelStorei(int parameter,int value){outsideBegin();GL11C.glPixelStorei(parameter,value);}
    public void glReadPixels(int x,int y,int w,int h,int format,int type,ByteBuffer pixels){outsideBegin();flushText();GL11C.glReadPixels(x,y,w,h,format,type,pixels);}
    public int glGetError(){outsideBegin();flushText();return GL11C.glGetError();}
    public String glGetString(int parameter){outsideBegin();flushText();if(parameter!=GL11C.GL_EXTENSIONS)return GL11C.glGetString(parameter);StringJoiner extensions=new StringJoiner(" ");for(int i=0;i<GL11C.glGetInteger(GL30C.GL_NUM_EXTENSIONS);i++)extensions.add(GL30C.glGetStringi(parameter,i));return extensions.toString();}
    public void glGetFloat(int parameter,FloatBuffer value) {
        outsideBegin();if(parameter==2982||parameter==2983||parameter==2984){double[] matrix=state.matrices.get(parameter);if(value.remaining()<16)throw new IllegalArgumentException("Matrix output buffer");for(int i=0;i<16;i++)value.put(value.position()+i,(float)matrix[i]);}
        else if(parameter==2816){float[] color=state.color();if(value.remaining()<4)throw new IllegalArgumentException("Color output buffer");for(int i=0;i<4;i++)value.put(value.position()+i,color[i]);}
        else if(parameter==GL11C.GL_LINE_WIDTH){if(!value.hasRemaining())throw new IllegalArgumentException("Line width output buffer");value.put(value.position(),raster.lineWidth());}
        else {flushText();GL11C.glGetFloatv(parameter,value);}
    }
    public int glGetInteger(int parameter){outsideBegin();if(parameter==2976)return state.matrices.mode();if(parameter==34016)return 33984+state.activeUnit();if(parameter==34017)return 33984+clientTexture;if(parameter==34018)return 4;if(parameter==2866)return 0;flushText();return GL11C.glGetInteger(parameter);}
    public void glGetInteger(int parameter,IntBuffer value){outsideBegin();if(parameter==2976||parameter==34016||parameter==34017||parameter==34018||parameter==2866){if(!value.hasRemaining())throw new IllegalArgumentException("Integer output buffer");value.put(value.position(),glGetInteger(parameter));}else {flushText();GL11C.glGetIntegerv(parameter,value);}}
    public void glDepthMask(boolean value){outsideBegin();if(compiling!=null&&!replayingMatrices){if(compiling.operations.size()>=1024*1024)throw new IllegalStateException("Oversized game model command group");compiling.operations.add(new Operation(){public void draw(){glDepthMask(value);}public void close(){}public int groups(){return 0x100;}public int batchDepthMask(){return value?1:0;}});return;}if(!pendingTerrain()||raster.depthMask()!=value)flushText();raster.depthMask(value);}
    public void glDepthFunc(int value){if(record(()->glDepthFunc(value)))return;raster.set("depthFunc",0x100,()->GL11C.glDepthFunc(value));}
    public void glBlendFunc(int source,int destination){if(record(()->glBlendFunc(source,destination)))return;raster.set("blendFunc",0x4000,()->GL11C.glBlendFunc(source,destination));}
    public void glBlendEquation(int value){if(record(()->glBlendEquation(value)))return;raster.set("blendEquation",0x4000,()->GL14C.glBlendEquation(value));}
    public void glColorMask(boolean r,boolean g,boolean b,boolean a){if(record(()->glColorMask(r,g,b,a)))return;raster.set("colorMask",0x4000,()->GL11C.glColorMask(r,g,b,a));}
    public void glLogicOp(int value){if(record(()->glLogicOp(value)))return;raster.set("logicOp",0x4000,()->GL11C.glLogicOp(value));}
    public void glCullFace(int value){if(record(()->glCullFace(value)))return;raster.set("cullFace",8,()->GL11C.glCullFace(value));}
    public void glFrontFace(int value){if(record(()->glFrontFace(value)))return;raster.set("frontFace",8,()->GL11C.glFrontFace(value));}
    public void glPolygonOffset(float factor,float units){if(record(()->glPolygonOffset(factor,units)))return;raster.set("polygonOffset",8,()->GL11C.glPolygonOffset(factor,units));}
    public void glLineWidth(float value){if(record(()->glLineWidth(value)))return;raster.lineWidth(value);}
    public void glViewport(int x,int y,int w,int h){if(record(()->glViewport(x,y,w,h)))return;raster.viewport(x,y,w,h);}
    public void glStencilFunc(int function,int ref,int mask){if(record(()->glStencilFunc(function,ref,mask)))return;raster.set("stencilFunc",0x400,()->GL11C.glStencilFunc(function,ref,mask));}
    public void glStencilOp(int fail,int zfail,int pass){if(record(()->glStencilOp(fail,zfail,pass)))return;raster.set("stencilOp",0x400,()->GL11C.glStencilOp(fail,zfail,pass));}
    public void glClearColor(float r,float g,float b,float a){if(record(()->glClearColor(r,g,b,a)))return;raster.set("clearColor",0x4000,()->GL11C.glClearColor(r,g,b,a));}
    public void glClearDepth(double value){if(record(()->glClearDepth(value)))return;raster.set("clearDepth",0x100,()->GL11C.glClearDepth(value));}
    public void glClearStencil(int value){if(record(()->glClearStencil(value)))return;raster.set("clearStencil",0x400,()->GL11C.glClearStencil(value));}
    public void glClear(int bits){if(record(()->glClear(bits)))return;GL11C.glClear(bits);}
    private GameChunks chunks() { if(chunks==null)chunks=new GameChunks(this,context);return chunks; }
    public void beginChunk(int handle,int x,int y,int z,int passes) { check();outsideBegin();flushText();if(compiling!=null||originalChunk!=null)throw new IllegalStateException("Nested terrain rebuild");chunks().begin(handle,x,y,z,passes); }
    public void finishChunk() { check();outsideBegin();if(compiling!=null)throw new IllegalStateException("Unfinished terrain pass");chunks().finish(); }
    public void abortChunk() { check();abortModel();if(chunks!=null)chunks.abort(); }
    public void drawChunks(List<?> selected,int pass,double x,double y,double z) { check();outsideBegin();flushText();chunks().draw(selected,pass,x,y,z); }
    public void beginOriginalChunk(int handle,int passes) {
        check();outsideBegin();flushText();
        if(compiling!=null||originalChunk!=null||callDepth!=0||(chunks!=null&&chunks.building()))throw new IllegalStateException("Nested original chunk rebuild");
        if(handle<=0||passes<1||passes>3||(long)handle+passes>Integer.MAX_VALUE)throw new IllegalArgumentException("Original chunk handle range");
        originalChunk=new OriginalChunk(handle,passes);
        if(!originalChunkReported){originalChunkReported=true;if(Boolean.getBoolean("mcgl.graphics.profile"))System.out.println("[MCGL Original Chunks] Core cached meshes; original scheduler and local transparency; no global face grouping");}
    }
    public void finishOriginalChunk() {
        check();outsideBegin();if(originalChunk==null||compiling!=null)throw new IllegalStateException("Unfinished original chunk transaction");
        OriginalChunk finished=originalChunk;List<Model> retired=new ArrayList<Model>();
        for(Map.Entry<Integer,Model> entry:finished.pending.entrySet()) {
            Model next=entry.getValue();Model old=next.operations.isEmpty()?models.remove(entry.getKey()):models.put(entry.getKey(),next);
            if(old!=null)retired.add(old);
        }
        originalChunk=null;for(Model old:retired)old.close();
    }
    public void abortOriginalChunk() {
        check();OriginalChunk failed=originalChunk;
        try{abortModel();if(failed!=null)for(Model model:failed.pending.values())model.close();}
        finally{originalChunk=null;if(failed!=null)failed.restore();}
    }
    public int residentChunks() { check();Set<Integer> original=new HashSet<Integer>();for(Model model:models.values())if(model.terrainOwner!=0)original.add(model.terrainOwner);return original.size()+(chunks==null?0:chunks.residentChunks()); }
    public long chunkDrawCalls() { check();return originalChunkDraws+(chunks==null?0:chunks.drawCalls()); }
    public long chunkIndexUploads() { check();return chunks==null?0:chunks.indexUploads(); }
    Runnable restoreInputs(int groups) {
        GameRenderState.Snapshot saved=state.snapshot(groups);Runnable nativeState=raster.snapshot(groups);
        return ()->{saved.restore(state);nativeState.run();};
    }
    double[] chunkBoundsTransform(List<Operation> prefix,int x,int y,int z) {
        GameMatrices.Snapshot saved=state.matrices.snapshot();boolean previous=replayingMatrices;replayingMatrices=true;
        try {
            state.matrices.mode(5888);state.matrices.loadIdentity();
            for(Operation operation:prefix)if(operation.matrix())operation.draw();
            double[] transform=state.matrices.modelView();transform[12]-=x&1023;transform[13]-=y;transform[14]-=z&1023;return transform;
        }finally{saved.restore(state.matrices);replayingMatrices=previous;}
    }
}
