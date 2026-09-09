import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Emit typed, immediate calls: no reflection, boxing, command allocation or reordering at runtime. */
public final class GenerateRenderBridge {
    private static final String ROOT = "local.mcgl.render";
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("manifest NEW-generated-directory");
        Path output = Paths.get(args[1]);
        if (Files.exists(output)) throw new IOException("Generated directory exists: " + output);
        Collection<RenderCommandSpec> commands = RenderCommandSpec.read(Paths.get(args[0])).values();
        StringBuilder contract = start(ROOT, "public interface LegacyRenderCommands");
        StringBuilder guarded = start(ROOT, "final class GuardedRenderCommands implements LegacyRenderCommands");
        guarded.append("    private final RenderContext context;\n    private final LegacyRenderCommands delegate;\n")
                .append("    GuardedRenderCommands(RenderContext context, LegacyRenderCommands delegate) { this.context = context; this.delegate = delegate; }\n");
        StringBuilder backend = start(ROOT + ".backend", "final class CompatibilityCommands implements " + ROOT + ".LegacyRenderCommands");
        backend.append("    private final CompatibilityState state;\n    CompatibilityCommands(CompatibilityState state) { this.state = state; }\n");
        Map<String, StringBuilder> adapters = new TreeMap<String, StringBuilder>();
        for (RenderCommandSpec command : commands) {
            String parameters = parameters(command), arguments = arguments(command);
            String result = command.result.getClassName();
            String returns = result.equals("void") ? "" : "return ";
            contract.append("    ").append(result).append(' ').append(command.dispatch).append('(').append(parameters).append(");\n");
            guarded.append("    public ").append(result).append(' ').append(command.dispatch).append('(').append(parameters)
                    .append(") { context.checkOwner(); ").append(returns).append("delegate.").append(command.dispatch).append('(').append(arguments).append("); }\n");
            StringBuilder adapter = adapters.get(command.family);
            if (adapter == null) {
                adapter = start(ROOT + ".legacy", "public final class " + command.family);
                adapter.append("    private ").append(command.family).append("() {}\n");
                adapters.put(command.family, adapter);
            }
            adapter.append("    public static ").append(result).append(' ').append(command.name).append('(').append(parameters).append(") { ");
            if (command.family.equals("GL11") && command.name.equals("glNewList"))
                adapter.append(ROOT).append(".RenderSystem.recordDisplayList(); ");
            adapter.append(returns).append(ROOT).append(".RenderSystem.commands().").append(command.dispatch).append('(').append(arguments).append("); }\n");
            backend.append("    public ").append(result).append(' ').append(command.dispatch).append('(').append(parameters)
                    .append(") { ").append(backendCall(command, returns, arguments)).append(" }\n");
        }
        write(output, ROOT, "LegacyRenderCommands", contract);
        write(output, ROOT, "GuardedRenderCommands", guarded);
        write(output, ROOT + ".backend", "CompatibilityCommands", backend);
        for (Map.Entry<String, StringBuilder> adapter : adapters.entrySet()) write(output, ROOT + ".legacy", adapter.getKey(), adapter.getValue());
        System.out.println("RENDER_BRIDGE_GENERATED commands=" + commands.size() + " legacyFamilies=" + adapters.size());
    }
    private static String parameters(RenderCommandSpec command) {
        StringJoiner result = new StringJoiner(", ");
        for (int i = 0; i < command.arguments.length; i++) result.add(command.arguments[i].getClassName() + " p" + i);
        return result.toString();
    }
    private static String arguments(RenderCommandSpec command) {
        StringJoiner result = new StringJoiner(", ");
        for (int i = 0; i < command.arguments.length; i++) result.add("p" + i);
        return result.toString();
    }
    private static String backendCall(RenderCommandSpec command, String returns, String arguments) {
        String name = command.name, after = "";
        if (command.family.equals("GL11")) {
            String signature = command.name + command.descriptor;
            switch (signature) {
                case "glFog(ILjava/nio/FloatBuffer;)V": name = "glFogfv"; break;
                case "glGetFloat(ILjava/nio/FloatBuffer;)V": name = "glGetFloatv"; break;
                case "glGetInteger(ILjava/nio/IntBuffer;)V": name = "glGetIntegerv"; break;
                case "glLight(IILjava/nio/FloatBuffer;)V": name = "glLightfv"; break;
                case "glLightModel(ILjava/nio/FloatBuffer;)V": name = "glLightModelfv"; break;
                case "glLoadMatrix(Ljava/nio/FloatBuffer;)V": name = "glLoadMatrixf"; break;
                case "glMultMatrix(Ljava/nio/FloatBuffer;)V": name = "glMultMatrixf"; break;
                case "glColorPointer(IZILjava/nio/ByteBuffer;)V":
                    arguments = "p0, p1 ? org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE : org.lwjgl.opengl.GL11.GL_BYTE, p2, p3";
                    after = " state.pointers.put(-1, p3);"; break;
                case "glNormalPointer(ILjava/nio/ByteBuffer;)V":
                    arguments = "org.lwjgl.opengl.GL11.GL_BYTE, p0, p1"; after = " state.pointers.put(-2, p1);"; break;
                case "glVertexPointer(IILjava/nio/FloatBuffer;)V":
                    arguments = "p0, org.lwjgl.opengl.GL11.GL_FLOAT, p1, p2"; after = " state.pointers.put(-3, p2);"; break;
                case "glTexCoordPointer(IILjava/nio/FloatBuffer;)V":
                    arguments = "p0, org.lwjgl.opengl.GL11.GL_FLOAT, p1, p2"; after = " state.pointers.put(state.textureUnit, p2);"; break;
                case "glTexCoordPointer(IILjava/nio/ShortBuffer;)V":
                    arguments = "p0, org.lwjgl.opengl.GL11.GL_SHORT, p1, p2"; after = " state.pointers.put(state.textureUnit, p2);"; break;
                case "glBegin(I)V": after = " state.begin = true;"; break;
                case "glEnd()V": after = " state.begin = false;"; break;
                case "glPushClientAttrib(I)V": after = " state.pushClientAttributes(p0);"; break;
                case "glPopClientAttrib()V": after = " state.popClientAttributes();"; break;
                default: break;
            }
        } else if ((command.family.equals("GL13") && name.equals("glClientActiveTexture"))
                || (command.family.equals("ARBMultitexture") && name.equals("glClientActiveTextureARB")))
            after = " state.textureUnit = p0;";
        return returns + command.owner.replace('/', '.') + "." + name + "(" + arguments + ");" + after;
    }
    private static StringBuilder start(String packageName, String declaration) {
        return new StringBuilder("// Generated from renderer/legacy-commands.txt by GenerateRenderBridge; do not edit.\npackage ")
                .append(packageName).append(";\n\n").append(declaration).append(" {\n");
    }
    private static void write(Path root, String packageName, String name, StringBuilder source) throws IOException {
        Path file = root.resolve(packageName.replace('.', '/')).resolve(name + ".java");
        Files.createDirectories(file.getParent());
        Files.write(file, source.append("}\n").toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
}
