import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

/** Executes original and patched upload bytecode and the packaged cache helpers.
 * Only GL calls are replaced, so no window, server, credentials or live profile. */
public final class LightmapPipelineTest implements Opcodes {
    static final String ENGINE = "net/A/U/Oooo";
    static final String HELPER = "local.mcgl.perf.LightmapCache";
    static Object context = new Object();
    static int bound, calls, failNext;
    static final Map<Integer, byte[]> textures = new HashMap<Integer, byte[]>();
    static final Map<Integer, Integer> parameters = new HashMap<Integer, Integer>();
    public static final class Settings { public boolean anaglyph; }
    public static final class FakeGL {
        public static Object getCapabilities() { return context; }
        public static void unsupported(String ignored) {}
        public static void glBindTexture(int target, int id) { bound = id; }
        public static void glTexParameteri(int target, int name, int value) { parameters.put(name, value); }
        public static void glTexSubImage2D(int target, int level, int x, int y,
                int width, int height, int format, int type, ByteBuffer pixels) {
            if (failNext-- > 0) throw new IllegalStateException("injected upload failure");
            calls++;
            byte[] data = new byte[width * height * 4];
            pixels.duplicate().get(data);
            textures.put(bound, data);
        }
    }
    static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    static final class Helpers extends ClassLoader {
        final String jarPath;
        Helpers(String path) { super(LightmapPipelineTest.class.getClassLoader()); jarPath = path; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("local.mcgl.perf.")) return super.loadClass(name, resolve);
            Class<?> c = findLoadedClass(name);
            if (c == null) try (JarFile jar = new JarFile(jarPath)) {
                ClassNode n = new ClassNode();
                new ClassReader(jar.getInputStream(jar.getJarEntry(name.replace('.','/')+".class"))).accept(n,ClassReader.SKIP_FRAMES);
                for (MethodNode m : n.methods) for (AbstractInsnNode ins : m.instructions.toArray())
                    if (ins instanceof MethodInsnNode) {
                        MethodInsnNode i = (MethodInsnNode)ins;
                        if (i.owner.equals("org/lwjgl/opengl/GL11")) i.owner = "LightmapPipelineTest$FakeGL";
                        if (i.owner.equals("org/lwjgl/opengl/GLContext") && i.name.equals("getCapabilities")) {
                            i.owner = "LightmapPipelineTest$FakeGL"; i.desc = "()Ljava/lang/Object;";
                        }
                    }
                ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                n.accept(w); byte[] bytes = w.toByteArray(); c = defineClass(name,bytes,0,bytes.length);
            } catch(Exception e) { throw new ClassNotFoundException(name,e); }
            if (resolve) resolveClass(c); return c;
        }
        Class<?> define(String name, byte[] bytes) { return defineClass(name,bytes,0,bytes.length); }
    }
    static Object engine(String jarPath, String name, Helpers loader) throws Exception {
        ClassNode original = new ClassNode();
        try(JarFile jar = new JarFile(jarPath)) {
            new ClassReader(jar.getInputStream(jar.getJarEntry(ENGINE+".class"))).accept(original,ClassReader.SKIP_FRAMES);
        }
        ClassNode c = new ClassNode(); c.version = V1_6; c.access = ACC_PUBLIC;
        c.name = ENGINE; c.superName = "java/lang/Object";
        for(MethodNode m: original.methods)
            if(m.desc.equals("([IIII)V") && (m.name.equals("o00000") || m.name.equals("mcglUploadLightmap")))
                c.methods.add(m);
        for(FieldNode f:original.fields)
            if(Arrays.asList("new","ø00000","Ó00000","return","ÔO0000").contains(f.name))
                c.fields.add(new FieldNode(ACC_PUBLIC|(f.access&ACC_STATIC),f.name,f.desc,null,null));
        MethodNode ctor=new MethodNode(ACC_PUBLIC,"<init>","()V",null,null);
        ctor.visitVarInsn(ALOAD,0); ctor.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V");
        ctor.visitInsn(RETURN); ctor.visitMaxs(1,1); c.methods.add(ctor);
        Map<String,String> names=new HashMap<String,String>();
        names.put(ENGINE,name); names.put("net/A/for/o00Oo","LightmapPipelineTest$Settings");
        names.put("net/A/for/o00Oo.öÕ0000","anaglyph");
        names.put(ENGINE+".new","buffer"); names.put(ENGINE+".ÔO0000","settings");
        names.put(ENGINE+".return","clamp"); names.put(ENGINE+".Ó00000","blur"); names.put(ENGINE+".ø00000","mipmap");
        names.put(ENGINE+".o00000([IIII)V","generic");
        names.put("org/lwjgl/opengl/GL11","LightmapPipelineTest$FakeGL");
        names.put("local/mcgl/perf/ChunkVbo","LightmapPipelineTest$FakeGL");
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
        c.accept(new RemappingClassAdapter(writer,new SimpleRemapper(names)));
        Class<?> cls=loader.define(name,writer.toByteArray());
        Object o=cls.newInstance();
        cls.getField("buffer").set(o,ByteBuffer.allocateDirect(4096));
        cls.getField("settings").set(o,new Settings());
        return o;
    }
    static Method uploadMethod(Object engine, boolean special) throws Exception {
        return engine.getClass().getMethod(special?"mcglUploadLightmap":"generic",int[].class,int.class,int.class,int.class);
    }
    static void upload(Object engine, boolean special, int[] pixels, int edge, int id) throws Exception {
        uploadMethod(engine,special).invoke(engine,pixels,edge,edge,id);
    }
    public static void main(String[] args) throws Exception {
        boolean enabled = !"false".equals(System.getProperty("mcgl.lightmap.cache"));
        Helpers loader=new Helpers(args[1]);
        Object original=engine(args[0],"OriginalLightmapUploader",loader);
        Object patched=engine(args[1],"PatchedLightmapUploader",loader);
        Class<?> cache=loader.loadClass(HELPER), animation=loader.loadClass("local.mcgl.perf.AnimationCache");
        Method invalidate=animation.getMethod("invalidateAll");
        Method cachedAnimation=animation.getMethod("upload",Object.class,byte[].class,int.class,int.class,int.class,int.class,int.class);
        Object animationOwner=new Object(); byte[] animationBytes=new byte[16];
        Random random=new Random(164); int[] pixels=new int[256];
        int originalCalls=0, patchedCalls=0;
        for(int frame=0;frame<5000;frame++) {
            if(frame%7==0) for(int i=0;i<pixels.length;i++) pixels[i]=random.nextInt();
            boolean anaglyph=frame/31%2==0, blur=frame/43%2==0, clamp=frame/53%2==0, mipmap=frame/67%2==0;
            int id=10+frame/101%3;
            for(Object e:new Object[]{original,patched}) {
                ((Settings)e.getClass().getField("settings").get(e)).anaglyph=anaglyph;
                e.getClass().getField("blur").setBoolean(e,blur);
                e.getClass().getField("clamp").setBoolean(e,clamp);
                e.getClass().getField("mipmap").setBoolean(null,mipmap);
            }
            if(frame%113==0) { invalidate.invoke(null); textures.remove(id); }
            if(frame%199==0) { context=new Object(); textures.clear(); }
            // Keep separate emulated texture stores: the original must not hide
            // a missing write by the patched pipeline.
            byte[] previous=textures.get(id);
            int before=calls; upload(original,false,pixels,16,id); originalCalls+=calls-before;
            byte[] expected=textures.get(id).clone();
            Map<Integer,Integer> expectedParams=new HashMap<Integer,Integer>(parameters);
            if(previous==null) textures.remove(id); else textures.put(id,previous);
            parameters.clear(); bound=-1;
            before=calls; upload(patched,true,pixels,16,id); patchedCalls+=calls-before;
            check(Arrays.equals(expected,textures.get(id)),"pixel mismatch frame "+frame);
            check(bound==id && expectedParams.equals(parameters),"binding/sampler side effects frame "+frame);
        }
        check(originalCalls==5000,"all original uploads");
        check(enabled?patchedCalls<1500:patchedCalls==5000,"repeated lighting frames");

        invalidate.invoke(null);
        check((Boolean)cachedAnimation.invoke(null,animationOwner,animationBytes,0,0,0,2,1),"first animation");
        check(!(Boolean)cachedAnimation.invoke(null,animationOwner,animationBytes,0,0,0,2,1),"cached animation");
        upload(patched,true,pixels,16,100);
        check(((Boolean)cachedAnimation.invoke(null,animationOwner,animationBytes,0,0,0,2,1)) != enabled,
            "lighting isolation / opt-out");
        upload(patched,false,pixels,16,200);
        check((Boolean)cachedAnimation.invoke(null,animationOwner,animationBytes,0,0,0,2,1),"generic writes invalidate animation");
        int before=calls; upload(patched,true,pixels,16,100);
        check(calls==before+1,"generic write invalidates lighting");
        invalidate.invoke(null);
        upload(patched,true,pixels,16,100);
        failNext=1; pixels[0]^=123;
        try { upload(patched,true,pixels,16,100); throw new AssertionError("expected injected failure"); }
        catch(InvocationTargetException expected) { check(expected.getCause() instanceof IllegalStateException,"injected exception"); }
        before=calls; upload(patched,true,pixels,16,100); check(calls==before+1,"retry after failed upload");
        // Unknown shapes always upload and preserve former global invalidation.
        before=calls; upload(patched,true,new int[64],8,101); upload(patched,true,new int[64],8,101);
        check(calls==before+2,"unknown format fallback");
        Method report=cache.getDeclaredMethod("report"); report.setAccessible(true); report.invoke(null);
        System.out.println("LIGHTMAP_PIPELINE_PASS frames=5000 uploads="+originalCalls+"->"+patchedCalls+
            " enabled="+enabled+" | exact pixels every frame, anaglyph, sampler/binding state, texture ids, reload/context changes, atlas isolation, exceptions, fallback; NOT game FPS");
    }
}
