import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Executes the actual patched resize helper in a small, account-free GPU fixture. */
public final class BuildWindowResizeFixture implements Opcodes {
    public static void main(String[] args) throws Exception {
        ClassNode client = new ClassNode();
        try (JarFile jar = new JarFile(args[0]);
             InputStream in = jar.getInputStream(jar.getJarEntry("net/minecraft/client/Minecraft.class"))) {
            new ClassReader(in).accept(client, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        MethodNode helper = null;
        int hooks = 0, stateHooks = 0;
        for (MethodNode method : client.methods) {
            if (method.name.equals("mcglResizeWindow")) helper = method;
            if (method.name.equals("ôo0000")) {
                java.util.List<AbstractInsnNode> real = new java.util.ArrayList<AbstractInsnNode>();
                for (AbstractInsnNode instruction : method.instructions.toArray())
                    if (instruction.getOpcode() >= 0) real.add(instruction);
                if (real.size() >= 3 && real.get(0) instanceof VarInsnNode
                        && real.get(0).getOpcode() == ALOAD && ((VarInsnNode)real.get(0)).var == 0
                        && real.get(1) instanceof MethodInsnNode && real.get(2) instanceof FieldInsnNode) {
                    MethodInsnNode call = (MethodInsnNode)real.get(1);
                    FieldInsnNode field = (FieldInsnNode)real.get(2);
                    if (call.getOpcode() == INVOKESTATIC && call.owner.equals("org/lwjgl/opengl/Display")
                            && call.name.equals("isFullscreen") && call.desc.equals("()Z")
                            && field.getOpcode() == PUTFIELD && field.owner.equals(client.name) && field.desc.equals("Z")) stateHooks++;
                }
                for (AbstractInsnNode instruction : method.instructions.toArray())
                    if (instruction instanceof MethodInsnNode
                            && ((MethodInsnNode)instruction).name.equals("mcglResizeWindow")) hooks++;
            }
        }
        if (helper == null) throw new AssertionError("Missing client resize helper");
        String name = "org/lwjgl/opengl/WindowResizeFixture";
        helper.access = ACC_PUBLIC; helper.name = "sync";
        for (AbstractInsnNode instruction : helper.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (!field.owner.equals(client.name) || !field.desc.equals("Z"))
                    throw new AssertionError("Unexpected helper field");
                field.owner = name; field.name = "fullscreen";
            }
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode)instruction;
            if (call.owner.equals(client.name)) {
                if (!call.desc.equals("(II)V")) throw new AssertionError("Unexpected helper call");
                call.owner = name; call.name = "resize";
            }
        }
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        out.visit(V1_7, ACC_PUBLIC, name, null, "java/lang/Object", null);
        out.visitField(ACC_PUBLIC, "fullscreen", "Z", null, null).visitEnd();
        for (String field : new String[] {"width", "height", "resizes"})
            out.visitField(ACC_PUBLIC, field, "I", null, null).visitEnd();
        MethodVisitor init = out.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V");
        init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
        MethodVisitor resize = out.visitMethod(ACC_PRIVATE, "resize", "(II)V", null, null);
        resize.visitCode();
        resize.visitVarInsn(ALOAD, 0); resize.visitVarInsn(ILOAD, 1);
        resize.visitFieldInsn(PUTFIELD, name, "width", "I");
        resize.visitVarInsn(ALOAD, 0); resize.visitVarInsn(ILOAD, 2);
        resize.visitFieldInsn(PUTFIELD, name, "height", "I");
        resize.visitVarInsn(ALOAD, 0); resize.visitInsn(DUP);
        resize.visitFieldInsn(GETFIELD, name, "resizes", "I");
        resize.visitInsn(ICONST_1); resize.visitInsn(IADD);
        resize.visitFieldInsn(PUTFIELD, name, "resizes", "I");
        resize.visitInsn(RETURN); resize.visitMaxs(0, 0); resize.visitEnd();
        helper.accept(out); out.visitEnd();
        Path path = Paths.get(args[1], name + ".class");
        Files.createDirectories(path.getParent()); Files.write(path, out.toByteArray());
        System.out.println("WINDOW_RESIZE_FIXTURE actual-client-helper; post-fullscreen-hooks=" + hooks
                + " before-toggle-state-hooks=" + stateHooks);
        if (args.length == 3 && args[2].equals("--require-hook") && (hooks != 1 || stateHooks != 1))
            throw new AssertionError("Expected exactly one resize hook and one pre-toggle state hook");
    }
}
