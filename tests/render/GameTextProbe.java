package local.mcgl.render.tests;

import java.io.File;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import local.mcgl.render.*;
import org.lwjgl.opengl.*;

/** Account-free exact RGBA comparisons with the unchanged ordinary Core glyph path. */
public final class GameTextProbe {
    private static int checks;
    private interface Scene {void draw()throws Exception;}
    public static int run(GameRenderCommands g)throws Exception {
        checks=0;reset(g);
        int a=texture(g,0),b=texture(g,1);
        try {
            for(int mode:new int[]{5,7})for(int shade:new int[]{7424,7425})for(int transform=0;transform<4;transform++) {
                final int style=transform;
                compare(g,"immediate mode="+mode+" shade="+shade+" transform="+style,()->{
                    g.glShadeModel(shade);g.glBindTexture(3553,a);g.glEnable(3553);g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(770,771);
                    if(style==1){g.glTranslatef(.017f,-.039f,0);g.glRotatef(19,0,0,1);g.glScalef(.81f,.67f,0);}
                    if(style==2){g.glMatrixMode(5889);g.glLoadMatrix(floats(1,0,0,0,0,1,0,0,0,0,1,-.3f,0,0,0,1));g.glMatrixMode(5888);g.glRotatef(23,1,1,0);}
                    if(style==3){g.glTranslated(-8192,8192,0);g.glTranslated(8192,-8192,0);g.glScalef(-.9f,1,1);}
                    for(int i=0;i<18;i++){g.glPushMatrix();g.glTranslatef((i%6)*.28f-.7f,(i/6)*.5f-.65f,i%3*.02f);g.glScalef(.35f,.35f,1);quad(g,mode,true);g.glPopMatrix();}
                });
            }
            compare(g,"ordered A/B/A pages, alpha, scissor, projection, texture matrix and raster barriers",()->{
                g.glEnable(3553);g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(770,771);
                g.glBindTexture(3553,a);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);
                for(int i=0;i<9;i++) {
                    g.glBindTexture(3553,i%3==1?b:a);g.glColor4f(i%2,.6f,.7f,.65f);quad(g,7,false);
                    if(i==0){g.glEnable(3008);g.glAlphaFunc(516,.4f);}
                    if(i==1){g.glEnable(GL11C.GL_SCISSOR_TEST);GL11C.glScissor(5,9,45,39);}
                    if(i==2){g.glMatrixMode(5890);g.glTranslatef(.31f,.16f,0);g.glMatrixMode(5888);}
                    if(i==3){g.glMatrixMode(5889);g.glScalef(.8f,.7f,1);g.glMatrixMode(5888);}
                    if(i==4){g.glDisable(GL11C.GL_SCISSOR_TEST);g.glColorMask(true,false,true,true);}
                    if(i==5){g.glColorMask(true,true,true,true);g.glTexParameteri(3553,10241,9729);g.glTexParameteri(3553,10240,9729);}
                    if(i==6){g.glEnable(GL11C.GL_CULL_FACE);g.glFrontFace(GL11C.GL_CW);}
                    g.glTranslatef(.07f,-.06f,.01f);
                }
            });
            compare(g,"fog and lightmap inputs",()->{
                g.glEnable(2912);g.glFogi(2917,9729);g.glFogf(2915,-.2f);g.glFogf(2916,1.2f);g.glFog(2918,floats(.2f,.4f,.7f,1));
                g.glActiveTexture(33985);g.glEnable(3553);g.glBindTexture(3553,b);g.glMatrixMode(5890);g.glScalef(.8f,.6f,1);g.glMatrixMode(5888);g.glActiveTexture(33984);
                for(int i=0;i<8;i++){g.glMultiTexCoord2f(33985,i*.13f,.6f);g.glTranslatef(.06f,.04f,-.03f);quad(g,5,true);}
            });
            compare(g,"ordinary geometry is an ordered barrier",()->{
                g.glColor4f(1,0,0,.7f);quad(g,7,false);g.glColor4f(0,1,0,1);g.glBegin(6);g.glVertex3f(-.9f,-.9f,0);g.glVertex3f(.9f,-.9f,0);g.glVertex3f(0,.9f,0);g.glEnd();
                g.glColor4f(0,0,1,1);g.glTranslatef(.3f,0,0);quad(g,5,false);
            });
            cached(g);litCached(g,a,b);actualGlyphs(g,a,b);actualCachedStrings(g,a);fallback(g,a);scopes(g);bounds(g);signCost(g,a,b);
            check(g.glGetError()==0,"text paths are Core-valid");
        }finally{reset(g);g.glDeleteTextures(a);g.glDeleteTextures(b);}
        System.out.println("GAME_TEXT_GPU_PASS checks="+checks+" exact-RGBA/actual-original-glyphs/order/bounds/lifetimes");return checks;
    }
    private static void cached(GameRenderCommands g)throws Exception {
        int old=g.glGenLists(2),next=g.glGenLists(2);g.defineFontGlyphs(next,1);
        OriginalChunkEmitter t=new OriginalChunkEmitter(new File("bin/mcgl.jar"),128);
        for(int first:new int[]{old,next}) {
            g.glNewList(first,4864);t.begin();t.quad(-.4,-.3,.4,.3,0);t.draw();g.glTranslatef(.12f,.03f,0);g.glEndList();
            g.glNewList(first+1,4864);g.glColor4f(.8f,.3f,.6f,.7f);g.glEndList();
        }
        try {
            reset(g);clear(g);cachedScene(g,old);byte[] expected=pixels(g);float[] matrix=matrix(g),color=color(g);
            reset(g);clear(g);long draws=g.textDraws();int scope=g.beginText();cachedScene(g,next);g.endText(scope);
            equal(expected,pixels(g),"declared original raw glyphs and color command lists");check(g.textDraws()-draws==1,"cached color and model translations share one run");
            check(Arrays.equals(matrix,matrix(g))&&Arrays.equals(color,color(g)),"cached final matrix and color preserved");
            // Lazy standalone fallback, followed by the same batched representation.
            reset(g);clear(g);g.glCallList(next);byte[] fallback=pixels(g);reset(g);clear(g);scope=g.beginText();g.glCallList(next);g.endText(scope);equal(fallback,pixels(g),"lazy cached standalone fallback");
        }finally{g.glDeleteLists(old,2);g.glDeleteLists(next,2);g.glDeleteLists(next,2);}
        check(g.cachedModels()==3,"font fixtures retired independently of game model registry");
    }
    private static void cachedScene(GameRenderCommands g,int first) {
        g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(770,771);g.glTranslatef(-.4f,-.2f,0);
        IntBuffer names=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asIntBuffer();names.put(999).put(first).put(first+1).put(first).put(first).put(999);names.position(1);names.limit(5);
        g.glCallLists(names);check(names.position()==1&&names.limit()==5,"original list buffer cursor retained");g.glColor4f(.1f,.8f,.4f,.6f);g.glCallList(first);
    }
    private static void litCached(GameRenderCommands g,int a,int b)throws Exception {
        int name=g.glGenLists(1);g.defineFontGlyphs(name,1);
        OriginalChunkEmitter t=new OriginalChunkEmitter(new File("bin/mcgl.jar"),128);
        g.glNewList(name,4864);t.begin();t.quad(-.4,-.3,.4,.3,0);t.draw();g.glTranslatef(.02f,.03f,0);g.glEndList();
        try {
            for(int shade:new int[]{7424,7425})for(boolean point:new boolean[]{false,true})for(int transform=0;transform<4;transform++) {
                final int style=transform;long draws=g.drawCalls(),batches=g.textDraws(),glyphs=g.textGlyphs();
                compare(g,"lit cached glyphs shade="+shade+" point="+point+" transform="+style,()->{
                    litSetup(g,a,b,point,5634,2977);g.glShadeModel(shade);
                    if(style==1){g.glRotatef(23,1,1,0);g.glScalef(.8f,.65f,1.1f);}
                    if(style==2){g.glTranslated(-8192,8192,0);g.glTranslated(8192,-8192,0);g.glScalef(-.9f,.8f,.6f);}
                    if(style==3)g.glScalef(.8f,.8f,0);
                    litGlyphs(g,name);
                });
                check(g.textDraws()-batches==1&&g.textGlyphs()-glyphs==8,"lit cached glyphs share one run");
                check(g.drawCalls()-draws==9,"eight standalone glyph draws versus one lit batch");
            }
            for(int material:new int[]{0,4608,4609,5634})for(int normalize:new int[]{0,2977,32826}) {
                compare(g,"lit cached material="+material+" normalize="+normalize,()->{
                    litSetup(g,a,b,true,material,normalize);g.glScalef(.9f,.7f,1.3f);litGlyphs(g,name);
                });
            }
            compare(g,"lit cached light/material/normalization/texture/attribute barriers",()->{
                litSetup(g,a,b,true,5634,2977);g.glCallList(name);
                g.glPushAttrib(0x40|0x2000|1);g.glDisable(16385);g.glColorMaterial(1032,4608);g.glColor4f(.7f,.2f,.4f,.6f);g.glCallList(name);
                g.glLight(16384,4611,floats(-.4f,.1f,1.2f,1));g.glCallList(name);g.glPopAttrib();g.glCallList(name);
                g.glDisable(2977);g.glNormal3f(.2f,.3f,.7f);g.glCallList(name);
                g.glBindTexture(3553,b);g.glCallList(name);g.glDisable(2896);g.glCallList(name);
                g.glEnable(2896);g.glCallList(name);
            });
            try(GameEffect effect=new GameEffect("/shader/default","/shader/default")) {
                long before=g.textGlyphs();
                compare(g,"lit cached custom effect retains standalone path",()->{
                    litSetup(g,a,b,false,5634,2977);effect.begin();try{g.glCallList(name);g.glTranslatef(.1f,.03f,0);g.glCallList(name);}finally{effect.end();}
                });
                check(g.textGlyphs()==before,"lit text does not replace custom effects");
            }
            for(int size:new int[]{0,1,16,17,32,33,64,65,128,129,256,257,512,513,1025}) {
                reset(g);litSetup(g,a,b,false,5634,2977);long glyphs=g.textGlyphs(),draws=g.textDraws();
                int scope=g.beginText();for(int i=0;i<size;i++)g.glCallList(name);g.endText(scope);
                check(g.textGlyphs()-glyphs==size&&g.textDraws()-draws==(size+511)/512,"lit count and bounded split at "+size);
            }
            reset(g);litSetup(g,a,b,false,5634,2977);
            for(int run=0;run<4;run++){int scope=g.beginText();for(int i=0;i<60;i++)g.glCallList(name);g.endText(scope);}
            long creations=g.transientMeshCreations();
            for(int run=0;run<8;run++){int scope=g.beginText();for(int i=0;i<60;i++)g.glCallList(name);g.endText(scope);}
            check(g.transientMeshCreations()==creations,"warmed lit text reuses GPU meshes");
        }finally{reset(g);g.glDeleteLists(name,1);}
    }
    private static void litSetup(GameRenderCommands g,int a,int b,boolean point,int material,int normalize) {
        g.glEnable(2896);g.glEnable(16384);g.glEnable(16385);
        g.glLight(16384,4611,floats(.4f,.7f,1,point?1:0));g.glLight(16385,4611,floats(-.8f,-.3f,.6f,point?1:0));
        g.glLight(16384,4608,floats(.04f,.03f,.02f,1));g.glLight(16385,4608,floats(.02f,.03f,.04f,1));
        g.glLight(16384,4609,floats(.45f,.33f,.25f,1));g.glLight(16385,4609,floats(.13f,.25f,.38f,1));
        g.glLightModel(2899,floats(.1f,.15f,.2f,1));
        if(material!=0){g.glColorMaterial(1032,material);g.glEnable(2903);}if(normalize!=0)g.glEnable(normalize);
        g.glEnable(3553);g.glBindTexture(3553,a);g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(770,771);
        g.glActiveTexture(33985);g.glEnable(3553);g.glBindTexture(3553,b);g.glActiveTexture(33984);
        g.glEnable(2912);g.glFogi(2917,9729);g.glFogf(2915,-.4f);g.glFogf(2916,1.5f);g.glFog(2918,floats(.2f,.1f,.3f,1));
    }
    private static void litGlyphs(GameRenderCommands g,int name) {
        for(int i=0;i<8;i++) {
            g.glPushMatrix();g.glTranslatef((i%4)*.4f-.6f,(i/4)*.7f-.35f,i*.02f);
            g.glScalef(.6f,.5f+i*.02f,.7f+i*.03f);g.glColor4f(.2f+i*.08f,.6f-i*.03f,.3f+i*.05f,.7f);
            g.glNormal3f(.2f+i*.07f,.4f,.8f-i*.07f);g.glMultiTexCoord2f(33985,i*.12f,.6f);g.glCallList(name);g.glPopMatrix();
        }
    }
    /** Identical account-free sign text through standalone and batched paths; timings are not game FPS. */
    private static void signCost(GameRenderCommands g,int a,int b)throws Exception {
        int name=g.glGenLists(1);g.defineFontGlyphs(name,1);
        OriginalChunkEmitter t=new OriginalChunkEmitter(new File("bin/mcgl.jar"),128);
        g.glNewList(name,4864);t.begin();t.quad(0,0,.018,.07,0);t.draw();g.glTranslatef(.021f,0,0);g.glEndList();
        try {
            byte[] expected=null;
            for(boolean batch:new boolean[]{false,true}) {
                reset(g);litSetup(g,a,b,false,5634,2977);g.glNormal3f(0,0,1);
                clear(g);signScene(g,name,batch);byte[] image=pixels(g);
                if(expected==null)expected=image;else equal(expected,image,"16 four-line lit signs, 16 glyphs per line");
                for(int frame=0;frame<16;frame++){clear(g);signScene(g,name,batch);GL11C.glFinish();}
                double[] times=new double[48];long draws=g.drawCalls(),created=g.transientMeshCreations();
                for(int frame=0;frame<times.length;frame++) {
                    long start=System.nanoTime();clear(g);signScene(g,name,batch);GL11C.glFinish();times[frame]=(System.nanoTime()-start)/1e6;
                }
                long perFrame=(g.drawCalls()-draws)/times.length;Arrays.sort(times);
                check(perFrame==(batch?64:1024),"sign fixture exact draw count");
                check(g.transientMeshCreations()==created,"warmed sign fixture has no GPU mesh churn");
                System.out.printf(Locale.ROOT,"SIGN_TEXT_COST case=%s glyphs=1024 lines=64 draws_per_frame=%d p50_ms=%.3f p95_ms=%.3f synthetic-not-game-fps%n",
                        batch?"lit-batched":"standalone",perFrame,times[times.length/2],times[(int)Math.ceil(times.length*.95)-1]);
            }
        }finally{reset(g);g.glDeleteLists(name,1);}
    }
    private static void signScene(GameRenderCommands g,int name,boolean batch) {
        for(int sign=0;sign<16;sign++) {
            g.glPushMatrix();g.glTranslatef((sign%4)*.48f-.94f,(sign/4)*.46f-.91f,0);g.glRotatef((sign%3-1)*15,0,1,0);
            for(int line=0;line<4;line++) {
                int scope=batch?g.beginText():0;g.glPushMatrix();g.glTranslatef(0,line*.085f,0);
                for(int letter=0;letter<16;letter++){if(letter%4==0)g.glColor4f(.3f+letter*.025f,.6f,.8f,1);g.glCallList(name);}
                g.glPopMatrix();if(batch)g.endText(scope);
            }
            g.glPopMatrix();
        }
    }
    private static void actualGlyphs(GameRenderCommands g,int a,int b)throws Exception {
        // TEST ONLY: bypass texture-loader/game constructors; invoke the actual untouched glyph methods.
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field single=unsafeType.getDeclaredField("theUnsafe");single.setAccessible(true);Object unsafe=single.get(null);
        Class<?> type=Class.forName("net.A.U.E.oOOO");Object font=unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,type);
        int[] widths=new int[256];Arrays.fill(widths,7);set(font,"Óo0000",widths);set(font,"voidsuper",a);
        byte[] glyphWidths=new byte[65536];Arrays.fill(glyphWidths,(byte)0x17);set(font,"Öo0000",glyphWidths);
        int[] pages=new int[256];Arrays.fill(pages,b);set(font,"floatsuper",pages);
        Method latin=type.getDeclaredMethod("Ó00000",int.class),unicode=type.getDeclaredMethod("Ó00000",char.class);latin.setAccessible(true);unicode.setAccessible(true);
        for(boolean shadow:new boolean[]{false,true})compare(g,"actual Latin/Unicode methods with page switches and shadow="+shadow,()->{
            g.glEnable(3553);g.glEnable(GL11C.GL_BLEND);g.glBlendFunc(770,771);g.glScalef(.027f,.045f,0);g.glTranslatef(-30,-15,0);set(font,"Ôo0000",-1);
            for(int pass=0;pass<(shadow?2:1);pass++) {
                set(font,"Oo0000",pass==0?1f:0f);set(font,"øO0000",pass==0?1f:0f);g.glColor4f(pass==0?.1f:.9f,pass==0?.2f:.8f,.5f,.8f);
                for(int i=0;i<9;i++){if(i%3==1)unicode.invoke(font,(char)(0x410+i));else latin.invoke(font,65+i);}
            }
        });
    }
    private static void fallback(GameRenderCommands g,int texture)throws Exception {
        long before=g.textGlyphs();
        compare(g,"lighting uses unchanged glyph path",()->{g.glEnable(2896);g.glEnable(16384);quad(g,7,true);quad(g,5,true);});
        check(g.textGlyphs()==before,"lit glyphs not misclassified");
        try(GameEffect effect=new GameEffect("/shader/default","/shader/default")) {
            compare(g,"custom effect uses unchanged glyph path",()->{g.glEnable(3553);g.glBindTexture(3553,texture);effect.begin();try{quad(g,7,true);quad(g,5,true);}finally{effect.end();}});
        }
        check(g.textGlyphs()==before,"custom effects not replaced by text shader");
    }
    private static void actualCachedStrings(GameRenderCommands g,int texture)throws Exception {
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field single=unsafeType.getDeclaredField("theUnsafe");single.setAccessible(true);
        Class<?> type=Class.forName("net.A.U.E.C");Object font=unsafeType.getMethod("allocateInstance",Class.class).invoke(single.get(null),type);
        int first=g.glGenLists(288);g.defineFontGlyphs(first,256);set(font,"class",first);set(font,"Ö00000",texture);
        int[] widths=new int[256];Arrays.fill(widths,7);set(font,"Ø00000",widths);
        OriginalChunkEmitter t=new OriginalChunkEmitter(new File("bin/mcgl.jar"),128);
        for(int i=0;i<288;i++) {
            g.glNewList(first+i,4864);
            if(i<256){t.begin();t.quad(0,0,6.9,7.9,0);t.draw();g.glTranslatef(7,0,0);}
            else {int c=i-256;g.glColor3f((c%3)*.4f,c<16?.8f:.2f,c<16?.6f:.15f);}
            g.glEndList();
        }
        try {
            String alphabet=(String)Class.forName("net.A.for.oo0O").getField("Õ00000").get(null);check(alphabet.length()>=128,"actual font alphabet resource available");
            for(boolean integer:new boolean[]{false,true}) {
                Class<?> coordinate=integer?int.class:float.class;Class<?>[] signature={String.class,coordinate,coordinate,int.class,boolean.class};
                Method body=type.getDeclaredMethod("mcglDrawTextBody",signature),wrapper=type.getMethod("o00000",signature);body.setAccessible(true);
                Object x=integer?(Object)Integer.valueOf(0):Float.valueOf(.15f),y=integer?(Object)Integer.valueOf(0):Float.valueOf(.25f);
                for(boolean shadow:new boolean[]{false,true})for(boolean lit:new boolean[]{false,true}) {
                    if(integer&&shadow)continue; // Integer boolean is decoration, not a shadow flag.
                    String message="AbCD §2EfGh §cIjKl MNOP";
                    reset(g);clear(g);cachedFontSetup(g,lit);set(font,"ô00000",ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asIntBuffer());
                    body.invoke(font,message,x,y,0xaaffcc80,shadow);byte[] expected=pixels(g);float[] expectedMatrix=matrix(g),expectedColor=color(g);
                    reset(g);clear(g);cachedFontSetup(g,lit);set(font,"ô00000",ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asIntBuffer());
                    long draws=g.textDraws(),glyphs=g.textGlyphs();wrapper.invoke(font,message,x,y,0xaaffcc80,shadow);
                    equal(expected,pixels(g),"actual complete cached string wrapper integer="+integer+" shadow="+shadow+" lit="+lit);
                    check(g.textDraws()-draws==1&&g.textGlyphs()-glyphs>12,"actual cached string batches across original buffer drains and color codes");
                    check(Arrays.equals(expectedMatrix,matrix(g))&&Arrays.equals(expectedColor,color(g)),"actual cached string final state retained");
                }
                set(font,"ô00000",null);boolean failed=false;
                try{wrapper.invoke(font,"A",x,y,-1,false);}catch(InvocationTargetException expected){failed=expected.getCause() instanceof NullPointerException;}
                check(failed,"actual wrapper preserves original exception");int scope=g.beginText();check(scope==1,"actual failed wrapper releases text scope");g.endText(scope);
                wrapper.invoke(font,null,x,y,-1,false);scope=g.beginText();check(scope==1,"actual null string releases text scope");g.endText(scope);
            }
        }finally{g.glDeleteLists(first,288);}
    }
    private static void cachedFontSetup(GameRenderCommands g,boolean lit) {
        g.glEnable(3553);
        if(lit){g.glEnable(2896);g.glEnable(16384);g.glEnable(2977);g.glEnable(2903);g.glNormal3f(0,0,-1);g.glLight(16384,4611,floats(.3f,.5f,-1,0));g.glRotatef(18,0,1,0);}
        g.glScalef(.012f,.08f,lit?.012f:0);
    }
    private static void scopes(GameRenderCommands g)throws Exception {
        reset(g);clear(g);long calls=g.textDraws();int outer=g.beginText();quad(g,7,false);int inner=g.beginText();quad(g,5,false);g.endText(inner);check(g.textDraws()==calls,"nested scope does not prematurely flush");g.endText(outer);check(g.textDraws()==calls+1,"outer scope flushes once");
        reset(g);clear(g);quad(g,7,false);byte[] expected=pixels(g);reset(g);clear(g);int scope=g.beginText();quad(g,7,false);g.glBegin(7);g.glVertex3f(0,0,0);g.abortText(scope);equal(expected,pixels(g),"failed scope preserves completed prefix and discards partial glyph");check(!g.insideBeginEnd(),"failed text leaves no open primitive");
        scope=g.beginText();boolean failed=false;try{g.endText(scope+1);}catch(IllegalStateException ok){failed=true;}check(failed,"mismatched scope rejected");g.abortText(scope);
        int[] nested=new int[64];for(int i=0;i<64;i++)nested[i]=g.beginText();failed=false;try{g.beginText();}catch(IllegalStateException ok){failed=true;}check(failed,"scope depth bounded");for(int i=63;i>=0;i--)g.endText(nested[i]);
        AtomicReference<Throwable> failure=new AtomicReference<Throwable>();Thread thread=new Thread(()->{try{g.beginText();}catch(Throwable e){failure.set(e);}});thread.start();thread.join();check(failure.get() instanceof IllegalStateException,"text scope enforces context owner");
        int name=g.glGenLists(1);g.glNewList(name,4864);check(g.beginText()==0,"unrelated compiled model retains old path");quad(g,7,false);g.endText(0);g.glEndList();g.glDeleteLists(name,1);
        failed=false;try{g.defineFontGlyphs(Integer.MAX_VALUE,256);}catch(IllegalArgumentException ok){failed=true;}check(failed,"unowned glyph range rejected");
        scope=g.beginText();g.glBegin(7);failed=false;try{g.glVertex3f(Float.NaN,0,0);}catch(IllegalArgumentException ok){failed=true;}g.abortText(scope);check(failed,"nonfinite glyph rejected and scope recovers");
    }
    private static void bounds(GameRenderCommands g)throws Exception {
        for(int size:new int[]{0,1,15,16,17,31,32,33,63,64,65,127,128,129,255,256,257,511,512,513,1025}) {
            compare(g,"bounded run size "+size,()->{for(int i=0;i<size;i++){g.glColor4f((i%3)*.4f,.5f,.8f,1);quad(g,7,false);}});
            reset(g);long glyphs=g.textGlyphs(),calls=g.textDraws();int scope=g.beginText();for(int i=0;i<size;i++)quad(g,5,false);g.endText(scope);
            check(g.textGlyphs()-glyphs==size&&g.textDraws()-calls==(size+511)/512,"exact live count and split at 512");
        }
        check(g.transientMeshCount()<=108&&g.transientMeshBytes()<=16L*1024*1024,"ordinary and both text layouts retain bounded streams");
        reset(g);for(int warm=0;warm<4;warm++){int scope=g.beginText();for(int i=0;i<60;i++)quad(g,7,false);g.endText(scope);}long creations=g.transientMeshCreations();
        for(int warm=0;warm<8;warm++){int scope=g.beginText();for(int i=0;i<60;i++)quad(g,7,false);g.endText(scope);}check(g.transientMeshCreations()==creations,"warmed text causes no GPU object churn");
    }
    private static void compare(GameRenderCommands g,String label,Scene scene)throws Exception {
        reset(g);clear(g);scene.draw();byte[] expected=pixels(g);float[] matrix=matrix(g),color=color(g);
        reset(g);clear(g);int scope=g.beginText();try{scene.draw();g.endText(scope);}catch(Throwable failure){g.abortText(scope);throw failure;}
        equal(expected,pixels(g),label);check(Arrays.equals(matrix,matrix(g))&&Arrays.equals(color,color(g)),"final logical state: "+label);
    }
    private static void quad(GameRenderCommands g,int mode,boolean varying) {
        float[] xy=mode==5?new float[]{-.6f,-.5f,-.6f,.5f,.6f,-.5f,.6f,.5f}:new float[]{-.6f,-.5f,.6f,-.5f,.6f,.5f,-.6f,.5f};
        g.glBegin(mode);for(int v=0;v<4;v++){if(varying)g.glColor4f(v*.23f,.8f-v*.17f,.2f+v*.21f,.6f+v*.1f);g.glTexCoord2f((xy[v*2]+.6f)/1.2f,(xy[v*2+1]+.5f));g.glVertex3f(xy[v*2],xy[v*2+1],0);}g.glEnd();
    }
    private static int texture(GameRenderCommands g,int variant) {
        int name=g.glGenTextures();g.glBindTexture(3553,name);g.glTexParameteri(3553,10241,9728);g.glTexParameteri(3553,10240,9728);ByteBuffer pixels=ByteBuffer.allocateDirect(8*8*4);
        for(int i=0;i<64;i++)pixels.put((byte)(variant==0?255:i*4)).put((byte)(i*3)).put((byte)(variant==0?i*4:255)).put((byte)(i%3==0?90:255));pixels.flip();g.glTexImage2D(3553,0,32856,8,8,0,6408,5121,pixels);return name;
    }
    private static void reset(GameRenderCommands g) {
        for(int unit=0;unit<2;unit++){g.glActiveTexture(33984+unit);g.glDisable(3553);g.glBindTexture(3553,0);g.glMatrixMode(5890);g.glLoadIdentity();g.glMultiTexCoord2f(33984+unit,0,0);}g.glActiveTexture(33984);
        for(int cap:new int[]{2896,16384,16385,2977,32826,3008,2912,2903,GL11C.GL_BLEND,GL11C.GL_DEPTH_TEST,GL11C.GL_CULL_FACE,GL11C.GL_SCISSOR_TEST})g.glDisable(cap);
        g.glColorMask(true,true,true,true);g.glDepthMask(true);g.glFrontFace(GL11C.GL_CCW);g.glShadeModel(7425);g.glColor4f(1,1,1,1);g.glViewport(0,0,64,64);
        g.glMatrixMode(5889);g.glLoadIdentity();g.glMatrixMode(5888);g.glLoadIdentity();
        g.glNormal3f(0,0,1);g.glColorMaterial(1032,5634);g.glEnable(2903);g.glColor4f(.2f,.2f,.2f,1);g.glColorMaterial(1032,4609);g.glColor4f(.8f,.8f,.8f,1);g.glDisable(2903);g.glColorMaterial(1032,5634);g.glColor4f(1,1,1,1);
        g.glLightModel(2899,floats(.2f,.2f,.2f,1));
        for(int i=0;i<2;i++){g.glLight(16384+i,4611,floats(0,0,1,0));g.glLight(16384+i,4608,floats(0,0,0,1));g.glLight(16384+i,4609,floats(i==0?1:0,i==0?1:0,i==0?1:0,1));}
    }
    private static void clear(GameRenderCommands g){g.glClearColor(0,0,0,1);g.glClear(GL11C.GL_COLOR_BUFFER_BIT);}
    private static byte[] pixels(GameRenderCommands g){ByteBuffer b=ByteBuffer.allocateDirect(64*64*4);g.glReadPixels(0,0,64,64,6408,5121,b);byte[] out=new byte[b.remaining()];b.get(out);return out;}
    private static float[] matrix(GameRenderCommands g){FloatBuffer b=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();g.glGetFloat(2982,b);float[] out=new float[16];b.get(out);return out;}
    private static float[] color(GameRenderCommands g){FloatBuffer b=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();g.glGetFloat(2816,b);float[] out=new float[4];b.get(out);return out;}
    private static FloatBuffer floats(float... values){FloatBuffer b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(values).flip();return b;}
    private static void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static void equal(byte[] expected,byte[] actual,String label){int differences=0,max=0;for(int i=0;i<expected.length;i++)if(expected[i]!=actual[i]){differences++;max=Math.max(max,Math.abs((expected[i]&255)-(actual[i]&255)));}check(differences==0,"RGBA mismatch: "+label+" bytes="+differences+" max="+max);}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
