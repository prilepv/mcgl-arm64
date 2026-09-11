package local.mcgl.render.tests;

import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Whole-renderer resource/coherence checks for GPU texture replicas. */
public final class GameTextureArraysProbe {
    private static final int SIZE=64;
    private static int checks;
    public static void main(String[] ignored)throws Exception {
        System.setProperty("mcgl.graphics.unlitShaders","true");System.setProperty("mcgl.terrain.materials","true");
        try{for(boolean wide:new boolean[]{false,true}){
            System.setProperty("mcgl.terrain.wideArena",Boolean.toString(wide));
            Display.setDisplayMode(new DisplayMode(300,220));Display.setTitle("MCGL texture coherence validation");MCGLCoreDisplay.create(new PixelFormat().withDepthBits(24));
            try{run(RenderSystem.game());}finally{Display.destroy();}
        }}finally{Display.shutdown();}
    }
    private static void run(GameRenderCommands g)throws Exception {
        int fbo=GL30C.glGenFramebuffers(),color=GL30C.glGenRenderbuffers(),depth=GL30C.glGenRenderbuffers();
        g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,fbo);g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,color);g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL11C.GL_RGBA8,SIZE,SIZE);g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL30C.GL_RENDERBUFFER,color);
        g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,depth);g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL30C.GL_DEPTH24_STENCIL8,SIZE,SIZE);g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_DEPTH_STENCIL_ATTACHMENT,GL30C.GL_RENDERBUFFER,depth);
        int first=g.glGenLists(32*3);int[] textures=new int[8];for(int i=0;i<8;i++)textures[i]=texture(g,32,i,GL11C.GL_RGBA8);
        // This target is registered before the first terrain arena exists.
        int earlyTarget=texture(g,32,11,GL11C.GL_RGBA8),sourceFbo=GL30C.glGenFramebuffers();g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,sourceFbo);g.GL30_glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,earlyTarget,0);g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,fbo);
        g.glViewport(0,0,SIZE,SIZE);g.glMatrixMode(5889);g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-2,2);g.glMatrixMode(5888);g.glLoadIdentity();g.glDisable(2896);g.glDisable(GL11C.GL_CULL_FACE);g.glDisable(GL11C.GL_DITHER);g.glDisable(GL11C.GL_DEPTH_TEST);g.glDisable(GL11C.GL_BLEND);g.glEnable(3553);g.glColor4f(1,1,1,1);
        for(int i=0;i<32;i++){int handle=first+i*3;g.beginOriginalChunk(handle,3);g.glNewList(handle,4864);g.glPushMatrix();g.glTranslatef((i%8)*.22f-.78f,(i/8)*.45f-.67f,0);g.glBegin(7);g.glTexCoord2f(0,0);g.glVertex3f(-.11f,-.2f,0);g.glTexCoord2f(1,0);g.glVertex3f(.11f,-.2f,0);g.glTexCoord2f(1,1);g.glVertex3f(.11f,.2f,0);g.glTexCoord2f(0,1);g.glVertex3f(-.11f,.2f,0);g.glEnd();g.glPopMatrix();g.glEndList();g.finishOriginalChunk();}
        // Immediate float geometry is intentionally outside arena format bounds;
        // replace the fixtures with the original packed quad producer.
        for(int i=0;i<32;i++)replace(g,first+i*3,i);
        int sentinelArray=GL11C.glGenTextures(),sentinelSampler=GL33C.glGenSamplers();GL13C.glActiveTexture(GL13C.GL_TEXTURE0+12);GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY,sentinelArray);GL12C.glTexImage3D(GL30C.GL_TEXTURE_2D_ARRAY,0,GL11C.GL_RGBA8,1,1,1,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,(ByteBuffer)null);GL33C.glSamplerParameteri(sentinelSampler,GL11C.GL_TEXTURE_WRAP_S,GL13C.GL_CLAMP_TO_BORDER);GL33C.glBindSampler(12,sentinelSampler);GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        try{
            long[] calls=compare(g,first,textures,"eight compatible textures",()->{});check(calls[0]==16&&calls[1]==1,"array path actually collapses 16 original calls to one");
            sentinel(sentinelArray,sentinelSampler);
            compare(g,first,textures,"subimage while second unit owns source",()->{g.glActiveTexture(GL13C.GL_TEXTURE1);g.glBindTexture(3553,textures[0]);g.glTexSubImage2D(3553,0,3,4,2,2,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,pixels(2,7));g.glActiveTexture(GL13C.GL_TEXTURE0);});
            compare(g,first,textures,"changed filter and wrap",()->{g.glBindTexture(3553,textures[1]);g.glTexParameteri(3553,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_LINEAR);g.glTexParameteri(3553,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_LINEAR);g.glTexParameteri(3553,GL11C.GL_TEXTURE_WRAP_S,GL14C.GL_MIRRORED_REPEAT);});
            compare(g,first,textures,"attribute restore invalidates parameter selection",()->{g.glBindTexture(3553,textures[2]);g.glPushAttrib(0x40000);g.glTexParameteri(3553,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_LINEAR);g.glPopAttrib();});
            compare(g,first,textures,"level zero shape replacement",()->{g.glBindTexture(3553,textures[3]);upload(g,16,4,GL11C.GL_RGBA8);});
            compare(g,first,textures,"copy subimage from framebuffer",()->{g.glClearColor(.7f,.2f,.4f,1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT);g.glBindTexture(3553,textures[4]);g.glCopyTexSubImage2D(3553,0,2,2,0,0,3,3);});
            int original=textures[5];textures[5]=earlyTarget;
            compare(g,first,textures,"target registered before first arena",()->{g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,sourceFbo);g.glClearColor(.3f,.6f,.8f,1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT);g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,fbo);});check(!entries(g).containsKey(earlyTarget),"preexisting render target is never copied");textures[5]=original;
            compare(g,first,textures,"existing array source becomes a render target",()->{g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,sourceFbo);g.GL30_glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,textures[6],0);g.glClearColor(.9f,.1f,.3f,1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT);g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,fbo);});check(!entries(g).containsKey(textures[6]),"new render target is removed from replicas");
            compare(g,first,textures,"source deletion and regenerated name",()->{g.glDeleteTextures(textures[7]);textures[7]=g.glGenTextures();g.glBindTexture(3553,textures[7]);g.glTexParameteri(3553,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);g.glTexParameteri(3553,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_NEAREST);upload(g,32,13,GL11C.GL_RGBA8);});
            int single=texture(g,32,9,GL30C.GL_R8),rgb=texture(g,32,10,GL11C.GL_RGB8);original=textures[5];textures[5]=single;compare(g,first,textures,"unsupported channel format fallback",()->{});check(!entries(g).containsKey(single),"single-channel format not admitted");textures[5]=rgb;compare(g,first,textures,"RGB implicit alpha survives layer copy",()->{});textures[5]=original;g.glDeleteTextures(single);g.glDeleteTextures(rgb);
            int incomplete=texture(g,32,12,GL11C.GL_RGBA8);g.glBindTexture(3553,incomplete);g.glTexParameteri(3553,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_LINEAR_MIPMAP_LINEAR);original=textures[5];textures[5]=incomplete;compare(g,first,textures,"incomplete mip chain fallback",()->{});check(!entries(g).containsKey(incomplete),"incomplete source keeps native sampling");textures[5]=original;g.glDeleteTextures(incomplete);
            GL33C.glBindSampler(0,sentinelSampler);calls=compare(g,first,textures,"external primary sampler fallback",()->{});check(calls[0]==calls[1],"primary sampler disables replicas");GL33C.glBindSampler(0,0);
            local.mcgl.render.backend.GameTextureArrayBudgetProbe.run(g);
            sentinel(sentinelArray,sentinelSampler);check(g.glGetError()==0,"no native error after coherence cases");
            System.out.println("GAME_TEXTURE_ARRAYS_PASS checks="+checks+" exact-RGBA-depth/coherence/FBO-targets/reuse/filter-scope/sampler-restoration");
        }finally{
            g.glDeleteLists(first,32*3);for(int texture:textures)g.glDeleteTextures(texture);g.glDeleteTextures(earlyTarget);GL33C.glBindSampler(12,0);GL33C.glDeleteSamplers(sentinelSampler);GL13C.glActiveTexture(GL13C.GL_TEXTURE0+12);GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY,0);GL11C.glDeleteTextures(sentinelArray);GL13C.glActiveTexture(GL13C.GL_TEXTURE0);g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,0);GL30C.glDeleteFramebuffers(sourceFbo);GL30C.glDeleteFramebuffers(fbo);GL30C.glDeleteRenderbuffers(color);GL30C.glDeleteRenderbuffers(depth);
        }
    }
    private static void replace(GameRenderCommands g,int handle,int i){int[] raw=new int[32];float[] xy={-.11f,-.2f,.11f,-.2f,.11f,.2f,-.11f,.2f};for(int v=0;v<4;v++){raw[v*8]=Float.floatToRawIntBits(xy[v*2]);raw[v*8+1]=Float.floatToRawIntBits(xy[v*2+1]);raw[v*8+3]=Float.floatToRawIntBits(v==1||v==2?1:0);raw[v*8+4]=Float.floatToRawIntBits(v>=2?1:0);raw[v*8+5]=-1;}g.beginOriginalChunk(handle,3);g.glNewList(handle,4864);g.glPushMatrix();g.glTranslatef((i%8)*.22f-.78f,(i/8)*.45f-.67f,0);g.raw(raw,32,4,7,false,true,true,true,false,false);g.glPopMatrix();g.glEndList();g.finishOriginalChunk();}
    private static long[] compare(GameRenderCommands g,int first,int[] textures,String label,Runnable setup){byte[] expected=null;long[] calls=new long[2];for(int mode=0;mode<2;mode++){System.setProperty("mcgl.terrain.textureArrays",Boolean.toString(mode!=0));setup.run();g.glClearColor(0,0,0,1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT);long start=g.chunkDrawCalls();int scope=g.beginOriginalTerrain();try{for(int i=0;i<32;i++){g.glBindTexture(3553,textures[i%8]);g.glCallList(first+i*3);}}finally{g.endOriginalTerrain(scope);}calls[mode]=g.chunkDrawCalls()-start;byte[] actual=read(g);if(expected==null)expected=actual;else check(Arrays.equals(expected,actual),label+" exact framebuffer");}return calls;}
    private static void sentinel(int array,int sampler){int active=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);GL13C.glActiveTexture(GL13C.GL_TEXTURE0+12);int actualArray=GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY),actualSampler=GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);check(actualArray==array&&actualSampler==sampler,"external array and sampler bindings restored array="+actualArray+" expected="+array+" sampler="+actualSampler+" expected="+sampler);GL13C.glActiveTexture(active);}
    private static Map<?,?> entries(GameRenderCommands g)throws Exception{Field target=g.getClass().getDeclaredField("target");target.setAccessible(true);Object render=target.get(g);Field batch=Class.forName("local.mcgl.render.backend.GameRenderer").getDeclaredField("terrainBatch");batch.setAccessible(true);Object queue=batch.get(render);Field arrays=queue.getClass().getDeclaredField("arrays");arrays.setAccessible(true);Object cache=arrays.get(queue);Field entries=cache.getClass().getDeclaredField("entries");entries.setAccessible(true);return (Map<?,?>)entries.get(cache);}
    private static int texture(GameRenderCommands g,int size,int variant,int format){int texture=g.glGenTextures();g.glBindTexture(3553,texture);g.glTexParameteri(3553,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);g.glTexParameteri(3553,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_NEAREST);upload(g,size,variant,format);return texture;}
    private static void upload(GameRenderCommands g,int size,int variant,int format){g.glTexImage2D(3553,0,format,size,size,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,pixels(size,variant));}
    private static ByteBuffer pixels(int size,int variant){ByteBuffer b=ByteBuffer.allocateDirect(size*size*4);for(int y=0;y<size;y++)for(int x=0;x<size;x++)b.put((byte)(variant*19+x*7)).put((byte)(variant*11+y*13)).put((byte)(x*17+y*23)).put((byte)255);b.flip();return b;}
    private static byte[] read(GameRenderCommands g){ByteBuffer b=ByteBuffer.allocateDirect(SIZE*SIZE*8);g.glReadPixels(0,0,SIZE,SIZE,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,b);b.position(SIZE*SIZE*4);g.glReadPixels(0,0,SIZE,SIZE,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT,b);b.clear();byte[] result=new byte[b.remaining()];b.get(result);return result;}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
