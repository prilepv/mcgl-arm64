package local.mcgl.render.tests;

import java.io.File;
import java.nio.*;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Exact RGBA/depth/state comparisons against individual original cached draws.
 * The reference does not use a palette or multi-draw submission. */
public final class GameTerrainBatchProbe {
    private static int checks;
    public static int run(GameRenderCommands g)throws Exception{
        checks=0;int first=g.glGenLists(3*96),previousModels=g.cachedModels(),previousMembers=g.terrainArenaMembers();
        int framebuffer=GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING),renderbuffer=GL11C.glGetInteger(GL30C.GL_RENDERBUFFER_BINDING);
        int target=GL30C.glGenFramebuffers(),color=GL30C.glGenRenderbuffers(),depth=GL30C.glGenRenderbuffers();
        g.glPushAttrib(-1);g.glMatrixMode(5889);g.glPushMatrix();g.glMatrixMode(5888);g.glPushMatrix();
        int textureA=0,textureB=0;
        try{
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,target);g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,color);g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL11C.GL_RGBA8,64,64);g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL30C.GL_RENDERBUFFER,color);
            g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,depth);g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL30C.GL_DEPTH24_STENCIL8,64,64);g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_DEPTH_STENCIL_ATTACHMENT,GL30C.GL_RENDERBUFFER,depth);
            check(g.GL30_glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)==GL30C.GL_FRAMEBUFFER_COMPLETE,"independent RGBA/depth/stencil target");g.glViewport(0,0,64,64);reset(g);
            textureA=texture(g,0);textureB=texture(g,1);final int a=textureA,b=textureB;
            OriginalChunkEmitter t=new OriginalChunkEmitter(new File("bin/mcgl.jar"),1024);
            for(int i=0;i<70;i++){
                int handle=first+i*3;g.beginOriginalChunk(handle,3);g.glNewList(handle,4864);g.glPushMatrix();g.glTranslatef((i%7)*.21f-.63f,(i%5)*.19f-.38f,(i%9)*.06f-.24f);
                g.glTranslatef(-8,-8,-8);g.glScalef(1.000001f,1.000001f,1.000001f);g.glTranslatef(8,8,8);g.glDepthMask(true);emit(t,i,7);g.glPopMatrix();g.glEndList();g.finishOriginalChunk();
            }
            int[] twelve=handles(first,12),many=handles(first,70);
            long calls=compare(g,"twelve compatible original model transforms",twelve,()->{});
            check(calls==1,"twelve compatible original draws become one ordered multi-submission");
            int[] reversed=twelve.clone();reverse(reversed);
            for(int shade:new int[]{7424,7425})for(int style=0;style<4;style++){
                final int transform=style;
                compare(g,"alpha/order shade="+shade+" transform="+style,reversed,()->{
                    g.glShadeModel(shade);g.glEnable(GL11C.GL_BLEND);
                    if(transform==1){g.glRotatef(19,0,0,1);g.glScalef(-.9f,.8f,.7f);}
                    if(transform==2){g.glMatrixMode(5889);g.glLoadMatrix(floats(1.1f,0,0,0,0,1.1f,0,0,0,0,1,-.3f,0,0,0,1));g.glMatrixMode(5888);g.glRotatef(27,1,1,0);}
                    if(transform==3){g.glTranslated(-8192,8192,-4096);g.glTranslated(8192,-8192,4096);g.glScalef(.8f,-.9f,1);g.glEnable(GL11C.GL_CULL_FACE);g.glFrontFace(GL11C.GL_CW);}
                });
            }
            compare(g,"ordered duplicate models",new int[]{twelve[0],twelve[1],twelve[0],twelve[3],twelve[1]},()->g.glEnable(GL11C.GL_BLEND));
            int[] repeated=new int[513];Arrays.fill(repeated,first);calls=compare(g,"duplicate runs cross the 256-submission limit",repeated,()->g.glEnable(GL11C.GL_BLEND));check(calls==3,"bounded runs preserve all 513 repeated draws, including the singleton tail");
            calls=compare(g,"more than thirty-two distinct matrices",many,()->g.glEnable(GL11C.GL_BLEND));check(calls>=3&&calls<70,"palette collisions split runs without losing batching");
            calls=compare(g,"two live meshes sharing a recycled palette tag",new int[]{first,first+32*3},()->g.glEnable(GL11C.GL_BLEND));check(calls==2,"conflicting live tag values cannot overwrite an earlier matrix");
            long creations=g.terrainArenaCreations(),bytes=g.terrainArenaBytes();
            for(int turn=0;turn<8;turn++){final int camera=turn;int[] selected=Arrays.copyOfRange(many,turn,70-turn);if((turn&1)!=0)reverse(selected);compare(g,"sparse camera selection "+turn,selected,()->g.glTranslatef(camera*.013f,-camera*.019f,0));}
            check(g.terrainArenaCreations()==creations&&g.terrainArenaBytes()==bytes,"camera/visibility/order changes reuse exact GPU ranges without repacking");
            compare(g,"fog, primary texture and transformed lightmap",twelve,()->{
                g.glEnable(3553);g.glBindTexture(3553,a);g.glEnable(2912);g.glFogi(2917,9729);g.glFogf(2915,0);g.glFogf(2916,1);g.glFog(2918,floats(.2f,.4f,.7f,1));
                g.glMatrixMode(5890);g.glScalef(.7f,.9f,1);g.glTranslatef(.13f,.11f,0);g.glMatrixMode(5888);
                g.glActiveTexture(33985);g.glEnable(3553);g.glBindTexture(3553,b);g.glMatrixMode(5890);g.glScalef(1/240f,1/240f,1);g.glMatrixMode(5888);g.glActiveTexture(33984);
            });
            compare(g,"depth and alpha testing",twelve,()->{g.glEnable(GL11C.GL_DEPTH_TEST);g.glEnable(3008);g.glAlphaFunc(516,.45f);g.glColorMask(true,false,true,true);});
            int interleaved=first+70*3;
            for(int i=0;i<6;i++){
                int h=interleaved+i*3;g.beginOriginalChunk(h,3);g.glNewList(h,4864);g.glPushMatrix();g.glTranslatef(i*.08f-.2f,0,0);g.glBindTexture(3553,i==2||i==3?b:a);g.glEnable(3553);g.glDepthMask(i<3);
                if(i==4){g.glDisable(GL11C.GL_CULL_FACE);g.glColorMask(true,true,false,true);}
                emit(t,i,7);g.glPopMatrix();g.glEndList();g.finishOriginalChunk();
            }
            calls=compare(g,"A/A/B/B/A/A textures, depth and color-mask barriers",handles(interleaved,6),()->g.glEnable(GL11C.GL_BLEND));check(calls>=3,"incompatible raster/texture states never merge");
            int ordinary=first+76*3;g.glNewList(ordinary,4864);emit(t,5,7);g.glEndList();
            calls=compare(g,"ordinary model interleaves original terrain",new int[]{first,first+3,ordinary,first+6,first+9},()->g.glEnable(GL11C.GL_BLEND));check(calls==2,"terrain runs stop on both sides of ordinary geometry");
            int nested=first+77*3;g.beginOriginalChunk(nested,3);g.glNewList(nested,4864);g.glPushMatrix();g.glTranslatef(-.2f,0,0);g.glCallList(first);g.glTranslatef(.4f,0,0);g.glCallList(first);g.glPopMatrix();g.glEndList();g.finishOriginalChunk();
            calls=compare(g,"same member under two nested transforms",new int[]{nested},()->g.glEnable(GL11C.GL_BLEND));check(calls==2,"one member cannot reuse its tag under conflicting transforms in a run");
            int distant=nested+3;g.beginOriginalChunk(distant,3);g.glNewList(distant,4864);g.glPushMatrix();g.glTranslatef(1<<24,-(1<<24),8192);g.glCallLists(IntBuffer.wrap(twelve));g.glPopMatrix();g.glEndList();g.finishOriginalChunk();
            compare(g,"large positive/negative parent matrices and nested original list groups",new int[]{distant},()->g.glTranslated(-(1<<24),1<<24,-8192));
            int changed=distant+3;g.beginOriginalChunk(changed,3);g.glNewList(changed,4864);g.glCallList(first);g.glMatrixMode(5889);g.glScalef(.7f,.8f,1);g.glMatrixMode(5888);g.glCallList(first+3);g.glEndList();g.finishOriginalChunk();
            calls=compare(g,"projection change between original draws",new int[]{changed},()->{});check(calls==2,"projection changes flush pending original geometry");
            int current=changed+3;g.beginOriginalChunk(current,3);g.glNewList(current,4864);g.glCallList(first);g.glColor3f(.4f,.8f,.6f);g.glMultiTexCoord2f(33985,.2f,.3f);g.glCallList(first+3);g.glEndList();g.finishOriginalChunk();
            compare(g,"current attribute changes preserve final logical state",new int[]{current},()->{});
            int malformed=current+3;g.beginOriginalChunk(malformed,3);g.glNewList(malformed,4864);g.glEnable(0x123456);g.glEndList();g.finishOriginalChunk();
            reset(g);clear(g);g.glCallList(first);g.glCallList(first+3);byte[] prefix=pixels(g);
            reset(g);clear(g);rejects(()->g.glCallLists(IntBuffer.wrap(new int[]{first,first+3,malformed})));check(Arrays.equals(prefix,pixels(g)),"failed list preserves completed prefix and clears pending scope");
            compare(g,"valid run after a failed list",twelve,()->{});
            calls=compare(g,"lighting retains ordinary shader path",twelve,()->g.glEnable(2896));check(calls==12,"lit models are deliberately not combined by an unlit palette");
            try(GameEffect effect=new GameEffect("/shader/default","/shader/default")){
                int sampler=effect.uniform("colorMap");effect.set(sampler,0);
                calls=compare(g,"imported effect retains its original vertex shader",twelve,()->{g.glEnable(3553);g.glBindTexture(3553,a);effect.begin();});
                effect.end();check(calls==12,"imported effect is an explicit ordinary fallback");
            }
            reset(g);int[] empty={999999,0};clear(g);long before=g.chunkDrawCalls();g.glCallLists(IntBuffer.wrap(empty));g.glCallLists(IntBuffer.allocate(0));check(g.chunkDrawCalls()==before,"missing/empty selected lists draw nothing");
            for(int mode:new int[]{4,5,6,7,8}){
                int h=first+86*3;for(int i=0;i<2;i++){g.beginOriginalChunk(h+i*3,3);g.glNewList(h+i*3,4864);g.glPushMatrix();g.glTranslatef(i*.2f,0,0);emit(t,i,mode);g.glPopMatrix();g.glEndList();g.finishOriginalChunk();}
                compare(g,"original primitive topology "+mode,new int[]{h,h+3},()->g.glEnable(GL11C.GL_BLEND));g.glDeleteLists(h,6);
            }
            check(g.terrainArenaBytes()<=256L*1024*1024&&g.terrainArenaMembers()>0,"original shared storage remains bounded");check(g.glGetError()==0,"original model batching is Core-valid");
        }finally{
            g.selectEffect(null);g.abortOriginalChunk();g.glDeleteLists(first,3*96);if(textureA!=0)g.glDeleteTextures(textureA);if(textureB!=0)g.glDeleteTextures(textureB);
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,framebuffer);g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,renderbuffer);GL30C.glDeleteFramebuffers(target);GL30C.glDeleteRenderbuffers(color);GL30C.glDeleteRenderbuffers(depth);
            g.glMatrixMode(5888);g.glPopMatrix();g.glMatrixMode(5889);g.glPopMatrix();g.glMatrixMode(5888);g.glPopAttrib();
        }
        check(g.cachedModels()==previousModels&&g.terrainArenaMembers()==previousMembers,"all original fixture members are independently retired");System.out.println("GAME_TERRAIN_BATCH_GPU_PASS checks="+checks+" exact-RGBA/depth/order/matrices/fallbacks/lifetimes");return checks;
    }
    private static void emit(OriginalChunkEmitter t,int color,int mode){t.beginMode(mode);t.lightmap(0x00b00080);t.normal(0,0,1);int corner=0;for(float[] xy:new float[][]{{-.42f,-.38f},{.42f,-.38f},{.42f,.38f},{-.42f,.38f}}){t.color((color*37+corner*41)%256,(color*71+corner*23+50)%256,(color*53+corner*11+90)%256,100+corner*35);t.uv(corner==1||corner==2?1:0,corner>=2?1:0);t.vertex(xy[0],xy[1],0);corner++;}t.draw();}
    private static int[] handles(int first,int count){int[] names=new int[count];for(int i=0;i<count;i++)names[i]=first+i*3;return names;}
    private static void reverse(int[] values){for(int i=0;i<values.length/2;i++){int t=values[i];values[i]=values[values.length-1-i];values[values.length-1-i]=t;}}
    private static long compare(GameRenderCommands g,String label,int[] names,Runnable setup){
        reset(g);clear(g);setup.run();for(int name:names)g.glCallList(name);byte[] expected=pixels(g),expectedDepth=depthPixels(g);float[] matrix=matrix(g),projection=projection(g),current=color(g);boolean depth=g.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        if(label.equals("twelve compatible original model transforms")){boolean visible=false;for(int i=0;i<expected.length;i+=4)if(expected[i]!=0||expected[i+1]!=0||expected[i+2]!=0){visible=true;break;}check(visible,"ordinary reference renders actual terrain pixels");}
        reset(g);clear(g);setup.run();IntBuffer selected=ByteBuffer.allocateDirect((names.length+2)*4).order(ByteOrder.nativeOrder()).asIntBuffer();selected.put(-1).put(names).put(-1);selected.position(1);selected.limit(names.length+1);
        long before=g.chunkDrawCalls();g.glCallLists(selected);long calls=g.chunkDrawCalls()-before;
        check(Arrays.equals(expected,pixels(g)),label+" exact RGBA");check(Arrays.equals(expectedDepth,depthPixels(g)),label+" exact depth buffer");check(Arrays.equals(matrix,matrix(g))&&Arrays.equals(projection,projection(g))&&Arrays.equals(current,color(g))&&depth==g.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),label+" final logical/raster state");check(selected.position()==1&&selected.limit()==names.length+1,label+" caller cursor");return calls;
    }
    private static void reset(GameRenderCommands g){g.selectEffect(null);g.glActiveTexture(33984);for(int capability:new int[]{3553,2896,2903,2977,32826,2912,3008,GL11C.GL_DEPTH_TEST,GL11C.GL_CULL_FACE,GL11C.GL_BLEND,GL11C.GL_STENCIL_TEST,GL11C.GL_SCISSOR_TEST})g.glDisable(capability);g.glColorMask(true,true,true,true);g.glDepthMask(true);g.glDepthFunc(GL11C.GL_LEQUAL);g.glBlendFunc(770,771);g.glFrontFace(GL11C.GL_CCW);g.glColor4f(1,1,1,1);g.glNormal3f(0,0,1);g.glShadeModel(7425);for(int unit=0;unit<2;unit++){g.glActiveTexture(33984+unit);g.glDisable(3553);g.glBindTexture(3553,0);g.glMatrixMode(5890);g.glLoadIdentity();}g.glActiveTexture(33984);g.glMatrixMode(5889);g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-2,2);g.glMatrixMode(5888);g.glLoadIdentity();}
    private static int texture(GameRenderCommands g,int variant){int texture=g.glGenTextures();g.glBindTexture(3553,texture);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);ByteBuffer pixels=ByteBuffer.allocateDirect(16);for(int i=0;i<4;i++)pixels.put((byte)(variant==0?255:100+i*30)).put((byte)(120+i*30)).put((byte)(variant==0?100+i*30:255)).put((byte)255);pixels.flip();g.glTexImage2D(3553,0,32856,2,2,0,6408,5121,pixels);return texture;}
    private static FloatBuffer floats(float... values){FloatBuffer b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(values).flip();return b;}
    private static float[] matrix(GameRenderCommands g){return value(g,2982,16);}private static float[] projection(GameRenderCommands g){return value(g,2983,16);}private static float[] color(GameRenderCommands g){return value(g,2816,4);}
    private static float[] value(GameRenderCommands g,int name,int count){FloatBuffer b=ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder()).asFloatBuffer();g.glGetFloat(name,b);float[] values=new float[count];b.get(values);return values;}
    private static byte[] pixels(GameRenderCommands g){ByteBuffer buffer=ByteBuffer.allocateDirect(64*64*4);g.glReadPixels(0,0,64,64,6408,5121,buffer);byte[] result=new byte[buffer.remaining()];buffer.get(result);return result;}
    private static byte[] depthPixels(GameRenderCommands g){ByteBuffer buffer=ByteBuffer.allocateDirect(64*64*4).order(ByteOrder.nativeOrder());g.glReadPixels(0,0,64,64,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT,buffer);byte[] result=new byte[buffer.remaining()];buffer.get(result);return result;}
    private static void clear(GameRenderCommands g){g.glClearColor(0,0,0,1);g.glClearDepth(1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT|GL11C.GL_STENCIL_BUFFER_BIT);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}check(rejected,"invalid original list rejected");}
}
