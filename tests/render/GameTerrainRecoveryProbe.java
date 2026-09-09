package local.mcgl.render.tests;

import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** A tiny injected arena forces actual immutable-mesh overflow and recovery.
 * The production queue, transfer cap, native import and renderer are unchanged. */
public final class GameTerrainRecoveryProbe {
    private static int checks;
    public static int run(GameRenderCommands g)throws Exception {
        checks=0;Object renderer=field(g,"target");Field batchField=find(renderer.getClass(),"terrainBatch");
        Object savedBatch=batchField.get(renderer);
        Class<?> type=Class.forName("local.mcgl.render.backend.GameTerrainBatch");
        Constructor<?> constructor=type.getDeclaredConstructor(Class.forName("local.mcgl.render.backend.GameRenderer"),RenderContext.class);constructor.setAccessible(true);
        Object batch=constructor.newInstance(renderer,RenderSystem.current());
        MeshArena arena=RenderSystem.current().meshes().createArena("recovery/limited",128,192,6400);
        find(type,"arena").set(batch,arena);batchField.set(renderer,batch);
        int first=g.glGenLists(32*3),models=g.cachedModels();
        int framebuffer=GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING),renderbuffer=GL11C.glGetInteger(GL30C.GL_RENDERBUFFER_BINDING);
        int target=GL30C.glGenFramebuffers(),color=GL30C.glGenRenderbuffers(),depth=GL30C.glGenRenderbuffers();
        g.glPushAttrib(-1);g.glMatrixMode(5889);g.glPushMatrix();g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-10,10);g.glMatrixMode(5888);g.glPushMatrix();g.glLoadIdentity();
        try {
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,target);g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,color);
            g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL11C.GL_RGBA8,64,64);
            g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL30C.GL_RENDERBUFFER,color);
            g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,depth);g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL30C.GL_DEPTH24_STENCIL8,64,64);
            g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_DEPTH_STENCIL_ATTACHMENT,GL30C.GL_RENDERBUFFER,depth);
            check(g.GL30_glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)==GL30C.GL_FRAMEBUFFER_COMPLETE,"recovery RGBA/depth target");
            g.glViewport(0,0,64,64);g.glDisable(3553);g.glDisable(2896);g.glDisable(2912);g.glDisable(3008);g.glDisable(GL11C.GL_CULL_FACE);g.glDisable(GL11C.GL_DITHER);
            g.glEnable(GL11C.GL_DEPTH_TEST);g.glDepthFunc(GL11C.GL_LEQUAL);g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(770,771);g.glShadeModel(7425);
            build(g,first,32,0);
            check(arena.residentMembers()==32&&arena.residentBytes()==6400,"prefix fills exact bounded arena");
            IntBuffer selected=ByteBuffer.allocateDirect(20*4).order(ByteOrder.nativeOrder()).asIntBuffer();
            List<Mesh> sources=new ArrayList<Mesh>();
            for(int i=0;i<20;i++){int handle=first+(i+1)*3;build(g,handle,1,i+1);selected.put(handle);sources.add(mesh(renderer,handle));}
            selected.flip();
            check(g.terrainPendingRecoveries()==20&&g.terrainRecoveries()==0,"overflow is registered without retaining CPU geometry");
            for(Mesh source:sources)check(source.layout().stride()==40&&!source.isClosed(),"standalone source is immutable untagged shared storage format");
            int extra=first+21*3;build(g,extra,1,1);check(g.terrainPendingRecoveries()==21,"unloaded overflow is initially queued");
            g.glDeleteLists(extra,3);check(g.terrainPendingRecoveries()==20,"unloading removes queued overflow immediately");
            g.beginOriginalChunk(extra,3);g.glNewList(extra,4864);raw(g,2);raw(g,3);g.glEndList();
            check(g.terrainPendingRecoveries()==22,"staged original transaction may temporarily own overflow");
            g.abortOriginalChunk();check(g.terrainPendingRecoveries()==20&&arena.residentMembers()==32,"aborted transaction removes only staged recovery entries");
            byte[] reference=draw(g,selected);
            check(nonempty(reference),"reference contains visible colored geometry and depth");
            for(int i=0;i<3;i++){RenderSystem.completeFrame(64,64,false);check(Arrays.equals(reference,draw(g,selected)),"full-arena retry preserves exact frame");}
            check(g.terrainRecoveries()==0&&g.terrainRecoveryBytes()==0&&g.terrainPendingRecoveries()==20,"no source transfers while capacity is unavailable");
            g.glDeleteLists(first,3);
            check(arena.residentMembers()==0&&arena.residentBytes()==0&&g.terrainPendingRecoveries()==20,"unload releases GPU capacity without rebuilding selected models");
            for(int frame=0;frame<3;frame++){
                long previous=g.terrainRecoveries(),bytes=g.terrainRecoveryBytes();RenderSystem.completeFrame(64,64,false);
                check(Arrays.equals(reference,draw(g,selected)),"bounded recovery preserves full RGBA/depth and ordered transparency");
                check(g.terrainRecoveries()-previous==Math.min(8,20-frame*8),"at most eight immutable sources migrate per frame");
                check(g.terrainRecoveryBytes()-bytes<=4L*1024*1024,"explicit per-frame source transfer cap");
                check(arena.residentBytes()<=6400,"destination never exceeds arena budget");
                long sameFrame=g.terrainRecoveries();check(Arrays.equals(reference,draw(g,selected)),"same-frame second pass retains pixels");
                check(g.terrainRecoveries()==sameFrame,"additional passes never multiply the recovery budget");
            }
            check(g.terrainPendingRecoveries()==0&&g.terrainRecoveries()==20&&arena.residentMembers()==20,"all queued small meshes recover automatically");
            check(g.terrainRecoveryBytes()==20L*(4*40+6*2),"source bytes counted once, including original UINT16 indices");
            for(int i=0;i<20;i++)check(sources.get(i).isClosed()&&mesh(renderer,first+(i+1)*3)!=sources.get(i),"successful import atomically replaces and retires only its standalone source");
            check(g.cachedModels()==models+20,"recovery does not replace original model commands or rebuild the world");
            long bytes=g.terrainRecoveryBytes(),creations=arena.pageCreations();
            for(int i=0;i<4;i++){RenderSystem.completeFrame(64,64,false);check(Arrays.equals(reference,draw(g,selected)),"settled scene retains exact frame");}
            check(bytes==g.terrainRecoveryBytes()&&creations==arena.pageCreations(),"steady draw/visibility replay performs no recovery readback or page rebuild");
            check(g.glGetError()==0,"recovery remains Core-valid");
        } finally {
            g.abortOriginalChunk();g.glDeleteLists(first,32*3);check(arena.residentBytes()==0&&arena.residentMembers()==0,"recovery cleanup retires all pages and members");
            arena.close();batchField.set(renderer,savedBatch);
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,framebuffer);g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,renderbuffer);
            GL30C.glDeleteFramebuffers(target);GL30C.glDeleteRenderbuffers(color);GL30C.glDeleteRenderbuffers(depth);
            g.glMatrixMode(5888);g.glPopMatrix();g.glMatrixMode(5889);g.glPopMatrix();g.glMatrixMode(5888);g.glPopAttrib();
        }
        check(g.cachedModels()==models,"fixture preserves pre-existing model registry");
        System.out.println("GAME_TERRAIN_RECOVERY_GPU_PASS checks="+checks+" exact-RGBA/depth/overflow/transfer-cap/abort/retirement");return checks;
    }
    private static void build(GameRenderCommands g,int handle,int parts,int variant){
        g.beginOriginalChunk(handle,3);g.glNewList(handle,4864);g.glPushMatrix();g.glTranslatef((variant%5)*.15f-.3f,(variant%4)*.13f-.2f,variant*.015f);
        g.glDepthMask((variant&1)==0);for(int i=0;i<parts;i++)raw(g,variant);g.glPopMatrix();g.glEndList();g.finishOriginalChunk();
    }
    private static void raw(GameRenderCommands g,int variant){
        int[] words=new int[32];float[] xy={-.5f,-.5f,.5f,-.5f,.5f,.5f,-.5f,.5f};
        for(int v=0;v<4;v++){words[v*8]=Float.floatToRawIntBits(xy[v*2]);words[v*8+1]=Float.floatToRawIntBits(xy[v*2+1]);words[v*8+5]=(128<<24)|((variant%3)*90<<16)|(120<<8)|180;}
        g.raw(words,32,4,7,false,true,true,false,false,false);
    }
    private static byte[] draw(GameRenderCommands g,IntBuffer names){
        g.glDepthMask(true);g.glClearColor(0,0,0,1);g.glClearDepth(1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT);g.glCallLists(names);
        ByteBuffer rgba=ByteBuffer.allocateDirect(64*64*4).order(ByteOrder.nativeOrder()),depth=ByteBuffer.allocateDirect(64*64*4).order(ByteOrder.nativeOrder());
        GL11C.glReadPixels(0,0,64,64,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,rgba);GL11C.glReadPixels(0,0,64,64,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT,depth);
        byte[] all=new byte[rgba.remaining()+depth.remaining()];rgba.get(all,0,rgba.remaining());depth.get(all,64*64*4,depth.remaining());return all;
    }
    private static boolean nonempty(byte[] image){for(int i=0;i<64*64*4;i+=4)if((image[i]&255)>20&&(image[i+1]&255)>20)return true;return false;}
    private static Field find(Class<?> type,String name)throws Exception {for(;type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException missing){}throw new NoSuchFieldException(name);}
    private static Object field(Object value,String name)throws Exception{return find(value.getClass(),name).get(value);}
    private static Mesh mesh(Object renderer,int handle)throws Exception{Object model=((Map<?,?>)field(renderer,"models")).get(handle);for(Object operation:(List<?>)field(model,"operations"))if(operation.getClass().getSimpleName().equals("Draw"))return (Mesh)field(operation,"mesh");throw new AssertionError("model mesh missing");}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}

