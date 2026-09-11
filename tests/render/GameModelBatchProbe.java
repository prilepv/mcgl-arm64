package local.mcgl.render.tests;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import local.mcgl.render.GameEffect;
import local.mcgl.render.GameRenderCommands;
import org.lwjgl.opengl.GL11C;

/** Same-context reference/merged model comparisons, including original box emission. */
public final class GameModelBatchProbe {
    private static int checks;
    private interface Scene { void run() throws Exception; }
    public static int run(GameRenderCommands g) throws Exception {
        checks = 0;
        int originalModels = g.cachedModels();
        OriginalChunkEmitter t = new OriginalChunkEmitter(new File("bin/mcgl.jar"), 1024);
        reset(g);
        int a = texture(g, false), b = texture(g, true);
        try {
            for (int shade : new int[]{7424,7425}) for (boolean lit : new boolean[]{false,true})
                for (int transform = 0; transform < 3; transform++) {
                    final int style = transform;
                    compare(g, "six adjacent faces shade=" + shade + " lit=" + lit + " transform=" + style,
                            () -> setup(g,a,b,shade,lit,style), () -> faces(t,6), 6,1);
                }
            compare(g, "ordered color, texture, normal, transform, depth, enable and attribute barriers",
                    () -> setup(g,a,b,7425,true,0), () -> {
                        faces(t,2); g.glColor4f(.2f,.7f,.4f,.65f); faces(t,2);
                        g.glBindTexture(3553,b); faces(t,2); g.glBindTexture(3553,a);
                        g.glNormal3f(.3f,.2f,.8f); faces(t,2);
                        g.glPushMatrix(); g.glTranslatef(.09f,-.04f,.1f); faces(t,2); g.glPopMatrix();
                        g.glDepthMask(false); faces(t,2); g.glDepthMask(true);
                        g.glDisable(2896); faces(t,2); g.glEnable(2896);
                        g.glPushAttrib(1|0x2000); g.glColor3f(.8f,.1f,.4f); faces(t,2); g.glPopAttrib(); faces(t,2);
                    },18,9);
            compare(g, "per-vertex immediate attributes and inherited caller inputs",
                    () -> setup(g,a,b,7424,true,1), () -> {
                        for (int face=0;face<4;face++) {
                            g.glBegin(7);
                            for(int v=0;v<4;v++) {
                                if((v&1)==0)g.glColor4f(.2f+face*.15f,.8f-v*.1f,.4f,.6f);
                                g.glNormal3f(v*.1f,face*.1f,1);g.glTexCoord2f(v==1||v==2?1:0,v>=2?1:0);
                                g.glVertex3f(v==1||v==2?.7f:-.7f,v>=2?.7f:-.7f,face*.06f);
                            }
                            g.glEnd();
                        }
                    },4,4);
            compare(g, "bounded run split preserves alpha order", () -> setup(g,a,b,7425,false,0), () -> faces(t,257),257,2);
            int nested=compile(g,true,()->faces(t,2));
            try {
                compare(g,"nested model call is a barrier",()->setup(g,a,b,7425,false,0),()->{
                    faces(t,2);g.glCallList(nested);faces(t,2);
                },5,3);
                // A parent retains the list handle, not a copy of the child's old geometry.
                int parent=compile(g,true,()->g.glCallList(nested));
                try {
                    reset(g);clear(g);g.glCallList(parent);byte[] before=pixels(g);
                    g.glNewList(nested,4864);g.glColor3f(1,0,0);faces(t,2);g.glEndList();
                    reset(g);clear(g);g.glCallList(parent);check(!Arrays.equals(before,pixels(g)),"nested redefinition remains visible");
                } finally {g.glDeleteLists(parent,1);}
            } finally {g.glDeleteLists(nested,1);}
            try(GameEffect effect=new GameEffect("/shader/default","/shader/default")) {
                effect.begin();
                try{compare(g,"original effect over combined geometry",()->setup(g,a,b,7425,false,1),()->faces(t,6),6,1);}
                finally{effect.end();}
            }
            originalBox(g,a,b);
            int aborted=g.glGenLists(1);g.glNewList(aborted,4864);faces(t,3);g.abortModel();
            check(g.cachedModels()==originalModels,"aborted pending CPU run is not published");
            g.glNewList(aborted,4864);faces(t,2);g.glEndList();g.glDeleteLists(aborted,1);g.glDeleteLists(aborted,1);
            check(g.cachedModels()==originalModels&&g.glGetError()==0,"model resources retired and Core-valid");
        } finally {reset(g);g.glDeleteTextures(a);g.glDeleteTextures(b);}
        System.out.println("GAME_MODEL_BATCH_GPU_PASS checks="+checks+" exact-RGBA/original-box/order/bounds/abort/redefinition");
        return checks;
    }
    private static int compile(GameRenderCommands g,boolean merged,Scene body)throws Exception {
        String previous=System.getProperty("mcgl.model.batch");int name=g.glGenLists(1);
        try{System.setProperty("mcgl.model.batch",Boolean.toString(merged));g.glNewList(name,4864);body.run();g.glEndList();return name;}
        catch(Exception|Error failure){g.abortModel();g.glDeleteLists(name,1);throw failure;}
        finally{restore(previous);}
    }
    private static void restore(String value){if(value==null)System.clearProperty("mcgl.model.batch");else System.setProperty("mcgl.model.batch",value);}
    private static void compare(GameRenderCommands g,String label,Scene setup,Scene body,int oldDraws,int newDraws)throws Exception {
        reset(g);setup.run();int old=compile(g,false,body),next=compile(g,true,body);
        try {
            reset(g);setup.run();clear(g);long draws=g.drawCalls(),vertices=g.submittedVertices();g.glCallList(old);
            byte[] expected=pixels(g);float[] color=color(g),matrix=matrix(g);long submitted=g.submittedVertices()-vertices;
            check(visible(expected),"reference image is not an empty clear: "+label);
            check(g.drawCalls()-draws==oldDraws,"reference draw count: "+label);
            reset(g);setup.run();clear(g);draws=g.drawCalls();vertices=g.submittedVertices();g.glCallList(next);
            equal(expected,pixels(g),label);check(g.drawCalls()-draws==newDraws,"merged draw count: "+label);
            check(g.submittedVertices()-vertices==submitted,"all original vertices retained: "+label);
            check(Arrays.equals(color,color(g))&&Arrays.equals(matrix,matrix(g)),"final caller state: "+label);
            // Inputs are inherited anew on subsequent executions, not baked at compilation.
            reset(g);clear(g);g.glColor4f(.7f,.3f,.9f,.8f);g.glCallList(old);expected=pixels(g);
            reset(g);clear(g);g.glColor4f(.7f,.3f,.9f,.8f);g.glCallList(next);equal(expected,pixels(g),label+" changed caller");
        } finally {g.glDeleteLists(old,1);g.glDeleteLists(next,1);}
    }
    private static void originalBox(GameRenderCommands g,int a,int b)throws Exception {
        Class<?> base=Class.forName("net.A.B.DA"),part=Class.forName("net.A.B.OoO0");
        Object[] models=new Object[2];int[] names=new int[2];String previous=System.getProperty("mcgl.model.batch");
        Field handle=part.getDeclaredField("Ø00000");handle.setAccessible(true);
        try {
            byte[] expected=null;
            for(int i=0;i<2;i++) {
                models[i]=part.getConstructor(base.getSuperclass(),int.class,int.class).newInstance(base.getConstructor().newInstance(),0,0);
                part.getMethod("o00000",float.class,float.class,float.class,int.class,int.class,int.class)
                        .invoke(models[i],-.5f,-.5f,-.5f,1,1,1);
                System.setProperty("mcgl.model.batch",Boolean.toString(i==1));reset(g);setup(g,a,b,7424,true,1);clear(g);
                long draws=g.drawCalls();part.getMethod("Ô00000",float.class).invoke(models[i],1f);names[i]=handle.getInt(models[i]);
                byte[] actual=pixels(g);check(g.drawCalls()-draws==(i==0?6:1),"actual original box emits six faces, combined into one");
                check(visible(actual),"actual original box is visible");
                if(i==0)expected=actual;else equal(expected,actual,"actual original model/box/quad/tessellator chain");
            }
        } finally {restore(previous);for(int name:names)if(name>0)g.glDeleteLists(name,1);}
    }
    private static void faces(OriginalChunkEmitter t,int count) {
        for(int face=0;face<count;face++) {
            t.begin();t.normal((face%3-1)*.3f,(face%2)*.4f,1);t.lightmap((face%8)*0x00100010);
            t.quad(-.72+face%3*.09,-.67+face%2*.11,.65,.71,face%6*.07);t.draw();
        }
    }
    private static void setup(GameRenderCommands g,int a,int b,int shade,boolean lit,int transform) {
        g.glShadeModel(shade);g.glEnable(3553);g.glBindTexture(3553,a);g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(770,771);
        g.glActiveTexture(33985);g.glEnable(3553);g.glBindTexture(3553,b);g.glActiveTexture(33984);
        if(lit){g.glEnable(2896);g.glEnable(16384);g.glEnable(2977);g.glEnable(2903);g.glLight(16384,4611,floats(.3f,.7f,1,transform==1?1:0));}
        g.glColor4f(.8f,.5f,.9f,.7f);g.glEnable(2912);g.glFogi(2917,9729);g.glFogf(2915,-.5f);g.glFogf(2916,1.5f);g.glFog(2918,floats(.1f,.2f,.3f,1));
        if(transform==1){g.glRotatef(27,1,1,0);g.glScalef(.8f,.6f,1.1f);}
        if(transform==2){g.glTranslated(-8192,8192,0);g.glTranslated(8192,-8192,0);g.glScalef(-.8f,.9f,.7f);}
    }
    private static int texture(GameRenderCommands g,boolean reverse) {
        int name=g.glGenTextures();g.glBindTexture(3553,name);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);
        ByteBuffer data=ByteBuffer.allocateDirect(4*4*4);for(int i=0;i<16;i++)data.put((byte)(reverse?i*16:255)).put((byte)(100+i*8)).put((byte)(reverse?255:220-i*10)).put((byte)(i%3==0?120:255));data.flip();
        g.glTexImage2D(3553,0,32856,4,4,0,6408,5121,data);return name;
    }
    private static void reset(GameRenderCommands g) {
        for(int unit=0;unit<2;unit++){g.glActiveTexture(33984+unit);g.glDisable(3553);g.glBindTexture(3553,0);g.glMatrixMode(5890);g.glLoadIdentity();g.glMultiTexCoord2f(33984+unit,0,0);}g.glActiveTexture(33984);
        for(int cap:new int[]{2896,16384,16385,2977,32826,3008,2912,2903,GL11C.GL_BLEND,GL11C.GL_DEPTH_TEST,GL11C.GL_CULL_FACE,GL11C.GL_SCISSOR_TEST})g.glDisable(cap);
        g.glColorMask(true,true,true,true);g.glDepthMask(true);g.glShadeModel(7425);g.glViewport(0,0,64,64);
        g.glMatrixMode(5889);g.glLoadIdentity();g.glMatrixMode(5888);g.glLoadIdentity();g.glNormal3f(0,0,1);
        g.glColorMaterial(1032,5634);g.glEnable(2903);g.glColor4f(.2f,.2f,.2f,1);g.glColorMaterial(1032,4609);g.glColor4f(.8f,.8f,.8f,1);g.glDisable(2903);g.glColorMaterial(1032,5634);g.glColor4f(1,1,1,1);
        g.glLightModel(2899,floats(.2f,.2f,.2f,1));g.glLight(16384,4608,floats(0,0,0,1));g.glLight(16384,4609,floats(1,1,1,1));
    }
    private static void clear(GameRenderCommands g){g.glClearColor(0,0,0,1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT);}
    private static byte[] pixels(GameRenderCommands g){ByteBuffer b=ByteBuffer.allocateDirect(64*64*4);g.glReadPixels(0,0,64,64,6408,5121,b);byte[] out=new byte[b.remaining()];b.get(out);return out;}
    private static float[] matrix(GameRenderCommands g){FloatBuffer b=floats(new float[16]);g.glGetFloat(2982,b);float[] out=new float[16];b.get(out);return out;}
    private static float[] color(GameRenderCommands g){FloatBuffer b=floats(new float[4]);g.glGetFloat(2816,b);float[] out=new float[4];b.get(out);return out;}
    private static FloatBuffer floats(float... values){FloatBuffer b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(values).flip();return b;}
    private static void equal(byte[] a,byte[] b,String label){int differences=0;for(int i=0;i<a.length;i++)if(a[i]!=b[i])differences++;check(differences==0,label+" RGBA differences="+differences);}
    private static boolean visible(byte[] pixels){for(int i=0;i<pixels.length;i+=4)if(pixels[i]!=0||pixels[i+1]!=0||pixels[i+2]!=0)return true;return false;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
}
