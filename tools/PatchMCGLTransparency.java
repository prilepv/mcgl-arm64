import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Enables the official client's existing transparent-quad sorting by default. */
public final class PatchMCGLTransparency implements Opcodes {
    static final String SETTINGS = "net/A/for/o00Oo";
    static final String RENDERER = "net/A/U/H";
    static final String ALPHA_SORT = "ÖÓ0000";

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Unsupported MCGL transparency bytecode: " + message);
        }
    }

    static void patch(Map<String, ClassNode> classes, Set<String> changed) {
        ClassNode settings = classes.get(SETTINGS + ".class");
        ClassNode renderer = classes.get(RENDERER + ".class");
        require(settings != null && renderer != null, "required classes missing");

        int fields = 0;
        for (FieldNode field : settings.fields) {
            if (field.name.equals(ALPHA_SORT) && field.desc.equals("Z")) fields++;
        }
        require(fields == 1, "alpha-sort field");

        MethodNode gate = null;
        MethodNode constructor = null;
        for (MethodNode method : settings.methods) {
            if (method.name.equals("void") && method.desc.equals("()Z")) gate = method;
            if (method.name.equals("<init>") && method.desc.equals(
                    "(Lnet/minecraft/client/Minecraft;Ljava/io/File;)V")) constructor = method;
        }
        require(gate != null && constructor != null, "settings methods");

        int gateReads = 0;
        for (AbstractInsnNode instruction : gate.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == GETFIELD && field.owner.equals(SETTINGS) &&
                        field.name.equals(ALPHA_SORT) && field.desc.equals("Z")) gateReads++;
            }
        }
        require(gateReads == 1, "alpha-sort gate changed");

        int rendererCalls = 0;
        for (MethodNode method : renderer.methods) {
            if (!method.name.equals("Ö00000") || !method.desc.equals("()V")) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.getOpcode() == INVOKEVIRTUAL && call.owner.equals(SETTINGS) &&
                            call.name.equals("void") && call.desc.equals("()Z")) rendererCalls++;
                }
            }
        }
        require(rendererCalls == 1, "chunk renderer no longer uses alpha-sort gate");

        int defaults = 0;
        for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode)) continue;
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (field.getOpcode() != PUTFIELD || !field.owner.equals(SETTINGS) ||
                    !field.name.equals(ALPHA_SORT) || !field.desc.equals("Z")) continue;
            AbstractInsnNode value = instruction.getPrevious();
            AbstractInsnNode receiver = value == null ? null : value.getPrevious();
            require(value != null && value.getOpcode() == ICONST_0,
                    "alpha-sort default is no longer false");
            require(receiver instanceof VarInsnNode && receiver.getOpcode() == ALOAD &&
                    ((VarInsnNode) receiver).var == 0, "alpha-sort constructor shape");
            constructor.instructions.set(value, new org.objectweb.asm.tree.InsnNode(ICONST_1));
            defaults++;
        }
        require(defaults == 1, "single alpha-sort default");
        changed.add(settings.name);
    }

    private PatchMCGLTransparency() {}
}
