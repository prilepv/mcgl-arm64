import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Bind the game command producers to CPU state in the separate bgfx runtime JAR. */
public final class PrepareBgfxGameBridge {
    private static final String STATE = "local/mcgl/render/backend/BgfxGameState";
    private static final Set<String> PRODUCERS = new HashSet<>(Arrays.asList(
            "GameCommands", "GameRasterState", "GameRenderer", "MetalWorldCapture", "TerrainGLReadback"));

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Set<String> methods = new HashSet<>();
        for (Object value : read(root.resolve(STATE + ".class")).methods) {
            MethodNode method = (MethodNode)value;
            if ((method.access & Opcodes.ACC_STATIC) != 0) methods.add(method.name + method.desc);
        }

        int count = 0;
        try (DirectoryStream<Path> classes = Files.newDirectoryStream(root.resolve("local/mcgl/render/backend"), "*.class")) {
            for (Path file : classes) {
                String name = file.getFileName().toString().split("[.$]", 2)[0];
                if (!PRODUCERS.contains(name)) continue;
                ClassNode type = read(file);
                boolean changed = false;
                for (Object value : type.methods) {
                    MethodNode method = (MethodNode)value;
                    for (AbstractInsnNode instruction : method.instructions.toArray()) {
                        if (!(instruction instanceof MethodInsnNode)) continue;
                        MethodInsnNode call = (MethodInsnNode)instruction;
                        if (!call.owner.startsWith("org/lwjgl/opengl/")) continue;
                        if (call.getOpcode() != Opcodes.INVOKESTATIC || !methods.contains(call.name + call.desc))
                            throw new IllegalStateException(type.name + ": unsupported native call " + call.owner + "." + call.name + call.desc);
                        call.owner = STATE;
                        count++;
                        changed = true;
                    }
                    for (AbstractInsnNode instruction : method.instructions.toArray()) {
                        if (!(instruction instanceof InvokeDynamicInsnNode)) continue;
                        for (Object argument : ((InvokeDynamicInsnNode)instruction).bsmArgs) {
                            if (argument instanceof MethodHandle && ((MethodHandle)argument).getOwner().startsWith("org/lwjgl/opengl/"))
                                throw new IllegalStateException(type.name + ": native OpenGL method reference");
                        }
                    }
                }
                if (changed) {
                    ClassWriter writer = new ClassWriter(0);
                    type.accept(writer);
                    Files.write(file, writer.toByteArray());
                }
            }
        }
        if (count == 0) throw new IllegalStateException("Missing game command producers");
        System.out.println("BGFX_GAME_BRIDGE " + count + " calls routed to CPU state");
    }

    private static ClassNode read(Path file) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(node, 0);
        return node;
    }
}
