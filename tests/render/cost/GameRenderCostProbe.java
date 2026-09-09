package local.mcgl.render.tests;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Java 21 account-free A/B cost diagnostic. These synthetic wall times are not game FPS measurements. */
public final class GameRenderCostProbe {
    public static void main(String[] ignored) throws Exception {
        Display.setDisplayMode(new DisplayMode(256,192));Display.setTitle("MCGL — render cost diagnostic");
        MCGLCoreDisplay.create(new PixelFormat());
        try(Recording recording=new Recording(Configuration.getConfiguration("profile"))) {
            recording.setName("Account-free MCGL render cost");
            for(String event:new String[]{"jdk.InitialEnvironmentVariable","jdk.InitialSystemProperty","jdk.SystemProcess","jdk.JVMInformation"})recording.disable(event);
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(2));recording.start();
            GameRenderCommands g=RenderSystem.game();g.glViewport(0,0,256,192);
            g.glDisable(3553);g.glDisable(2896);g.glDisable(3008);g.glDisable(2912);
            g.glDisable(GL11C.GL_CULL_FACE);g.glDisable(GL11C.GL_DEPTH_TEST);g.glDisable(GL11C.GL_BLEND);
            int texture=g.glGenTextures();g.glBindTexture(3553,texture);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);
            java.nio.ByteBuffer white=java.nio.ByteBuffer.allocateDirect(4);white.putInt(-1).flip();g.glTexImage2D(3553,0,32856,1,1,0,6408,5121,white);
            g.glActiveTexture(33985);g.glBindTexture(3553,texture);g.glActiveTexture(33984);
            int[] quad=quad();int model=g.glGenLists(1);g.glNewList(model,4864);raw(g,quad);g.glEndList();
            measure("cached-model-512",g,()->{for(int i=0;i<512;i++){g.glPushMatrix();g.glTranslatef((i%32)/32f,0,0);g.glCallList(model);g.glPopMatrix();}});
            measure("dynamic-batches-96",g,()->{for(int i=0;i<96;i++)raw(g,quad);});
            fontCost(g,texture);
            originalTerrainCost(g);
            int handles=g.glGenLists(512*3);List<GameChunkHandle> chunks=new ArrayList<GameChunkHandle>();
            for(int i=0;i<512;i++) {
                final int handle=handles+i*3;int x=(i%32)*16,z=(i/32)*16;chunks.add(()->handle);
                g.beginChunk(handle,x,0,z,2);g.glNewList(handle,4864);g.glPushMatrix();g.glTranslatef(x&1023,0,z&1023);raw(g,quad);g.glPopMatrix();g.glEndList();
                g.glNewList(handle+1,4864);g.glPushMatrix();g.glTranslatef(x&1023,0,z&1023);g.glDepthMask(false);raw(g,quad);g.glPopMatrix();g.glEndList();g.finishChunk();
            }
            measure("opaque-chunks-512",g,()->g.drawChunks(chunks,0,0,0,0));
            measure("transparent-chunks-512",g,()->g.drawChunks(chunks,1,0,0,0));
            for(int i=0;i<512;i++) {
                int handle=handles+i*3,x=(i%32)*16,z=(i/32)*16;
                g.beginChunk(handle,x,0,z,2);
                for(int pass=0;pass<2;pass++){g.glNewList(handle+pass,4864);g.glPushMatrix();g.glTranslatef(x&1023,0,z&1023);if((i&1)!=0)g.glEnable(GL11C.GL_CULL_FACE);g.glDisable(GL11C.GL_CULL_FACE);raw(g,quad);g.glPopMatrix();g.glEndList();}g.finishChunk();
            }
            measure("switched-opaque-chunks-512",g,()->g.drawChunks(chunks,0,0,0,0));
            measure("switched-transparent-chunks-512",g,()->g.drawChunks(chunks,1,0,0,0));
            g.glDeleteLists(handles,512*3);chunks.clear();handles=g.glGenLists(64*3);int[] surface=surface();
            for(int i=0;i<64;i++) {
                final int handle=handles+i*3;int x=(i%8)*16,z=(i/8)*16;chunks.add(()->handle);
                g.beginChunk(handle,x,0,z,2);g.glNewList(handle+1,4864);g.glPushMatrix();g.glTranslatef(x,0,z);g.glDepthMask(false);raw(g,surface);g.glPopMatrix();g.glEndList();g.finishChunk();
            }
            measure("dense-transparent-64x256",g,()->g.drawChunks(chunks,1,64,2,64));
            g.glDeleteLists(handles,64*3);chunks.clear();handles=g.glGenLists(64*3);
            for(int i=0;i<64;i++) {
                final int handle=handles+i*3;int x=(i%8)*16-64,z=(i/8)*16-64;chunks.add(()->handle);
                g.beginChunk(handle,x,0,z,3);
                for(int pass=0;pass<3;pass++){g.glNewList(handle+pass,4864);g.glPushMatrix();g.glTranslatef(x&1023,0,z&1023);if(pass!=0)g.glDepthMask(false);if(pass==2)g.glBindTexture(3553,texture);raw(g,surface);g.glPopMatrix();g.glEndList();}g.finishChunk();
            }
            measure("cross-region-opaque-64x256",g,()->g.drawChunks(chunks,0,0,2,0));
            measure("cross-region-transparent-64x256",g,()->g.drawChunks(chunks,1,0,2,0));
            measure("cross-region-textured-64x256",g,()->g.drawChunks(chunks,2,0,2,0));
            // Turning changes the scheduler selection even without moving the camera position.
            // Alternate two overlapping views, after the shared source geometry is already warm.
            List<GameChunkHandle> left=new ArrayList<GameChunkHandle>(chunks.subList(0,48)),right=new ArrayList<GameChunkHandle>(chunks.subList(16,64));
            int[] turn={0};
            measure("visibility-changing-opaque-48of64x256",g,()->g.drawChunks((turn[0]++&1)==0?left:right,0,0,2,0));
            measure("visibility-changing-transparent-48of64x256",g,()->g.drawChunks((turn[0]++&1)==0?left:right,1,0,2,0));
            g.glDeleteLists(handles,64*3);chunks.clear();handles=g.glGenLists(512*3);
            for(int i=0;i<512;i++) {
                final int handle=handles+i*3;int x=(i%32)*16-256,z=(i/32)*16-128;chunks.add(()->handle);
                g.beginChunk(handle,x,0,z,2);
                for(int pass=0;pass<2;pass++){g.glNewList(handle+pass,4864);g.glPushMatrix();g.glTranslatef(x&1023,0,z&1023);raw(g,surface);g.glPopMatrix();g.glEndList();}g.finishChunk();
            }
            List<GameChunkHandle> largeLeft=new ArrayList<GameChunkHandle>(chunks.subList(0,384)),largeRight=new ArrayList<GameChunkHandle>(chunks.subList(128,512));
            measure("large-visibility-opaque-384of512x256",g,()->g.drawChunks((turn[0]++&1)==0?largeLeft:largeRight,0,0,2,0));
            measure("large-visibility-transparent-384of512x256",g,()->g.drawChunks((turn[0]++&1)==0?largeLeft:largeRight,1,0,2,0));
            final int rebuilt=handles;
            g.beginChunk(rebuilt,-256,0,-128,2);g.glNewList(rebuilt,4864);g.glPushMatrix();g.glTranslatef((-256)&1023,0,(-128)&1023);raw(g,surface);g.glPopMatrix();g.glEndList();g.finishChunk();
            measure("opaque-only-rebuild-before-transparent-511x256",g,()->{
                g.beginChunk(rebuilt,-256,0,-128,2);g.glNewList(rebuilt,4864);g.glPushMatrix();g.glTranslatef((-256)&1023,0,(-128)&1023);raw(g,surface);g.glPopMatrix();g.glEndList();g.finishChunk();
                g.drawChunks(chunks,1,0,2,0);
            });
            g.glDeleteLists(handles,512*3);chunks.clear();handles=g.glGenLists(64*3);
            int[] textures=new int[4];
            for(int i=0;i<textures.length;i++){textures[i]=g.glGenTextures();g.glBindTexture(3553,textures[i]);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);white.rewind();g.glTexImage2D(3553,0,32856,1,1,0,6408,5121,white);}
            g.glEnable(3553);
            for(int i=0;i<64;i++){
                final int handle=handles+i*3;int x=(i%8)*16-64,z=(i/8)*16-64;chunks.add(()->handle);
                g.beginChunk(handle,x,0,z,3);
                for(int pass:new int[]{0,2}){g.glNewList(handle+pass,4864);g.glPushMatrix();g.glTranslatef(x&1023,0,z&1023);
                    for(int t=0;t<4;t++){g.glBindTexture(3553,textures[t]);int[] planes=planes(t);g.raw(planes,planes.length,planes.length/8,7,false,true,true,true,true,true);}
                    g.glPopMatrix();g.glEndList();}g.finishChunk();
            }
            measure("interleaved-textures-opaque-64x4x6",g,()->g.drawChunks(chunks,0,0,2,0));
            measure("interleaved-textures-transparent-64x4x6",g,()->g.drawChunks(chunks,2,0,2,0));
            g.glDeleteLists(handles,64*3);for(int t:textures)g.glDeleteTextures(t);g.glDisable(3553);g.glDeleteLists(model,1);g.glDeleteTextures(texture);
            if(g.glGetError()!=0||g.residentChunks()!=0||g.cachedModels()!=0)throw new AssertionError("render cost fixture state/leak");
            recording.stop();recording.dump(Paths.get("render-cost.jfr"));
        } finally {Display.destroy();Display.shutdown();}
        System.out.println("GAME_RENDER_COST_PASS synthetic-wall-time-only");
    }
    private static void measure(String name,GameRenderCommands g,Runnable frame) {
        for(int i=0;i<32;i++){frame.run();GL11C.glFinish();}
        double[] times=new double[96];long draws=g.drawCalls()+g.chunkDrawCalls(),transientCreated=transientCreations(g),arenaCreated=arenaCreations(g);
        for(int i=0;i<times.length;i++){long start=System.nanoTime();frame.run();GL11C.glFinish();times[i]=(System.nanoTime()-start)/1e6;}
        long submitted=g.drawCalls()+g.chunkDrawCalls()-draws;Arrays.sort(times);
        long after=transientCreations(g);
        long arenaAfter=arenaCreations(g);
        System.out.printf(Locale.ROOT,"GAME_RENDER_COST case=%s p50_ms=%.3f p95_ms=%.3f draws_per_frame=%d transient-created=%d arena-created=%d%n",name,times[times.length/2],times[(int)Math.ceil(times.length*.95)-1],submitted/times.length,after<0?-1:after-transientCreated,arenaAfter<0?-1:arenaAfter-arenaCreated);
        Display.update();
    }
    private static void raw(GameRenderCommands g,int[] words){g.raw(words,words.length,words.length/8,7,false,true,true,false,false,false);}
    private static void fontCost(GameRenderCommands g,int texture) {
        // The identical fixture also runs against build 182; optional scope methods are resolved once.
        java.lang.reflect.Method begin=optional(g,"beginText"),end=optional(g,"endText",int.class),define=optional(g,"defineFontGlyphs",int.class,int.class);
        int glyph=g.glGenLists(1);if(define!=null)invoke(g,define,glyph,1);
        int[] raw=new int[32];float[] xy={0,0,.018f,0,.018f,.07f,0,.07f};
        for(int v=0;v<4;v++){raw[v*8]=Float.floatToRawIntBits(xy[v*2]);raw[v*8+1]=Float.floatToRawIntBits(xy[v*2+1]);raw[v*8+3]=Float.floatToRawIntBits(v==1||v==2?1:0);raw[v*8+4]=Float.floatToRawIntBits(v>=2?1:0);}
        g.glNewList(glyph,4864);g.raw(raw,32,4,7,false,true,false,true,false,false);g.glTranslatef(.021f,0,0);g.glEndList();
        g.glEnable(3553);g.glBindTexture(3553,texture);g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(770,771);
        for(boolean cached:new boolean[]{false,true})measure(cached?"font-cached-2048-shadow-color":"font-immediate-2048-shadow-color",g,()->{
            for(int line=0;line<16;line++) {
                int scope=begin==null?0:(Integer)invoke(g,begin);
                for(int pass=0;pass<2;pass++) {
                    g.glPushMatrix();g.glTranslatef(-.95f+pass*.003f,-.95f+line*.115f+pass*.005f,0);
                    for(int c=0;c<64;c++) {
                        if(c%8==0)g.glColor4f(pass==0?.1f:(c%3)*.4f,pass==0?.1f:.8f,pass==0?.1f:.7f,.9f);
                        if(cached)g.glCallList(glyph);
                        else {g.glBegin(5);g.glTexCoord2f(0,0);g.glVertex3f(0,0,0);g.glTexCoord2f(0,1);g.glVertex3f(0,.07f,0);g.glTexCoord2f(1,0);g.glVertex3f(.018f,0,0);g.glTexCoord2f(1,1);g.glVertex3f(.018f,.07f,0);g.glEnd();g.glTranslatef(.021f,0,0);}
                    }
                    g.glPopMatrix();
                }
                if(end!=null)invoke(g,end,scope);
            }
        });
        g.glDeleteLists(glyph,1);g.glDisable(3553);g.glDisable(GL11C.GL_BLEND);g.glColor3f(1,1,1);
    }
    private static java.lang.reflect.Method optional(GameRenderCommands g,String name,Class<?>... arguments){try{java.lang.reflect.Method method=g.getClass().getMethod(name,arguments);method.setAccessible(true);return method;}catch(NoSuchMethodException old){return null;}}
    private static void originalTerrainCost(GameRenderCommands g){
        // Identical fixture on the signed pre-batching build and the new renderer.
        // No new arena APIs or diagnostics are required by the old baseline.
        java.lang.reflect.Method begin=optional(g,"beginOriginalChunk",int.class,int.class),finish=optional(g,"finishOriginalChunk");
        if(begin==null||finish==null){System.out.println("GAME_COST_SKIP original-terrain: candidate predates original-cache adapter");return;}
        g.glMatrixMode(5889);g.glPushMatrix();g.glLoadIdentity();g.glOrtho(-272,272,-272,272,-1024,1024);g.glMatrixMode(5888);g.glPushMatrix();g.glLoadIdentity();g.glRotatef(65,1,0,0);
        int count=1024,first=g.glGenLists(count*3);int[] surface=surface();
        for(int i=0;i<count;i++){
            int h=first+i*3;invoke(g,begin,h,3);
            for(int pass=0;pass<2;pass++){
                g.glNewList(h+pass,4864);g.glPushMatrix();g.glTranslatef((i%32)*16-256,0,(i/32)*16-256);
                g.glTranslatef(-8,-8,-8);g.glScalef(1.000001f,1.000001f,1.000001f);g.glTranslatef(8,8,8);g.glDepthMask(pass==0);raw(g,surface);g.glPopMatrix();g.glEndList();
            }
            invoke(g,finish);
        }
        java.nio.IntBuffer opaque=originalSelection(first,0,0,count,false),transparent=originalSelection(first,1,0,count,false),reverse=originalSelection(first,0,0,count,true);
        java.nio.IntBuffer left=originalSelection(first,0,0,768,false),right=originalSelection(first,0,256,768,true);
        int[] turn={0};
        measure("original-opaque-1024x256",g,()->g.glCallLists(opaque));
        measure("original-transparent-1024x256",g,()->g.glCallLists(transparent));
        measure("original-pass-replay-3072x256",g,()->{g.glCallLists(opaque);g.glCallLists(transparent);g.glCallLists(transparent);});
        measure("original-changing-order-1024x256",g,()->g.glCallLists((turn[0]++&1)==0?opaque:reverse));
        measure("original-changing-visibility-768of1024x256",g,()->g.glCallLists((turn[0]++&1)==0?left:right));
        List<Integer> shuffled=new ArrayList<Integer>();for(int i=0;i<count;i++)shuffled.add(first+i*3);Collections.shuffle(shuffled,new Random(184));
        java.nio.IntBuffer scattered=java.nio.ByteBuffer.allocateDirect(count*4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();for(int h:shuffled)scattered.put(h);scattered.flip();
        measure("original-scattered-order-1024x256",g,()->g.glCallLists(scattered));
        measure("original-rebuild-one-then-1024x256",g,()->{
            invoke(g,begin,first,3);g.glNewList(first,4864);g.glPushMatrix();g.glTranslatef(-256,0,-256);g.glDepthMask(true);raw(g,surface);g.glPopMatrix();g.glEndList();invoke(g,finish);g.glCallLists(opaque);
        });
        g.glDeleteLists(first,count*3);g.glDepthMask(true);g.glPopMatrix();g.glMatrixMode(5889);g.glPopMatrix();g.glMatrixMode(5888);
    }
    private static java.nio.IntBuffer originalSelection(int first,int pass,int start,int count,boolean reverse){
        java.nio.IntBuffer names=java.nio.ByteBuffer.allocateDirect(count*4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();for(int i=0;i<count;i++)names.put(first+(start+(reverse?count-1-i:i))*3+pass);names.flip();return names;
    }
    private static Object invoke(GameRenderCommands g,java.lang.reflect.Method method,Object... args){try{return method.invoke(g,args);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static long transientCreations(GameRenderCommands g){try{java.lang.reflect.Method m=g.getClass().getMethod("transientMeshCreations");m.setAccessible(true);return (Long)m.invoke(g);}catch(NoSuchMethodException olderCandidate){return -1;}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static long arenaCreations(GameRenderCommands g){java.lang.reflect.Method method=optional(g,"terrainArenaCreations");return method==null?-1:(Long)invoke(g,method);}
    private static int[] quad(){int[] words=new int[32];float[] xy={-.1f,-.1f,.1f,-.1f,.1f,.1f,-.1f,.1f};for(int i=0;i<4;i++){words[i*8]=Float.floatToRawIntBits(xy[i*2]);words[i*8+1]=Float.floatToRawIntBits(xy[i*2+1]);words[i*8+5]=-1;}return words;}
    private static int[] surface(){int[] words=new int[256*32];for(int z=0;z<16;z++)for(int x=0;x<16;x++)for(int v=0;v<4;v++){int at=(z*16+x)*32+v*8;words[at]=Float.floatToRawIntBits(x+(v==1||v==2?1:0));words[at+2]=Float.floatToRawIntBits(z+(v>=2?1:0));words[at+5]=-1;}return words;}
    private static int[] planes(int texture){int[] words=new int[6*32];for(int face=0;face<6;face++)for(int v=0;v<4;v++){int at=face*32+v*8;words[at]=Float.floatToRawIntBits((v==1||v==2?1:0));words[at+1]=Float.floatToRawIntBits(face+texture*.2f);words[at+2]=Float.floatToRawIntBits(v>=2?1:0);words[at+5]=-1;words[at+6]=0x007f00;}return words;}
}
