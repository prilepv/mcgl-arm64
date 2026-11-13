import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Publisher-only comparison of guarded methods against a signed reference. */
public final class GuardDelta {
    public static void main(String[] args) throws Exception {
        Set<Long> guards = new HashSet<>();
        for (String line : Files.readAllLines(Path.of(args[2]))) {
            guards.add(Long.parseLong(line));
        }
        Set<String> aliases = new java.util.TreeSet<>();
        Map<String, ClassNode> reference = ClientShapeAudit.read(args[0]);
        Map<String, ClassNode> candidate = ClientShapeAudit.read(args[1]);
        for (ClassNode type : reference.values()) {
            for (MethodNode method : type.methods) {
                long expected = MCGLBytecodeFingerprint.of(method);
                if (!guards.contains(expected)) continue;
                String label = type.name + "\t" + method.name + "\t" + method.desc;
                ClassNode changed = candidate.get(type.name);
                MethodNode match = changed == null ? null : changed.methods.stream()
                    .filter(value -> value.name.equals(method.name) && value.desc.equals(method.desc))
                    .findFirst().orElse(null);
                if (match == null) {
                    throw new IllegalStateException("Missing guarded method: " + label);
                }
                byte[] oldBytes = MCGLBytecodeFingerprint.bytes(method);
                byte[] newBytes = MCGLBytecodeFingerprint.bytes(match);
                if (!Arrays.equals(oldBytes, newBytes)) {
                    if (!Arrays.equals(decoded(oldBytes), decoded(newBytes))) {
                        throw new IllegalStateException("Changed guarded method: " + label);
                    }
                    aliases.add(MCGLBytecodeFingerprint.sha256(newBytes) + "\t" + expected);
                }
            }
        }
        Files.write(Path.of(args[3]), aliases, StandardCharsets.UTF_8);
    }

    private static byte[] decoded(byte[] bytes) {
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        MethodNode method = type.methods.get(0);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("net/A/oO0O/Oo0O0$1$G") || !call.name.equals("‎   ")
                    || !call.desc.equals("(Ljava/lang/String;I)Ljava/lang/String;")) continue;
            AbstractInsnNode key = call.getPrevious();
            AbstractInsnNode value = key == null ? null : key.getPrevious();
            Integer number = integer(key);
            if (number == null || !(value instanceof LdcInsnNode constant)
                    || !(constant.cst instanceof String text)) continue;
            char[] plaintext = text.toCharArray();
            for (int i = 0; i < plaintext.length; i++) plaintext[i] ^= number;
            constant.cst = new String(plaintext);
            method.instructions.set(key, new InsnNode(Opcodes.ICONST_0));
        }
        return MCGLBytecodeFingerprint.bytes(method);
    }

    private static Integer integer(AbstractInsnNode node) {
        if (node instanceof IntInsnNode number) return number.operand;
        if (node instanceof LdcInsnNode value && value.cst instanceof Integer number) return number;
        if (node != null && node.getOpcode() >= Opcodes.ICONST_M1 && node.getOpcode() <= Opcodes.ICONST_5)
            return node.getOpcode() - Opcodes.ICONST_0;
        return null;
    }
}
