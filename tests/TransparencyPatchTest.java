import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class TransparencyPatchTest implements Opcodes {
    private static ClassNode read(JarFile jar, String name) throws Exception {
        try (InputStream input = jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new AssertionError("original mcgl.jar required");
        Map<String, ClassNode> classes = new HashMap<String, ClassNode>();
        try (JarFile jar = new JarFile(args[0])) {
            for (String name : new String[]{PatchMCGLTransparency.SETTINGS,
                                            PatchMCGLTransparency.RENDERER}) {
                classes.put(name + ".class", read(jar, name));
            }
        }
        HashSet<String> changed = new HashSet<String>();
        PatchMCGLTransparency.patch(classes, changed);
        if (!changed.contains(PatchMCGLTransparency.SETTINGS)) {
            throw new AssertionError("settings class was not marked changed");
        }
        int enabledDefaults = 0;
        ClassNode settings = classes.get(PatchMCGLTransparency.SETTINGS + ".class");
        for (MethodNode method : settings.methods) if (method.name.equals("<init>")) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode)) continue;
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == PUTFIELD && field.owner.equals(PatchMCGLTransparency.SETTINGS) &&
                        field.name.equals(PatchMCGLTransparency.ALPHA_SORT)) {
                    if (instruction.getPrevious().getOpcode() != ICONST_1) {
                        throw new AssertionError("alpha-sort default was not enabled");
                    }
                    enabledDefaults++;
                }
            }
        }
        if (enabledDefaults != 1) throw new AssertionError("unexpected default count " + enabledDefaults);
        System.out.println("TRANSPARENCY_PATCH_PASS default enabled and renderer gate verified");
    }
}
