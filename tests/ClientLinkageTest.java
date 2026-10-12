import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Resolve game/support member references from metadata, never initialize game code. */
public final class ClientLinkageTest implements Opcodes {
    static Map<String, ClassNode> classes(String client, String[] dependencies) throws Exception {
        Map<String, ClassNode> result = new HashMap<String, ClassNode>();
        for (String jar : dependencies) {
            Map<String, byte[]> files = MCGLClientCompatibility.readJar(java.nio.file.Paths.get(jar));
            // Binding dependencies contain multi-release/module entries; only
            // their base Java 8 game-facing classes participate in this audit.
            for (String path : new ArrayList<String>(files.keySet()))
                if (path.startsWith("META-INF/") || path.equals("module-info.class")) files.remove(path);
            result.putAll(MCGLClientCompatibility.hierarchy(files));
        }
        // Client definitions win, including its newly bundled performance helpers.
        result.putAll(MCGLClientCompatibility.hierarchy(MCGLClientCompatibility.readJar(java.nio.file.Paths.get(client))));
        return result;
    }
    static ClassNode node(Map<String, ClassNode> all, String name) throws Exception {
        ClassNode result = all.get(name); if (result != null) return result;
        if (!name.startsWith("java/") && !name.startsWith("javax/")) return null;
        // Bootstrap/platform Java metadata only, with initialization explicitly off.
        Class<?> type = Class.forName(name.replace('/', '.'), false, null);
        result = new ClassNode(); result.name = name; result.access = type.getModifiers();
        result.superName = type.getSuperclass() == null ? null : org.objectweb.asm.Type.getInternalName(type.getSuperclass());
        for (Class<?> face : type.getInterfaces()) result.interfaces.add(org.objectweb.asm.Type.getInternalName(face));
        for (Field f : type.getDeclaredFields()) result.fields.add(new FieldNode(f.getModifiers(), f.getName(), org.objectweb.asm.Type.getDescriptor(f.getType()), null, null));
        for (Method m : type.getDeclaredMethods()) result.methods.add(new MethodNode(m.getModifiers(), m.getName(), org.objectweb.asm.Type.getMethodDescriptor(m), null, null));
        for (Constructor<?> c : type.getDeclaredConstructors()) result.methods.add(new MethodNode(c.getModifiers(), "<init>", org.objectweb.asm.Type.getConstructorDescriptor(c), null, null));
        all.put(name, result); return result;
    }
    static Integer resolve(Map<String, ClassNode> all, String owner, String name, String desc, boolean field, Set<String> visited) throws Exception {
        if (!visited.add(owner)) return null; ClassNode c = node(all, owner); if (c == null) return null;
        if (field) { for (FieldNode f : c.fields) if (f.name.equals(name) && f.desc.equals(desc)) return f.access; }
        else { for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m.access; }
        if (name.equals("<init>")) return null;
        for (String face : c.interfaces) { Integer access = resolve(all, face, name, desc, field, visited); if (access != null) return access; }
        return c.superName == null ? null : resolve(all, c.superName, name, desc, field, visited);
    }
    static Set<String> unresolved(String jar, String[] deps) throws Exception {
        Map<String, ClassNode> all = classes(jar, deps); Set<String> errors = new TreeSet<String>(); int references = 0;
        for (Map.Entry<String, byte[]> e : MCGLClientCompatibility.readJar(java.nio.file.Paths.get(jar)).entrySet()) if (e.getKey().endsWith(".class")) {
            ClassNode c = new ClassNode(); new ClassReader(e.getValue()).accept(c, 0);
            for (MethodNode m : c.methods) for (AbstractInsnNode i : m.instructions.toArray()) {
                String owner, name, desc; boolean field = i instanceof FieldInsnNode;
                if (field) { FieldInsnNode f = (FieldInsnNode)i; owner = f.owner; name = f.name; desc = f.desc; }
                else if (i instanceof MethodInsnNode) { MethodInsnNode call = (MethodInsnNode)i; owner = call.owner; name = call.name; desc = call.desc; }
                else continue;
                if (!owner.startsWith("net/") && !owner.startsWith("local/mcgl/")) continue;
                references++; Integer access = resolve(all, owner, name, desc, field, new HashSet<String>());
                boolean isStatic = i.getOpcode() == INVOKESTATIC || i.getOpcode() == GETSTATIC || i.getOpcode() == PUTSTATIC;
                if (access == null || ((access & ACC_STATIC) != 0) != isStatic)
                    errors.add((field ? "F " : "M ") + owner + "." + name + desc + " opcode=" + i.getOpcode());
            }
        }
        System.out.println("CLIENT_LINKAGE references=" + references + " unresolved=" + errors.size()); return errors;
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("official.jar adapted.jar dependency.jar...");
        String[] deps = Arrays.copyOfRange(args, 2, args.length);
        Set<String> baseline = unresolved(args[0], deps), updated = unresolved(args[1], deps);
        if (Boolean.getBoolean("mcgl.linkage.verbose"))
            for (String error : baseline) System.out.println("BASELINE_UNRESOLVED " + error);
        if (!baseline.isEmpty()) throw new AssertionError("Incomplete baseline dependencies: " + baseline.size());
        updated.removeAll(baseline);
        for (String error : updated) System.err.println(error);
        if (!updated.isEmpty()) throw new AssertionError("New unresolved game/support members: " + updated.size());
        System.out.println("CLIENT_LINKAGE_TEST_PASS no introduced missing/static-mismatched references");
    }
}
