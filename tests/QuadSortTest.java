import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import local.mcgl.perf.QuadSort;

/** Differential oracle is the actual original bytecode, not a reimplementation. */
public final class QuadSortTest implements Opcodes {
    public interface Sorter { void sort(int[] raw, int words, double x, double y, double z); }
    static int serial;
    static Sorter fixture(String path, boolean optimized) throws Exception {
        ClassNode original = new ClassNode();
        try (JarFile jar = new JarFile(path)) {
            ClassNode chunk = new ClassNode(); new ClassReader(jar.getInputStream(jar.getJarEntry("net/A/U/H.class"))).accept(chunk, 0);
            String tess = null;
            for (FieldNode f : chunk.fields) if ((f.access & ACC_STATIC) != 0 && f.desc.startsWith("Lnet/A/for/")) tess = f.desc.substring(1, f.desc.length() - 1);
            new ClassReader(jar.getInputStream(jar.getJarEntry(tess + ".class"))).accept(original, 0);
        }
        ClassNode c = new ClassNode(); c.version = V1_6; c.access = ACC_PUBLIC; c.name = original.name; c.superName = "java/lang/Object";
        c.interfaces.add("QuadSortTest$Sorter");
        for (MethodNode m : original.methods) if (m.name.equals("Ó00000") && m.desc.equals("(DDD)V")) c.methods.add(m);
        if (optimized) PatchMCGLQuadSort.patch(c);
        c.fields.add(new FieldNode(ACC_PRIVATE, "Õ00000", "[I", null, null));
        c.fields.add(new FieldNode(ACC_PRIVATE, "return", "I", null, null));
        MethodNode init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitVarInsn(ALOAD, 0); init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V"); init.visitInsn(RETURN); c.methods.add(init);
        MethodNode wrapper = new MethodNode(ACC_PUBLIC, "sort", "([IIDDD)V", null, null);
        wrapper.visitVarInsn(ALOAD, 0); wrapper.visitVarInsn(ALOAD, 1); wrapper.visitFieldInsn(PUTFIELD, c.name, "Õ00000", "[I");
        wrapper.visitVarInsn(ALOAD, 0); wrapper.visitVarInsn(ILOAD, 2); wrapper.visitFieldInsn(PUTFIELD, c.name, "return", "I");
        wrapper.visitVarInsn(ALOAD, 0); wrapper.visitVarInsn(DLOAD, 3); wrapper.visitVarInsn(DLOAD, 5); wrapper.visitVarInsn(DLOAD, 7);
        wrapper.visitMethodInsn(INVOKEVIRTUAL, c.name, "Ó00000", "(DDD)V"); wrapper.visitInsn(RETURN); c.methods.add(wrapper);
        String name = "SortFixture" + serial++;
        // Recompute fixture frames after adding the entry hook and remapping.
        // Keep the original compressed frames until fingerprint validation.
        for (MethodNode m : c.methods) for (AbstractInsnNode insn : m.instructions.toArray())
            if (insn instanceof FrameNode) m.instructions.remove(insn);
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        c.accept(new RemappingClassAdapter(out, new SimpleRemapper(c.name, name)));
        final byte[] bytes = out.toByteArray();
        Class<?> type = new ClassLoader(QuadSortTest.class.getClassLoader()) { Class<?> define() { return defineClass(null, bytes, 0, bytes.length); } }.define();
        return (Sorter)type.newInstance();
    }
    static int[] data(int n, int kind, Random random) {
        int[] words = new int[n * 32 + 19];
        for (int i = 0; i < words.length; i++) words[i] = random.nextInt();
        for (int i = 0; i < n; i++) {
            float x = kind == 0 || kind == 4 ? random.nextInt(40) - 20 : kind == 1 ? i : n - i;
            float y = kind == 0 || kind == 4 ? random.nextInt(40) - 20 : 0;
            float z = 0;
            if (kind == 0 && i % 31 == 0) x = Float.NaN;
            if (kind == 0 && i % 37 == 0) y = Float.POSITIVE_INFINITY;
            for (int v : new int[]{0, 16}) {
                words[i * 32 + v] = Float.floatToRawIntBits(x);
                words[i * 32 + v + 1] = Float.floatToRawIntBits(y);
                words[i * 32 + v + 2] = Float.floatToRawIntBits(z);
                // Include unequal opposite vertices, subnormals, signed zero,
                // overflow and arbitrary NaN payloads, not just integer centers.
                if (kind == 3) for (int axis = 0; axis < 3; axis++) words[i * 32 + v + axis] = random.nextInt();
            }
        }
        return words;
    }
    static void differential(Sorter old, Sorter fast, int[] data, int n, double x, double y, double z) {
        int[] a = data.clone(), b = data.clone();
        old.sort(a, n * 32, x, y, z); fast.sort(b, n * 32, x, y, z);
        if (!Arrays.equals(a, b)) throw new AssertionError("Different ordering: n=" + n + " camera=" + x);
    }
    public static void main(String[] args) throws Exception {
        final Sorter old = fixture(args[0], false), fast = fixture(args[0], true);
        Random random = new Random(129567);
        for (int i = 0; i < 5000; i++) {
            int n = random.nextInt(257);
            differential(old, fast, data(n, i % 4, random), n, i % 113 == 0 ? Double.NaN : random.nextDouble() * 30,
                    i % 137 == 0 ? Double.POSITIVE_INFINITY : -3.5, i % 17);
        }
        differential(old, fast, data(4096, 1, random), 4096, 0, 0, 0);
        differential(old, fast, data(65536, 2, random), 65536, 0, 0, 0);
        if (!"false".equals(System.getProperty("mcgl.quad.sort"))) {
            if (QuadSort.sort(new int[31], 31, 0, 0, 0) || QuadSort.sort(new int[32], 64, 0, 0, 0) || QuadSort.sort(null, 0, 0, 0, 0)) throw new AssertionError("Invalid input accepted");
        }
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> jobs = new ArrayList<Future<?>>();
            for (int thread = 0; thread < 2; thread++) {
                final Sorter a = fixture(args[0], false), b = fixture(args[0], true);
                jobs.add(workers.submit(new Runnable() { public void run() {
                    Random r = new Random(123);
                    for (int i = 0; i < 200; i++) differential(a, b, data(512, i % 3, r), 512, i, 3, 1);
                }}));
            }
            for (Future<?> job : jobs) job.get();
        } finally { workers.shutdown(); }
        System.out.println("QUAD_SORT_PASS 5402 exact bytecode comparisons, stable ties/NaN/Infinity, tail preserved, 65536 quads, 2 threads");
        for (int kind : new int[]{1, 2, 4, 0}) {
            int[] source = data(2048, kind, random);
            double[] before = new double[7], after = new double[7];
            for (int i = -5; i < 7; i++) {
                int[] a = source.clone(), b = source.clone();
                long start = System.nanoTime(); old.sort(a, 2048 * 32, 0, 0, 0); long middle = System.nanoTime();
                fast.sort(b, 2048 * 32, 0, 0, 0); long end = System.nanoTime();
                if (i >= 0) { before[i] = (middle - start) / 1e6; after[i] = (end - middle) / 1e6; }
            }
            Arrays.sort(before); Arrays.sort(after);
            System.out.printf(Locale.ROOT, "QUAD_SORT_BENCH kind=%d quads=2048 oldMs=%.4f newMs=%.4f (CPU operation, not FPS)%n", kind, before[3], after[3]);
        }
    }
}
