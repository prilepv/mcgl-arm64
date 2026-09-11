package local.mcgl.render.tests;

import java.nio.*;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Account-free dense original-geometry workload. Not a game FPS benchmark. */
public final class DenseTerrainProbe {
    private static final int PARTS=4096,DRAW_PARTS=3072,VERTICES=1280,SIZE=256;
    private static final Map<Integer,byte[]> snapshots=new HashMap<Integer,byte[]>();
    private static final java.lang.management.ThreadMXBean cpu=java.lang.management.ManagementFactory.getThreadMXBean();
    private static final int[] geometry=geometry();
    private static int rebuildCursor;
    public static void main(String[] args)throws Exception {
        Display.setDisplayMode(new DisplayMode(300,220));Display.setTitle("MCGL dense geometry validation");
        MCGLCoreDisplay.create(new PixelFormat().withDepthBits(24).withStencilBits(8));
        try{for(boolean wide:new boolean[]{false,true,true,false}){
            resetTestBatch();System.setProperty("mcgl.terrain.wideArena",Boolean.toString(wide));run(wide);
        }}finally{Display.destroy();Display.shutdown();}
    }
    /** Isolated fixture only: discard the empty terrain queue so each placement policy starts fresh in one GL context. */
    private static void resetTestBatch()throws Exception {
        Object g=RenderSystem.game();java.lang.reflect.Field target=g.getClass().getDeclaredField("target");target.setAccessible(true);Object renderer=target.get(g);
        java.lang.reflect.Field batch=Class.forName("local.mcgl.render.backend.GameRenderer").getDeclaredField("terrainBatch");batch.setAccessible(true);Object previous=batch.get(renderer);
        if(previous!=null){java.lang.reflect.Field arena=previous.getClass().getDeclaredField("arena");arena.setAccessible(true);MeshArena storage=(MeshArena)arena.get(previous);if(storage!=null){if(storage.residentMembers()!=0)throw new AssertionError("live fixture leaked");storage.close();}java.lang.reflect.Field arrays=previous.getClass().getDeclaredField("arrays");arrays.setAccessible(true);Object cache=arrays.get(previous);java.lang.reflect.Method close=cache.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(cache);batch.set(renderer,null);}
    }
    private static void run(boolean wide)throws Exception {
        GameRenderCommands g=RenderSystem.game();int first=g.glGenLists(PARTS*3);
        int fbo=GL30C.glGenFramebuffers(),color=GL30C.glGenRenderbuffers(),depth=GL30C.glGenRenderbuffers();
        int[] textures=new int[8];
        try{
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,fbo);
            g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,color);g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL11C.GL_RGBA8,SIZE,SIZE);g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL30C.GL_RENDERBUFFER,color);
            g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,depth);g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL30C.GL_DEPTH24_STENCIL8,SIZE,SIZE);g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_DEPTH_STENCIL_ATTACHMENT,GL30C.GL_RENDERBUFFER,depth);
            if(g.GL30_glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)!=GL30C.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("framebuffer");
            g.glViewport(0,0,SIZE,SIZE);g.glMatrixMode(5889);g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-10,10);g.glMatrixMode(5888);g.glLoadIdentity();
            g.glDisable(2896);g.glDisable(GL11C.GL_CULL_FACE);g.glEnable(3553);g.glEnable(GL11C.GL_DEPTH_TEST);g.glDepthFunc(GL11C.GL_LEQUAL);g.glColor4f(1,1,1,1);
            for(int i=0;i<textures.length;i++){
                textures[i]=g.glGenTextures();g.glBindTexture(3553,textures[i]);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);
                ByteBuffer b=ByteBuffer.allocateDirect(16*16*4);for(int p=0;p<256;p++)b.put((byte)(80+i*20)).put((byte)(200-i*15)).put((byte)(40+p%16*10)).put((byte)255);b.flip();g.glTexImage2D(3553,0,32856,16,16,0,6408,5121,b);
            }
            for(int part=0;part<PARTS;part++)replace(g,first,part);
            System.out.println("DENSE_TERRAIN_SCENE wide="+wide+" parts="+PARTS+" drawn="+DRAW_PARTS+" vertices="+(DRAW_PARTS*VERTICES)+" arena_pages="+g.terrainArenaPages()+" arena_members="+g.terrainArenaMembers()+" arena_bytes="+g.terrainArenaBytes()+" max_vertex_uniform_components="+GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_UNIFORM_COMPONENTS));
            for(int test=0;test<3;test++){
                int textureCount=test==0?1:8;boolean rebuild=test==2;
                byte[] reference=snapshots.get(textureCount);
                for(int mode=0;mode<4;mode++){
                    configure(mode);draw(g,first,textures,textureCount,0);byte[] rgba=read(g,6408,5121),z=read(g,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT);byte[] snapshot=new byte[rgba.length+z.length];System.arraycopy(rgba,0,snapshot,0,rgba.length);System.arraycopy(z,0,snapshot,rgba.length,z.length);
                    if(reference==null){reference=snapshot;snapshots.put(textureCount,snapshot);}else if(!Arrays.equals(reference,snapshot))throw new AssertionError("dense exact pixels mode="+mode+" textures="+textureCount+" wide="+wide);
                }
                double[][] samples=new double[4][16],cpuSamples=new double[4][16];int[] used=new int[4];long[] calls=new long[4];
                for(int round=-4;round<8;round++){for(int mode:new int[]{0,1,2,3,3,2,1,0}){
                    configure(mode);GL11C.glFinish();long before=g.chunkDrawCalls(),startCpu=cpu.getCurrentThreadCpuTime(),start=System.nanoTime();draw(g,first,textures,textureCount,(round&1)==0?0:.0001f);if(rebuild)for(int i=0;i<8;i++)replace(g,first,(rebuildCursor++*1543)%PARTS);GL11C.glFinish();double ms=(System.nanoTime()-start)/1e6,cpuMs=(cpu.getCurrentThreadCpuTime()-startCpu)/1e6;calls[mode]=g.chunkDrawCalls()-before;if(round>=0){cpuSamples[mode][used[mode]]=cpuMs;samples[mode][used[mode]++]=ms;}
                }present(g,fbo);}
                for(int mode=0;mode<4;mode++){Arrays.sort(samples[mode]);Arrays.sort(cpuSamples[mode]);System.out.printf(Locale.ROOT,"DENSE_TERRAIN_COST wide=%s textures=%d rebuild=%s order=blocks16 mode=%s logical_calls=%d p50_ms=%.3f p95_ms=%.3f cpu_p50_ms=%.3f synthetic-not-world-fps%n",wide,textureCount,rebuild,new String[]{"195","arrays","unlit","both"}[mode],calls[mode],samples[mode][8],samples[mode][15],cpuSamples[mode][8]);}
            }
            if(g.glGetError()!=0)throw new AssertionError("native error");System.out.println("DENSE_TERRAIN_PASS exact-RGBA-depth");
        }catch(Throwable failure){failure.printStackTrace();throw failure;}finally{
            g.abortOriginalChunk();
            g.glDeleteLists(first,PARTS*3);for(int texture:textures)if(texture!=0)g.glDeleteTextures(texture);
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,0);GL30C.glDeleteFramebuffers(fbo);GL30C.glDeleteRenderbuffers(color);GL30C.glDeleteRenderbuffers(depth);
        }
    }
    private static void configure(int mode){System.setProperty("mcgl.terrain.materials","true");System.setProperty("mcgl.terrain.textureArrays",Boolean.toString((mode&1)!=0));System.setProperty("mcgl.graphics.unlitShaders",Boolean.toString(mode>=2));}
    private static int[] geometry(){int[] raw=new int[VERTICES*8];for(int v=0;v<VERTICES;v++){int quad=v/4,corner=v%4;float x=(quad%16+(corner==1||corner==2?1:0))/512f,y=(quad/16+(corner>=2?1:0))/512f;raw[v*8]=Float.floatToRawIntBits(x);raw[v*8+1]=Float.floatToRawIntBits(y);raw[v*8+2]=Float.floatToRawIntBits((quad%3)*.0001f);raw[v*8+3]=Float.floatToRawIntBits(corner==1||corner==2?1:0);raw[v*8+4]=Float.floatToRawIntBits(corner>=2?1:0);raw[v*8+5]=-1;raw[v*8+6]=0x007f0000;}return raw;}
    private static void replace(GameRenderCommands g,int first,int part){int handle=first+part*3;g.beginOriginalChunk(handle,3);g.glNewList(handle,4864);g.glPushMatrix();g.glTranslatef((part%64)/32f-1,(part/64)/32f-1,(part%5)*.01f);g.raw(geometry,geometry.length,VERTICES,7,false,true,true,true,true,true);g.glPopMatrix();g.glEndList();g.finishOriginalChunk();}
    private static void present(GameRenderCommands g,int framebuffer){g.GL30_glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,framebuffer);g.GL30_glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,0);GL30C.glBlitFramebuffer(0,0,SIZE,SIZE,0,0,300,220,GL11C.GL_COLOR_BUFFER_BIT,GL11C.GL_NEAREST);Display.update();g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,framebuffer);}
    private static void draw(GameRenderCommands g,int first,int[] textures,int textureCount,float camera){
        g.glDepthMask(true);g.glClearColor(0,0,0,1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT);g.glLoadIdentity();g.glTranslatef(camera,0,0);
        int scope=g.beginOriginalTerrain();try{for(int i=0;i<DRAW_PARTS;i++){g.glBindTexture(3553,textures[i/8%textureCount]);if(i%22==0)g.glDepthMask(i/22%2==0);int part=((i/16*151)%(PARTS/16))*16+i%16;g.glCallList(first+part*3);}}finally{g.endOriginalTerrain(scope);}
    }
    private static byte[] read(GameRenderCommands g,int format,int type){ByteBuffer b=ByteBuffer.allocateDirect(SIZE*SIZE*4).order(ByteOrder.nativeOrder());g.glReadPixels(0,0,SIZE,SIZE,format,type,b);byte[] result=new byte[b.remaining()];b.get(result);return result;}
}
