package local.mcgl.render.tests;

import java.nio.*;
import java.util.*;
import java.lang.reflect.Method;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Same selected geometry and ordered submissions on 184/185 and the cache fix.
 * These are synthetic wall costs, not game FPS. No account/client/world is loaded. */
public final class TerrainCachePressureProbe {
    private static final int PREFIX=512,SELECTED=256,PARTS=8;
    private static long expectedPixels=Long.MIN_VALUE;
    public static void main(String[] ignored)throws Exception {
        System.setProperty("mcgl.fps.limit","0");
        Display.setDisplayMode(new DisplayMode(480,340));Display.setResizable(true);
        Display.setTitle("MCGL — mixed-layout terrain cache");MCGLCoreDisplay.create(new PixelFormat());
        Display.setVSyncEnabled(false);
        try {
            GameRenderCommands g=RenderSystem.game();
            boolean fixed=metric(g,"terrainPendingRecoveries")>=0;
            g.glViewport(0,0,256,256);g.glDisable(3553);g.glDisable(2896);g.glDisable(3008);g.glDisable(2912);
            g.glDisable(GL11C.GL_CULL_FACE);g.glDisable(GL11C.GL_DEPTH_TEST);g.glDisable(GL11C.GL_BLEND);
            g.glMatrixMode(5889);g.glLoadIdentity();g.glOrtho(0,256,0,256,-100,100);g.glMatrixMode(5888);g.glLoadIdentity();
            int[] big=surface(1024),small=surface(128);
            for(boolean mixed:new boolean[]{false,true}){
                int rare=g.glGenLists(3),first=g.glGenLists((PREFIX+SELECTED)*3);
                if(mixed){
                    g.beginOriginalChunk(rare,3);g.glNewList(rare,4864);
                    int[] triangle=new int[24];triangle[8]=Float.floatToRawIntBits(1);triangle[17]=Float.floatToRawIntBits(1);
                    g.raw(triangle,24,3,4,false,true,true,false,false,false);g.glEndList();g.finishOriginalChunk();
                }
                for(int i=0;i<PREFIX;i++)build(g,first+i*3,i,1,big);
                IntBuffer selection=ByteBuffer.allocateDirect(SELECTED*4).order(ByteOrder.nativeOrder()).asIntBuffer();
                for(int i=0;i<SELECTED;i++){int handle=first+(PREFIX+i)*3;build(g,handle,i,PARTS,small);selection.put(handle);}selection.flip();
                for(int i=0;i<24;i++){frame(g,selection);GL11C.glFinish();}
                long calls=g.drawCalls()+g.chunkDrawCalls(),parts=g.terrainBatchParts(),batches=g.terrainBatches(),vertices=g.submittedVertices();
                int frames=48;double[] milliseconds=new double[frames];
                for(int i=0;i<frames;i++){long started=System.nanoTime();frame(g,selection);GL11C.glFinish();milliseconds[i]=(System.nanoTime()-started)/1e6;}
                calls=(g.drawCalls()+g.chunkDrawCalls()-calls)/frames;parts=(g.terrainBatchParts()-parts)/frames;batches=(g.terrainBatches()-batches)/frames;vertices=(g.submittedVertices()-vertices)/frames;
                Arrays.sort(milliseconds);
                System.out.printf(Locale.ROOT,"TERRAIN_CACHE_PRESSURE case=%s fixed=%s render_p50_ms=%.3f render_p95_ms=%.3f calls=%d parts=%d multis=%d vertices=%d pages=%d MiB=%.2f members=%d pending=%d%n",
                    mixed?"mixed":"common",fixed,milliseconds[frames/2],milliseconds[45],calls,parts,batches,vertices,g.terrainArenaPages(),g.terrainArenaBytes()/1048576.0,g.terrainArenaMembers(),metric(g,"terrainPendingRecoveries"));
                check(vertices==1048576,"same one-million-vertex selected scene");
                if(fixed||!mixed){check(calls>=64&&calls<=66&&parts==2048,"selected geometry remains batched");check(g.terrainArenaMembers()==PREFIX+SELECTED*PARTS+(mixed?1:0),"every source has shared storage");}
                else check(calls==2048&&parts==0&&g.terrainArenaMembers()==PREFIX+1,"old signed candidate reproduces mixed-layout fallback");
                check(g.terrainArenaBytes()<=256L*1024*1024,"hard arena budget");
                if(fixed)check(metric(g,"terrainPendingRecoveries")==0&&metric(g,"terrainRecoveryBytes")==0,"normal mixed geometry needs no recovery copies");
                ByteBuffer image=ByteBuffer.allocateDirect(256*256*4);
                GL11C.glReadPixels(0,0,256,256,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,image);
                long hash=0xcbf29ce484222325L;int bright=0;
                for(int i=0;i<image.limit();i++)hash=(hash^(image.get(i)&255))*0x100000001b3L;
                for(int i=0;i<image.limit();i+=4)if((image.get(i)&255)>200)bright++;
                check(bright>16000,"visible nonempty reference");if(expectedPixels==Long.MIN_VALUE)expectedPixels=hash;
                check(hash==expectedPixels,"exact same selected pixels with mixed storage");
                System.out.println("TERRAIN_CACHE_PIXELS case="+(mixed?"mixed":"common")+" hash="+Long.toUnsignedString(hash,16));
                g.glDeleteLists(rare,3);g.glDeleteLists(first,(PREFIX+SELECTED)*3);
                check(g.terrainArenaMembers()==0&&g.terrainArenaBytes()==0&&g.residentChunks()==0,"whole scene releases all GPU ranges");
                Display.update();
            }
            check(g.glGetError()==0,"Core error-free");
            System.out.println("TERRAIN_CACHE_PRESSURE_PASS fixed="+fixed+" exact-pixels/vertices/order/budget/lifetime");
        }finally{Display.destroy();Display.shutdown();}
    }
    private static long metric(GameRenderCommands g,String name){try{Method method=g.getClass().getMethod(name);method.setAccessible(true);return ((Number)method.invoke(g)).longValue();}catch(NoSuchMethodException old){return -1;}catch(Exception error){throw new AssertionError(error);}}
    private static void build(GameRenderCommands g,int handle,int tile,int count,int[] words){
        g.beginOriginalChunk(handle,3);g.glNewList(handle,4864);g.glPushMatrix();g.glTranslatef((tile%16)*16,(tile/16%16)*16,0);
        for(int part=0;part<count;part++){g.glPushMatrix();g.glTranslatef(0,(part&1)*8,0);g.raw(words,words.length,words.length/8,7,false,true,true,false,false,false);g.glPopMatrix();}
        g.glPopMatrix();g.glEndList();g.finishOriginalChunk();
    }
    private static void frame(GameRenderCommands g,IntBuffer selection){g.glClearColor(.03f,.05f,.07f,1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT);g.glCallLists(selection);}
    private static int[] surface(int quads){int[] words=new int[quads*32];for(int q=0;q<quads;q++)for(int v=0;v<4;v++){int at=q*32+v*8;words[at]=Float.floatToRawIntBits(q%16+(v==1||v==2?1:0));words[at+1]=Float.floatToRawIntBits(q/16%16+(v>=2?1:0));words[at+5]=-1;}return words;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

