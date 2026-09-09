package local.mcgl.render.tests;

import java.io.File;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Account-free GPU check of original per-chunk model publication, ordering and resort snapshots. */
public final class OriginalChunkCacheProbe {
    private static int checks;
    public static int run(GameRenderCommands g)throws Exception {
        checks=0;int handles=g.glGenLists(12),cached=g.cachedModels(),resident=g.residentChunks();
        g.glPushAttrib(-1);g.glMatrixMode(5889);g.glPushMatrix();g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-10,10);
        g.glMatrixMode(5888);g.glPushMatrix();g.glLoadIdentity();
        try {
            g.glActiveTexture(33984);g.glDisable(3553);g.glDisable(2896);g.glDisable(2912);g.glDisable(3008);
            g.glDisable(GL11C.GL_DEPTH_TEST);g.glDisable(GL11C.GL_CULL_FACE);g.glDisable(GL11C.GL_BLEND);g.glShadeModel(7424);
            OriginalChunkEmitter t=new OriginalChunkEmitter(new File("bin/mcgl.jar"),1024);
            g.beginOriginalChunk(handles,3);
            quad(g,t,handles,255,0,0,255,0);quad(g,t,handles+1,0,0,255,128,.4);quad(g,t,handles+2,0,255,0,255,0);
            check(g.cachedModels()==cached&&g.residentChunks()==resident,"unpublished passes stay outside live registry");
            g.finishOriginalChunk();check(g.cachedModels()==cached+3&&g.residentChunks()==resident+1,"one original chunk owns three independent Core passes");
            List<Mesh> meshes=meshes(g,handles,3);check(meshes.size()==3,"one indexed mesh per original drained batch");
            for(Mesh mesh:meshes)check(mesh.layout().stride()==44&&mesh.indexCount()==6&&(mesh.layout().attributeMask()&(1<<14))!=0,"original quad words and flat inputs plus one immutable arena tag; no CPU-transformed positions");
            long uploads=g.chunkIndexUploads(),calls=g.chunkDrawCalls(),ordinary=g.drawCalls();
            clear(g);g.glCallList(handles);pixel(255,0,0,"original solid pass");
            check(g.chunkDrawCalls()==calls+1&&g.drawCalls()==ordinary,"original terrain submissions remain separate from other geometry counters");
            for(int i=0;i<12;i++){g.glPushMatrix();g.glTranslated((i&1)*.02,0,0);g.glCallList(handles+(i%3));g.glPopMatrix();}
            check(meshes.equals(meshes(g,handles,3))&&g.chunkIndexUploads()==uploads,"camera/selection changes reuse exact mesh objects without EBO updates");

            g.beginOriginalChunk(handles,3);quad(g,t,handles,255,255,0,255,0);
            clear(g);g.glCallList(handles);pixel(255,0,0,"old pass remains visible before publication");
            List<Mesh> pending=pendingMeshes(g);g.glNewList(handles+1,4864);t.begin();t.color(255,0,255,255);t.quad(-.8,-.8,.8,.8,0);t.draw();
            rejects(g::finishOriginalChunk);g.abortOriginalChunk();
            check(pending.get(0).isClosed()&&!meshes.get(0).isClosed(),"failed multi-pass replacement retires only staged meshes");
            clear(g);g.glCallList(handles);pixel(255,0,0,"aborted chunk retains complete previous GPU data");
            g.beginOriginalChunk(handles,3);quad(g,t,handles+1,255,255,0,255,0);g.finishOriginalChunk();
            check(meshes(g,handles,1).get(0)==meshes.get(0)&&meshes.get(1).isClosed()&&!meshes.get(2).isClosed(),"resort publication replaces only its original pass");
            clear(g);g.glCallList(handles+1);pixel(255,255,0,"resorted pass visible");

            g.beginOriginalChunk(handles,3);rejects(()->g.beginOriginalChunk(handles,3));rejects(()->g.beginChunk(handles,0,0,0,3));
            rejects(()->g.glNewList(handles+3,4864));rejects(()->g.glDeleteLists(handles,3));
            g.glColor4f(.1f,.2f,.3f,.4f);g.glPushMatrix();g.glTranslatef(8,0,0);g.abortOriginalChunk();
            FloatBuffer matrix=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();g.glGetFloat(2982,matrix);check(matrix.get(12)==0,"abort restores unbalanced temporary matrix changes");
            rejects(()->g.beginOriginalChunk(0,3));rejects(()->g.beginOriginalChunk(handles,4));rejects(g::finishOriginalChunk);
            g.abortOriginalChunk(); // Idempotent cleanup after a rejected begin.
            g.glDeleteLists(handles,3);check(g.cachedModels()==cached&&g.residentChunks()==resident,"original chunk disposal unloads every pass");

            // Use the actual original CPU sorter/snapshot, then replay its retained Core model.
            g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(GL11C.GL_SRC_ALPHA,GL11C.GL_ONE_MINUS_SRC_ALPHA);g.glShadeModel(7425);
            g.beginOriginalChunk(handles+3,3);g.glNewList(handles+4,4864);
            t.begin();t.color(255,0,0,128);t.quad(-.8,-.8,.8,.8,-.5);t.color(0,0,255,128);t.quad(-.8,-.8,.8,.8,.5);
            t.sort(0,0,2);int[] snapshot=t.snapshot();t.draw();g.glEndList();g.finishOriginalChunk();
            clear(g);g.glCallList(handles+4);byte[] forward=pixels();pixel(64,0,128,"original local transparency sort");
            g.beginOriginalChunk(handles+3,3);t.restore(snapshot);t.sort(0,0,-2);g.glNewList(handles+4,4864);t.draw();g.glEndList();g.finishOriginalChunk();
            clear(g);g.glCallList(handles+4);pixel(128,0,64,"original retained snapshot resorts only requested chunk");
            g.beginOriginalChunk(handles+3,3);t.restore(snapshot);t.sort(0,0,2);g.glNewList(handles+4,4864);t.draw();g.glEndList();g.finishOriginalChunk();
            clear(g);g.glCallList(handles+4);check(Arrays.equals(forward,pixels()),"repeated original resort restores exact RGBA frame");
            check(g.chunkIndexUploads()==uploads,"original path uses original rebuild schedule, not global EBO sorting");

            g.beginOriginalChunk(handles+6,3);quad(g,t,handles+7,0,255,0,128,0);g.finishOriginalChunk();
            IntBuffer sequence=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asIntBuffer();sequence.put(new int[]{0,handles+4,handles+7,0});sequence.position(1);sequence.limit(3);
            clear(g);g.glCallList(handles+4);g.glCallList(handles+7);byte[] expected=pixels();
            clear(g);g.glCallLists(sequence);check(sequence.position()==1&&sequence.limit()==3&&Arrays.equals(expected,pixels()),"original selected-list ordering and caller buffer cursor retained");
            clear(g);g.glCallList(handles+7);g.glCallList(handles+4);check(!Arrays.equals(expected,pixels()),"control preserves chunk order instead of silently restoring global face order");
            // Original slope accumulator transitions remain valid in the model-based path.
            g.glDisable(GL11C.GL_BLEND);g.beginOriginalChunk(handles+9,3);g.glNewList(handles+9,4864);
            t.begin();t.draw();t.beginMode(6);t.color(40,120,200,255);t.quad(-.8,-.8,.8,.8,0);t.draw();t.begin();t.draw();g.glEndList();g.finishOriginalChunk();
            clear(g);g.glCallList(handles+9);pixel(40,120,200,"original quad/fan/quad transition reaches indexed Core mesh");
            check(meshes(g,handles+9,1).get(0).layout().stride()==44,"fan retains original 32-byte records, disabled flat inputs and one arena tag in the shared quad format");
            check(g.glGetError()==0,"original chunk cache is Core-valid");
        }finally {
            g.abortOriginalChunk();g.glDeleteLists(handles,12);
            g.glMatrixMode(5888);g.glPopMatrix();g.glMatrixMode(5889);g.glPopMatrix();g.glMatrixMode(5888);g.glPopAttrib();
        }
        check(g.cachedModels()==cached&&g.residentChunks()==resident,"control fixture leaves no resident models");
        System.out.println("ORIGINAL_CHUNK_CACHE_GPU_PASS checks="+checks);return checks;
    }
    private static void quad(GameRenderCommands g,OriginalChunkEmitter t,int handle,int r,int green,int b,int a,double z){g.glNewList(handle,4864);t.begin();t.color(r,green,b,a);t.quad(-.8,-.8,.8,.8,z);t.draw();g.glEndList();}
    private static Object field(Object object,String name)throws Exception{Class<?> type=object.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException ignored){type=type.getSuperclass();}}throw new NoSuchFieldException(name);}
    private static List<Mesh> meshes(GameRenderCommands g,int first,int count)throws Exception{Map<?,?> models=(Map<?,?>)field(field(g,"target"),"models");List<Mesh> result=new ArrayList<Mesh>();for(int i=0;i<count;i++){Object model=models.get(first+i);if(model!=null)addMeshes(result,model);}return result;}
    private static List<Mesh> pendingMeshes(GameRenderCommands g)throws Exception{Map<?,?> models=(Map<?,?>)field(field(field(g,"target"),"originalChunk"),"pending");List<Mesh> result=new ArrayList<Mesh>();for(Object model:models.values())addMeshes(result,model);return result;}
    private static void addMeshes(List<Mesh> result,Object model)throws Exception{for(Object op:(List<?>)field(model,"operations"))if(op.getClass().getSimpleName().equals("Draw"))result.add((Mesh)field(op,"mesh"));}
    private static void clear(GameRenderCommands g){g.glClearColor(0,0,0,1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT);}
    private static byte[] pixels(){ByteBuffer buffer=ByteBuffer.allocateDirect(64*64*4);GL11C.glReadPixels(0,0,64,64,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,buffer);byte[] result=new byte[buffer.capacity()];buffer.get(result);return result;}
    private static void pixel(int r,int g,int b,String message){byte[] image=pixels();int at=(32*64+32)*4;check(Math.abs((image[at]&255)-r)<=1&&Math.abs((image[at+1]&255)-g)<=1&&Math.abs((image[at+2]&255)-b)<=1,message);}
    private static void rejects(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalStateException|IllegalArgumentException expected){rejected=true;}check(rejected,"invalid original-cache operation rejected");}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
