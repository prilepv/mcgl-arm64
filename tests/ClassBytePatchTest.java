import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** No credentials, native calls or client class loading; real and synthetic bytecode. */
public final class ClassBytePatchTest implements Opcodes {
    private static final String CLIENT = "net/minecraft/client/Minecraft";
    private static final String APPLET = "net/minecraft/client/MinecraftApplet";
    private static final String DISPLAY = "org/lwjgl/opengl/Display";

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static ClassNode node(byte[] data) {
        ClassNode node = new ClassNode();
        new ClassReader(data).accept(node, 0);
        return node;
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] methodBytes(MethodNode method) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_6, ACC_PUBLIC, "MethodFingerprint", null, "java/lang/Object", null);
        method.accept(writer);
        writer.visitEnd();
        method.instructions.resetLabels();
        return writer.toByteArray();
    }

    private static int calls(MethodNode method, String name, String desc) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (DISPLAY.equals(call.owner) && name.equals(call.name) && desc.equals(call.desc)) count++;
        }
        return count;
    }

    private static void verify(byte[] original, String description) throws Exception {
        byte[] snapshot = original.clone();
        byte[] patched = ClassBytePatch.patch(original);
        check(Arrays.equals(original, snapshot), "patch mutated its input array");
        ClassNode before = node(original), after = node(patched);
        check(before.methods.size() == after.methods.size(), "method count changed");
        int modified = 0;
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode a = before.methods.get(i), b = after.methods.get(i);
            if (calls(a, "setParent", "(Ljava/awt/Canvas;)V") == 0) {
                check(Arrays.equals(methodBytes(a), methodBytes(b)), "unrelated method changed: " + a.name + a.desc);
                continue;
            }
            modified++;
            check(calls(b, "setParent", "(Ljava/awt/Canvas;)V") == 0, "setParent remains");
            check(calls(b, "setDisplayMode", "(Lorg/lwjgl/opengl/DisplayMode;)V") == 0, "startup mode remains");
            check(calls(b, "create", "(Lorg/lwjgl/opengl/PixelFormat;)V") == 0, "startup create remains");
            check(calls(a, "create", "()V") == calls(b, "create", "()V"), "fallback changed");
            int pop2 = 0, nullBranches = 0;
            for (AbstractInsnNode instruction : b.instructions.toArray()) {
                if (instruction.getOpcode() == POP2) pop2++;
                if (instruction.getOpcode() == IFNULL && instruction.getPrevious().getOpcode() == ACONST_NULL)
                    nullBranches++;
            }
            int originalPop2 = 0;
            for (AbstractInsnNode instruction : a.instructions.toArray())
                if (instruction.getOpcode() == POP2) originalPop2++;
            check(pop2 == originalPop2 + 12 && nullBranches == 1, "loading screen/Canvas edits incomplete");
            // BasicVerifier validates stack and local types without resolving or
            // initializing obfuscated game/native/AWT classes.
            new Analyzer(new BasicVerifier()).analyze(after.name, b);
        }
        check(modified == 1, "wrong startup count");
        reject(patched, "already patched");
        System.out.println("CLASS_BYTE_PATCH_PASS " + description + " startup-only/stack-safe");
    }

    private static void reject(byte[] input, String description) throws Exception {
        Path temporary = Files.createTempFile("mcgl-window-negative-", ".class");
        try {
            Files.write(temporary, input);
            boolean rejected = false;
            try { ClassBytePatch.main(new String[]{temporary.toString()}); }
            catch (IllegalStateException expected) {
                check(expected.getMessage().startsWith("Unsupported official Minecraft.class:"), "missing error context");
                rejected = true;
            }
            check(rejected, "accepted " + description);
            check(Arrays.equals(input, Files.readAllBytes(temporary)), "failure changed input: " + description);
        } finally {
            Files.delete(temporary);
        }
    }

    private static byte[] fixture(String loadingName, int padding) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_6, ACC_PUBLIC, CLIENT, null, "java/lang/Object", null);
        for (int i = 0; i < padding; i++) w.newConst("unrelated constant " + i);
        w.visitField(ACC_PUBLIC, "canvas", "Ljava/awt/Canvas;", null, null).visitEnd();
        w.visitField(ACC_PUBLIC, "applet", "L" + APPLET + ";", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "startup", "()V", null, null);
        m.visitCode();
        for (int i = 0; i < 12; i++) {
            m.visitVarInsn(ALOAD, 0);
            m.visitFieldInsn(GETFIELD, CLIENT, "applet", "L" + APPLET + ";");
            m.visitFieldInsn(GETFIELD, APPLET, "renamedLoadingCanvas", "Lofficial/ObfuscatedCanvas;");
            m.visitLdcInsn("status");
            m.visitMethodInsn(INVOKEVIRTUAL, "official/ObfuscatedCanvas", loadingName, "(Ljava/lang/String;)V");
        }
        Label standalone = new Label();
        m.visitVarInsn(ALOAD, 0); m.visitFieldInsn(GETFIELD, CLIENT, "canvas", "Ljava/awt/Canvas;");
        m.visitJumpInsn(IFNULL, standalone);
        m.visitVarInsn(ALOAD, 0); m.visitFieldInsn(GETFIELD, CLIENT, "canvas", "Ljava/awt/Canvas;");
        m.visitMethodInsn(INVOKESTATIC, DISPLAY, "setParent", "(Ljava/awt/Canvas;)V");
        m.visitLabel(standalone);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKESTATIC, DISPLAY, "setDisplayMode", "(Lorg/lwjgl/opengl/DisplayMode;)V");
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKESTATIC, DISPLAY, "create", "(Lorg/lwjgl/opengl/PixelFormat;)V");
        m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "inGameMode", "()V", null, null);
        m.visitCode(); m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKESTATIC, DISPLAY, "setDisplayMode", "(Lorg/lwjgl/opengl/DisplayMode;)V");
        m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        byte[] fixture = fixture("oldName", 0);
        verify(fixture, "synthetic original");
        verify(fixture("newObfuscatedName", 500), "renamed loading method + shifted constant pool");
        for (String target : new String[]{"setParent", "setDisplayMode", "create", "oldName"}) {
            ClassNode broken = node(fixture);
            for (AbstractInsnNode instruction : broken.methods.get(0).instructions.toArray()) {
                if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals(target)) {
                    ((MethodInsnNode) instruction).name = "unexpectedMethod";
                    break;
                }
            }
            reject(bytes(broken), "missing/changed " + target);
        }
        ClassNode ambiguous = node(fixture);
        MethodNode startup = ambiguous.methods.get(0);
        startup.instructions.insert(new MethodInsnNode(INVOKESTATIC, DISPLAY, "setParent", "(Ljava/awt/Canvas;)V"));
        reject(bytes(ambiguous), "ambiguous setParent");
        ClassNode wrong = node(fixture); wrong.name = "wrong/Client";
        reject(bytes(wrong), "wrong class");
        for (String path : args) {
            try (JarFile jar = new JarFile(path);
                 InputStream in = jar.getInputStream(jar.getJarEntry(CLIENT + ".class"));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384];
                for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
                verify(out.toByteArray(), path);
            }
        }
        System.out.println("CLASS_BYTE_PATCH_NEGATIVE_PASS rejects unknown/ambiguous/partially patched input without writing");
    }
}
