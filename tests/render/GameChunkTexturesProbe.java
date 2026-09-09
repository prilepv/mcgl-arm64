package local.mcgl.render.tests;

import java.nio.*;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** GPU equivalence for interleaved texture tables, inherited inputs, effects and scoped spare units. */
public final class GameChunkTexturesProbe {
    private static int checks;
    public static int run(GameRenderCommands g)throws Exception {
        checks=0;int[] textures=new int[9],spare=new int[8];int handle=g.glGenLists(27);
        g.glPushAttrib(-1);g.glMatrixMode(5889);g.glPushMatrix();g.glLoadIdentity();g.glOrtho(-1,1,-1,1,-10,10);
        g.glMatrixMode(5888);g.glPushMatrix();g.glLoadIdentity();
        try {
            g.glActiveTexture(33984);g.glEnable(3553);g.glDisable(2896);g.glDisable(2912);g.glDisable(3008);
            g.glDisable(GL11C.GL_DEPTH_TEST);g.glDisable(GL11C.GL_CULL_FACE);g.glEnable(GL11C.GL_BLEND);
            g.glBlendFunc(GL11C.GL_SRC_ALPHA,GL11C.GL_ONE_MINUS_SRC_ALPHA);g.glDepthMask(false);g.glColor4f(1,1,1,1);
            for(int i=0;i<textures.length;i++){textures[i]=g.glGenTextures();g.glBindTexture(3553,textures[i]);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);upload(g,i==0?255:0,i==1?255:0,i==2?255:40+i*15,128);}
            for(int i=0;i<8;i++){GL13C.glActiveTexture(33988+i);spare[i]=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);GL11C.glBindTexture(3553,textures[i]);}GL13C.glActiveTexture(33984);
            List<GameChunkHandle> selected=build(g,handle,textures);
            compare(g,selected,textures,2,1,"mixed masks and interleaved textures");
            compare(g,selected,textures,-2,1,"reversed global transparency");
            g.glColor4f(.4f,.7f,.9f,.8f);compare(g,selected,textures,2,1,"draw-time inherited color");
            g.glColor4f(1,1,1,1);g.glNormal3f(1,0,0);g.glEnable(2896);g.glEnable(16384);g.glEnable(2903);
            g.glLight(16384,4611,vector(0,0,1,0));g.glLight(16384,4608,vector(0,0,0,1));g.glLight(16384,4609,vector(1,1,1,1));g.glLightModel(2899,vector(0,0,0,1));
            compare(g,selected,textures,2,1,"mixed stored and inherited normals under lighting");
            g.glDisable(2896);g.glDisable(2903);g.glNormal3f(0,0,1);
            g.glColor4f(1,1,1,1);int green=textures[1];textures[1]=textures[3];compare(g,selected,textures,2,1,"draw-time inherited primary texture");textures[1]=green;
            g.glBindTexture(3553,textures[0]);upload(g,0,255,255,128);compare(g,selected,textures,2,1,"animated texture update is visible without vertex rebuild");
            for(int i=0;i<3;i++){
                g.glBindTexture(3553,textures[i]);g.glTexParameteri(3553,10241,GL11C.GL_NEAREST_MIPMAP_NEAREST);
                for(int level=0;level<=4;level++){int size=16>>level;ByteBuffer image=ByteBuffer.allocateDirect(size*size*4);for(int p=0;p<size*size;p++)image.put((byte)(i==0?255:level*35)).put((byte)(i==1?255:level*25)).put((byte)(i==2?255:level*15)).put((byte)128);image.flip();g.glTexImage2D(3553,level,GL11C.GL_RGBA8,size,size,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,image);}
            }
            uvSpan=32;g.glDeleteLists(handle,9);selected=build(g,handle,textures);compare(g,selected,textures,2,1,"explicit gradients retain mipmap selection");
            uvSpan=0;g.glDeleteLists(handle,9);selected=build(g,handle,textures);
            for(int i=0;i<3;i++){g.glBindTexture(3553,textures[i]);g.glTexParameteri(3553,10241,GL11C.GL_LINEAR);g.glTexParameteri(3553,10240,GL11C.GL_LINEAR);
                ByteBuffer image=ByteBuffer.allocateDirect(16);image.put(new byte[]{(byte)255,0,0,(byte)128,0,(byte)255,0,(byte)128,0,0,(byte)255,(byte)128,(byte)255,(byte)255,0,(byte)128}).flip();g.glTexImage2D(3553,0,GL11C.GL_RGBA8,2,2,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,image);}
            uvSpan=2;g.glDeleteLists(handle,9);selected=build(g,handle,textures);compare(g,selected,textures,2,1,"linear texture filtering agrees with individual draws");
            uvSpan=0;g.glDeleteLists(handle,9);selected=build(g,handle,textures);
            for(int i=0;i<3;i++){g.glBindTexture(3553,textures[i]);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);upload(g,i==0?255:0,i==1?255:0,i==2?255:0,128);}
            for(int i=0;i<8;i++){GL13C.glActiveTexture(33988+i);check(GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)==textures[i],"spare texture unit restored "+i);}GL13C.glActiveTexture(33984);
            try(GameEffect effect=new GameEffect("/shader/default","/shader/default")){
                effect.set(effect.uniform("colorMap"),0);effect.begin();compare(g,selected,textures,2,1,"original effect texture routing");
                g.glActiveTexture(33985);g.glBindTexture(3553,textures[1]);g.glActiveTexture(33984);
                effect.set(effect.uniform("colorMap"),1);compare(g,selected,textures,2,-1,"non-primary effect sampler retains ordinary batches");
                effect.set(effect.uniform("colorMap"),0);compare(g,selected,textures,2,1,"effect sampler returns to texture table");effect.end();
            }
            try(GameEffect effect=new GameEffect("/shader/transparency","/shader/transparency")){
                java.lang.reflect.Field field=GameEffect.class.getDeclaredField("program");field.setAccessible(true);ShaderProgram program=(ShaderProgram)field.get(effect);
                ShaderProgram table=GameEffect.chunkProgram(program);
                check(table!=null&&table!=program,"table effect owns a separate shader program");
                check(!program.uniformNames().contains("uGameChunkTexture1")&&table.uniformNames().contains("uGameChunkTexture1"),"ordinary effect has no spare sampler overhead");
                check(!GameEffect.chunkTexturesSupported(program),"uninitialized auxiliary samplers aliasing primary reject texture tables");
                effect.set(effect.uniform("colorMap"),0);effect.set(effect.uniform("pseudoLightMap"),1);effect.set(effect.uniform("pseudoReflections"),2);
                check(GameEffect.chunkTexturesSupported(program),"separate auxiliary sampler units permit texture tables");
                effect.set(effect.uniform("pseudoLightMap"),0);check(!GameEffect.chunkTexturesSupported(program),"later auxiliary sampler alias rejects texture tables");
                effect.set(effect.uniform("water_transparency"),.37f);effect.set(effect.uniform("waterfall_color"),.2f,.4f,.6f);
                table.bind();int tableNative=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
                program.bind();int ordinaryNative=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
                check(Math.abs(GL20C.glGetUniformf(tableNative,GL20C.glGetUniformLocation(tableNative,"water_transparency"))-.37f)<.00001f
                        &&Math.abs(GL20C.glGetUniformf(ordinaryNative,GL20C.glGetUniformLocation(ordinaryNative,"water_transparency"))-.37f)<.00001f,"scalar effect setters update both program variants");
                FloatBuffer ordinaryColor=ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).asFloatBuffer(),tableColor=ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).asFloatBuffer();
                GL20C.glGetUniformfv(ordinaryNative,GL20C.glGetUniformLocation(ordinaryNative,"waterfall_color"),ordinaryColor);GL20C.glGetUniformfv(tableNative,GL20C.glGetUniformLocation(tableNative,"waterfall_color"),tableColor);
                check(ordinaryColor.get(0)==.2f&&ordinaryColor.get(1)==.4f&&ordinaryColor.get(2)==.6f&&ordinaryColor.equals(tableColor),"vector effect setters update both program variants");
            }
            g.glDeleteLists(handle,9);selected.clear();
            for(int i=0;i<9;i++){final int current=handle+i*3;selected.add(()->current);g.beginChunk(current,0,0,i*16,3);g.glNewList(current+2,4864);g.glPushMatrix();g.glTranslatef(0,0,i*16);g.glBindTexture(3553,textures[i]);raw(g,0,-i*16-.8f+i*.2f,true,true);g.glPopMatrix();g.glEndList();g.finishChunk();}
            clear(g);for(int i=0;i<9;i++){g.glBindTexture(3553,textures[i]);raw(g,0,-.8f+i*.2f,true,true);}byte[] expected=pixels();
            clear(g);long calls=g.chunkDrawCalls();g.drawChunks(selected,2,0,0,2);check(g.chunkDrawCalls()==calls+2,"ninth distinct texture splits a bounded table");
            check(Arrays.equals(expected,pixels()),"table overflow preserves exact blended pixels");
            g.glDeleteLists(handle,27);check(g.residentChunks()==0,"texture table chunks unload");
            g.glBindTexture(3553,textures[2]);clear(g);raw(g,0,0,true,false);
            ByteBuffer pixel=ByteBuffer.allocateDirect(4);GL11C.glReadPixels(32,32,1,1,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,pixel);
            check((pixel.get(2)&255)>120&&(pixel.get(0)&255)==0,"ordinary draw after table retirement uses its own texture");
            check(g.glGetError()==0,"texture tables remain Core-valid");
        }finally {
            g.selectEffect(null);g.glDeleteLists(handle,27);
            for(int i=0;i<8;i++){GL13C.glActiveTexture(33988+i);GL11C.glBindTexture(3553,spare[i]);}GL13C.glActiveTexture(33984);
            for(int texture:textures)if(texture!=0)g.glDeleteTextures(texture);
            g.glMatrixMode(5888);g.glPopMatrix();g.glMatrixMode(5889);g.glPopMatrix();g.glMatrixMode(5888);g.glPopAttrib();
        }
        uvSpan=0;return checks;
    }
    private static float uvSpan;
    private static List<GameChunkHandle> build(GameRenderCommands g,int first,int[] textures){
        List<GameChunkHandle> selected=new ArrayList<GameChunkHandle>();
        for(int i=0;i<3;i++){final int handle=first+i*3;selected.add(()->handle);g.beginChunk(handle,i*16,0,0,3);g.glNewList(handle+2,4864);g.glPushMatrix();g.glTranslatef(i*16,0,0);
            if(i!=1)g.glBindTexture(3553,textures[i]);
            if(i==0){raw(g,-i*16,-.6f,true,true);raw(g,-i*16,.6f,true,true);}
            else if(i==1)raw(g,-i*16,0,false,false);else{raw(g,-i*16,-.3f,true,false);raw(g,-i*16,.3f,true,false);}
            g.glPopMatrix();g.glEndList();g.finishChunk();
        }return selected;
    }
    private static void compare(GameRenderCommands g,List<GameChunkHandle> selected,int[] textures,double camera,int count,String name){
        int[] parts={0,2,1,2,0};float[] z={-.6f,-.3f,0,.3f,.6f};
        clear(g);g.glPushMatrix();g.glTranslated(0,0,-camera);
        for(int at=0;at<5;at++){int i=camera>0?at:4-at,part=parts[i];g.glBindTexture(3553,textures[part]);raw(g,0,z[i],part!=1,part==0);}g.glPopMatrix();byte[] expected=pixels();
        clear(g);g.glBindTexture(3553,textures[1]);long calls=g.chunkDrawCalls();g.drawChunks(selected,2,0,0,camera);
        check(count==-1?g.chunkDrawCalls()>calls+1:g.chunkDrawCalls()==calls+count,name+" draw count");
        check(Arrays.equals(expected,pixels()),name+" exact blended pixels");
        check(GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)==33984&&GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)==textures[1],name+" primary binding restored");
    }
    private static void raw(GameRenderCommands g,float x,float z,boolean color,boolean normal){
        int[] words=new int[32];float[] xy={-.8f,-.8f,.8f,-.8f,.8f,.8f,-.8f,.8f};
        for(int i=0;i<4;i++){int at=i*8;words[at]=Float.floatToRawIntBits(x+xy[i*2]);words[at+1]=Float.floatToRawIntBits(xy[i*2+1]);words[at+2]=Float.floatToRawIntBits(z);words[at+3]=Float.floatToRawIntBits(.5f+uvSpan*(i==1||i==2?1:0));words[at+4]=Float.floatToRawIntBits(.5f+uvSpan*(i>=2?1:0));words[at+5]=-1;words[at+6]=0x007f0000;}
        g.raw(words,words.length,4,7,false,true,color,true,false,normal);
    }
    private static void upload(GameRenderCommands g,int r,int green,int b,int a){ByteBuffer data=ByteBuffer.allocateDirect(4);data.put((byte)r).put((byte)green).put((byte)b).put((byte)a).flip();g.glTexImage2D(3553,0,GL11C.GL_RGBA8,1,1,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,data);}
    private static void clear(GameRenderCommands g){g.glClearColor(0,0,0,0);g.glClear(GL11C.GL_COLOR_BUFFER_BIT);}
    private static FloatBuffer vector(float a,float b,float c,float d){FloatBuffer data=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();data.put(new float[]{a,b,c,d}).flip();return data;}
    private static byte[] pixels(){ByteBuffer buffer=ByteBuffer.allocateDirect(64*64*4);GL11C.glReadPixels(0,0,64,64,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,buffer);byte[] values=new byte[buffer.capacity()];buffer.get(values);return values;}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
