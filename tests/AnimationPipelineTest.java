import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import local.mcgl.perf.AnimationCache;

/** Executes the actual official animation method before/after transformation,
 * replacing only its dependencies with deterministic in-memory fixtures. */
public final class AnimationPipelineTest implements Opcodes {
    static final String ENGINE = "net/A/U/Oooo", FX = "net/A/U/A/J";
    public static String terrain = "terrain-A", items = "items-A";
    static Context current;

    public static final class Settings { public boolean anaglyph; }
    public static final class Coordinates {
        public static int x(int index) { return index % 16; }
        public static int y(int index) { return index / 16; }
    }
    public static final class Effect {
        public boolean anaglyph;
        public int sheet, index, repeat, edge, ticks;
        public byte[] pixels;
        Effect(int index, int edge, int repeat, int sheet) {
            this.index = index; this.edge = edge; this.repeat = repeat; this.sheet = sheet;
            pixels = new byte[edge * edge * 4];
        }
        public int size() { return edge; }
        public void tick() {
            ticks++;
            // Both mutated-in-place arrays and fresh arrays with identical data.
            if (ticks % 29 == 0) pixels = pixels.clone();
            Arrays.fill(pixels, (byte)(ticks / 4 + index + (anaglyph ? 1 : 0)));
            current.ticks++;
        }
        public void bind(Object engine) {
            current.binds++;
            current.bound = sheet == 1 ? items : terrain;
            if (!current.atlases.containsKey(current.bound)) {
                current.atlases.put(current.bound, new byte[64 * 64 * 4]);
                // Mirrors a lazy texture load/reload invalidating the cache.
                AnimationCache.invalidateAll();
            }
        }
    }
    static final class Context {
        Map<String, byte[]> atlases = new HashMap<String, byte[]>();
        String bound;
        long copies, uploads, ticks, binds;
    }
    public static final class FakeGL {
        public static ByteBuffer put(ByteBuffer buffer, byte[] pixels) {
            current.copies++;
            return buffer.put(pixels);
        }
        public static void glTexSubImage2D(int target, int level, int x, int y,
                int width, int height, int format, int type, ByteBuffer pixels) {
            if (width != height) throw new AssertionError("Unexpected rectangle");
            current.uploads++;
            byte[] atlas = current.atlases.get(current.bound);
            for (int row = 0; row < height; row++) for (int col = 0; col < width * 4; col++)
                atlas[((y + row) * 64 + x) * 4 + col] = pixels.get(row * width * 4 + col);
        }
    }

