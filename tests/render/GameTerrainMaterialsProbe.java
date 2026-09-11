package local.mcgl.render.tests;

import java.io.File;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** The same original client geometry is rendered individually, with the prior
 * matrix-only batch, and with material tables. No world/account is loaded. */
public final class GameTerrainMaterialsProbe {
    private static int checks;
    private static final String FLAG="mcgl.terrain.materials";
    private static final int SIZE=64,COUNT=4096;
    public static void main(String[] args)throws Exception {
        Display.setDisplayMode(new DisplayMode(300,220));Display.setTitle("MCGL material validation");
        MCGLCoreDisplay.create(new PixelFormat().withDepthBits(24).withStencilBits(8));
        try{run(RenderSystem.game());}finally{Display.destroy();Display.shutdown();}
    }
    public static int run(GameRenderCommands g)throws Exception {
        checks=0;String previous=System.getProperty(FLAG),previousArrays=System.getProperty("mcgl.terrain.textureArrays"),previousUnlit=System.getProperty("mcgl.graphics.unlitShaders");
        int first=g.glGenLists((COUNT+4)*3),models=g.cachedModels(),members=g.terrainArenaMembers();
        int framebuffer=GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING),renderbuffer=GL11C.glGetInteger(GL30C.GL_RENDERBUFFER_BINDING);
        int target=GL30C.glGenFramebuffers(),color=GL30C.glGenRenderbuffers(),depth=GL30C.glGenRenderbuffers();int[] textures=new int[10];
        int[] savedSpare=spares();
        g.glPushAttrib(-1);g.glMatrixMode(5889);g.glPushMatrix();g.glMatrixMode(5888);g.glPushMatrix();
        try{
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,target);g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,color);
            g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL11C.GL_RGBA8,SIZE,SIZE);g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL30C.GL_RENDERBUFFER,color);
            g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,depth);g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL30C.GL_DEPTH24_STENCIL8,SIZE,SIZE);
            g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_DEPTH_STENCIL_ATTACHMENT,GL30C.GL_RENDERBUFFER,depth);
            check(g.GL30_glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)==GL30C.GL_FRAMEBUFFER_COMPLETE,"material test target");
            g.glViewport(0,0,SIZE,SIZE);reset(g);
            for(int i=0;i<textures.length;i++)textures[i]=texture(g,i);
            for(int i=0;i<8;i++){GL13C.glActiveTexture(33988+i);GL11C.glBindTexture(3553,textures[9-i]);}GL13C.glActiveTexture(33984);
            uniformArrays();
            OriginalChunkEmitter t=new OriginalChunkEmitter(new File("bin/mcgl.jar"),1024);
            for(int i=0;i<COUNT;i++){
                int handle=first+i*3;g.beginOriginalChunk(handle,3);g.glNewList(handle,4864);
                g.glPushMatrix();g.glTranslatef((i%7)*.13f-.39f,(i%5)*.11f-.22f,(i%9)*.04f-.16f);
                emit(t,i,i<32||i>=96?15:i%16,i>=96?(i/12%2==0?4:7):(i%3==0?4:7));g.glPopMatrix();g.glEndList();g.finishOriginalChunk();
            }
            int[] twelve=handles(first,12),mixed=handles(first+32*3,32),many=handles(first,96);
            long calls=compare(g,"alternating primary textures",twelve,()->textured(g,textures[0]),()->sequence(g,twelve,textures,2));
            check(calls==1,"two textures share one original ordered submission");
            calls=compare(g,"mixed missing/present attributes",mixed,()->textured(g,textures[0]),()->sequence(g,mixed,textures,2));
            check(calls==1,"32 attribute/matrix entries remain in one page and batch");
            compare(g,"untextured material tables",mixed,()->{},()->sequence(g,mixed,textures,4));
            for(int shade:new int[]{7424,7425})for(int style=0;style<5;style++){
                final int transform=style;
                compare(g,"transparent flat/smooth texture transform "+shade+"/"+style,mixed,()->{
                    textured(g,textures[0]);g.glEnable(GL11C.GL_BLEND);g.glShadeModel(shade);
                    g.glMatrixMode(5890);
                    if(transform==0){g.glScalef(12,9,1);g.glTranslatef(.13f,-.07f,0);}
                    if(transform==1)g.glLoadMatrix(floats(.9f,0,0,.19f,0,.8f,0,-.13f,0,0,1,0,.17f,-.11f,0,1));
                    if(transform==2)g.glLoadMatrix(floats(.9f,0,0,0,0,.8f,0,0,0,0,1,0,.17f,-.11f,0,-1));
                    g.glMatrixMode(5888);
                    if(transform==3){g.glTranslated(-(1<<24),1<<24,-8192);g.glTranslated(1<<24,-(1<<24),8192);g.glScalef(-.8f,.9f,1);}
                    if(transform==4){g.glMatrixMode(5889);g.glLoadMatrix(floats(1.1f,0,0,0,0,1.1f,0,0,0,0,1,-.3f,0,0,0,1));g.glMatrixMode(5888);g.glRotatef(27,1,1,0);}
                },()->sequence(g,mixed,textures,8));
            }
            compare(g,"lightmap fog depth alpha test",mixed,()->{
                textured(g,textures[0]);g.glEnable(2912);g.glFogi(2917,9729);g.glFogf(2915,0);g.glFogf(2916,1);g.glFog(2918,floats(.2f,.4f,.7f,1));
                g.glEnable(GL11C.GL_DEPTH_TEST);g.glEnable(3008);g.glAlphaFunc(516,.4f);g.glColorMask(true,false,true,true);
                g.glActiveTexture(33985);g.glEnable(3553);g.glBindTexture(3553,textures[9]);g.glMatrixMode(5890);g.glScalef(1/240f,1/240f,1);g.glMatrixMode(5888);g.glActiveTexture(33984);
            },()->sequence(g,mixed,textures,8));
            calls=compare(g,"nine texture overflow",twelve,()->textured(g,textures[0]),()->sequence(g,twelve,textures,9));check(calls==6,"third texture splits the bounded two-texture table");
            compare(g,"palette collisions over 96 unique members",many,()->{textured(g,textures[0]);g.glEnable(GL11C.GL_BLEND);},()->sequence(g,many,textures,8));
            int[] duplicate={first,first,first,first+3,first,first+3};
            calls=compare(g,"same tag and matrix with changing texture",duplicate,()->{textured(g,textures[0]);g.glEnable(GL11C.GL_BLEND);},()->sequence(g,duplicate,textures,2));check(calls>1,"tag cannot change texture while occupied");
            int[] reverse=mixed.clone();for(int i=0;i<reverse.length/2;i++){int v=reverse[i];reverse[i]=reverse[reverse.length-1-i];reverse[reverse.length-1-i]=v;}
            compare(g,"reverse transparent order",reverse,()->{textured(g,textures[0]);g.glEnable(GL11C.GL_BLEND);},()->sequence(g,reverse,textures,8));
            int[] repeated=new int[513];Arrays.fill(repeated,first);
            calls=compare(g,"256 draw capacity plus singleton tail",repeated,()->textured(g,textures[0]),()->sequence(g,repeated,textures,1));check(calls==3,"all 513 submissions including tail retained");
            compare(g,"pending singleton followed by new binding",new int[]{first},()->textured(g,textures[0]),()->{g.glCallList(first);g.glBindTexture(3553,textures[1]);});
            compare(g,"pending zero texture singleton",new int[]{first},()->textured(g,0),()->{g.glCallList(first);g.glBindTexture(3553,textures[1]);});
            compare(g,"zero and nonzero texture table",twelve,()->textured(g,0),()->{for(int i=0;i<twelve.length;i++){g.glBindTexture(3553,i%2==0?0:textures[0]);g.glCallList(twelve[i]);}});
            compare(g,"primary texture mutation flushes prior pixels",twelve,()->{textured(g,textures[0]);upload(g,0,32,0);},()->{
                sequence(g,Arrays.copyOf(twelve,6),textures,2);g.glBindTexture(3553,textures[0]);
                g.glTexSubImage2D(3553,0,0,0,1,1,6408,5121,bytes(7,251,19,220));sequence(g,Arrays.copyOfRange(twelve,6,12),textures,2);
            });
            compare(g,"native query barrier within terrain",twelve,()->textured(g,textures[0]),()->{
                sequence(g,Arrays.copyOf(twelve,6),textures,2);g.glGetError();
                check(Arrays.equals(spares(),new int[]{textures[9],textures[8],textures[7],textures[6],textures[5],textures[4],textures[3],textures[2]}),"external query restores spare units");
                sequence(g,Arrays.copyOfRange(twelve,6,12),textures,2);
            });
            compare(g,"nonprimary active unit fallback",twelve,()->{textured(g,textures[0]);g.glActiveTexture(33985);},()->sequence(g,twelve,textures,2));
            compare(g,"lighting fallback",twelve,()->{textured(g,textures[0]);g.glEnable(2896);},()->sequence(g,twelve,textures,2));
            int invalid=first+COUNT*3,collision=invalid+3;
            g.beginOriginalChunk(invalid,3);g.glNewList(invalid,4864);g.glEnable(0x123456);g.glEndList();g.finishOriginalChunk();
            g.beginOriginalChunk(collision,3);g.glNewList(collision,4864);g.glPushMatrix();g.glTranslatef(-.39f,-.22f,-.16f);emit(t,0,0,7);g.glPopMatrix();g.glEndList();g.finishOriginalChunk();
            int[] sameTag={first,collision};calls=compare(g,"recycled tag same matrix different input mask",sameTag,()->textured(g,textures[0]),()->sequence(g,sameTag,textures,1));check(calls==2,"same matrix cannot hide a conflicting attribute palette entry");
            compare(g,"failed material run retains prefix and restores spare bindings",twelve,()->textured(g,textures[0]),()->{
                int scope=g.beginOriginalTerrain();try{sequence(g,twelve,textures,2);rejects(()->g.glCallList(invalid));}finally{g.endOriginalTerrain(scope);}
            });
            final int[] temporary={0};compare(g,"texture deletion flushes its pending material draws",twelve,()->{temporary[0]=texture(g,7);textured(g,temporary[0]);},()->{
                for(int i=0;i<twelve.length;i++){g.glBindTexture(3553,i%2==0?temporary[0]:textures[0]);g.glCallList(twelve[i]);}g.glDeleteTextures(temporary[0]);g.glBindTexture(3553,textures[0]);
            });
            try(GameEffect effect=new GameEffect("/shader/default","/shader/default")){
                effect.set(effect.uniform("colorMap"),0);
                compare(g,"custom effect fallback",twelve,()->{textured(g,textures[0]);effect.begin();},()->sequence(g,twelve,textures,2));effect.end();
            }
            check(g.glGetError()==0,"material palette leaves no native error");
            if(Boolean.getBoolean("mcgl.tests.performance")){cost(g,first,textures);fillCost(g,first,textures,color,depth);}
        }finally{
            g.selectEffect(null);g.abortOriginalChunk();g.glDeleteLists(first,(COUNT+4)*3);
            for(int i=0;i<8;i++){GL13C.glActiveTexture(33988+i);GL11C.glBindTexture(3553,savedSpare[i]);}GL13C.glActiveTexture(33984);
            for(int texture:textures)if(texture!=0)g.glDeleteTextures(texture);
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER,framebuffer);g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,renderbuffer);
            GL30C.glDeleteFramebuffers(target);GL30C.glDeleteRenderbuffers(color);GL30C.glDeleteRenderbuffers(depth);
            g.glMatrixMode(5888);g.glPopMatrix();g.glMatrixMode(5889);g.glPopMatrix();g.glMatrixMode(5888);g.glPopAttrib();
            if(previous==null)System.clearProperty(FLAG);else System.setProperty(FLAG,previous);
            restore("mcgl.terrain.textureArrays",previousArrays);restore("mcgl.graphics.unlitShaders",previousUnlit);
        }
        check(g.cachedModels()==models&&g.terrainArenaMembers()==members,"material fixtures retire independently");
        System.out.println("GAME_TERRAIN_MATERIALS_GPU_PASS checks="+checks+" exact-RGBA/depth/order/mipmaps/state/fallbacks");return checks;
    }
    private static long compare(GameRenderCommands g,String label,int[] names,Runnable setup,Runnable draw){
        Snapshot reference=null;long calls=0;
        for(int mode=0;mode<6;mode++){
            System.setProperty("mcgl.terrain.textureArrays",Boolean.toString(mode>=3));System.setProperty("mcgl.graphics.unlitShaders",Boolean.toString(mode>=3));
            System.setProperty(FLAG,mode%3==2?"true":"false");reset(g);clear(g);setup.run();int[] spare=spares();long start=g.chunkDrawCalls();
            int scope=mode%3==0?0:g.beginOriginalTerrain();try{draw.run();}finally{if(scope!=0)g.endOriginalTerrain(scope);}
            if(mode==2)calls=g.chunkDrawCalls()-start;Snapshot actual=new Snapshot(g);
            check(Arrays.equals(spare,spares()),label+" mode="+mode+" exact spare unit restoration");
            if(reference==null)reference=actual;else{
                if(!Arrays.equals(reference.rgba,actual.rgba)){int different=0,max=0;for(int i=0;i<actual.rgba.length;i++){int delta=Math.abs((reference.rgba[i]&255)-(actual.rgba[i]&255));if(delta!=0)different++;max=Math.max(max,delta);}System.out.println("MATERIAL_PIXEL_DIFFERENCE case="+label+" mode="+mode+" components="+different+" max_delta="+max);}
                check(Arrays.equals(reference.rgba,actual.rgba),label+" mode="+mode+" exact RGBA");
                check(Arrays.equals(reference.depth,actual.depth),label+" mode="+mode+" exact depth");
                check(Arrays.equals(reference.state,actual.state),label+" mode="+mode+" exact logical/raster state");
            }
        }
        return calls;
    }
    private static final class Snapshot {
        final byte[] rgba,depth;final int[] state;
        Snapshot(GameRenderCommands g){rgba=pixels(g,6408,5121);depth=pixels(g,GL11C.GL_DEPTH_COMPONENT,5126);
            int[] names={GL13C.GL_ACTIVE_TEXTURE,GL11C.GL_TEXTURE_BINDING_2D,GL11C.GL_DEPTH_WRITEMASK,GL30C.GL_FRAMEBUFFER_BINDING};state=new int[names.length+36];
            for(int i=0;i<names.length;i++){IntBuffer v=ints(0);g.glGetInteger(names[i],v);state[i]=v.get(0);}
            int offset=names.length;for(int name:new int[]{2982,2983,2816}){FloatBuffer v=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();g.glGetFloat(name,v);int count=name==2816?4:16;for(int i=0;i<count;i++)state[offset++]=Float.floatToRawIntBits(v.get(i));}
        }
    }
    private static void sequence(GameRenderCommands g,int[] names,int[] textures,int count){for(int i=0;i<names.length;i++){g.glBindTexture(3553,textures[i%count]);g.glCallList(names[i]);}}
    private static void emit(OriginalChunkEmitter t,int id,int fields,int mode){
        t.beginMode(mode);if((fields&4)!=0)t.lightmap(0x00b00080);if((fields&8)!=0)t.normal(0,0,1);
        int n=mode==4?3:4;float[][] xy={{-.42f,-.38f},{.42f,-.38f},{.42f,.38f},{-.42f,.38f}};
        for(int i=0;i<n;i++){if((fields&1)!=0)t.color((id*37+i*41)%256,(id*71+i*23+50)%256,(id*53+i*11+90)%256,100+i*35);if((fields&2)!=0)t.uv(i==1||i==2?1:0,i>=2?1:0);t.vertex(xy[i][0],xy[i][1],0);}t.draw();
    }
    private static void uniformArrays()throws Exception{
        RenderContext c=RenderSystem.current();String v="#version 410 core\nlayout(location=0) in vec3 p;void main(){gl_Position=vec4(p,1);}";
        String f="#version 410 core\nuniform int values[%d];out vec4 color;void main(){color=vec4(float(values[int(gl_FragCoord.x)%%%d])/255.0);}";
        for(int count:new int[]{1,32,128}){
            String name=count==128?"uOriginalInputPalette":"values";
            ShaderProgram p=c.shaders().create(new ShaderSources("test/int-array-"+count,v,String.format(Locale.ROOT,f,count,count).replace("values",name)));ShaderUniform u=p.uniform(name);
            check(u.type()==ShaderUniform.Type.INT_ARRAY,"integer palette reflected as bounded array");
            IntBuffer data=ByteBuffer.allocateDirect((count+2)*4).order(ByteOrder.nativeOrder()).asIntBuffer();data.put(0,-777);data.put(count+1,-888);for(int i=0;i<count;i++)data.put(i+1,i*13-71);data.position(1);data.limit(count+1);
            int previous=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);u.setIntArray(data);u.setIntArray(data);check(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)==previous,"integer array update preserves bound program");p.bind();int id=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            for(int i=0;i<count;i++)check(GL20C.glGetUniformi(id,GL20C.glGetUniformLocation(id,name+"["+i+"]"))==i*13-71,"all signed integer entries reach native uniform");
            check(data.position()==1&&data.limit()==count+1&&data.get(0)==-777,"array cursor and surrounding storage unchanged");
            rejects(()->u.setIntArray(null));rejects(()->u.setIntArray(IntBuffer.allocate(count)));rejects(()->u.setIntArray(ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN?ByteOrder.BIG_ENDIAN:ByteOrder.LITTLE_ENDIAN).asIntBuffer()));
            rejects(()->u.setIntArray(ints(1,2,3)));rejects(()->u.setInt(1));
            AtomicReference<Throwable> failure=new AtomicReference<Throwable>();Thread thread=new Thread(()->{try{u.setIntArray(data);}catch(Throwable e){failure.set(e);}});thread.start();thread.join();check(failure.get() instanceof IllegalStateException,"foreign thread cannot write integer palette");
            GL20C.glUseProgram(previous);p.close();rejects(()->u.setIntArray(data));
        }
        rejects(()->c.shaders().create(new ShaderSources("test/oversized-int-array",v,String.format(Locale.ROOT,f,33,33))));
        rejects(()->c.shaders().create(new ShaderSources("test/oversized-original-array",v,String.format(Locale.ROOT,f,129,129).replace("values","uOriginalInputPalette"))));
        ShaderProgram p=c.shaders().create(new ShaderSources("test/array-sampler",v,"#version 410 core\nuniform sampler2DArray image;out vec4 color;void main(){color=texture(image,vec3(gl_FragCoord.xy,0));}"));
        ShaderUniform sampler=p.uniform("image");check(sampler.type()==ShaderUniform.Type.SAMPLER_2D_ARRAY,"array sampler has an explicit type");int previous=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);sampler.setInt(12);check(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)==previous,"array sampler upload preserves bound program");
        rejects(()->sampler.setInt(-1));rejects(()->sampler.setFloat(1));rejects(()->sampler.setIntArray(ints(1)));
        AtomicReference<Throwable> failure=new AtomicReference<Throwable>();Thread thread=new Thread(()->{try{sampler.setInt(0);}catch(Throwable e){failure.set(e);}});thread.start();thread.join();check(failure.get() instanceof IllegalStateException,"array sampler enforces render owner");p.close();rejects(()->sampler.setInt(0));
    }
    private static void restore(String property,String value){if(value==null)System.clearProperty(property);else System.setProperty(property,value);}
    private static void cost(GameRenderCommands g,int first,int[] textures){
        int[] names=handles(first,4096);
        for(int textureCount:new int[]{1,2,8}){
            double[][] samples={new double[48],new double[48]};long[] draws=new long[2];int[] used=new int[2];
            for(int warm=0;warm<24;warm++)timed(g,names,textures,textureCount,(warm&1)!=0,null);
            for(int round=0;round<24;round++)for(int mode:new int[]{0,1,1,0})samples[mode][used[mode]++]=timed(g,names,textures,textureCount,mode==1,draws);
            for(int mode=0;mode<2;mode++){Arrays.sort(samples[mode]);System.out.printf(Locale.ROOT,"ORIGINAL_MATERIAL_COST textures=%d parts=%d mode=%s draws_per_frame=%d p50_ms=%.3f p95_ms=%.3f synthetic-not-game-fps%n",textureCount,names.length,mode==0?"matrix-only":"materials",draws[mode],samples[mode][24],samples[mode][45]);}
        }
        double[][] samples={new double[48],new double[48]};int[] used=new int[2];long[] calls=new long[2];
        for(int round=-6;round<24;round++)for(int mode:new int[]{0,1,1,0}){
            System.setProperty(FLAG,Boolean.toString(mode==1));reset(g);clear(g);textured(g,textures[0]);GL11C.glFinish();
            long before=g.chunkDrawCalls(),start=System.nanoTime();int scope=g.beginOriginalTerrain();try{
                for(int i=0;i<names.length;i++){g.glBindTexture(3553,textures[i/8%2]);if(i%22==0)g.glDepthMask(i/22%2==0);g.glCallList(names[i]);}
            }finally{g.endOriginalTerrain(scope);}GL11C.glFinish();double ms=(System.nanoTime()-start)/1e6;calls[mode]=g.chunkDrawCalls()-before;if(round>=0)samples[mode][used[mode]++]=ms;
        }
        for(int mode=0;mode<2;mode++){Arrays.sort(samples[mode]);System.out.printf(Locale.ROOT,"ORIGINAL_MATERIAL_COST pattern=mixed-boundaries distinct_meshes=%d mode=%s draws_per_frame=%d p50_ms=%.3f p95_ms=%.3f synthetic-not-captured-world-not-game-fps%n",names.length,mode==0?"matrix-only":"materials",calls[mode],samples[mode][24],samples[mode][45]);}
    }
    private static double timed(GameRenderCommands g,int[] names,int[] textures,int count,boolean enabled,long[] draws){
        System.setProperty(FLAG,Boolean.toString(enabled));reset(g);clear(g);textured(g,textures[0]);GL11C.glFinish();
        long before=g.chunkDrawCalls(),start=System.nanoTime();int scope=g.beginOriginalTerrain();try{sequence(g,names,textures,count);}finally{g.endOriginalTerrain(scope);}GL11C.glFinish();
        double ms=(System.nanoTime()-start)/1e6;if(draws!=null)draws[enabled?1:0]=g.chunkDrawCalls()-before;return ms;
    }
    private static void fillCost(GameRenderCommands g,int first,int[] textures,int color,int depth){
        g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,color);g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL11C.GL_RGBA8,1280,720);
        g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER,depth);g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL30C.GL_DEPTH24_STENCIL8,1280,720);g.glViewport(0,0,1280,720);
        for(boolean blended:new boolean[]{false,true})for(int count:new int[]{1,2,8}){
            int[] names=handles(first,32);double[][] samples={new double[24],new double[24]};int[] used=new int[2];long[] calls=new long[2];
            for(int round=-4;round<12;round++)for(int mode:new int[]{0,1,1,0}){
                System.setProperty(FLAG,Boolean.toString(mode==1));reset(g);clear(g);textured(g,textures[0]);if(blended)g.glEnable(GL11C.GL_BLEND);else g.glEnable(GL11C.GL_DEPTH_TEST);
                GL11C.glFinish();long before=g.chunkDrawCalls(),start=System.nanoTime();int scope=g.beginOriginalTerrain();try{sequence(g,names,textures,count);}finally{g.endOriginalTerrain(scope);}GL11C.glFinish();
                double ms=(System.nanoTime()-start)/1e6;calls[mode]=g.chunkDrawCalls()-before;if(round>=0)samples[mode][used[mode]++]=ms;
            }
            for(int mode=0;mode<2;mode++){Arrays.sort(samples[mode]);System.out.printf(Locale.ROOT,"ORIGINAL_MATERIAL_FILL_COST size=1280x720 alpha=%s textures=%d parts=32 mode=%s draws=%d p50_ms=%.3f p95_ms=%.3f synthetic-not-game-fps%n",blended,count,mode==0?"matrix-only":"materials",calls[mode],samples[mode][12],samples[mode][22]);}
        }
    }
    private static int texture(GameRenderCommands g,int variant){int texture=g.glGenTextures();g.glBindTexture(3553,texture);g.glTexParameteri(3553,10241,9987);g.glTexParameteri(3553,10240,9729);for(int level=0,size=32;size>=1;level++,size/=2)upload(g,level,size,variant);return texture;}
    private static void upload(GameRenderCommands g,int level,int size,int variant){ByteBuffer b=ByteBuffer.allocateDirect(size*size*4);for(int y=0;y<size;y++)for(int x=0;x<size;x++)b.put((byte)(40+(variant*17+x*13+level*31)%216)).put((byte)(30+(variant*29+y*19)%226)).put((byte)(50+(x*11+y*7+variant*43)%206)).put((byte)(140+(x+y)%116));b.flip();g.glTexImage2D(3553,level,32856,size,size,0,6408,5121,b);}
    private static void reset(GameRenderCommands g){g.selectEffect(null);g.glActiveTexture(33984);for(int cap:new int[]{3553,2896,2903,2977,32826,2912,3008,GL11C.GL_DEPTH_TEST,GL11C.GL_CULL_FACE,GL11C.GL_BLEND,GL11C.GL_STENCIL_TEST,GL11C.GL_SCISSOR_TEST})g.glDisable(cap);g.glColorMask(true,true,true,true);g.glDepthMask(true);g.glDepthFunc(GL11C.GL_LEQUAL);g.glBlendFunc(770,771);g.glFrontFace(GL11C.GL_CCW);g.glColor4f(.8f,.7f,.6f,.9f);g.glNormal3f(0,0,1);g.glShadeModel(7425);for(int unit=0;unit<2;unit++){g.glActiveTexture(33984+unit);g.glDisable(3553);g.glBindTexture(3553,0);g.glMatrixMode(5890);g.glLoadIdentity();g.glMultiTexCoord2f(33984+unit,.19f,.27f);}g.glActiveTexture(33984);g.glMatrixMode(5889);g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-2,2);g.glMatrixMode(5888);g.glLoadIdentity();}
    private static void textured(GameRenderCommands g,int texture){g.glEnable(3553);g.glBindTexture(3553,texture);}
    private static int[] handles(int first,int count){int[] values=new int[count];for(int i=0;i<count;i++)values[i]=first+i*3;return values;}
    private static int[] spares(){int active=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);int[] values=new int[8];for(int i=0;i<8;i++){GL13C.glActiveTexture(33988+i);values[i]=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);}GL13C.glActiveTexture(active);return values;}
    private static FloatBuffer floats(float... values){FloatBuffer b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(values).flip();return b;}
    private static IntBuffer ints(int... values){IntBuffer b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asIntBuffer();b.put(values).flip();return b;}
    private static ByteBuffer bytes(int... values){ByteBuffer b=ByteBuffer.allocateDirect(values.length);for(int v:values)b.put((byte)v);b.flip();return b;}
    private static byte[] pixels(GameRenderCommands g,int format,int type){ByteBuffer b=ByteBuffer.allocateDirect(SIZE*SIZE*4).order(ByteOrder.nativeOrder());g.glReadPixels(0,0,SIZE,SIZE,format,type,b);byte[] values=new byte[b.remaining()];b.get(values);return values;}
    private static void clear(GameRenderCommands g){g.glClearColor(0,0,0,1);g.glClearDepth(1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT|GL11C.GL_STENCIL_BUFFER_BIT);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}check(rejected,"invalid palette operation rejected");}
}
