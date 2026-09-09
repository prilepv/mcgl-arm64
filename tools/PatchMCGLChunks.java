import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.CRC32;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Opt-in CPU sink in the real accumulator. Original vertex emission and legacy draw body are retained. */
public final class PatchMCGLChunks implements Opcodes {
    private static final String API = "local/mcgl/render/ChunkTessellator";
    private static final String SINK = "local/mcgl/render/ChunkMeshBuilder$Sink";
    private static final String SINK_DESC = "L" + SINK + ";";
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input-client.jar NEW-output.jar");
        Path output = Paths.get(args[1]).toAbsolutePath();
        if (Files.exists(output)) throw new IOException("Output exists: " + output);
        try (JarFile input = new JarFile(args[0])) {
            ClassNode chunk = type(read(input, "net/A/U/H.class"));
            String tess = null;
            for (FieldNode field : chunk.fields) if ((field.access & ACC_STATIC) != 0 && field.desc.startsWith("Lnet/A/for/")) {
                require(tess == null, "Ambiguous terrain accumulator");
                tess = field.desc.substring(1, field.desc.length() - 1);
            }
            require(tess != null, "Missing terrain accumulator");
            ClassNode node = type(read(input, tess + ".class"));
            patch(node);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
            byte[] updated = writer.toByteArray();
            Path temporary = Files.createTempFile(output.getParent(), ".mcgl-chunks-", ".jar");
            try {
                try (JarOutputStream result = new JarOutputStream(Files.newOutputStream(temporary))) {
                    for (Enumeration<JarEntry> entries = input.entries(); entries.hasMoreElements();) {
                        JarEntry entry = entries.nextElement(); if (entry.isDirectory()) continue;
                        JarEntry target = new JarEntry(entry.getName()); target.setTime(0);
                        result.putNextEntry(target);
                        result.write(entry.getName().equals(tess + ".class") ? updated : read(input, entry.getName()));
                        result.closeEntry();
                    }
                }
                Files.move(temporary, output);
            } finally { Files.deleteIfExists(temporary); }
        }
        System.out.println("CHUNK_ADAPTER_PASS rewrittenClasses=1 original-emitter/legacy-draw=preserved capacity-flush=CPU-when-bound");
    }
    private static void patch(ClassNode node) throws IOException {
        require(!node.interfaces.contains(API), "Chunk adapter is already installed; use a fresh staged client");
        for (MethodNode method : node.methods) require(!method.name.startsWith("mcglChunk") && !method.name.startsWith("mcglBindChunk")
                && !method.name.startsWith("mcglAbortChunk"), "Conflicting chunk method");
        for (FieldNode field : node.fields) require(!field.name.equals("mcglChunkSink"), "Conflicting chunk field");
        // Fingerprints are from the original 1.6.7 client's CPU methods, inspected independently with javap.
        // They remain identical through the existing performance/LWJGL/renderer linkage stages.
        require(fingerprint(method(node, "new", "(DDD)V")) == 589273501L, "Changed original vertex emitter");
        require(fingerprint(method(node, "Ó00000", "()V")) == 1560815914L, "Changed original reset");
        require(fingerprint(method(node, "Ó00000", "(I)V")) == 4229781381L, "Changed original begin");
        for (String key : new String[] {"Õ00000:[I", "return:I", "oO0000:I", "thissuper:I", "Ôo0000:Z",
                "Ô00000:Z", "õO0000:Z", "o00000:Z", "Stringsuper:Z", "while:Z"}) {
            boolean found = false;
            for (FieldNode f : node.fields) if ((f.name + ":" + f.desc).equals(key)) {
                require(((f.access & ACC_STATIC) != 0) == f.name.equals("while"), "Changed field storage: " + key); found = true;
            }
            require(found, "Missing raw accumulator field: " + key);
        }
        MethodNode original = method(node, "new", "()I");
        require(original.access == ACC_PUBLIC, "Changed original draw access");
        original.name = "mcglChunkLegacyDraw"; original.access = ACC_PRIVATE | ACC_SYNTHETIC;
        node.interfaces.add(API);
        node.fields.add(new FieldNode(ACC_PRIVATE, "mcglChunkSink", SINK_DESC, null, null));
        MethodNode draw = add(node, ACC_PUBLIC, "new", "()I");
        Label cpu = new Label(); get(draw, node, "mcglChunkSink", SINK_DESC);
        draw.visitJumpInsn(IFNONNULL, cpu);
        draw.visitVarInsn(ALOAD, 0); draw.visitMethodInsn(INVOKESPECIAL, node.name, original.name, "()I"); draw.visitInsn(IRETURN);
        draw.visitLabel(cpu); draw.visitFrame(F_SAME, 0, null, 0, null);
        draw.visitVarInsn(ALOAD, 0); draw.visitMethodInsn(INVOKESPECIAL, node.name, "mcglChunkDrain", "()I"); draw.visitInsn(IRETURN);
        end(draw);
        MethodNode drain = add(node, ACC_PRIVATE | ACC_SYNTHETIC, "mcglChunkDrain", "()I");
        get(drain, node, "mcglChunkSink", SINK_DESC);
        get(drain, node, "Õ00000", "[I"); get(drain, node, "return", "I");
        get(drain, node, "oO0000", "I"); get(drain, node, "thissuper", "I");
        drain.visitFieldInsn(GETSTATIC, node.name, "while", "Z");
        for (String name : new String[] {"Ôo0000", "Ô00000", "õO0000", "o00000", "Stringsuper"}) get(drain, node, name, "Z");
        drain.visitMethodInsn(INVOKEVIRTUAL, SINK, "append", "([IIIIZZZZZZ)I");
        drain.visitVarInsn(ISTORE, 1); // Commit reset only after the CPU sink accepted the whole batch.
        reset(drain, node);
        drain.visitVarInsn(ILOAD, 1); drain.visitInsn(IRETURN); end(drain);
        MethodNode bind = add(node, ACC_PUBLIC, "mcglBindChunkBatch", "(" + SINK_DESC + ")V");
        Label idle = new Label(); get(bind, node, "Ôo0000", "Z"); bind.visitJumpInsn(IFEQ, idle);
        fail(bind, "Cannot switch chunk sink inside a begun batch");
        bind.visitLabel(idle); bind.visitFrame(F_SAME, 0, null, 0, null);
        bind.visitVarInsn(ALOAD, 0); bind.visitVarInsn(ALOAD, 1); bind.visitFieldInsn(PUTFIELD, node.name, "mcglChunkSink", SINK_DESC);
        bind.visitInsn(RETURN); end(bind);
        MethodNode abort = add(node, ACC_PUBLIC, "mcglAbortChunkBatch", "()V");
        Label bound = new Label(); get(abort, node, "mcglChunkSink", SINK_DESC); abort.visitJumpInsn(IFNONNULL, bound);
        fail(abort, "No chunk sink is bound");
        abort.visitLabel(bound); abort.visitFrame(F_SAME, 0, null, 0, null);
        reset(abort, node);
        abort.visitVarInsn(ALOAD, 0); abort.visitInsn(ACONST_NULL); abort.visitFieldInsn(PUTFIELD, node.name, "mcglChunkSink", SINK_DESC);
        abort.visitInsn(RETURN); end(abort);
    }
    private static MethodNode add(ClassNode node, int access, String name, String descriptor) {
        MethodNode method = new MethodNode(access, name, descriptor, null, null); node.methods.add(method); method.visitCode(); return method;
    }
    private static void end(MethodNode method) { method.visitMaxs(0, 0); method.visitEnd(); }
    private static void get(MethodVisitor method, ClassNode node, String name, String descriptor) {
        method.visitVarInsn(ALOAD, 0); method.visitFieldInsn(GETFIELD, node.name, name, descriptor);
    }
    private static void reset(MethodVisitor method, ClassNode node) {
        method.visitVarInsn(ALOAD, 0); method.visitInsn(ICONST_0); method.visitFieldInsn(PUTFIELD, node.name, "Ôo0000", "Z");
        method.visitVarInsn(ALOAD, 0); method.visitMethodInsn(INVOKESPECIAL, node.name, "Ó00000", "()V");
    }
    private static void fail(MethodVisitor method, String message) {
        method.visitTypeInsn(NEW, "java/lang/IllegalStateException"); method.visitInsn(DUP); method.visitLdcInsn(message);
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V"); method.visitInsn(ATHROW);
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) throws IOException {
        for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        throw new IOException("Missing accumulator method: " + name + descriptor);
    }
    private static long fingerprint(MethodNode method) {
        ClassWriter writer = new ClassWriter(0); writer.visit(50, 1, "Fingerprint", null, "java/lang/Object", null);
        method.accept(writer); writer.visitEnd(); method.instructions.resetLabels();
        CRC32 crc = new CRC32(); crc.update(writer.toByteArray()); return crc.getValue();
    }
    private static ClassNode type(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] read(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name); if (entry == null) throw new IOException("Missing client entry: " + name);
        try (InputStream input = jar.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); return output.toByteArray();
        }
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
}