    static Object pipeline(String jarPath, final String name, boolean optimized) throws Exception {
        ClassNode original = new ClassNode();
        try (JarFile jar = new JarFile(jarPath)) {
            new ClassReader(jar.getInputStream(jar.getJarEntry(ENGINE + ".class"))).accept(original, ClassReader.SKIP_FRAMES);
        }
        ClassNode c = new ClassNode();
        c.version = V1_6; c.access = ACC_PUBLIC; c.name = ENGINE; c.superName = "java/lang/Object";
        for (MethodNode m : original.methods) if (m.name.equals("Õ00000") && m.desc.equals("()V")) c.methods.add(m);
        check(c.methods.size() == 1, "official animation method");
        if (optimized) {
            Field keys = PatchMCGLPerformance.class.getDeclaredField("atlasKeys"); keys.setAccessible(true);
            @SuppressWarnings("unchecked") List<InsnList> values = (List<InsnList>)keys.get(null);
            values.clear();
            for (String field : new String[]{"terrain", "items"}) {
                InsnList expression = new InsnList();
                expression.add(new FieldInsnNode(GETSTATIC, "AnimationPipelineTest", field, "Ljava/lang/String;"));
                values.add(expression);
            }
            Method patch = PatchMCGLPerformance.class.getDeclaredMethod("patchTextures", ClassNode.class);
            patch.setAccessible(true); patch.invoke(null, c);
        }
        c.fields.add(new FieldNode(ACC_PUBLIC, "new", "Ljava/nio/ByteBuffer;", null, null));
        c.fields.add(new FieldNode(ACC_PUBLIC, "OO0000", "Ljava/util/List;", null, null));
        c.fields.add(new FieldNode(ACC_PUBLIC, "ÔO0000", "Lnet/A/for/o00Oo;", null, null));
        MethodNode constructor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V");
        constructor.visitInsn(RETURN); constructor.visitMaxs(1, 1); c.methods.add(constructor);
        Map<String, String> names = new HashMap<String, String>();
        names.put(ENGINE, name); names.put(FX, "AnimationPipelineTest$Effect");
        names.put("net/A/for/o00Oo", "AnimationPipelineTest$Settings");
        names.put("net/A/U/K", "AnimationPipelineTest$Coordinates");
        names.put("org/lwjgl/opengl/GL11", "AnimationPipelineTest$FakeGL");
        names.put(ENGINE + ".new", "buffer"); names.put(ENGINE + ".OO0000", "effects");
        names.put(ENGINE + ".ÔO0000", "settings"); names.put(ENGINE + ".Õ00000()V", "animate");
        names.put(FX + ".new", "anaglyph"); names.put(FX + ".return", "pixels");
        names.put(FX + ".o00000", "sheet"); names.put(FX + ".Ô00000", "index"); names.put(FX + ".Ó00000", "repeat");
        names.put(FX + ".new()I", "size"); names.put(FX + ".o00000()V", "tick");
        names.put(FX + ".o00000(L" + ENGINE + ";)V", "bind");
        names.put("net/A/for/o00Oo.öÕ0000", "anaglyph");
        names.put("net/A/U/K.new(I)I", "x"); names.put("net/A/U/K.o00000(I)I", "y");
        ClassNode remapped = new ClassNode();
        c.accept(new RemappingClassAdapter(remapped, new SimpleRemapper(names)));
        for (MethodNode m : remapped.methods) for (AbstractInsnNode n : m.instructions.toArray())
            if (n instanceof MethodInsnNode) {
                MethodInsnNode i = (MethodInsnNode)n;
                if (i.owner.equals("AnimationPipelineTest$Effect") && i.name.equals("bind")) i.desc = "(Ljava/lang/Object;)V";
                if (i.owner.equals("java/nio/ByteBuffer") && i.name.equals("put") && i.desc.equals("([B)Ljava/nio/ByteBuffer;"))
                    m.instructions.set(i, new MethodInsnNode(INVOKESTATIC, "AnimationPipelineTest$FakeGL", "put", "(Ljava/nio/ByteBuffer;[B)Ljava/nio/ByteBuffer;"));
            }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        remapped.accept(writer);
        final byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(AnimationPipelineTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(name, bytes, 0, bytes.length); }
        }.define();
        Object instance = type.newInstance();
        type.getField("buffer").set(instance, ByteBuffer.allocateDirect(4096));
        type.getField("settings").set(instance, new Settings());
        List<Effect> effects = new ArrayList<Effect>();
        // Repeated indices, overlapping regions, varying sizes and both atlases.
        for (int i = 0; i < 24; i++) effects.add(new Effect(i % 19, i % 5 == 0 ? 4 : 2, i % 3 == 0 ? 2 : 1, i % 2));
        type.getField("effects").set(instance, effects);
        return instance;
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Object baseline = pipeline(args[0], "OriginalAnimationPipeline", false);
        Object optimized = pipeline(args[0], "OptimizedAnimationPipeline", true);
        Method originalTick = baseline.getClass().getMethod("animate"), patchedTick = optimized.getClass().getMethod("animate");
        Context before = new Context(), after = new Context();
        for (int frame = 0; frame < 2000; frame++) {
            terrain = "terrain-" + (frame / 71 % 3); items = "items-" + (frame / 47 % 2);
            if (frame % 113 == 0) {
                before.atlases.clear(); after.atlases.clear(); AnimationCache.invalidateAll();
            }
            ((Settings)baseline.getClass().getField("settings").get(baseline)).anaglyph = frame % 97 < 40;
            ((Settings)optimized.getClass().getField("settings").get(optimized)).anaglyph = frame % 97 < 40;
            current = before; originalTick.invoke(baseline);
            current = after; patchedTick.invoke(optimized);
            check(before.ticks == after.ticks && before.binds == after.binds, "tick/binder order changed at " + frame);
            check(before.atlases.keySet().equals(after.atlases.keySet()), "atlas keys differ");
            for (String key : before.atlases.keySet()) check(Arrays.equals(before.atlases.get(key), after.atlases.get(key)), "pixels differ at " + frame + "/" + key);
        }
        check(after.copies < before.copies, "unchanged frame copies were not skipped");
        check(after.uploads < before.uploads, "unchanged uploads were not skipped");
        System.out.println("ANIMATION_PIPELINE_PASS frames=2000 ticks=" + after.ticks + " binds=" + after.binds +
            " copies=" + before.copies + "->" + after.copies + " uploads=" + before.uploads + "->" + after.uploads +
            " | exact pixel equality, reloads, atlas switches, overlapping writes; not game FPS");
    }
}
