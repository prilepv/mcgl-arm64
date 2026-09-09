import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Link-only renderer boundary. Descriptors, locals, branches and all game algorithms stay intact. */
public final class PatchMCGLRenderer {
    private final SortedMap<String, RenderCommandSpec> commands;
    private int callsites;
    private PatchMCGLRenderer(Path manifest) throws IOException { commands = RenderCommandSpec.read(manifest); }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("input.jar NEW-output.jar render-commands.txt");
        Path output = Paths.get(args[1]).toAbsolutePath();
        if (Files.exists(output)) throw new IOException("Output exists: " + output);
        PatchMCGLRenderer patch = new PatchMCGLRenderer(Paths.get(args[2]));
        Path temporary = Files.createTempFile(output.getParent(), ".mcgl-render-", ".jar");
        int changed = 0, retained = 0;
        try {
            try (JarFile input = new JarFile(args[0]); JarOutputStream result = new JarOutputStream(Files.newOutputStream(temporary))) {
                if (input.getEntry("org/lwjgl/Version.class") != null)
                    throw new IOException("Renderer adaptation must not rewrite the binding library itself");
                for (Enumeration<JarEntry> entries = input.entries(); entries.hasMoreElements();) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    byte[] original;
                    try (InputStream stream = input.getInputStream(entry)) { original = read(stream); }
                    byte[] bytes = original;
                    if (entry.getName().endsWith(".class")) {
                        ClassNode type = new ClassNode();
                        new ClassReader(original).accept(type, 0);
                        int before = patch.callsites;
                        for (MethodNode method : type.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
                            if (instruction instanceof MethodInsnNode) {
                                MethodInsnNode call = (MethodInsnNode)instruction;
                                call.owner = patch.owner(call.owner, call.name, call.desc, call.getOpcode() == Opcodes.INVOKESTATIC);
                            } else if (instruction instanceof InvokeDynamicInsnNode) {
                                InvokeDynamicInsnNode call = (InvokeDynamicInsnNode)instruction;
                                call.bsm = patch.handle(call.bsm);
                                for (int i = 0; i < call.bsmArgs.length; i++)
                                    if (call.bsmArgs[i] instanceof MethodHandle) call.bsmArgs[i] = patch.handle((MethodHandle)call.bsmArgs[i]);
                            } else if (instruction instanceof LdcInsnNode && ((LdcInsnNode)instruction).cst instanceof MethodHandle) {
                                LdcInsnNode value = (LdcInsnNode)instruction;
                                value.cst = patch.handle((MethodHandle)value.cst);
                            }
                        }
                        if (before != patch.callsites) {
                            ClassWriter writer = new ClassWriter(0); type.accept(writer); bytes = writer.toByteArray(); changed++;
                        } else retained++;
                    }
                    JarEntry target = new JarEntry(entry.getName()); target.setTime(0);
                    result.putNextEntry(target); result.write(bytes); result.closeEntry();
                }
            }
            Files.move(temporary, output);
        } finally { Files.deleteIfExists(temporary); }
        System.out.println("RENDER_REMAP rewrittenClasses=" + changed + " untouchedClasses=" + retained + " callsites=" + patch.callsites);
    }
    private String owner(String owner, String name, String descriptor, boolean isStatic) throws IOException {
        if (!owner.startsWith("org/lwjgl/opengl/") || !name.startsWith("gl")) return owner;
        RenderCommandSpec command = commands.get(RenderCommandSpec.key(owner, name, descriptor));
        if (command == null || !isStatic)
            throw new IOException("Unknown render command; refusing bypass: " + owner + "." + name + descriptor);
        callsites++;
        return command.bridge;
    }
    private MethodHandle handle(MethodHandle handle) throws IOException {
        String mapped = owner(handle.getOwner(), handle.getName(), handle.getDesc(), handle.getTag() == Opcodes.MH_INVOKESTATIC);
        return mapped.equals(handle.getOwner()) ? handle : new MethodHandle(handle.getTag(), mapped, handle.getName(), handle.getDesc());
    }
    private static byte[] read(InputStream stream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = stream.read(buffer)) != -1) result.write(buffer, 0, count);
        return result.toByteArray();
    }
}
