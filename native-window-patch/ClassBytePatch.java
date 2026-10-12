import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Disable only startup AWT/window ownership, without constant-pool offsets. */
public final class ClassBytePatch implements Opcodes {
    private static final String CLIENT = "net/minecraft/client/Minecraft";
    private static final String APPLET = "net/minecraft/client/MinecraftApplet";
    private static final String DISPLAY = "org/lwjgl/opengl/Display";

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode result = instruction.getPrevious();
        while (result != null && result.getOpcode() < 0) result = result.getPrevious();
        return result;
    }

    private static boolean call(AbstractInsnNode instruction, String name, String desc) {
        if (!(instruction instanceof MethodInsnNode)) return false;
        MethodInsnNode method = (MethodInsnNode) instruction;
        return method.getOpcode() == INVOKESTATIC && DISPLAY.equals(method.owner)
                && name.equals(method.name) && desc.equals(method.desc);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException("Unsupported official Minecraft.class: " + reason);
    }

    private static <T> T one(List<T> matches, String description) {
        require(matches.size() == 1, "expected one " + description + ", found " + matches.size());
        return matches.get(0);
    }

    private static boolean sameField(AbstractInsnNode instruction, FieldInsnNode expected) {
        if (!(instruction instanceof FieldInsnNode)) return false;
        FieldInsnNode field = (FieldInsnNode) instruction;
        return field.getOpcode() == GETFIELD && field.owner.equals(expected.owner)
                && field.name.equals(expected.name) && field.desc.equals(expected.desc);
    }

    /** Returns new bytes only after every expected startup site has been validated. */
    public static byte[] patch(byte[] data) {
        ClassReader reader = new ClassReader(data);
        ClassNode owner = new ClassNode();
        reader.accept(owner, 0);
        require(CLIENT.equals(owner.name), "wrong class " + owner.name);
        List<MethodInsnNode> parents = new ArrayList<MethodInsnNode>();
        MethodNode startup = null;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (call(instruction, "setParent", "(Ljava/awt/Canvas;)V")) {
                    parents.add((MethodInsnNode) instruction);
                    startup = method;
                }
            }
        }
        MethodInsnNode parent = one(parents, "Display.setParent call");
        require("()V".equals(startup.desc) && (startup.access & ACC_STATIC) == 0,
                "unexpected startup method");
        AbstractInsnNode receiver = previousReal(parent);
        require(receiver instanceof FieldInsnNode, "setParent must receive the client Canvas field");
        FieldInsnNode canvas = (FieldInsnNode) receiver;
        AbstractInsnNode self = previousReal(canvas);
        require(canvas.getOpcode() == GETFIELD && CLIENT.equals(canvas.owner)
                && "Ljava/awt/Canvas;".equals(canvas.desc)
                && self instanceof VarInsnNode && self.getOpcode() == ALOAD
                && ((VarInsnNode) self).var == 0, "unexpected setParent receiver");

        List<FieldInsnNode> branches = new ArrayList<FieldInsnNode>();
        List<MethodInsnNode> modes = new ArrayList<MethodInsnNode>();
        List<MethodInsnNode> creates = new ArrayList<MethodInsnNode>();
        Set<String> appletFieldTypes = new HashSet<String>();
        for (AbstractInsnNode instruction : startup.instructions.toArray()) {
            if (instruction instanceof JumpInsnNode && instruction.getOpcode() == IFNULL
                    && sameField(previousReal(instruction), canvas)
                    && startup.instructions.indexOf(instruction) < startup.instructions.indexOf(parent)
                    && startup.instructions.indexOf(((JumpInsnNode) instruction).label)
                        > startup.instructions.indexOf(parent)) {
                branches.add((FieldInsnNode) previousReal(instruction));
            }
            if (call(instruction, "setDisplayMode", "(Lorg/lwjgl/opengl/DisplayMode;)V"))
                modes.add((MethodInsnNode) instruction);
            if (call(instruction, "create", "(Lorg/lwjgl/opengl/PixelFormat;)V"))
                creates.add((MethodInsnNode) instruction);
            if (instruction instanceof FieldInsnNode && instruction.getOpcode() == GETFIELD) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                // The loading Canvas is an object field of MinecraftApplet. Its
                // class/field/method names may change in the official obfuscator.
                if (APPLET.equals(field.owner) && field.desc.startsWith("L"))
                    appletFieldTypes.add(field.desc.substring(1, field.desc.length() - 1));
            }
        }
        FieldInsnNode branchCanvas = one(branches, "embedded-window branch");
        MethodInsnNode mode = one(modes, "startup Display.setDisplayMode call");
        MethodInsnNode create = one(creates, "startup Display.create(PixelFormat) call");
        List<MethodInsnNode> loading = new ArrayList<MethodInsnNode>();
        Set<String> loadingTargets = new HashSet<String>();
        for (AbstractInsnNode instruction : startup.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode method = (MethodInsnNode) instruction;
            if (method.getOpcode() == INVOKEVIRTUAL && "(Ljava/lang/String;)V".equals(method.desc)
                    && appletFieldTypes.contains(method.owner)) {
                loading.add(method);
                loadingTargets.add(method.owner + "." + method.name + method.desc);
            }
        }
        require(loadingTargets.size() == 1 && loading.size() == 12,
                "expected 12 calls to one applet loading-screen method, found "
                + loading.size() + " calls / " + loadingTargets.size() + " methods");

        // Stack-neutral replacements preserve branches and existing frames.
        // DirectLauncher already owns the window and its OpenGL context.
        startup.instructions.set(parent, new InsnNode(POP));
        InsnList nullCanvas = new InsnList();
        nullCanvas.add(new InsnNode(POP)); // discard client reference instead of reading its Canvas
        nullCanvas.add(new InsnNode(ACONST_NULL));
        startup.instructions.insertBefore(branchCanvas, nullCanvas);
        startup.instructions.remove(branchCanvas);
        startup.instructions.set(mode, new InsnNode(POP));
        startup.instructions.set(create, new InsnNode(POP));
        for (MethodInsnNode method : loading) startup.instructions.set(method, new InsnNode(POP2));
        // Other methods (including in-game display-mode changes) stay untouched.
        ClassWriter writer = new ClassWriter(reader, 0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected Minecraft.class path");
        Path path = Paths.get(args[0]);
        byte[] patched = patch(Files.readAllBytes(path));
        Files.write(path, patched);
        System.out.println("Patched startup window ownership and 12 AWT loading-screen calls (semantic bytecode matching).");
    }
}
