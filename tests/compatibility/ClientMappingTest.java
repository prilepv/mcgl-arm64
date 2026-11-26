import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** A changed argument type must not overwrite an already known class mapping. */
public final class ClientMappingTest implements Opcodes {
    private static byte[] type(String name, String fieldDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_6, ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "field", fieldDescriptor, null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] controller(String first, String changed, boolean withCall) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_6, ACC_PUBLIC, "net/minecraft/client/Minecraft", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "consume", "(L" + first + ";L" + changed + ";)V", null, null);
        method.visitCode();
        if (withCall) method.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J");
        if (withCall) method.visitInsn(POP2);
        method.visitInsn(RETURN);
        method.visitMaxs(2, 3);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] world(String prefix, boolean changed) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_6, ACC_PUBLIC, prefix + "World", null, "java/lang/Object", null);
        if (changed) writer.visitField(ACC_PUBLIC, "added", "Z", null, null).visitEnd();
        String field = changed ? "renamed" : "value";
        writer.visitField(ACC_PUBLIC, field, "I", null, null).visitEnd();
        MethodVisitor draw = writer.visitMethod(ACC_PUBLIC, changed ? "render" : "draw", "()I", null, null);
        draw.visitCode();
        draw.visitVarInsn(ALOAD, 0);
        draw.visitFieldInsn(GETFIELD, prefix + "World", field, "I");
        draw.visitInsn(IRETURN);
        draw.visitMaxs(1, 1);
        draw.visitEnd();
        if (changed) {
            MethodVisitor extra = writer.visitMethod(ACC_PUBLIC, "extra", "()D", null, null);
            extra.visitCode();
            extra.visitInsn(DCONST_0);
            extra.visitInsn(DRETURN);
            extra.visitMaxs(2, 1);
            extra.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Path jar(Path root, String name, String prefix, boolean changed) throws Exception {
        Path path = root.resolve(name);
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(prefix + "Player", type(prefix + "Player", "L" + prefix + "World;"));
        classes.put(prefix + "Entity", type(prefix + "Entity", "J"));
        classes.put(prefix + "Action", type(prefix + "Action", "F"));
        classes.put(prefix + "World", world(prefix, changed));
        classes.put("net/minecraft/client/Minecraft", controller(prefix + "Player", prefix + (changed ? "Action" : "Entity"), changed));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return path;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mcgl-mapping-test-");
        Path original = jar(root, "original.jar", "old/", false);
        Path updated = jar(root, "updated.jar", "new/", true);
        Path output = root.resolve("mapping.tsv");
        BuildClientMapping.main(new String[] {original.toString(), updated.toString(), output.toString()});
        if (Files.size(root.resolve("mapping.tsv.conflicts")) != 0) throw new AssertionError("Changed argument generated false conflicts");
        String mapping = Files.readString(output);
        if (!mapping.contains("C\told/Entity\tnew/Entity\n")) throw new AssertionError("Entity correspondence overwritten");
        if (mapping.contains("M\tnet/minecraft/client/Minecraft\tconsume\t")) throw new AssertionError("Changed signature reused old method alias");
        if (!mapping.contains("F\told/World\tvalue\tI\trenamed\n")) throw new AssertionError("Added fields lost existing field mapping");
        if (!mapping.contains("M\told/World\tdraw\t()I\trender\n")) throw new AssertionError("Added methods lost existing method mapping");
        // A genuine conflicting class correspondence must still be rejected.
        BuildClientMapping.pair("old/Entity", "new/Action", "regression check");
        if (BuildClientMapping.conflicts.isEmpty()) throw new AssertionError("Class conflict ignored");
        BuildClientMapping.main(new String[] {original.toString(), updated.toString(), root.resolve("retry.tsv").toString()});
        if (!BuildClientMapping.conflicts.isEmpty()) throw new AssertionError("Previous run leaked into retry");
        System.out.println("CLIENT_MAPPING_PASS changed arguments, added members, class conflicts, repeat invocation");
    }
}
