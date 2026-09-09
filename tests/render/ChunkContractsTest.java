package local.mcgl.render;

import java.io.File;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import local.mcgl.render.tests.OriginalChunkEmitter;

/** CPU-only contracts, real client accumulator integration, and failure-injected resource ownership. */
public final class ChunkContractsTest {
    private static int checks;
    private static final ChunkMaterial SOLID = material("solid", 0), GLASS = material("glass", 1);
    public static void main(String[] args) throws Exception {
        data(); sharedTopology(); batchTransforms(); realAccumulator(new File(args[0])); registry(); batchRegistry(); boundedBatches(); visibilityBatches(); visibilityIndexType(); independentPassRebuilds();
        System.out.println("CHUNK_CONTRACTS_PASS checks=" + checks + " real-emitter/overflow/snapshots/global-sort/revisions/rollback/lifetime/no-native");
    }
    private static ChunkMaterial material(String key, int pass) { return new ChunkMaterial(key, pass, ShaderLibrary.Material.COLOR, ShaderLibrary.AlphaTest.DISABLED, 0); }
    private static ChunkMeshBuilder builder(int maximum) { return new ChunkMeshBuilder(0, 0, 0, maximum); }
    private static int[] quad(float z) {
        int[] raw = new int[32]; float[] xy = {-0.8f, -0.8f, 0.8f, -0.8f, 0.8f, 0.8f, -0.8f, 0.8f};
        for (int i = 0; i < 4; i++) { raw[i * 8] = Float.floatToRawIntBits(xy[i * 2]); raw[i * 8 + 1] = Float.floatToRawIntBits(xy[i * 2 + 1]); raw[i * 8 + 2] = Float.floatToRawIntBits(z); }
        return raw;
    }
    private static void append(ChunkMeshBuilder b, ChunkMaterial material, int[] raw) {
        b.sink(material, -1, 0.25f, 0.5f, 0x007f0000, 0x00100020)
                .append(raw, raw.length, raw.length / 8, 7, false, true, false, false, false, false);
    }
    private static ChunkMeshData one(float z) { ChunkMeshBuilder b = builder(4); append(b, SOLID, quad(z)); return b.finish(); }
    private static void data() throws Exception {
        rejects(() -> new ChunkMeshBuilder(1, 0, 0, 4)); rejects(() -> builder(-1));
        rejects(() -> material("bad key", 0)); rejects(() -> material("bad", 3));
        ChunkMeshBuilder b = builder(4); int[] raw = quad(0); append(b, SOLID, raw); raw[0] = 0;
        ChunkMeshData snapshot = b.finish(); ByteBuffer vertices = snapshot.parts().get(0).mesh.vertices();
        check(vertices.getFloat(0) == -0.8f && vertices.getFloat(12) == 0.25f && vertices.getFloat(16) == 0.5f
                && vertices.getInt(20) == -1 && vertices.getInt(24) == 0x007f0000 && vertices.getInt(28) == 0x00100020, "snapshot/default disabled arrays");
        check(snapshot.vertexCount == 4 && snapshot.parts().get(0).mesh.indices().count() == 6, "quad triangulated");
        try { vertices.put(0,(byte)1); throw new AssertionError("mutable vertices"); } catch (ReadOnlyBufferException expected) { checks++; }
        ChunkMeshBuilder empty=builder(0);append(empty,SOLID,new int[0]);check(empty.finish().parts().isEmpty(),"empty batches allocate no mesh parts");
        rejects(b::finish); rejects(() -> append(b, SOLID, quad(0)));
        try { snapshot.parts().clear(); throw new AssertionError("mutable parts"); } catch (UnsupportedOperationException expected) { checks++; }
        ChunkMeshBuilder invalid = builder(4); ChunkMeshBuilder.Sink sink = invalid.sink(SOLID, -1, 0, 0, 0, 0);
        rejects(() -> sink.append(quad(0), 32, 4, 7, false, false, true, true, true, true));
        rejects(() -> sink.append(quad(0), 31, 4, 7, false, true, true, true, true, true));
        rejects(() -> sink.append(quad(0), 32, 4, 7, true, true, true, true, true, true));
        rejects(() -> sink.append(quad(0), 32, 4, 1, false, true, true, true, true, true));
        int[] nan = quad(0); nan[0] = Float.floatToRawIntBits(Float.NaN);
        rejects(() -> append(invalid, SOLID, nan)); append(invalid, SOLID, quad(0));
        rejects(() -> append(invalid, SOLID, quad(0))); check(invalid.finish().vertexCount == 4, "failed append leaves builder unchanged");
        ChunkMeshBuilder ownership = builder(4); foreign(() -> rejects(() -> append(ownership, SOLID, quad(0)))); ownership.abort();
        ChunkMeshBuilder large = builder(65540); int[] all = new int[65540 * 8]; append(large, GLASS, all);
        check(large.finish().parts().get(0).mesh.indices().type() == IndexData.Type.UINT32, "large chunk indices promote");
        double[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
        ChunkFrustum f = ChunkFrustum.fromMatrix(identity); identity[0] = 999;
        check(f.intersects(-1,-1,-1,1,1,1) && f.intersects(1,0,0,2,1,1) && !f.intersects(1.01,0,0,2,1,1), "frustum boundary/conservative planes/snapshot");
        check(!f.intersects(-3,0,0,-2,1,1) && !f.intersects(0,2,0,1,3,1) && !f.intersects(0,0,-3,1,1,-2), "all clipping axes");
        rejects(() -> ChunkFrustum.fromMatrix(new double[16])); rejects(() -> f.intersects(Double.NaN,0,0,1,1,1));
        double[] transform=GameMatrices.identity();transform[0]=2;transform[12]=4;transform[13]=3;transform[14]=2;
        ChunkMeshBuilder transformed=builder(4);ChunkMeshBuilder.Sink transformedSink=transformed.sink(GLASS,-1,0,0,0,0,transform);
        transform[12]=999;int[] original=quad(1);transformedSink.append(original,32,4,7,false,true,true,true,true,true);
        ChunkMeshData.Part part=transformed.finish().parts().get(0);double[] bounds=part.bounds(),center=part.centers();
        check(Math.abs(bounds[0]-2.4)<1e-6&&Math.abs(bounds[3]-5.6)<1e-6&&center[0]==4&&center[1]==3&&center[2]==3,"matrix snapshot transforms only bounds and sort centers");
        for(int i=0;i<32;i++)check(part.mesh.vertices().getInt(i*4)==original[i],"transformed metadata leaves every original raw word intact");
        double[] perspective=GameMatrices.identity();perspective[3]=1;rejects(()->builder(4).sink(GLASS,-1,0,0,0,0,perspective));
    }
    private static void realAccumulator(File client) throws Exception {
        OriginalChunkEmitter t = new OriginalChunkEmitter(client, 96); // auto-flush after TWO quads
        ChunkMeshBuilder b = builder(100); t.bind(b, GLASS); t.begin();
        rejects(t::unbind); t.translate(0.125, 0.25, 0.5); t.color(32,64,128,192); t.lightmap(0x00100020); t.normal(0,0,1);
        for (int i = 0; i < 20; i++) t.quad(-0.5,-0.5,0.5,0.5,i);
        check(t.draw() == 0, "explicit draw after exact capacity flush is empty");
        rejects(t::draw); t.unbind(); ChunkMeshData result = b.finish();
        check(result.vertexCount == 80 && result.parts().size() == 10, "original automatic flushes all reach CPU sink");
        ByteBuffer bytes = result.parts().get(0).mesh.vertices();
        check(bytes.getFloat(0) == -0.375f && bytes.getFloat(4) == -0.25f && bytes.getFloat(8) == 0.5f, "original double translation and float positions");
        check((bytes.get(20)&255)==32 && (bytes.get(21)&255)==64 && (bytes.get(22)&255)==128 && (bytes.get(23)&255)==192
                && bytes.get(26)==127 && bytes.getShort(28)==32 && bytes.getShort(30)==16, "original packed color/normal/lightmap");
        ChunkMeshBuilder exact = builder(4); OriginalChunkEmitter bigger = new OriginalChunkEmitter(client, 256);
        bigger.bind(exact, SOLID); bigger.begin(); bigger.color(3,7,11,255); bigger.lightmap(0x00400080); bigger.normal(0,1,0); bigger.quad(0,0,1,1,0);
        int[] original = bigger.raw(); check(bigger.draw()==128, "original draw byte count"); bigger.unbind();
        ByteBuffer captured = exact.finish().parts().get(0).mesh.vertices();
        for (int i=0;i<32;i++) check(captured.getInt(i*4)==original[i], "every original raw word preserved");
        ChunkMeshBuilder cube = builder(24); bigger.bind(cube,SOLID); bigger.begin(); bigger.cube(0.5f,0.5f,0.5f); bigger.draw(); bigger.unbind();
        check(cube.finish().vertexCount==24, "original six-face cuboid emitter");
        ChunkMeshBuilder fail = builder(0); bigger.bind(fail,SOLID); bigger.begin(); bigger.quad(0,0,1,1,0); rejects(bigger::draw);
        bigger.adapter.mcglAbortChunkBatch(); fail.abort(); rejects(bigger.adapter::mcglAbortChunkBatch);
        ChunkMeshBuilder recovered=builder(3); bigger.bind(recovered,SOLID); bigger.beginMode(4);
        bigger.vertex(0,0,0); bigger.vertex(1,0,0); bigger.vertex(0,1,0); bigger.draw(); bigger.unbind();
        check(recovered.finish().parts().get(0).mesh.indices().count()==3, "abort recovery and original triangles");
        ChunkMeshBuilder slopes=builder(14);bigger.bind(slopes,GLASS);
        bigger.begin();bigger.quad(-1,-1,1,1,0);bigger.draw();
        bigger.beginMode(6);bigger.quad(-1,-1,1,1,0);check(bigger.draw()==128,"original slope triangle fan returns source byte count");
        bigger.begin();bigger.quad(-1,-1,1,1,0);bigger.draw();bigger.unbind();
        ChunkMeshData slopeData=slopes.finish();check(slopeData.vertexCount==14&&slopeData.parts().size()==3,"original quad/fan/quad transition stays in one CPU build");
    }
    private static void sharedTopology() {
        int[] raw=quad(0);for(int v=0;v<4;v++){raw[v*8+3]=Float.floatToRawIntBits(v/4f);raw[v*8+4]=Float.floatToRawIntBits(v/8f);raw[v*8+5]=0x10203040+v;raw[v*8+6]=0x00010203+v;raw[v*8+7]=0x00080010+v;}
        int[] untouched=raw.clone();
        for(int mode:new int[]{5,6}) {
            ChunkMeshBuilder b=builder(6);check(b.sink(GLASS,-1,0,0,0,0).append(raw,32,4,mode,false,true,true,true,true,true)==128,"shared topology preserves accumulator byte count");
            ChunkMeshData mesh=b.finish();ChunkMeshData.Part part=mesh.parts().get(0);int[] source=mode==5?new int[]{0,1,2,2,1,3}:new int[]{0,1,2,0,2,3};
            check(mesh.vertexCount==6&&part.faceWidth==3&&part.mesh.indices().count()==6,"shared topology becomes two independent sortable triangles");
            for(int v=0;v<6;v++)for(int word=0;word<8;word++)check(part.mesh.vertices().getInt((v*8+word)*4)==raw[source[v]*8+word],"shared topology copies every vertex word without changing winding or provoking vertex");
            double[] centers=part.centers();for(int face=0;face<2;face++)for(int axis=0;axis<3;axis++){double expected=0;for(int v=0;v<3;v++)expected+=Float.intBitsToFloat(raw[source[face*3+v]*8+axis])/3d;check(Math.abs(centers[face*3+axis]-expected)<1e-7,"transparent shared topology has per-triangle sort centers");}
            ChunkMeshBuilder bounded=builder(5);rejects(()->bounded.sink(GLASS,-1,0,0,0,0).append(raw,32,4,mode,false,true,true,true,true,true));check(bounded.finish().parts().isEmpty(),"expanded topology budget failure publishes nothing");
        }
        check(Arrays.equals(raw,untouched),"shared topology never modifies the original accumulator");
    }
    private static void registry() throws Exception {
        Backend backend = new Backend(); RenderDevice device = new RenderDevice((p,f)->backend); Object key = new Object();
        RenderContext context = device.attach(key,RenderProfile.CORE_41,true,()->{}); ChunkRenderer renderer = new ChunkRenderer(context);
        ChunkRenderer.Ticket stale=renderer.request(0,0,0), first=renderer.request(0,0,0);
        rejects(()->renderer.publish(first,new ChunkMeshBuilder(16,0,0,0).finish()));
        ChunkRenderer otherRenderer=new ChunkRenderer(context);
        rejects(()->otherRenderer.publish(first,one(0)));otherRenderer.close();
        check(!renderer.publish(stale,one(0)) && backend.created==0, "stale result rejected before allocation");
        check(renderer.publish(first,one(0)) && !renderer.publish(first,one(0)), "publish exactly once");
        ChunkRenderer.Ticket next=renderer.request(0,0,0); check(renderer.residentChunks()==1,"last good remains resident");
        ChunkMeshBuilder multi=builder(8); append(multi,SOLID,quad(0)); append(multi,SOLID,quad(0)); ChunkMeshData replacement=multi.finish();
        backend.failAt=backend.attempts+2;
        rejects(()->renderer.publish(next,replacement)); check(backend.closed==1 && renderer.residentChunks()==1,"partial upload rollback retains last good");
        backend.failAt=-1; check(renderer.publish(next,replacement) && backend.closed==2,"successful replacement retires previous mesh");
        renderer.unload(0,0,0); check(backend.closed==4 && !renderer.publish(next,replacement),"unload deletes meshes/rejects late build");
        ChunkRenderer.Ticket glass=renderer.request(0,0,0); ChunkMeshBuilder transparent=builder(12);
        int[] extremes=new int[64]; System.arraycopy(quad(-0.6f),0,extremes,0,32); System.arraycopy(quad(0.6f),0,extremes,32,32);
        append(transparent,GLASS,extremes); append(transparent,material("other",1),quad(0)); renderer.publish(glass,transparent.finish());
        List<String> calls=new ArrayList<String>(); ChunkRenderer.MaterialBinder binder=new ChunkRenderer.MaterialBinder(){
            public void begin(int pass){calls.clear();} public void bind(ChunkMaterial m,int x,int y,int z){calls.add(m.key);}
            public void end(int pass){}
        };
        ChunkRenderer.DrawStats a=renderer.drawPass(1,ChunkFrustum.ALL,0,0,2,binder);
        check(calls.equals(Arrays.asList("glass","other","glass")) && a.drawCalls==3 && a.indices==18 && a.indexUploads==0,"global cross-material interleaving");
        a=renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder);
        check(a.indexUploads==1 && backend.lastUpdated.bytes().getShort(0)==4,"camera reversal updates EBO order only");
        check(renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder).indexUploads==0,"same camera reuses plan/no uploads");
        check(renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder,Collections.<ChunkRenderer.Ticket>emptyList()).drawCalls==0,"explicit empty selection excludes resident data");
        check(renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder,Collections.singletonList(glass)).drawCalls==3,"selection change invalidates the cached draw plan");
        ChunkRenderer.Ticket currentGlass=renderer.request(0,0,0);renderer.unload(glass);
        check(renderer.residentChunks()==1,"obsolete ticket cannot unload current chunk revision");
        check(renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder,Collections.singletonList(currentGlass)).drawCalls==3,"selected pending revision retains last good geometry");
        ChunkRenderer.MaterialBinder reenter=new ChunkRenderer.MaterialBinder(){public void begin(int p){} public void bind(ChunkMaterial m,int x,int y,int z){renderer.unload(0,0,0);} public void end(int p){calls.add("ended");}};
        rejects(()->renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,reenter)); check(calls.contains("ended"),"binder failure ends scope/reentry rejected");
        foreign(()->rejects(()->renderer.request(16,0,0))); device.detach(); rejects(renderer::close); device.attach(key,RenderProfile.CORE_41,true,()->{});
        check(renderer.residentChunks()==1,"reattach preserves chunks");
        renderer.unload(0,0,0);
        ChunkMeshBuilder edge=builder(8);int[] edgeFaces=new int[64];System.arraycopy(quad(0),0,edgeFaces,0,32);System.arraycopy(quad(15),0,edgeFaces,32,32);
        append(edge,GLASS,edgeFaces);renderer.publish(renderer.request(0,0,0),edge.finish());
        ChunkMeshBuilder neighbor=new ChunkMeshBuilder(0,0,16,4);append(neighbor,material("neighbor",1),quad(0));renderer.publish(renderer.request(0,0,16),neighbor.finish());
        renderer.drawPass(1,ChunkFrustum.ALL,0,0,8,binder);
        check(calls.equals(Arrays.asList("glass","neighbor","glass")),"global sorting across real adjacent chunk origins and stable equal-distance tie");
        renderer.publish(renderer.request(0,0,16),new ChunkMeshBuilder(0,0,16,0).finish());
        check(renderer.residentChunks()==2&&renderer.drawPass(1,ChunkFrustum.ALL,0,0,8,binder).drawCalls==1,"empty rebuild replaces visible geometry without allocating buffers");
        renderer.close(); renderer.close(); check(backend.closed==backend.created,"all GPU handles released exactly once");
        rejects(()->renderer.request(0,0,0));
        ChunkRenderer abandoned=new ChunkRenderer(context); abandoned.publish(abandoned.request(0,0,0),one(0)); device.destroy(key);
        check(abandoned.isClosed(),"context destruction invalidates renderer"); abandoned.close();
    }
    private static void batchTransforms() {
        ChunkMeshBuilder builder=builder(4);int[] raw=quad(0);builder.sink(GLASS,-1,0,0,0,0).append(raw,32,4,7,false,true,true,true,true,true);
        ChunkMeshData original=builder.finish();Map<ChunkMaterial,float[]> transforms=new IdentityHashMap<ChunkMaterial,float[]>();float[] transform={16,32,48,1.000001f,1024,-2048,1<<24};transforms.put(GLASS,transform);
        ChunkMeshData batch=GameChunkGeometry.withTransforms(original,transforms);transform[0]=100;
        check(original.parts().get(0).mesh.layout().stride()==32&&batch.parts().get(0).mesh.layout().stride()==60,"batch metadata leaves original snapshot unchanged");
        ByteBuffer bytes=batch.parts().get(0).mesh.vertices();for(int v=0;v<4;v++){for(int word=0;word<8;word++)check(bytes.getInt(v*60+word*4)==raw[v*8+word],"batch retains every original vertex input");check(bytes.getFloat(v*60+32)==16&&bytes.getFloat(v*60+44)==1.000001f,"batch transform is snapshotted per vertex");check(bytes.getFloat(v*60+48)==1024&&bytes.getFloat(v*60+52)==-2048&&bytes.getFloat(v*60+56)==1<<24,"high coordinate region stays separate from low vertex translation");}
        check(Arrays.equals(original.parts().get(0).centers(),batch.parts().get(0).centers()),"batch augmentation preserves global sort centers");
        float[] tableTransform={16,32,48,1,1024,-2048,1<<24,(31<<4)|8};transforms.put(GLASS,tableTransform);
        ChunkMeshData table=GameChunkGeometry.withTransforms(original,transforms);tableTransform[7]=0;
        check(table.parts().get(0).mesh.layout().stride()==64,"texture slot and source attribute mask occupy one bounded input");
        for(int v=0;v<4;v++){check(table.parts().get(0).mesh.vertices().getFloat(v*64+60)==504,"texture/mask metadata is snapshotted per vertex");for(int word=0;word<8;word++)check(table.parts().get(0).mesh.vertices().getInt(v*64+word*4)==raw[v*8+word],"texture augmentation preserves raw vertex bytes");}
        rejects(()->GameChunkGeometry.withTransforms(original,transforms));tableTransform[7]=31*16+9;rejects(()->GameChunkGeometry.withTransforms(original,transforms));
    }
    private static void batchRegistry() {
        Backend backend=new Backend();RenderDevice device=new RenderDevice((p,f)->backend);Object contextKey=new Object();RenderContext context=device.attach(contextKey,RenderProfile.CORE_41,true,()->{});ChunkRenderer renderer=new ChunkRenderer(context);
        ChunkMeshBuilder a=builder(8);append(a,GLASS,quad(-.6f));append(a,GLASS,quad(.6f));renderer.publish(renderer.request(0,0,0),a.finish());
        ChunkMeshBuilder b=new ChunkMeshBuilder(0,0,16,4);append(b,GLASS,quad(-16));ChunkRenderer.Ticket second=renderer.request(0,0,16);renderer.publish(second,b.finish());
        ChunkRenderer.MaterialBinder binder=new ChunkRenderer.MaterialBinder(){public void begin(int p){}public void end(int p){}public void bind(ChunkMaterial m,int x,int y,int z){}public boolean batching(){return true;}public Object batchKey(ChunkMaterial m,int x,int y,int z){return "same";}public void bindBatch(ChunkMaterial m,int x,int y,int z){}};
        backend.failAt=backend.attempts+1;rejects(()->renderer.drawPass(1,ChunkFrustum.ALL,0,0,2,binder));check(renderer.residentChunks()==2&&backend.created==3,"combined allocation failure keeps all published source meshes");backend.failAt=-1;
        ChunkRenderer.DrawStats stats=renderer.drawPass(1,ChunkFrustum.ALL,0,0,2,binder);
        check(stats.drawCalls==1&&stats.indices==18&&backend.combined==1,"globally interleaved compatible parts become one draw");
        ShortBuffer order=backend.lastCombined.bytes().asShortBuffer();check(order.get(0)==0&&order.get(6)==8&&order.get(12)==4,"combined indices retain cross-chunk global face order and vertex offsets");
        stats=renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder);check(stats.drawCalls==1&&stats.indexUploads==1&&backend.combined==1,"camera reversal updates only the combined index buffer");
        check(renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder).indexUploads==0&&backend.combined==1,"stationary combined plan allocates and uploads nothing");
        stats=renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder,Collections.singletonList(second));check(stats.drawCalls==1&&stats.indices==6&&backend.closed==0&&backend.combined==1,"visibility reduction reuses resident combined vertices and draws only the selected indices");
        ChunkRenderer.MaterialBinder reenter=new ChunkRenderer.MaterialBinder(){public void begin(int p){}public void end(int p){}public void bind(ChunkMaterial m,int x,int y,int z){}public boolean batching(){return true;}public Object batchKey(ChunkMaterial m,int x,int y,int z){renderer.unload(0,0,0);return "bad";}};
        rejects(()->renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,reenter));check(renderer.residentChunks()==2,"batch-policy changes invalidate plans and callback re-entry is rejected");
        renderer.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder);renderer.close();check(backend.closed==backend.created,"combined meshes and sources retire exactly once");device.destroy(contextKey);
    }
    private static void boundedBatches() {
        Backend backend=new Backend();RenderDevice device=new RenderDevice((p,f)->backend);Object key=new Object();RenderContext context=device.attach(key,RenderProfile.CORE_41,true,()->{});ChunkRenderer renderer=new ChunkRenderer(context,8);
        for(int i=0;i<6;i++){ChunkMeshBuilder build=new ChunkMeshBuilder(i*16,0,0,8);append(build,SOLID,quad(i*.1f));append(build,GLASS,quad(i*.1f));renderer.publish(renderer.request(i*16,0,0),build.finish());}
        ChunkRenderer.MaterialBinder binder=new ChunkRenderer.MaterialBinder(){public void begin(int p){}public void end(int p){}public void bind(ChunkMaterial m,int x,int y,int z){}public boolean batching(){return true;}public Object batchKey(ChunkMaterial m,int x,int y,int z){return "same";}public void bindBatch(ChunkMaterial m,int x,int y,int z){}};
        ChunkRenderer.DrawStats opaque=renderer.drawPass(0,ChunkFrustum.ALL,0,0,2,binder);
        check(opaque.drawCalls==3&&opaque.indices==36&&backend.combined==3,"large opaque material splits into bounded combined meshes instead of falling back as a whole");
        ShortBuffer indices=backend.lastCombined.bytes().asShortBuffer();check(indices.get(0)==0&&indices.get(6)==4,"opaque combined indices do not depend on absent transparency centers");
        ChunkRenderer.DrawStats alpha=renderer.drawPass(1,ChunkFrustum.ALL,-10,0,2,binder);
        check(alpha.drawCalls==3&&alpha.indices==36&&backend.combined==6,"transparent groups retain global sequence across memory-budget partitions");
        renderer.close();check(backend.closed==backend.created,"bounded partitions and originals close exactly once");device.destroy(key);
    }
    private static void visibilityBatches() {
        Backend backend=new Backend();RenderDevice device=new RenderDevice((p,f)->backend);Object key=new Object();RenderContext context=device.attach(key,RenderProfile.CORE_41,true,()->{});ChunkRenderer renderer=new ChunkRenderer(context);
        List<ChunkRenderer.Ticket> tickets=new ArrayList<ChunkRenderer.Ticket>();
        for(int i=0;i<12;i++){ChunkMeshBuilder build=new ChunkMeshBuilder(i*16,0,0,8);append(build,SOLID,quad(0));append(build,GLASS,quad(0));ChunkRenderer.Ticket ticket=renderer.request(i*16,0,0);tickets.add(ticket);renderer.publish(ticket,build.finish());}
        ChunkRenderer.MaterialBinder binder=new ChunkRenderer.MaterialBinder(){public void begin(int p){}public void end(int p){}public void bind(ChunkMaterial m,int x,int y,int z){}public boolean batching(){return true;}public Object batchKey(ChunkMaterial m,int x,int y,int z){return "same";}public void bindBatch(ChunkMaterial m,int x,int y,int z){}};
        for(int pass=0;pass<2;pass++) {
            int before=backend.combined;
            long invisibleSorts=renderer.faceSortCount();
            check(renderer.drawPass(pass,ChunkFrustum.ALL,0,0,2,binder,Collections.<ChunkRenderer.Ticket>emptyList()).drawCalls==0&&backend.combined==before,"never-visible groups do not allocate combined buffers");
            check(renderer.faceSortCount()==invisibleSorts,"empty visibility does not sort hidden resident faces");
            renderer.drawPass(pass,ChunkFrustum.ALL,0,0,2,binder,tickets);
            check(backend.combined==before+1,"first visible plan uploads one resident group");
            long sorts=renderer.faceSortCount();
            for(int turn=0;turn<12;turn++) {
                List<ChunkRenderer.Ticket> selected=tickets.subList(turn%2==0?0:6,turn%2==0?6:12);
                ChunkRenderer.DrawStats stats=renderer.drawPass(pass,ChunkFrustum.ALL,0,0,2,binder,selected);
                check(stats.drawCalls==1&&stats.indices==36&&backend.combined==before+1,"camera visibility changes neither repack VBOs nor draw invisible parts");
                check(renderer.faceSortCount()==sorts,"turning in place does not repeat distance sorting");
            }
            check(renderer.drawPass(pass,ChunkFrustum.ALL,0,0,2,binder,Collections.<ChunkRenderer.Ticket>emptyList()).drawCalls==0&&backend.combined==before+1,"looking away retains still-resident GPU groups");
            renderer.drawPass(pass,ChunkFrustum.ALL,0,0,2,binder,tickets);
            check(backend.combined==before+1,"looking back reuses the same GPU group");
            renderer.drawPass(pass,ChunkFrustum.ALL,1,0,2,binder,tickets);
            check(renderer.faceSortCount()==sorts+(pass==0?0:1),"actual camera translation invalidates transparent distance order only");
        }
        int before=backend.combined;renderer.unload(tickets.get(0));
        check(renderer.drawPass(0,ChunkFrustum.ALL,0,0,2,binder,tickets).indices==66&&backend.combined==before+1,"unload invalidates old group membership and excludes retired vertices");
        renderer.close();check(backend.closed==backend.created,"visibility cache and all source meshes close exactly once");device.destroy(key);
    }
    private static void visibilityIndexType() {
        Backend backend=new Backend();RenderDevice device=new RenderDevice((p,f)->backend);Object key=new Object();RenderContext context=device.attach(key,RenderProfile.CORE_41,true,()->{});ChunkRenderer renderer=new ChunkRenderer(context);
        ChunkMeshBuilder small=builder(4);append(small,GLASS,quad(0));ChunkRenderer.Ticket first=renderer.request(0,0,0);renderer.publish(first,small.finish());
        ChunkMeshBuilder large=new ChunkMeshBuilder(16,0,0,65536);append(large,GLASS,new int[65536*8]);ChunkRenderer.Ticket second=renderer.request(16,0,0);renderer.publish(second,large.finish());
        ChunkRenderer.MaterialBinder binder=new ChunkRenderer.MaterialBinder(){public void begin(int p){}public void end(int p){}public void bind(ChunkMaterial m,int x,int y,int z){}public boolean batching(){return true;}public Object batchKey(ChunkMaterial m,int x,int y,int z){return "same";}public void bindBatch(ChunkMaterial m,int x,int y,int z){}};
        renderer.drawPass(1,ChunkFrustum.ALL,0,0,2,binder);
        check(backend.lastCombined.type()==IndexData.Type.UINT32,"combined resident vertex capacity promotes indices");
        ChunkRenderer.DrawStats stats=renderer.drawPass(1,ChunkFrustum.ALL,0,0,2,binder,Collections.singletonList(first));
        check(stats.indices==6&&stats.drawCalls==1&&backend.combined==1&&backend.lastUpdated.type()==IndexData.Type.UINT32,"small visible prefix preserves UINT32 allocation without drawing padded indices");
        check(backend.lastUpdated.count()==98310&&backend.lastUpdated.maximum()==65539,"hidden tail keeps fixed allocation size and a valid maximum index");
        renderer.close();check(backend.closed==backend.created,"large visibility buffers close exactly once");device.destroy(key);
    }
    private static void independentPassRebuilds(){
        Backend backend=new Backend();RenderDevice device=new RenderDevice((p,f)->backend);Object key=new Object();RenderContext context=device.attach(key,RenderProfile.CORE_41,true,()->{});ChunkRenderer renderer=new ChunkRenderer(context);
        ChunkMeshBuilder glass=builder(4);append(glass,GLASS,quad(0));ChunkRenderer.Ticket transparent=renderer.request(0,0,0);renderer.publish(transparent,glass.finish());
        ChunkMeshBuilder solid=new ChunkMeshBuilder(16,0,0,4);append(solid,SOLID,quad(0));renderer.publish(renderer.request(16,0,0),solid.finish());
        ChunkRenderer.MaterialBinder binder=new ChunkRenderer.MaterialBinder(){public void begin(int p){}public void end(int p){}public void bind(ChunkMaterial m,int x,int y,int z){}};
        renderer.drawPass(1,ChunkFrustum.ALL,0,0,2,binder);long sorts=renderer.faceSortCount();
        for(int i=0;i<3;i++){ChunkMeshBuilder changed=new ChunkMeshBuilder(16,0,0,4);append(changed,SOLID,quad(i*.1f));renderer.publish(renderer.request(16,0,0),changed.finish());
            check(renderer.drawPass(1,ChunkFrustum.ALL,0,0,2,binder).indices==6&&renderer.faceSortCount()==sorts,"solid-only rebuild retains transparent plan and order");}
        renderer.unload(16,0,0);renderer.drawPass(1,ChunkFrustum.ALL,0,0,2,binder);check(renderer.faceSortCount()==sorts,"solid-only unload retains transparent plan");
        ChunkMeshBuilder mixed=builder(8);append(mixed,SOLID,quad(0));append(mixed,GLASS,quad(.4f));renderer.publish(renderer.request(0,0,0),mixed.finish());
        check(renderer.drawPass(1,ChunkFrustum.ALL,0,0,2,binder).indices==6&&renderer.faceSortCount()==sorts+1,"changed transparent geometry invalidates its pass");
        renderer.publish(renderer.request(0,0,0),one(0));check(renderer.drawPass(1,ChunkFrustum.ALL,0,0,2,binder).indices==0,"transparent-to-solid transition retires transparent plan");
        renderer.close();check(backend.closed==backend.created,"independent pass plans release all resources");device.destroy(key);
    }
    private static final class Backend implements RenderBackend {
        int created,closed,attempts,failAt=-1,combined; IndexData lastUpdated,lastCombined;
        public void attach(){} public void detach(){} public void close(){} public boolean insideBeginEnd(){return false;}
        public LegacyRenderCommands commands(){return null;}
        public RenderCapabilities capabilities(){return new RenderCapabilities("test","test","4.1",Collections.singleton("OpenGL41"),RenderProfile.CORE_41,4,1,"4.10");}
        public FrameCommands frameCommands(){return new FrameCommands(){public void viewport(int x,int y,int w,int h){} public void clearColor(float r,float g,float b,float a){} public void clear(boolean c,boolean d,boolean s){}};}
        public ShaderPipeline shaders(){return new ShaderPipeline(){public void unbind(){} public ShaderProgram create(ShaderSources s){throw new AssertionError("shader not needed by CPU registry");}};}
        public MeshPipeline meshes(){return new MeshPipeline(){public void unbind(){} public Mesh create(String label,MeshData data,Usage usage){
            if(++attempts==failAt)throw new IllegalStateException("injected allocation failure"); created++;
            return new Mesh(){boolean dead; public String label(){return label;} public boolean isClosed(){return dead;}
                public VertexLayout layout(){return data.layout();} public int vertexCount(){return data.vertexCount();} public int indexCount(){return data.indices().count();}
                public IndexData.Type indexType(){return data.indices().type();} public Usage usage(){return usage;}
                public void draw(Primitive p,int first,int count){} public void updateVertices(int first,ByteBuffer b){} public void updateIndices(IndexData indices){lastUpdated=indices;}
                public void close(){if(!dead){closed++;dead=true;}}
            };
        }
        public Mesh combine(String label,List<Mesh> sources,IndexData indices){VertexLayout layout=sources.get(0).layout();int vertices=0;for(Mesh mesh:sources)vertices+=mesh.vertexCount();Mesh mesh=create(label,new MeshData(layout,ByteBuffer.allocateDirect(vertices*layout.stride()).order(ByteOrder.nativeOrder()),indices),Usage.DYNAMIC);combined++;lastCombined=indices;return mesh;}
        };}
    }
    private static void foreign(Runnable action)throws Exception{AtomicReference<Throwable> failure=new AtomicReference<Throwable>();Thread t=new Thread(()->{try{action.run();}catch(Throwable p){failure.set(p);}});t.start();t.join(2500);check(!t.isAlive()&&failure.get()==null,"foreign thread checks: "+failure.get());}
    private static void rejects(Runnable action){try{action.run();throw new AssertionError("invalid operation accepted");}catch(IllegalStateException|IllegalArgumentException expected){checks++;}}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
