import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Checks referenced public LWJGL members without initializing native libraries. */
public final class LWJGL3LinkageAudit {
    public static void main(String[] args) throws Exception {
        Map<String, Set<String>> references = new TreeMap<String, Set<String>>();
        for (String path : args) {
            try (JarFile jar = new JarFile(path)) {
                for (java.util.Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                    ClassNode node = new ClassNode();
                    try (InputStream in = jar.getInputStream(entry)) {
                        new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    }
                    for (MethodNode method : node.methods) {
                        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                            String owner, name, descriptor, kind;
                            if (insn instanceof MethodInsnNode) {
                                MethodInsnNode call = (MethodInsnNode) insn;
                                owner = call.owner; name = call.name; descriptor = call.desc; kind = "M";
                            } else if (insn instanceof FieldInsnNode) {
                                FieldInsnNode field = (FieldInsnNode) insn;
                                owner = field.owner; name = field.name; descriptor = field.desc; kind = "F";
                            } else continue;
                            if (!owner.startsWith("org/lwjgl/") && !owner.startsWith("local/mcgl/lwjgl3/")
                                    && !owner.startsWith("local/mcgl/render/legacy/")) continue;
                            // Internal GLU/math fields are not part of the migrated binding API.
                            if (owner.startsWith("org/lwjgl/util/")) continue;
                            String key = kind + " " + owner + " " + name + " " + descriptor;
                            Set<String> callers = references.get(key);
                            if (callers == null) references.put(key, callers = new TreeSet<String>());
                            callers.add(node.name);
                        }
                    }
                }
            }
        }
        int missing = 0;
        for (Map.Entry<String, Set<String>> entry : references.entrySet()) {
            String[] parts = entry.getKey().split(" ");
            boolean found = false;
            String failure = "";
            try {
                Class<?> owner = Class.forName(parts[1].replace('/', '.'), false,
                        LWJGL3LinkageAudit.class.getClassLoader());
                if (parts[0].equals("F")) {
                    for (Field field : owner.getFields())
                        if (field.getName().equals(parts[2]) && Type.getDescriptor(field.getType()).equals(parts[3])) found = true;
                } else if (parts[2].equals("<init>")) {
                    for (Constructor<?> ctor : owner.getConstructors())
                        if (Type.getConstructorDescriptor(ctor).equals(parts[3])) found = true;
                } else {
                    for (Method method : owner.getMethods())
                        if (method.getName().equals(parts[2]) && Type.getMethodDescriptor(method).equals(parts[3])) found = true;
                }
            } catch (Throwable error) { failure = " " + error; }
            if (!found) {
                missing++;
                System.out.println("MISSING " + entry.getKey() + failure + " callerClasses=" + entry.getValue().size());
            }
            if (parts[1].endsWith("ContextCapabilities")) System.out.println("CAPABILITY " + entry.getKey());
        }
        System.out.println("LWJGL_LINKAGE references=" + references.size() + " missing=" + missing);
        if (missing != 0) System.exit(1);
    }
}
