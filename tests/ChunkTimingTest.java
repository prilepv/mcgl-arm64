import java.lang.reflect.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import local.mcgl.perf.RenderDiagnostics;

/** Exercise the original clean/dirty entry guard with the new timing hook. */
public class ChunkTimingTest implements Opcodes {
    public static void main(String[] args) throws Exception {
        ClassNode original = new ClassNode();
        try (JarFile jar = new JarFile(args[0])) {
            new ClassReader(jar.getInputStream(jar.getJarEntry("net/A/U/H.class"))).accept(original, 0);
        }
        ClassNode fixture = new ClassNode(); fixture.name = original.name;
        fixture.version = V1_6; fixture.access = ACC_PUBLIC; fixture.superName = "java/lang/Object";
        fixture.fields.add(new FieldNode(ACC_PUBLIC, "õ00000", "Z", null, null));
        fixture.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "privatesuper", "I", null, null));
        MethodNode body = null;
        for (MethodNode m : original.methods) if (m.name.equals("Ö00000") && m.desc.equals("()V")) body = m;
        if (body == null) throw new AssertionError("No original rebuild method");
        boolean tail = false;
        for (AbstractInsnNode n : body.instructions.toArray()) {
            if (tail) body.instructions.remove(n);
            else if (n instanceof FieldInsnNode && n.getOpcode() == PUTSTATIC && ((FieldInsnNode)n).name.equals("privatesuper")) tail = true;
        }
        body.instructions.add(new InsnNode(RETURN)); fixture.methods.add(body);
        MethodNode ctor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitVarInsn(ALOAD, 0); ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V");
        ctor.visitInsn(RETURN); fixture.methods.add(ctor);
        Method hook = PatchMCGLPerformance.class.getDeclaredMethod("patchPhaseProbes", ClassNode.class);
        hook.setAccessible(true); hook.invoke(null, fixture);
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        fixture.accept(out); final byte[] bytes = out.toByteArray();
        Class<?> type = new ClassLoader(ChunkTimingTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        Object chunk = type.newInstance(); Method rebuild = type.getMethod("Ö00000");
        Field countsField = RenderDiagnostics.class.getDeclaredField("count"); countsField.setAccessible(true);
        long[] count = (long[])countsField.get(null);
        for (int i = 0; i < 1000; i++) rebuild.invoke(chunk);
        if (count[3] != 0 || type.getField("privatesuper").getInt(null) != 0) throw new AssertionError("Clean chunk counted");
        type.getField("õ00000").setBoolean(chunk, true);
        for (int i = 0; i < 1000; i++) rebuild.invoke(chunk);
        long expected = Boolean.getBoolean("mcgl.graphics.profile") ? 1000 : 0;
        if (count[3] != expected || type.getField("privatesuper").getInt(null) != 1000) throw new AssertionError("Dirty count");
        System.out.println("CHUNK_TIMING_PASS enabled=" + Boolean.getBoolean("mcgl.graphics.profile") + " clean=0 dirty=1000, original guard preserved");
    }
}
