package local.mcgl.render.tests;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Runs only migrated materials/effects, with an actual 4.1 Core native context. */
public final class GameInputsProbe {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Path shaders=Paths.get("bin/shader");
        for(int lifetime=0;lifetime<2;lifetime++) {
            Display.setDisplayMode(new DisplayMode(256,192));Display.setTitle("MCGL — game material migration");
            MCGLCoreDisplay.create(new PixelFormat().withDepthBits(24));
            try {
                RenderContext context=RenderSystem.current();check(context.profile()==RenderProfile.CORE_41,"Core profile");
                check((GL11C.glGetInteger(GL32C.GL_CONTEXT_PROFILE_MASK)&GL32C.GL_CONTEXT_CORE_PROFILE_BIT)!=0,"native Core profile bit");
                System.out.println("GAME_INPUTS_GL "+GL11C.glGetString(GL11C.GL_VERSION));
                int fbo=GL30C.glGenFramebuffers(),color=GL30C.glGenRenderbuffers();
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,fbo);GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER,color);
                GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL11C.GL_RGBA8,64,64);
                GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL30C.GL_RENDERBUFFER,color);
                check(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)==GL30C.GL_FRAMEBUFFER_COMPLETE,"test target");
                GL11C.glViewport(0,0,64,64);GL11C.glDisable(GL11C.GL_DITHER);GL11C.glDisable(GL11C.GL_DEPTH_TEST);GL11C.glDisable(GL11C.GL_CULL_FACE);
                GL11C.glDisable(GL11C.GL_BLEND);GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
                ShaderProgram material=context.shaders().create(GameMaterialProgram.sources());GameRenderState state=new GameRenderState();
                int[] raw=quad(new int[]{0xff0000ff,0xff00ff00,0xffff0000,0xff604020});
                GameGeometry geometry=GameGeometry.raw(raw,raw.length,4,7,false,true,true,true,true,true);
                Mesh mesh=context.meshes().create("game-quad",geometry.mesh,MeshPipeline.Usage.STATIC);
                state.shade(7424);draw(material,mesh,geometry,state);pixel(16,32,32,64,96,"quad flat fourth color first triangle");pixel(48,32,32,64,96,"quad flat fourth color second triangle");
                state.color(.5f,.25f,.75f,1);draw(material,mesh,geometry,state,1);pixel(32,32,128,64,191,"disabled array uses draw-time color");
                state.enable(3008,true);state.alpha(516,.6f);state.color(1,0,0,.5f);draw(material,mesh,geometry,state,1);pixel(32,32,0,0,0,"alpha discard");state.enable(3008,false);
                state.enable(2912,true);state.fog(2917,9729);state.fog(2915,0);state.fog(2916,1);state.fog(2918,new float[]{0,1,0,1});
                state.matrices.translate(0,0,-.5);state.color(1,0,0,1);draw(material,mesh,geometry,state,1);pixel(32,32,128,128,0,"linear fog uses explicit eye position");
                state.enable(2912,false);state.matrices.loadIdentity();state.enable(2896,true);state.enable(2903,true);state.colorMaterial(1032,5634);
                state.enable(16384,true);state.lightModel(2899,new float[]{.2f,.2f,.2f,1});state.light(0,4609,new float[]{.6f,.6f,.6f,1});state.normal(0,0,1);
                state.color(.5f,.25f,.75f,1);draw(material,mesh,geometry,state,1);pixel(32,32,102,51,153,"explicit material directional lighting");
                state.enable(2896,false);state.enable(3553,true);int atlas=texture(0,128,255,64),light=texture(1,255,128,255);
                state.activeUnit(1);state.enable(3553,true);state.activeUnit(0);state.color(1,1,1,1);draw(material,mesh,geometry,state,1);
                pixel(32,32,128,128,64,"texture and lightmap with draw-time coordinates");
                int effects=0;try(DirectoryStream<Path> paths=Files.newDirectoryStream(shaders,"*.frag")) {
                    for(Path fragment:paths) {
                        String name=fragment.getFileName().toString().replace(".frag","");Path vertex=shaders.resolve(name+".vert");
                        if(name.equals("armor_effect_2"))vertex=shaders.resolve("armor_effect.vert");
                        if(!Files.exists(vertex))vertex=shaders.resolve("default.vert");
                        ShaderProgram effect=context.shaders().create(new ShaderSources("game-effect/"+name,
                                GameShaderSource.migrate(35633,read(vertex)),GameShaderSource.migrate(35632,read(fragment))));
                        state.bind(effect,geometry.attributeMask);effect.bind();
                        check(!effect.isClosed(),"compiled migrated effect "+name);effect.close();effects++;
                        if(GameShaderSource.chunkTexturesSupported(read(fragment))){
                            ShaderProgram table=context.shaders().create(new ShaderSources("game-effect-chunk-textures/"+name,
                                    GameShaderSource.migrate(35633,read(vertex),true),GameShaderSource.migrate(35632,read(fragment),true)));
                            check(!table.isClosed(),"compiled texture-table effect "+name);table.close();
                            System.out.println("GAME_EFFECT_TABLE_COMPILE_PASS "+name);
                        }
                        System.out.println("GAME_EFFECT_COMPILE_PASS "+name);
                    }
                }check(effects==15,"all 15 original fragment effects");
                GL11C.glDeleteTextures(atlas);GL11C.glDeleteTextures(light);mesh.close();material.close();
                GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,0);GL30C.glDeleteFramebuffers(fbo);GL30C.glDeleteRenderbuffers(color);
                check(GL11C.glGetError()==GL11C.GL_NO_ERROR,"no fixed-function native errors");Display.update();
            }finally{Display.destroy();}
        }
        Display.shutdown();System.out.println("GAME_INPUTS_GPU_PASS checks="+checks+" coreLifetimes=2 effects=15");
    }
    private static String read(Path path)throws Exception{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static void draw(ShaderProgram p,Mesh m,GameGeometry g,GameRenderState s){draw(p,m,g,s,g.attributeMask);}
    private static void draw(ShaderProgram p,Mesh m,GameGeometry g,GameRenderState s,int mask){GL11C.glClearColor(0,0,0,1);GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);s.bind(p,mask);p.bind();m.draw(g.primitive,0,m.indexCount());}
    private static int[] quad(int[] colors){int[] raw=new int[32];float[] x={-.8f,.8f,.8f,-.8f},y={-.8f,-.8f,.8f,.8f};for(int i=0;i<4;i++){raw[i*8]=Float.floatToIntBits(x[i]);raw[i*8+1]=Float.floatToIntBits(y[i]);raw[i*8+5]=colors[i];raw[i*8+6]=0x007f0000;}return raw;}
    private static int texture(int unit,int r,int g,int b){GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);int id=GL11C.glGenTextures();GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,id);ByteBuffer bytes=ByteBuffer.allocateDirect(4);bytes.put((byte)r).put((byte)g).put((byte)b).put((byte)255).flip();GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL11C.GL_RGBA8,1,1,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,bytes);GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_NEAREST);return id;}
    private static void pixel(int x,int y,int r,int g,int b,String label){ByteBuffer p=ByteBuffer.allocateDirect(4);GL11C.glReadPixels(x,y,1,1,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,p);int ar=p.get(0)&255,ag=p.get(1)&255,ab=p.get(2)&255;check(Math.abs(ar-r)<=2&&Math.abs(ag-g)<=2&&Math.abs(ab-b)<=2,label+" got="+ar+","+ag+","+ab);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
