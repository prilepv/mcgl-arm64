import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** Link-only adaptation. Game instructions/control flow are not optimized. */
public final class PatchMCGLLwjgl3 {
    private static boolean touched;
    private static String replacement(String value) { touched = true; return value; }
    private static final Remapper REMAPPER = new Remapper() {
        @Override public String map(String name) {
            if (name.equals("org/lwjgl/opengl/GL11")) return replacement("org/lwjgl/opengl/MCGLGL11");
            if (name.equals("org/lwjgl/opengl/GL13")) return replacement("org/lwjgl/opengl/MCGLGL13");
            if (name.equals("org/lwjgl/opengl/ARBMultitexture")) return replacement("org/lwjgl/opengl/MCGLARBMultitexture");
            if (name.equals("org/lwjgl/openal/AL")) return replacement("org/lwjgl/openal/MCGLAL");
            if (name.equals("org/lwjgl/BufferUtils")) return replacement("org/lwjgl/MCGLBufferUtils");
            if (name.equals("org/lwjgl/PointerBuffer") || name.startsWith("org/lwjgl/PointerBuffer$"))
                return replacement(name.replace("org/lwjgl/PointerBuffer", "org/lwjgl/MCGLPointerBuffer"));
            return name;
        }
        @Override public String mapMethodName(String owner, String name, String descriptor) {
            String signature = owner + "." + name + descriptor;
            if (signature.equals("org/lwjgl/openal/AL10.alListener(ILjava/nio/FloatBuffer;)V")) return replacement("alListenerfv");
            if (signature.equals("org/lwjgl/openal/AL10.alSource(IILjava/nio/FloatBuffer;)V")) return replacement("alSourcefv");
            if (signature.equals("org/lwjgl/openal/AL10.alSourceStop(Ljava/nio/IntBuffer;)V")) return replacement("alSourceStopv");
            if (signature.equals("org/lwjgl/opengl/ARBOcclusionQuery.glGetQueryObjectuARB(IILjava/nio/IntBuffer;)V")) return replacement("glGetQueryObjectuivARB");
            if (signature.equals("org/lwjgl/opengl/ARBShaderObjects.glGetObjectParameterARB(IILjava/nio/IntBuffer;)V")) return replacement("glGetObjectParameterivARB");
            if (signature.equals("org/lwjgl/opengl/ARBShaderObjects.glUniformMatrix4ARB(IZLjava/nio/FloatBuffer;)V")) return replacement("glUniformMatrix4fvARB");
            if (signature.equals("org/lwjgl/opengl/GL20.glGetProgram(IILjava/nio/IntBuffer;)V")) return replacement("glGetProgramiv");
            if (signature.equals("org/lwjgl/opengl/GL20.glUniformMatrix4(IZLjava/nio/FloatBuffer;)V")) return replacement("glUniformMatrix4fv");
            return name;
        }
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3)
            throw new IllegalArgumentException("usage: input.jar NEW-output.jar [prepared-lwjgl2-source]");
        Path output = Paths.get(args[1]);
        if (Files.exists(output)) throw new IOException("Output exists: " + output);
        int changed = 0, retained = 0;
        try (JarFile input = new JarFile(args[0]);
             JarOutputStream result = new JarOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW))) {
            if (args.length == 3 && input.getJarEntry("org/lwjgl/Version.class") != null)
                throw new IOException("Window extraction requires the original LWJGL 2 SDK, not an already merged library");
            for (Enumeration<JarEntry> entries = input.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                // The library-only intermediate is merged under the LWJGL 3 manifest.
                // Client/resource manifests and notices otherwise remain byte-for-byte intact.
                if (args.length == 3 && name.equalsIgnoreCase("META-INF/MANIFEST.MF")) continue;
                byte[] original;
                try (InputStream stream = input.getInputStream(entry)) { original = read(stream); }
                byte[] bytes = original;
                if (name.endsWith(".class")) {
                    touched = false;
                    ClassNode node = new ClassNode();
                    new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
                    if (args.length == 3) {
                        String sourceName = node.sourceFile;
                        String directory = name.substring(0, name.lastIndexOf('/') + 1);
                        if (sourceName == null || !Files.isRegularFile(Paths.get(args[2], "src/java", directory, sourceName))) continue;
                        if (node.name.equals("org/lwjgl/BufferUtils")
                                || node.name.startsWith("org/lwjgl/opengl/GLContext")
                                || node.name.equals("org/lwjgl/opengl/GLChecks")
                                || node.name.equals("org/lwjgl/opengl/Util")
                                || node.name.startsWith("org/lwjgl/openal/")) continue;
                        if (node.name.equals("org/lwjgl/Sys")) {
                            for (MethodNode method : node.methods) {
                                if (method.name.equals("getVersion") && method.desc.equals("()Ljava/lang/String;")) {
                                    method.instructions.clear();
                                    method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                            "org/lwjgl/Version", "getVersion", "()Ljava/lang/String;"));
                                    method.instructions.add(new InsnNode(Opcodes.ARETURN));
                                    method.localVariables.clear();
                                    method.tryCatchBlocks.clear();
                                    method.maxStack = 1; method.maxLocals = 0;
                                    touched = true;
                                }
                                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                                    if (insn instanceof LdcInsnNode && "lwjgl".equals(((LdcInsnNode) insn).cst)) {
                                        ((LdcInsnNode) insn).cst = "mcgl-window";
                                        touched = true;
                                    }
                                }
                            }
                        }
                    }
                    ClassWriter writer = new ClassWriter(0);
                    node.accept(new RemappingClassAdapter(writer, REMAPPER) {
                        @Override protected MethodVisitor createRemappingMethodAdapter(
                                int access, String descriptor, MethodVisitor visitor) {
                            return new BindingMethodAdapter(access, descriptor, visitor);
                        }
                    });
                    name = REMAPPER.map(node.name) + ".class";
                    if (touched) bytes = writer.toByteArray();
                    retained++;
                    if (!Arrays.equals(original, bytes)) changed++;
                }
                JarEntry target = new JarEntry(name);
                target.setTime(0);
                result.putNextEntry(target); result.write(bytes); result.closeEntry();
            }
        }
        System.out.println("LWJGL3_REMAP classes=" + retained + " rewritten=" + changed);
    }

    /** ASM 4's general remapper renumbers locals; this link-only pass must not. */
    private static final class BindingMethodAdapter extends RemappingMethodAdapter {
        BindingMethodAdapter(int access, String descriptor, MethodVisitor visitor) {
            super(access, descriptor, visitor, REMAPPER);
        }
        @Override public void visitVarInsn(int opcode, int variable) { mv.visitVarInsn(opcode, variable); }
        @Override public void visitIincInsn(int variable, int increment) { mv.visitIincInsn(variable, increment); }
        @Override public void visitMaxs(int stack, int locals) { mv.visitMaxs(stack, locals); }
        @Override public void visitLocalVariable(String name, String descriptor, String signature,
                Label start, Label end, int index) {
            mv.visitLocalVariable(name, REMAPPER.mapDesc(descriptor), REMAPPER.mapSignature(signature, true), start, end, index);
        }
        @Override public void visitFrame(int type, int localCount, Object[] locals, int stackCount, Object[] stack) {
            mv.visitFrame(type, localCount, mapFrame(locals, localCount), stackCount, mapFrame(stack, stackCount));
        }
        private Object[] mapFrame(Object[] frame, int size) {
            if (frame == null) return null;
            Object[] mapped = frame.clone();
            for (int index = 0; index < size; index++)
                if (mapped[index] instanceof String) mapped[index] = REMAPPER.mapType((String) mapped[index]);
            return mapped;
        }
    }
    private static byte[] read(InputStream stream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = stream.read(buffer)) != -1) result.write(buffer, 0, count);
        return result.toByteArray();
    }
}
