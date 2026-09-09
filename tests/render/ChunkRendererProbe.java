package local.mcgl.render.tests;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Real Core GPU tests with geometry emitted by the shipped game's accumulator. No authenticated game. */
public final class ChunkRendererProbe {
    private static int checks;
    private static final ChunkMaterial SOLID=material("solid",0,ShaderLibrary.Material.LIGHTMAPPED);
    private static final ChunkMaterial A=material("glass-a",1,ShaderLibrary.Material.TEXTURED);
    private static final ChunkMaterial B=material("glass-b",1,ShaderLibrary.Material.TEXTURED);
    private static final ChunkMaterial C=material("extra-pass",2,ShaderLibrary.Material.TEXTURED);
    private static ChunkMaterial material(String key,int pass,ShaderLibrary.Material shader){return new ChunkMaterial(key,pass,shader,ShaderLibrary.AlphaTest.GREATER,0.01f);}
    public static void main(String[] args)throws Exception{
        File client=new File(args[0]);
        for(int lifetime=0;lifetime<2;lifetime++){
            Display.setDisplayMode(new DisplayMode(720,480));Display.setTitle("MCGL Core — original chunk geometry");Display.setResizable(true);
            MCGLCoreDisplay.create(new PixelFormat().withDepthBits(24));
            RenderContext context=RenderSystem.current();ChunkRenderer chunks=new ChunkRenderer(context);
            try{
                check(context.profile()==RenderProfile.CORE_41,"real Core context");
                check((GL11C.glGetInteger(GL32C.GL_CONTEXT_PROFILE_MASK)&GL32C.GL_CONTEXT_CORE_PROFILE_BIT)!=0,"driver Core bit");
                GL11C.glDisable(GL11C.GL_DITHER);GL11C.glDisable(GL11C.GL_CULL_FACE);GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
                int framebuffer=GL30C.glGenFramebuffers(),renderbuffer=GL30C.glGenRenderbuffers(),depth=GL30C.glGenRenderbuffers();
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,framebuffer);GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER,renderbuffer);
                GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL11C.GL_RGBA8,64,64);
                GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL30C.GL_RENDERBUFFER,renderbuffer);
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER,depth);GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL14C.GL_DEPTH_COMPONENT24,64,64);
                GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_DEPTH_ATTACHMENT,GL30C.GL_RENDERBUFFER,depth);
                check(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)==GL30C.GL_FRAMEBUFFER_COMPLETE,"RGBA8/depth test target");
                int white=texture(0,255,255,255),light=texture(1,255,128,255);
                Binder binder=new Binder(context,white,light);context.frameCommands().viewport(0,0,64,64);
                OriginalChunkEmitter t=new OriginalChunkEmitter(client,128*1024);
                ChunkMeshBuilder solid=new ChunkMeshBuilder(0,0,0,4);t.bind(solid,SOLID);t.begin();t.color(64,128,192,255);t.lightmap(0);t.quad(-0.8,-0.8,0.8,0.8,0);t.draw();t.unbind();
                ChunkRenderer.Ticket initial=chunks.request(0,0,0);check(chunks.publish(initial,solid.finish()),"actual chunk upload");
                List<int[]> oldNames=names(context);check(oldNames.size()==1,"single terrain VAO/VBO/EBO allocation");
                clear(context);ChunkRenderer.DrawStats stats=chunks.drawPass(0,ChunkFrustum.ALL,0,0,2,binder);
                pixel(64,64,192,"original packed terrain data and real lightmap sample");check(stats.drawCalls==1&&stats.indices==6,"indexed terrain draw");
                ChunkRenderer.Ticket stale=chunks.request(0,0,0),replacement=chunks.request(0,0,0);
                check(!chunks.publish(stale,empty(0,0,0))&&names(context).size()==1,"superseded build makes no GPU allocation");
                clear(context);chunks.drawPass(0,ChunkFrustum.ALL,0,0,2,binder);pixel(64,64,192,"old terrain remains during rebuild");
                ChunkMeshBuilder transparent=new ChunkMeshBuilder(0,0,0,16);
                t.bind(transparent,A);t.begin();t.color(255,0,0,128);t.quad(-0.8,-0.8,0.8,0.8,-0.6);t.color(0,0,255,128);t.quad(-0.8,-0.8,0.8,0.8,0.6);t.draw();
                t.bind(transparent,B);t.begin();t.color(0,255,0,128);t.quad(-0.8,-0.8,0.8,0.8,0);t.draw();
                t.bind(transparent,C);t.begin();t.color(255,255,0,255);t.quad(-0.8,-0.8,0.8,0.8,0);t.draw();t.unbind();
                check(chunks.publish(replacement,transparent.finish()),"replace complete chunk atomically");deleted(oldNames);
                check(names(context).size()==3,"three explicit material/pass buffers");
                clear(context);stats=chunks.drawPass(1,ChunkFrustum.ALL,0,0,2,binder);pixel(32,64,128,"global alpha interleave far red / green / near blue");
                check(binder.calls.equals(Arrays.asList("glass-a","glass-b","glass-a"))&&stats.drawCalls==3,"global order crosses texture batches");
                clear(context);stats=chunks.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder);pixel(128,64,32,"camera reversal changes transparent face order");
                check(stats.indexUploads==1,"only reordered EBO updated");
                check(chunks.drawPass(1,ChunkFrustum.ALL,0,0,-2,binder).indexUploads==0,"stationary camera uploads nothing");
                clear(context);chunks.drawPass(2,ChunkFrustum.ALL,0,0,2,binder);pixel(255,255,0,"optional third original terrain pass");
                double[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};ChunkFrustum frustum=ChunkFrustum.fromMatrix(identity);
                ChunkMeshBuilder distant=new ChunkMeshBuilder(16,0,0,4);t.bind(distant,B);t.begin();t.color(255,255,255,255);t.quad(-0.8,-0.8,0.8,0.8,0);t.draw();t.unbind();
                chunks.publish(chunks.request(16,0,0),distant.finish());clear(context);stats=chunks.drawPass(1,frustum,0,0,2,binder);
                check(stats.visibleParts==2&&stats.culledParts==1,"actual geometry bounds cull distant chunk");pixel(32,64,128,"culling preserves visible pixels");
                List<int[]> resident=names(context);chunks.unload(0,0,0);chunks.unload(16,0,0);deleted(resident);
                check(names(context).isEmpty(),"unload retires all chunk resources");
                largeChunk(context,chunks,t,binder);
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,0);
                preview(context,chunks,t,binder,lifetime);
                resident=names(context);chunks.close();deleted(resident);check(names(context).isEmpty(),"explicit close deletes all meshes");
                GL30C.glDeleteFramebuffers(framebuffer);GL30C.glDeleteRenderbuffers(renderbuffer);GL30C.glDeleteRenderbuffers(depth);
                GL11C.glDeleteTextures(white);GL11C.glDeleteTextures(light);
                check(GL11C.glGetError()==GL11C.GL_NO_ERROR,"no legacy operations/GL errors in chunk path");
                // A populated second renderer is intentionally invalidated by context destruction.
                chunks=new ChunkRenderer(context);chunks.publish(chunks.request(0,0,0),simple(t,SOLID,0,0,0,1,2,3));
            }finally{Display.destroy();}
            check(chunks.isClosed(),"destroy invalidates populated registry");chunks.close();
        }
        legacyFallback(client);
        Display.shutdown();System.out.println("CHUNK_GPU_PASS checks="+checks+" coreLifetimes=2 compatibilityLifetimes=1 real-tessellator/Core/pixels/global-alpha/large-indices/VAO-VBO-EBO/cleanup/legacy-fallback");
    }
    private static void legacyFallback(File client)throws Exception{
        Display.setDisplayMode(new DisplayMode(64,64));Display.create(new PixelFormat().withDepthBits(24));
        try{
            check(RenderSystem.current().profile()==RenderProfile.COMPATIBILITY_21,"ordinary game context remains compatibility");
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);GL11C.glDisable(GL11C.GL_BLEND);GL11C.glDisable(GL11C.GL_CULL_FACE);
            local.mcgl.render.legacy.GL11.glDisable(GL11C.GL_TEXTURE_2D);
            local.mcgl.render.legacy.GL11.glMatrixMode(5889);local.mcgl.render.legacy.GL11.glLoadIdentity();
            local.mcgl.render.legacy.GL11.glMatrixMode(5888);local.mcgl.render.legacy.GL11.glLoadIdentity();
            RenderContext context=RenderSystem.current();context.frameCommands().viewport(0,0,64,64);clear(context);
            OriginalChunkEmitter t=new OriginalChunkEmitter(client,256);t.begin();t.color(192,64,128,255);t.quad(-0.8,-0.8,0.8,0.8,0);
            check(t.draw()==128,"unbound original draw byte count");pixel(192,64,128,"unbound adapter uses unchanged legacy native draw");
            check(GL11C.glGetError()==GL11C.GL_NO_ERROR,"legacy draw wrapper leaves no GL errors");Display.update();
        }finally{Display.destroy();}
    }
    private static void largeChunk(RenderContext context,ChunkRenderer chunks,OriginalChunkEmitter ignored,Binder binder)throws Exception{
        // All vertices are emitted by the real client; only the final quad touches the center pixel.
        OriginalChunkEmitter t=new OriginalChunkEmitter(new File("bin/mcgl.jar"),600000);
        ChunkMeshBuilder build=new ChunkMeshBuilder(0,0,0,65540);t.bind(build,A);t.begin();t.color(0,0,0,0);
        for(int i=0;i<16384;i++)t.quad(2,2,3,3,0);
        t.color(255,0,255,255);t.quad(-0.8,-0.8,0.8,0.8,0);t.draw();t.unbind();
        ChunkMeshData data=build.finish();check(data.parts().size()==1&&data.parts().get(0).mesh.indices().type()==IndexData.Type.UINT32,"original stream crosses 16-bit address space");
        chunks.publish(chunks.request(0,0,0),data);clear(context);chunks.drawPass(1,ChunkFrustum.ALL,0,0,2,binder);pixel(255,0,255,"actual vertex indices 65536..65539 rendered");chunks.unload(0,0,0);
    }
    private static ChunkMeshData simple(OriginalChunkEmitter t,ChunkMaterial material,int x,int y,int z,int r,int g,int b){
        ChunkMeshBuilder builder=new ChunkMeshBuilder(x,y,z,4);t.bind(builder,material);t.begin();t.color(r,g,b,255);t.lightmap(0);t.quad(-0.8,-0.8,0.8,0.8,0);t.draw();t.unbind();return builder.finish();
    }
    private static ChunkMeshData empty(int x,int y,int z){return new ChunkMeshBuilder(x,y,z,0).finish();}
    private static void preview(RenderContext context,ChunkRenderer chunks,OriginalChunkEmitter t,Binder binder,int lifetime)throws Exception{
        ChunkMeshBuilder world=new ChunkMeshBuilder(0,0,0,20000);t.bind(world,SOLID);t.begin();t.lightmap(0);
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            t.translate(x,0,z);t.color(56+(x%3)*12,150+(z%3)*15,64,255);t.cube(0.48f,0.20f,0.48f);
            if((x==4||x==11)&&(z==4||z==11)){t.translate(x,0.7,z);t.color(150,105,64,255);t.cube(0.45f,0.5f,0.45f);}
        }
        t.draw();t.translate(0,0,0);t.unbind();chunks.publish(chunks.request(0,0,0),world.finish());
        binder.preview=true;context.frameCommands().viewport(0,0,720,480);context.frameCommands().clearColor(0.055f,0.08f,0.12f,1);context.frameCommands().clear(true,true,false);
        ChunkRenderer.DrawStats stats=chunks.drawPass(0,ChunkFrustum.ALL,24,22,30,binder);
        check(stats.indices==260*36,"original cuboid terrain fixture has 260 complete cubes");
        ByteBuffer rgba=ByteBuffer.allocateDirect(720*480*4);GL11C.glReadPixels(0,0,720,480,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,rgba);
        BufferedImage image=new BufferedImage(720,480,BufferedImage.TYPE_INT_RGB);int occupied=0;
        for(int y=0;y<480;y++)for(int x=0;x<720;x++){int p=(y*720+x)*4;int r=rgba.get(p)&255,g=rgba.get(p+1)&255,b=rgba.get(p+2)&255;image.setRGB(x,479-y,(r<<16)|(g<<8)|b);if(g>45)occupied++;}
        check(occupied>10000,"terrain fixture actually covers preview pixels");
        File output=new File("chunk-preview-"+lifetime+".png");ImageIO.write(image,"png",output);System.out.println("CHUNK_PREVIEW "+output.getAbsolutePath());
        Display.update();check(context.presentedFrames()>0,"normal Core window presentation");binder.preview=false;
    }
    private static final class Binder implements ChunkRenderer.MaterialBinder{
        final RenderContext context;final int texture,lightmap;final List<String> calls=new ArrayList<String>();boolean preview;
        Binder(RenderContext c,int t,int l){context=c;texture=t;lightmap=l;}
        public void begin(int pass){calls.clear();GL11C.glEnable(GL11C.GL_DEPTH_TEST);GL11C.glDepthFunc(GL11C.GL_LEQUAL);GL11C.glDepthMask(pass==0);if(pass==0)GL11C.glDisable(GL11C.GL_BLEND);else{GL11C.glEnable(GL11C.GL_BLEND);GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA,GL11C.GL_ONE_MINUS_SRC_ALPHA);}}
        public void bind(ChunkMaterial material,int x,int y,int z){
            calls.add(material.key);ShaderProgram p=context.materials().program(material.shader);p.bind();
            p.uniform("uAlphaFunction").setInt(material.alphaTest.id);p.uniform("uAlphaReference").setFloat(material.alphaReference);
            p.uniform("uModelView").setMatrix4(preview?view(x,y,z):translation(x,y,z));
            p.uniform("uProjection").setMatrix4(preview?ortho():translation(0,0,0));
            if(material.shader.textured){GL13C.glActiveTexture(GL13C.GL_TEXTURE0);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,texture);}
            if(material.shader.lightmapped){GL13C.glActiveTexture(GL13C.GL_TEXTURE1);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,lightmap);}
        }
        public void end(int pass){GL11C.glDepthMask(true);GL11C.glDisable(GL11C.GL_BLEND);GL13C.glActiveTexture(GL13C.GL_TEXTURE0);}
    }
    private static FloatBuffer translation(float x,float y,float z){return matrix(new float[]{1,0,0,0,0,1,0,0,0,0,1,0,x,y,z,1});}
    private static FloatBuffer ortho(){return matrix(new float[]{1f/14,0,0,0,0,1f/9.333f,0,0,0,0,-1f/50,0,0,0,0,1});}
    private static FloatBuffer view(int x,int y,int z){
        // Fixed orthonormal isometric camera, translated around the center of the 16x16 fixture.
        float a=(float)(1/Math.sqrt(2)),b=(float)(1/Math.sqrt(6)),c=(float)(1/Math.sqrt(3));
        float tx=x-7.5f,ty=y,tz=z-7.5f;
        return matrix(new float[]{a,-b,c,0,0,2*b,c,0,-a,-b,c,0,a*tx-a*tz,-b*tx+2*b*ty-b*tz,c*(tx+ty+tz)-20,1});
    }
    private static FloatBuffer matrix(float[] values){FloatBuffer b=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(values).flip();return b;}
    private static int texture(int unit,int r,int g,int b){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);int id=GL11C.glGenTextures();GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,id);ByteBuffer bytes=ByteBuffer.allocateDirect(4);bytes.put((byte)r).put((byte)g).put((byte)b).put((byte)255).flip();GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL11C.GL_RGBA8,1,1,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,bytes);GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_NEAREST);return id;}
    private static void clear(RenderContext context){GL11C.glDepthMask(true);context.frameCommands().clearColor(0,0,0,1);context.frameCommands().clear(true,true,false);}
    private static void pixel(int r,int g,int b,String message){ByteBuffer bytes=ByteBuffer.allocateDirect(4);GL11C.glReadPixels(32,32,1,1,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,bytes);int ar=bytes.get(0)&255,ag=bytes.get(1)&255,ab=bytes.get(2)&255;check(Math.abs(ar-r)<=2&&Math.abs(ag-g)<=2&&Math.abs(ab-b)<=2,message+" actual="+ar+","+ag+","+ab);}
    private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static List<int[]> names(RenderContext context)throws Exception{Object pipeline=field(field(context,"backend"),"meshes");List<int[]> names=new ArrayList<int[]>();for(Object mesh:(Set<?>)field(pipeline,"meshes"))names.add(new int[]{(Integer)field(mesh,"vao"),(Integer)field(mesh,"vbo"),(Integer)field(mesh,"ibo")});return names;}
    private static void deleted(List<int[]> names){for(int[] ids:names)check(!GL30C.glIsVertexArray(ids[0])&&!GL15C.glIsBuffer(ids[1])&&!GL15C.glIsBuffer(ids[2]),"native chunk VAO/VBO/EBO deleted");}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
