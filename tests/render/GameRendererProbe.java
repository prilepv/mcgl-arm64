package local.mcgl.render.tests;

import java.io.File;
import java.lang.reflect.*;
import java.nio.*;
import java.util.concurrent.atomic.AtomicReference;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Real original accumulator and effect API through the game adapter, in a Core context. */
public final class GameRendererProbe {
    private static int checks;
    public static void main(String[] args)throws Exception {
        for(int lifetime=0;lifetime<2;lifetime++) {
            System.setProperty("mcgl.terrain.materials",Boolean.toString(lifetime!=0));
            System.setProperty("mcgl.terrain.wideArena",Boolean.toString(lifetime!=0));
            System.setProperty("mcgl.terrain.textureArrays",Boolean.toString(lifetime!=0));
            System.setProperty("mcgl.graphics.unlitShaders",Boolean.toString(lifetime!=0));
            Display.setDisplayMode(new DisplayMode(300,220));Display.setTitle("MCGL — migrated game calls");MCGLCoreDisplay.create(new PixelFormat().withDepthBits(24).withStencilBits(8));
            GameRenderCommands g=RenderSystem.game();
            try {
                check(RenderSystem.current().profile()==RenderProfile.CORE_41,"Core game context");
                check(GLContext.getCapabilities().OpenGL13&&GLContext.getCapabilities().GL_ARB_framebuffer_object,"promoted game feature switches");
                check(g.glGetError()==0,"game raster initialization is Core-valid");
                g.glLineWidth(2);check(g.glGetError()==0,"original two-pixel game outlines are Core-valid");g.glLineWidth(1);
                int fbo=GL30C.glGenFramebuffers(),color=GL30C.glGenRenderbuffers();
                g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,fbo);g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,color);
                g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL11C.GL_RGBA8,64,64);
                g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL30C.GL_RENDERBUFFER,color);
                check(g.GL30_glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)==GL30C.GL_FRAMEBUFFER_COMPLETE,"game framebuffer adapters");
                g.glViewport(0,0,64,64);g.glDisable(GL11C.GL_DITHER);g.glDisable(GL11C.GL_DEPTH_TEST);g.glDisable(GL11C.GL_CULL_FACE);g.glDisable(GL11C.GL_BLEND);
                g.glDisable(3553);g.glDisable(2896);g.glDisable(3008);g.glShadeModel(7424);clear(g);
                OriginalChunkEmitter t=new OriginalChunkEmitter(new File("bin/mcgl.jar"),1024);
                t.begin();t.color(48,96,160,255);t.quad(-.8,-.8,.8,.8,0);check(t.draw()==128,"unbound original accumulator byte count");
                pixel(32,32,48,96,160,"actual game accumulator uses Core by default");check(g.rawBatches()==1&&g.drawCalls()==1,"game drain counters");
                int model=g.glGenLists(3);clear(g);g.glNewList(model,4864);g.glPushMatrix();g.glTranslatef(.3f,0,0);
                t.begin();t.quad(-.4,-.6,.4,.6,0);t.draw();g.glPopMatrix();g.glEndList();
                pixel(32,32,0,0,0,"compiling model does not draw");
                g.glColor3f(.2f,.6f,.8f);g.glCallList(model);pixel(40,32,51,153,204,"cached raw geometry inherits draw-time color");
                FloatBuffer matrix=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();g.glGetFloat(2982,matrix);check(matrix.get(12)==0,"cached matrix scope restored");
                clear(g);g.glColor3f(.8f,.2f,.4f);g.glCallList(model);pixel(40,32,204,51,102,"repeated model uses new color");
                g.glNewList(model+1,4864);g.glBegin(7);vertex(g,-.8f,-.8f);vertex(g,.8f,-.8f);g.glColor3f(.2f,.4f,.6f);vertex(g,.8f,.8f);vertex(g,-.8f,.8f);g.glEnd();g.glEndList();
                clear(g);g.glColor3f(.9f,.8f,.7f);g.glCallList(model+1);pixel(16,32,51,102,153,"cached immediate mixed inherited attributes first half");pixel(48,32,51,102,153,"cached immediate flat fourth vertex second half");
                FloatBuffer current=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();g.glGetFloat(2816,current);check(Math.abs(current.get(0)-.2f)<.0001,"cached immediate final current color");
                g.glNewList(model+2,4864);g.glBegin(7);vertex(g,-.8f,-.8f);vertex(g,.8f,-.8f);vertex(g,.8f,.8f);vertex(g,-.8f,.8f);g.glEnd();g.glEndList();
                clear(g);g.glColor3f(.1f,.7f,.3f);g.glCallList(model+2);pixel(32,32,26,179,77,"immediate model with no colors inherits caller");
                g.glPushAttrib(0x800);g.glViewport(1,2,3,4);g.glColor3f(1,0,0);g.glPopAttrib();IntBuffer view=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();g.glGetInteger(GL11C.GL_VIEWPORT,view);
                check(view.get(0)==0&&view.get(1)==0&&view.get(2)==64&&view.get(3)==64,"FBO viewport group restoration");g.glGetFloat(2816,current);check(current.get(0)==1&&current.get(1)==0,"viewport group does not restore current attributes");
                g.glPushAttrib(0x4000|0x2000|1);g.glEnable(GL11C.GL_BLEND);g.glColorMask(false,false,false,false);g.glColor3f(0,1,0);g.glPopAttrib();check(!g.glIsEnabled(GL11C.GL_BLEND),"native raster enable restored");
                clear(g);g.glCallList(model+2);pixel(32,32,255,0,0,"native color write mask and current color restored");
                long before=g.rawBatches();ChunkMeshBuilder builder=new ChunkMeshBuilder(0,0,0,4);t.bind(builder,new ChunkMaterial("fixture",0,ShaderLibrary.Material.COLOR,ShaderLibrary.AlphaTest.DISABLED,0));
                t.begin();t.quad(-.5,-.5,.5,.5,0);t.draw();t.unbind();check(builder.finish().vertexCount==4&&g.rawBatches()==before,"explicit stage 9 sink remains available");
                lines(g);textures(g);terrain(g,t);terrainFan(g,t);terrainBatchEdges(g,t);terrainRegions(g,t);terrainVisibility(g);terrainSwitches(g,t);checks+=GameChunkTexturesProbe.run(g);checks+=OriginalChunkCacheProbe.run(g);dynamicMeshes(g,t);originalChunk(g);originalEffects();
                checks+=GameTextProbe.run(g);checks+=GameTerrainBatchProbe.run(g);checks+=GameTerrainRecoveryProbe.run(g);
                checks+=GameModelBatchProbe.run(g);
                checks+=GameTerrainMaterialsProbe.run(g);
                AtomicReference<Throwable> failure=new AtomicReference<Throwable>();Thread foreign=new Thread(()->{try{g.glColor3f(1,1,1);}catch(Throwable e){failure.set(e);}});foreign.start();foreign.join();check(failure.get() instanceof IllegalStateException,"retained game facade enforces owner");
                int cached=g.cachedModels();check(cached==3&&g.cachedModelDraws()>=5,"GPU model registry connected");g.glDeleteLists(model,3);check(g.cachedModels()==0,"model deletion retires meshes");
                check(g.glGetError()==0,"all migrated game calls are Core-valid");g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,0);GL30C.glDeleteFramebuffers(fbo);GL30C.glDeleteRenderbuffers(color);Display.update();
            }finally{Display.destroy();}
            boolean stale=false;try{g.glColor3f(1,1,1);}catch(IllegalStateException expected){stale=true;}check(stale,"retained game facade invalidated");
        }
        Display.shutdown();System.out.println("GAME_RENDERER_GPU_PASS checks="+checks);
    }
    private static void lines(GameRenderCommands g) {
        clear(g);g.glColor3f(1,0,0);g.glLineWidth(4);g.glEnable(GL11C.GL_CULL_FACE);g.glEnable(GL11C.GL_POLYGON_OFFSET_FILL);
        g.glBegin(1);g.glVertex3f(0,-.8f,0);g.glVertex3f(0,.8f,0);g.glEnd();
        for(int x=30;x<34;x++)pixel(x,32,255,0,0,"four-pixel Core outline coverage");pixel(29,32,0,0,0,"outline left boundary");pixel(34,32,0,0,0,"outline right boundary");
        check(g.glIsEnabled(GL11C.GL_CULL_FACE)&&g.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL),"line triangles do not inherit polygon culling/offset and restore caller state");
        g.glDisable(GL11C.GL_CULL_FACE);g.glDisable(GL11C.GL_POLYGON_OFFSET_FILL);
        int handle=g.glGenLists(1);g.glNewList(handle,4864);g.glBegin(1);g.glVertex3f(-.8f,0,0);g.glVertex3f(.8f,0,0);g.glEnd();g.glEndList();
        clear(g);g.glLineWidth(6);g.glCallList(handle);pixel(32,29,255,0,0,"cached outline inherits draw-time width");pixel(32,28,0,0,0,"cached outline outer boundary");
        clear(g);g.glLineWidth(2);g.glCallList(handle);pixel(32,31,255,0,0,"cached outline narrower redraw");pixel(32,30,0,0,0,"cached outline does not retain old width");
        g.glPushAttrib(4);g.glLineWidth(7);g.glPopAttrib();FloatBuffer width=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer();g.glGetFloat(GL11C.GL_LINE_WIDTH,width);check(width.get(0)==2,"line attribute group restores logical width");
        g.glDeleteLists(handle,1);g.glLineWidth(1);
    }
    private static void textures(GameRenderCommands g) {
        int a=g.glGenTextures(),b=g.glGenTextures();g.glBindTexture(3553,a);g.glTexParameteri(3553,10241,9728);
        g.glPushAttrib(0x40000);g.glTexParameteri(3553,10241,9729);g.glBindTexture(3553,b);g.glTexParameteri(3553,10241,9728);g.glPopAttrib();
        check(GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)==a&&GL11C.glGetTexParameteri(3553,10241)==9728,"texture scope restores bound object and its filter");
        g.glBindTexture(3553,b);check(GL11C.glGetTexParameteri(3553,10241)==9728,"texture scope leaves initially unbound object changes");
        g.glPushAttrib(0x800);g.glTexParameteri(3553,10241,9729);g.glPopAttrib();check(GL11C.glGetTexParameteri(3553,10241)==9729,"non-texture group does not restore filter");
        g.glDeleteTextures(a);g.glDeleteTextures(b);check(GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)==0,"deleted texture binding clears logical and native state");
    }
    private static void originalChunk(GameRenderCommands g)throws Exception {
        Class<?> type=Class.forName("net.A.U.H");int handle=g.glGenLists(3);
        Object chunk=type.getConstructors()[0].newInstance(null,new java.util.ArrayList<Object>(),0,0,0,handle);
        check(chunk instanceof GameChunkHandle&&((GameChunkHandle)chunk).mcglChunkHandle()==handle,"actual H constructor exposes its typed renderer handle");
        type.getMethod("Ö00000").invoke(chunk);check(g.residentChunks()==0,"actual H clean rebuild guard does not publish an empty chunk");
        type.getField("õ00000").setBoolean(chunk,true);boolean failed=false;
        try{type.getMethod("Ö00000").invoke(chunk);}catch(InvocationTargetException expected){failed=expected.getCause() instanceof NullPointerException;}
        check(failed&&g.residentChunks()==0,"actual H failed world build aborts its pending publication");
        check(!Class.forName("net.A.C.voidfloat").getField("floatclass").getBoolean(null),"actual H failure restores shared build flag");
        type.getMethod("Ô00000").invoke(chunk);g.glDeleteLists(handle,3);
        g.beginChunk(handle,0,0,0,2);g.finishChunk();g.glDeleteLists(handle,3);check(g.residentChunks()==0,"renderer accepts a fresh rebuild after original H failure");
    }
    private static void terrain(GameRenderCommands g,OriginalChunkEmitter t) {
        int first=g.glGenLists(6);g.glMatrixMode(5889);g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-10,10);g.glMatrixMode(5888);
        g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(GL11C.GL_SRC_ALPHA,GL11C.GL_ONE_MINUS_SRC_ALPHA);
        g.beginChunk(first,0,0,0,2);g.glNewList(first+1,4864);g.glPushMatrix();g.glTranslatef(0,0,0);g.glDepthMask(false);
        t.begin();t.color(255,0,0,128);t.quad(-.8,-.8,.8,.8,-.6);t.color(0,0,255,128);t.quad(-.8,-.8,.8,.8,.6);t.draw();g.glPopMatrix();g.glEndList();g.finishChunk();
        g.beginChunk(first+3,16,0,0,2);g.glNewList(first+4,4864);g.glPushMatrix();g.glTranslatef(16,0,0);g.glDepthMask(false);
        t.begin();t.color(0,255,0,128);t.quad(-16.8,-.8,-15.2,.8,0);t.draw();g.glPopMatrix();g.glEndList();g.finishChunk();
        GameChunkHandle a=()->first,b=()->first+3;
        check(g.residentChunks()==2,"game rebuilds published through chunk registry");
        clear(g);long calls=g.chunkDrawCalls();g.drawChunks(java.util.Arrays.asList(a,b),1,0,0,2);pixel(32,32,32,64,128,"scheduler selection globally sorts chunk faces and materials");
        check(g.chunkDrawCalls()==calls+1,"compatible interleaved chunk faces use one combined draw");
        long uploads=g.chunkIndexUploads();clear(g);g.drawChunks(java.util.Arrays.asList(a,b),1,0,0,-2);pixel(32,32,128,64,32,"game camera reverses transparency order");check(g.chunkIndexUploads()==uploads+1,"game transparency updates only changed EBO");
        uploads=g.chunkIndexUploads();g.drawChunks(java.util.Arrays.asList(a,b),1,0,0,-2);check(g.chunkIndexUploads()==uploads,"stationary game camera does not upload indices");
        clear(g);g.drawChunks(java.util.Collections.singletonList(b),1,0,0,2);pixel(32,32,0,128,0,"scheduler visibility excludes other resident chunks");
        clear(g);g.drawChunks(java.util.Collections.emptyList(),1,0,0,2);pixel(32,32,0,0,0,"empty scheduler selection draws no terrain");
        g.beginChunk(first,0,0,0,2);g.finishChunk();clear(g);g.drawChunks(java.util.Collections.singletonList(a),1,0,0,2);pixel(32,32,0,0,0,"empty rebuild retires old terrain");
        g.glDeleteLists(first,6);check(g.residentChunks()==0,"original disposal handle range unloads modern terrain");
        g.glDisable(GL11C.GL_BLEND);g.glDepthMask(true);g.glMatrixMode(5889);g.glLoadIdentity();g.glMatrixMode(5888);
    }
    private static void originalEffects()throws Exception {
        Class<?> type=Class.forName("net.A.U.LA");Object effect=type.getConstructor(String.class).newInstance("/shader/default");
        int sampler=(Integer)type.getMethod("Ö00000",String.class).invoke(effect,"colorMap");
        type.getMethod("Ò00000").invoke(effect);type.getMethod("o00000",int.class,int.class).invoke(effect,sampler,0);
        type.getMethod("o00000").invoke(effect);check(true,"original effect wrapper creates typed Core program and sets sampler");
        Object matrixEffect=type.getConstructor(String.class).newInstance("/shader/color_white");int matrix=(Integer)type.getMethod("Ó00000",String.class).invoke(matrixEffect,"inverse_view");
        float[] rowMajor={1,0,0,2,0,1,0,3,0,0,1,4,0,0,0,1};type.getMethod("o00000",int.class,float[].class).invoke(matrixEffect,matrix,rowMajor);check(true,"original row-major effect matrix setter");
    }
    private static void terrainFan(GameRenderCommands g,OriginalChunkEmitter t) {
        int handle=g.glGenLists(3);GameChunkHandle chunk=()->handle;
        g.glShadeModel(7425);g.glEnable(GL11C.GL_CULL_FACE);g.glFrontFace(GL11C.GL_CCW);g.glCullFace(GL11C.GL_BACK);
        g.beginChunk(handle,0,0,0,2);g.glNewList(handle,4864);
        t.begin();t.draw();t.beginMode(6);t.color(80,160,240,255);t.quad(-.8,-.8,.8,.8,0);check(t.draw()==128,"actual original slope fan is captured inside the terrain pass");t.begin();t.draw();
        g.glEndList();g.finishChunk();clear(g);g.drawChunks(java.util.Collections.singletonList(chunk),0,0,0,0);
        pixel(44,20,80,160,240,"slope fan first triangle uses Core geometry");pixel(20,44,80,160,240,"slope fan second triangle retains winding");
        g.glDeleteLists(handle,3);g.glDisable(GL11C.GL_CULL_FACE);g.glShadeModel(7424);
    }
    private static void terrainBatchEdges(GameRenderCommands g,OriginalChunkEmitter t) {
        g.glMatrixMode(5889);g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-10,10);g.glMatrixMode(5888);g.glColor3f(1,1,1);
        g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(GL11C.GL_SRC_ALPHA,GL11C.GL_ONE_MINUS_SRC_ALPHA);
        int first=g.glGenLists(9);java.util.List<GameChunkHandle> selected=new java.util.ArrayList<GameChunkHandle>();
        for(int i=0;i<3;i++){final int handle=first+i*3;selected.add(()->handle);g.beginChunk(handle,i*16,0,0,2);g.glNewList(handle+1,4864);g.glPushMatrix();g.glTranslatef(i*16,0,0);g.glDepthMask(false);if(i==1)g.glColor3f(.2f,.3f,.4f);t.begin();t.color(i==0?255:0,i==1?255:0,i==2?255:0,128);t.quad(-i*16-.8,-.8,-i*16+.8,.8,(i-1)*.6);t.draw();g.glPopMatrix();g.glEndList();g.finishChunk();}
        clear(g);long draws=g.chunkDrawCalls();g.drawChunks(selected,1,0,0,2);pixel(32,32,32,64,128,"combined and incompatible material runs preserve global transparency interleaving");check(g.chunkDrawCalls()==draws+3,"combined ranges do not jump across incompatible material runs");
        FloatBuffer current=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();g.glGetFloat(2816,current);check(current.get(0)==1&&current.get(1)==1,"incompatible material state is restored before the next combined range");g.glDeleteLists(first,9);
        first=g.glGenLists(6);selected.clear();int origin=1<<24,y=-2048,z=2048;
        for(int i=0;i<2;i++){final int handle=first+i*3;int x=origin+i*16;selected.add(()->handle);g.beginChunk(handle,x,y,z,2);g.glNewList(handle+1,4864);g.glPushMatrix();g.glTranslatef(x&1023,y,z&1023);g.glDepthMask(false);t.begin();t.color(i==0?255:0,0,i==1?255:0,128);t.quad(-i*16-.8,-.8,-i*16+.8,.8,i==0?-.6:.6);t.draw();g.glPopMatrix();g.glEndList();g.finishChunk();}
        clear(g);draws=g.chunkDrawCalls();g.drawChunks(selected,1,origin,y,z+2);pixel(32,32,64,0,128,"combined region-relative transforms preserve distant and negative chunk coordinates");check(g.chunkDrawCalls()==draws+1,"same distant region still batches across original chunk origins");
        g.glDeleteLists(first,6);g.glDisable(GL11C.GL_BLEND);g.glDepthMask(true);g.glMatrixMode(5889);g.glLoadIdentity();g.glMatrixMode(5888);
    }
    private static void vertex(GameRenderCommands g,float x,float y){g.glVertex3f(x,y,0);}
    private static void dynamicMeshes(GameRenderCommands g,OriginalChunkEmitter t) {
        g.glDisable(3553);g.glDisable(GL11C.GL_BLEND);g.glMatrixMode(5888);g.glLoadIdentity();g.glColor3f(1,1,1);
        long before=g.transientMeshCreations();
        for(int i=0;i<12;i++){clear(g);t.begin();t.color(i%2==0?200:30,80,120,255);t.quad(-.8,-.8,.8,.8,0);t.draw();pixel(32,32,i%2==0?200:30,80,120,"reused transient geometry uploads current vertex colors without retaining producer data");}
        check(g.transientMeshCreations()-before<=3,"stable transient topology warms at most three GPU meshes");before=g.transientMeshCreations();
        for(int i=0;i<12;i++){t.begin();t.color(160,40,20,255);t.quad(-.8,-.8,.8,.8,0);t.draw();}
        check(g.transientMeshCreations()==before,"warmed transient geometry performs no create/delete churn");
        // Same vertex/count/type but different fan/strip indices must never alias in the cache.
        for(int mode:new int[]{6,5,6,5}){clear(g);t.beginMode(mode);t.color(40,120,200,255);t.quad(-.8,-.8,.8,.8,0);t.draw();pixel(20,20,40,120,200,"fan/strip cached index identity is retained");}
        for(int count=1;count<=32;count++){g.glBegin(0);for(int i=0;i<count;i++)g.glVertex3f(0,0,0);g.glEnd();}
        check(g.transientMeshCount()<=72&&g.transientMeshBytes()<=8L*1024*1024,"transient LRU is bounded by both count and GPU bytes");
        // A geometry above the single-entry budget keeps scoped ownership and cannot enlarge the cache.
        int[] large=new int[110000*8];for(int i=0;i<110000;i++)large[i*8+5]=-1;
        int live=g.transientMeshCount();long bytes=g.transientMeshBytes();g.raw(large,large.length,110000,0,false,true,true,false,false,false);
        check(g.transientMeshCount()==live&&g.transientMeshBytes()==bytes,"oversized transient submission is not retained");
        clear(g);t.begin();t.color(20,160,80,255);t.quad(-.8,-.8,.8,.8,0);t.draw();pixel(32,32,20,160,80,"quad cache repopulates correctly after eviction");
    }
    private static void terrainRegions(GameRenderCommands g,OriginalChunkEmitter t) {
        g.glMatrixMode(5889);g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-10,10);g.glMatrixMode(5888);g.glLoadIdentity();g.glColor3f(1,1,1);
        int white=g.glGenTextures();g.glBindTexture(3553,white);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);
        ByteBuffer texel=ByteBuffer.allocateDirect(4);texel.putInt(-1).flip();g.glTexImage2D(3553,0,32856,1,1,0,6408,5121,texel);g.glBindTexture(3553,0);
        for(int origin:new int[]{0,1024,-1024,1<<24,-(1<<24)})for(int pass=0;pass<3;pass++) {
            if(pass==0)g.glDisable(GL11C.GL_BLEND);else{g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(GL11C.GL_SRC_ALPHA,GL11C.GL_ONE_MINUS_SRC_ALPHA);}
            if(pass==2)g.glEnable(3553);else g.glDisable(3553);
            int first=g.glGenLists(6);java.util.List<GameChunkHandle> selected=new java.util.ArrayList<GameChunkHandle>();
            for(int i=0;i<2;i++) {
                final int handle=first+i*3;int offset=i==0?-16:0,x=origin+offset,y=origin+offset,z=origin+offset;selected.add(()->handle);
                g.beginChunk(handle,x,y,z,3);g.glNewList(handle+pass,4864);g.glPushMatrix();g.glTranslatef(x&1023,y,z&1023);
                g.glTranslatef(-8,-8,-8);g.glScalef(1.000001f,1.000001f,1.000001f);g.glTranslatef(8,8,8);
                if(pass!=0)g.glDepthMask(false);if(pass==2){g.glBindTexture(3553,0);g.glBindTexture(3553,white);}
                t.begin();t.color(i==0?255:0,0,i==1?255:0,pass==0?255:128);t.quad(-offset-.8,-offset-.8,-offset+.8,-offset+.8,(i==0?-.6:.6)-offset);t.draw();
                g.glPopMatrix();g.glEndList();g.finishChunk();
            }
            clear(g);long draws=g.chunkDrawCalls();g.drawChunks(selected,pass,origin,origin,origin+2);
            pixel(32,32,pass==0?0:64,0,pass==0?255:128,"split-coordinate batches preserve pixels across all three region boundaries, including distant negative origins: origin="+origin+" pass="+pass);
            check(g.chunkDrawCalls()==draws+1,"compatible opaque/transparent/textured parts cross region boundaries in one draw");
            long uploads=g.chunkIndexUploads();clear(g);g.drawChunks(selected,pass,origin-.125,origin,origin+2);
            pixel(32,32,pass==0?0:64,0,pass==0?255:128,"camera crossing a high-coordinate boundary preserves low coordinate precision");
            check(g.chunkIndexUploads()==uploads,"camera region change alone does not rewrite vertex or index membership");
            check(GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)==0,"collapsed texture binding restores the caller's sampler");
            g.glDeleteLists(first,6);
        }
        g.glDeleteTextures(white);g.glDisable(3553);g.glDisable(GL11C.GL_BLEND);g.glDepthMask(true);g.glMatrixMode(5889);g.glLoadIdentity();g.glMatrixMode(5888);
    }
    private static void clear(GameRenderCommands g){g.glClearColor(0,0,0,1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT);}
    private static void terrainSwitches(GameRenderCommands g,OriginalChunkEmitter t) {
        g.glDisable(3553);g.glDisable(2896);g.glDisable(3008);g.glDisable(2912);g.glDisable(2903);
        g.glMatrixMode(5889);g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-10,10);g.glMatrixMode(5888);g.glLoadIdentity();
        g.glEnable(GL11C.GL_CULL_FACE);g.glFrontFace(GL11C.GL_CW);g.glEnable(2977);
        for(int pass=0;pass<2;pass++) {
            if(pass==0)g.glDisable(GL11C.GL_BLEND);else{g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(GL11C.GL_SRC_ALPHA,GL11C.GL_ONE_MINUS_SRC_ALPHA);}
            int first=g.glGenLists(6);java.util.List<GameChunkHandle> selected=new java.util.ArrayList<GameChunkHandle>();
            for(int chunk=0;chunk<2;chunk++) {
                final int handle=first+chunk*3;selected.add(()->handle);g.beginChunk(handle,chunk*16,0,0,2);g.glNewList(handle+pass,4864);g.glPushMatrix();g.glTranslatef(chunk*16,0,0);
                if(chunk==1){g.glEnable(GL11C.GL_CULL_FACE);g.glEnable(2896);g.glEnable(2912);g.glEnable(3008);}
                g.glDisable(GL11C.GL_CULL_FACE);g.glDisable(2896);g.glDisable(2912);g.glDisable(3008);g.glDisable(2977);g.glDisable(3553);
                t.begin();t.color(chunk==0?255:0,chunk==1?255:0,0,pass==0?255:128);t.quad(-chunk*16-.8,-.8,-chunk*16+.8,.8,chunk==0?-.6:.6);t.draw();
                g.glPopMatrix();g.glEndList();g.finishChunk();
            }
            clear(g);long before=g.chunkDrawCalls();g.drawChunks(selected,pass,0,0,2);
            pixel(32,32,pass==0?0:64,pass==0?255:128,0,"independent final enable flags preserve opaque/transparent pixels");
            check(g.chunkDrawCalls()==before+1,"equivalent final switches batch despite redundant intermediate toggles");
            check(g.glIsEnabled(GL11C.GL_CULL_FACE)&&g.glIsEnabled(2977)&&!g.glIsEnabled(3553)&&!g.glIsEnabled(2896)&&!g.glIsEnabled(2912)&&!g.glIsEnabled(3008),"batch switches restore inherited raster and material flags");
            g.glDeleteLists(first,6);
        }
        g.glDisable(GL11C.GL_BLEND);
        for(boolean colorMaterial:new boolean[]{false,true}) {
            int first=g.glGenLists(6);java.util.List<GameChunkHandle> selected=new java.util.ArrayList<GameChunkHandle>();
            for(int chunk=0;chunk<2;chunk++) {
                final int handle=first+chunk*3;selected.add(()->handle);g.beginChunk(handle,chunk*16,0,0,2);g.glNewList(handle,4864);g.glPushMatrix();g.glTranslatef(chunk*16,0,0);
                g.glDisable(GL11C.GL_CULL_FACE);if(colorMaterial){g.glEnable(2903);g.glDisable(2903);}else if(chunk==1)g.glEnable(GL11C.GL_CULL_FACE);
                t.begin();t.color(chunk==0?255:0,chunk==1?255:0,0,255);t.quad(-chunk*16-.8,-.8,-chunk*16+.8,.8,0);t.draw();g.glPopMatrix();g.glEndList();g.finishChunk();
            }
            clear(g);long before=g.chunkDrawCalls();g.drawChunks(selected,0,0,0,0);
            pixel(32,32,colorMaterial?0:255,colorMaterial?255:0,0,"incompatible or side-effecting switches keep their original order");
            check(g.chunkDrawCalls()==before+2,"different switches and color-material tracking do not merge");
            check(!g.glIsEnabled(2903)&&g.glIsEnabled(GL11C.GL_CULL_FACE),"individual switch paths restore caller state");g.glDeleteLists(first,6);
        }
        g.glDisable(GL11C.GL_CULL_FACE);g.glFrontFace(GL11C.GL_CCW);g.glDisable(2977);g.glMatrixMode(5889);g.glLoadIdentity();g.glMatrixMode(5888);
    }
    private static void terrainVisibility(GameRenderCommands g) {
        g.glDisable(3553);g.glDisable(GL11C.GL_BLEND);g.glMatrixMode(5888);g.glLoadIdentity();g.glColor3f(1,1,1);
        for(int pass=0;pass<2;pass++) {
            int first=g.glGenLists(6);GameChunkHandle a=()->first,b=()->first+3;
            for(int chunk=0;chunk<2;chunk++) {
                int vertices=chunk==0?4:65536;int[] raw=new int[vertices*8];float[] xy={-.8f,-.8f,.8f,-.8f,.8f,.8f,-.8f,.8f};
                for(int v=0;v<vertices;v++){raw[v*8]=Float.floatToRawIntBits(xy[(v%4)*2]-chunk*16);raw[v*8+1]=Float.floatToRawIntBits(xy[(v%4)*2+1]);raw[v*8+5]=chunk==0?0xff00ff00:0xff0000ff;}
                int handle=first+chunk*3;g.beginChunk(handle,chunk*16,0,0,2);g.glNewList(handle+pass,4864);g.glPushMatrix();g.glTranslatef(chunk*16,0,0);
                g.raw(raw,raw.length,vertices,7,false,true,true,false,false,false);g.glPopMatrix();g.glEndList();g.finishChunk();
            }
            clear(g);g.drawChunks(java.util.Arrays.asList(a,b),pass,0,0,0);
            for(int turn=0;turn<4;turn++) {
                clear(g);long before=g.chunkDrawCalls();g.drawChunks(java.util.Collections.singletonList(turn%2==0?a:b),pass,0,0,0);
                pixel(32,32,turn%2==0?0:255,turn%2==0?255:0,0,"changing visibility preserves UINT32 indices and excludes hidden padded geometry");
                check(g.chunkDrawCalls()==before+1,"sparse visibility stays one combined draw");
            }
            clear(g);g.drawChunks(java.util.Collections.emptyList(),pass,0,0,0);pixel(32,32,0,0,0,"retained invisible combined storage draws nothing");
            g.glDeleteLists(first,6);check(g.residentChunks()==0,"visibility fixture unloads all sources");
        }
    }
    private static void pixel(int x,int y,int r,int g,int b,String message){ByteBuffer p=ByteBuffer.allocateDirect(4);GL11C.glReadPixels(x,y,1,1,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,p);int ar=p.get(0)&255,ag=p.get(1)&255,ab=p.get(2)&255;check(Math.abs(ar-r)<=2&&Math.abs(ag-g)<=2&&Math.abs(ab-b)<=2,message+" got="+ar+","+ag+","+ab);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
