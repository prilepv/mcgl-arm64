import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.CRC32;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ChunkVboPatchTest {
    static Map<String, ClassNode> read(String path) throws Exception {
        Map<String, ClassNode> classes = new LinkedHashMap<String, ClassNode>();
        try (JarFile jar = new JarFile(path)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                ClassNode c = new ClassNode();
                try (InputStream in = jar.getInputStream(e)) { new ClassReader(in).accept(c, 0); }
                classes.put(e.getName(), c);
            }
        }
        return classes;
    }
    public static void main(String[] args) throws Exception {
        Map<String, ClassNode> classes = read(args[0]);
        for (ClassNode c : classes.values()) if (c.name.equals("net/A/U/H") || c.name.startsWith("net/A/for/oOOOoOOOO"))
            for (MethodNode m : c.methods) if ((m.name.equals("new") && m.desc.equals("()I")) ||
                    (m.desc.equals("()V") && (m.name.equals("Ö00000") || m.name.equals("Õ00000")))) {
                ClassWriter w = new ClassWriter(0); w.visit(50, 1, "Fingerprint", null, "java/lang/Object", null);
                m.accept(w); w.visitEnd(); CRC32 crc = new CRC32(); crc.update(w.toByteArray()); m.instructions.resetLabels();
                System.out.println("FINGERPRINT " + (c.name.equals("net/A/U/H") ? "chunk" : "tess") + " " + m.name + " " + crc.getValue());
            }
        int originalBinds = 0;
        for (ClassNode c : classes.values()) for (MethodNode m : c.methods) for (AbstractInsnNode n : m.instructions.toArray())
            if (n instanceof MethodInsnNode) {
                MethodInsnNode i = (MethodInsnNode)n;
                if (i.owner.equals("org/lwjgl/opengl/GL11") && i.name.equals("glBindTexture") && i.desc.equals("(II)V")) originalBinds++;
            }
        PatchMCGLChunkVbo.patch(classes, new HashSet<String>());
        int draws = 0, starts = 0, guarded = 0, sorts = 0, binds = 0;
        for (ClassNode c : classes.values()) for (MethodNode m : c.methods) for (AbstractInsnNode n : m.instructions.toArray())
            if (n instanceof MethodInsnNode) {
                MethodInsnNode i = (MethodInsnNode)n;
                if (i.owner.equals(PatchMCGLQuadSort.HELPER) && i.name.equals("sort")) sorts++;
                if (i.owner.equals(PatchMCGLChunkVbo.HELPER)) {
                    if (i.name.equals("glDrawArrays")) draws++;
                    if (i.name.equals("glBindTexture") && i.desc.equals("(II)V")) binds++;
                    if (i.name.equals("unsupported")) {
                        guarded++;
                        if (!i.desc.equals("(Ljava/lang/String;)V") || !(i.getPrevious() instanceof LdcInsnNode) ||
                                !(((LdcInsnNode)i.getPrevious()).cst instanceof String) ||
                                ((String)((LdcInsnNode)i.getPrevious()).cst).isEmpty())
                            throw new AssertionError("Missing constant fallback reason");
                    }
                    if (i.name.equals("glNewList") && i.getPrevious().getOpcode() == Opcodes.ICONST_1) starts++;
                }
                if (i.owner.equals("org/lwjgl/opengl/GL11") && (i.name.equals("glNewList") || i.name.equals("glDeleteLists")))
                    throw new AssertionError("Untracked list lifecycle");
            }
        if (draws != 2 || starts != 2 || guarded < 100 || sorts != 1 || originalBinds == 0 || binds != originalBinds)
            throw new AssertionError("Patch coverage");
        System.out.println("CHUNK_PATCH_PASS captures=" + draws + " terrainBegins=" + starts + " guarded=" + guarded + " textureBinds=" + binds);
        Map<String, ClassNode> incompatible = read(args[0]);
        for (MethodNode m : incompatible.get("net/A/U/H.class").methods)
            if (m.name.equals("Ö00000")) m.instructions.insert(new InsnNode(Opcodes.NOP));
        try { PatchMCGLChunkVbo.patch(incompatible, new HashSet<String>()); throw new AssertionError("Changed renderer accepted"); }
        catch (IllegalStateException expected) { System.out.println("CHUNK_PATCH_REJECTION_PASS changed renderer rejected"); }
        incompatible = read(args[0]);
        for (ClassNode c : incompatible.values()) for (MethodNode m : c.methods)
            if (m.name.equals("Ó00000") && m.desc.equals("(DDD)V")) m.instructions.insert(new InsnNode(Opcodes.NOP));
        try { PatchMCGLChunkVbo.patch(incompatible, new HashSet<String>()); throw new AssertionError("Changed sorter accepted"); }
        catch (IllegalStateException expected) { System.out.println("QUAD_PATCH_REJECTION_PASS changed sorter rejected"); }
        if (args.length > 1) {
            try (JarFile input = new JarFile(args[0]); JarOutputStream out = new JarOutputStream(new FileOutputStream(args[1]))) {
                Enumeration<JarEntry> entries = input.entries();
                byte[] buffer = new byte[16384]; int size;
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement(); out.putNextEntry(new JarEntry(e.getName()));
                    ClassNode c = classes.get(e.getName());
                    if (c != null) { ClassWriter w = new PatchMCGLPerformance.HierarchyWriter(classes); c.accept(w); out.write(w.toByteArray()); }
                    else try (InputStream in = input.getInputStream(e)) { while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size); }
                    out.closeEntry();
                }
                for (String helper : new String[]{"ChunkVbo", "ChunkVbo$Mesh", "ChunkVbo$Command", "ChunkVbo$MeshIndex", "QuadSort", "QuadSort$Scratch"}) {
                    String path = "local/mcgl/perf/" + helper + ".class";
                    out.putNextEntry(new JarEntry(path));
                    try (InputStream in = ChunkVboPatchTest.class.getResourceAsStream("/" + path)) {
                        while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
                    }
                    out.closeEntry();
                }
            }
        }
    }
}
