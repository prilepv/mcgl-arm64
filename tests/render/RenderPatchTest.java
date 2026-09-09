import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Independent bytecode equivalence and strict-failure checks on the real post-1.6.11 client. */
public final class RenderPatchTest implements Opcodes {
    private static int checks, changedClasses, unchangedClasses;
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("manifest old-client.jar old-util.jar output-directory");
        Path root = Paths.get(args[3]); Files.createDirectories(root);
        for (int i = 1; i <= 2; i++) {
            Path input = Paths.get(args[i]), output = root.resolve("adapted-" + i + ".jar"), repeat = root.resolve("repeat-" + i + ".jar");
            PatchMCGLRenderer.main(new String[] {input.toString(), output.toString(), args[0]});
            compare(input, output);
            PatchMCGLRenderer.main(new String[] {output.toString(), repeat.toString(), args[0]});
            check(Arrays.equals(Files.readAllBytes(output), Files.readAllBytes(repeat)), "second adaptation is byte-identical");
            byte[] before = Files.readAllBytes(output);
            try { PatchMCGLRenderer.main(new String[] {input.toString(), output.toString(), args[0]}); throw new AssertionError("existing output overwritten"); }
            catch (IOException expected) { check(Arrays.equals(before, Files.readAllBytes(output)), "overwrite protection"); }
        }
        Path handles = root.resolve("handles.jar"), mapped = root.resolve("handles-mapped.jar");
        fixture(handles, "glClear", true, true);
        PatchMCGLRenderer.main(new String[] {handles.toString(), mapped.toString(), args[0]});
        compare(handles, mapped);
        Path unknown = root.resolve("unknown.jar"); fixture(unknown, "glNotInTheManifest", true, false);
        reject(unknown, root.resolve("unknown-output.jar"), args[0], "unknown command rejected without a partial JAR");
        Path instance = root.resolve("instance.jar"); fixture(instance, "glClear", false, false);
        reject(instance, root.resolve("instance-output.jar"), args[0], "instance invocation cannot bypass static contract");
        Path library = root.resolve("library.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(library, StandardOpenOption.CREATE_NEW))) {
            output.putNextEntry(new JarEntry("org/lwjgl/Version.class")); output.write(new byte[] {0}); output.closeEntry();
        }
        reject(library, root.resolve("library-output.jar"), args[0], "binding library cannot be rewritten");
        System.out.println("RENDER_PATCH_PASS checks=" + checks + " changed=" + changedClasses + " untouched=" + unchangedClasses
                + " descriptors/locals/control-flow/resources/handles/idempotence/rejection");
    }
    private static void compare(Path oldPath, Path newPath) throws Exception {
        try (JarFile old = new JarFile(oldPath.toFile()); JarFile updated = new JarFile(newPath.toFile())) {
            Set<String> oldNames = names(old), newNames = names(updated); check(oldNames.equals(newNames), "entry set preserved");
            for (String name : oldNames) {
                byte[] original = read(old, name), changed = read(updated, name);
                if (!name.endsWith(".class")) { check(Arrays.equals(original, changed), "resource/manifest bytes unchanged"); continue; }
                if (Arrays.equals(original, changed)) { unchangedClasses++; continue; }
                changedClasses++;
                check(Arrays.equals(normalize(original), normalize(changed)), "only render linkage changed: " + name);
            }
        }
    }
    private static byte[] normalize(byte[] bytes) {
        ClassNode type = new ClassNode(); new ClassReader(bytes).accept(type, 0);
        for (MethodNode method : type.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode)instruction;
                if (call.name.startsWith("gl")) call.owner = owner(call.owner);
            } else if (instruction instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode call = (InvokeDynamicInsnNode)instruction; call.bsm = handle(call.bsm);
                for (int i = 0; i < call.bsmArgs.length; i++) if (call.bsmArgs[i] instanceof MethodHandle) call.bsmArgs[i] = handle((MethodHandle)call.bsmArgs[i]);
            } else if (instruction instanceof LdcInsnNode && ((LdcInsnNode)instruction).cst instanceof MethodHandle) {
                LdcInsnNode value = (LdcInsnNode)instruction; value.cst = handle((MethodHandle)value.cst);
            }
        }
        ClassWriter writer = new ClassWriter(0); type.accept(writer); return writer.toByteArray();
    }
    private static String owner(String owner) {
        String prefix = "local/mcgl/render/legacy/";
        if (owner.startsWith(prefix)) return "org/lwjgl/opengl/" + owner.substring(prefix.length());
        return RenderCommandSpec.normalize(owner);
    }
    private static MethodHandle handle(MethodHandle handle) {
        return !handle.getName().startsWith("gl") ? handle : new MethodHandle(handle.getTag(), owner(handle.getOwner()), handle.getName(), handle.getDesc());
    }
    private static Set<String> names(JarFile jar) {
        Set<String> names = new TreeSet<String>();
        for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) { JarEntry entry = entries.nextElement(); if (!entry.isDirectory()) names.add(entry.getName()); }
        return names;
    }
    private static byte[] read(JarFile jar, String name) throws IOException {
        try (InputStream input = jar.getInputStream(jar.getJarEntry(name)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
    private static void reject(Path input, Path output, String manifest, String message) throws Exception {
        try { PatchMCGLRenderer.main(new String[] {input.toString(), output.toString(), manifest}); throw new AssertionError(message); }
        catch (IOException expected) { check(!Files.exists(output), message); }
    }
    private static void fixture(Path path, String name, boolean isStatic, boolean handles) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_7, ACC_PUBLIC, "RenderLinkFixture", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(I)V", null, null);
        method.visitCode();
        if (!isStatic) method.visitInsn(ACONST_NULL);
        method.visitVarInsn(ILOAD, 0);
        method.visitMethodInsn(isStatic ? INVOKESTATIC : INVOKEVIRTUAL, "org/lwjgl/opengl/MCGLGL11", name, "(I)V");
        if (handles) {
            MethodHandle target = new MethodHandle(MH_INVOKESTATIC, "org/lwjgl/opengl/MCGLGL11", "glClear", "(I)V");
            method.visitLdcInsn(target); method.visitInsn(POP);
            method.visitInvokeDynamicInsn("test", "()Ljava/lang/Object;", new MethodHandle(MH_INVOKESTATIC, "fixture/Bootstrap", "link",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;"), target);
            method.visitInsn(POP);
        }
        method.visitInsn(RETURN); method.visitMaxs(2, 1); method.visitEnd(); writer.visitEnd();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
            output.putNextEntry(new JarEntry("RenderLinkFixture.class")); output.write(writer.toByteArray()); output.closeEntry();
            output.putNextEntry(new JarEntry("fixture-resource.txt")); output.write(new byte[] {0, 7, 42}); output.closeEntry();
        }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
