import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class PatchMCGLQuadSort implements Opcodes {
    static final String HELPER = "local/mcgl/perf/QuadSort";
    static void patch(ClassNode tess) {
        for (MethodNode m : tess.methods) if (m.name.equals("Ó00000") && m.desc.equals("(DDD)V")) {
            long fingerprint = PatchMCGLChunkVbo.fingerprint(m);
            if (fingerprint != 3491325863L) throw new IllegalStateException("Unsupported MCGL quad-sort fingerprint: " + fingerprint);
            InsnList hook = new InsnList(); LabelNode original = new LabelNode();
            hook.add(new VarInsnNode(ALOAD, 0)); hook.add(new FieldInsnNode(GETFIELD, tess.name, "Õ00000", "[I"));
            hook.add(new VarInsnNode(ALOAD, 0)); hook.add(new FieldInsnNode(GETFIELD, tess.name, "return", "I"));
            hook.add(new VarInsnNode(DLOAD, 1)); hook.add(new VarInsnNode(DLOAD, 3)); hook.add(new VarInsnNode(DLOAD, 5));
            hook.add(new MethodInsnNode(INVOKESTATIC, HELPER, "sort", "([IIDDD)Z"));
            hook.add(new JumpInsnNode(IFEQ, original)); hook.add(new InsnNode(RETURN)); hook.add(original);
            m.instructions.insert(hook);
            return;
        }
        throw new IllegalStateException("Missing MCGL quad sorter");
    }
}
