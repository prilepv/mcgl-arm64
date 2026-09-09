import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.Type;

/** Shared, strict signature manifest for generation, installation and boundary tests. */
public final class RenderCommandSpec {
    public final String owner, name, descriptor, family, bridge, dispatch;
    public final Type result;
    public final Type[] arguments;
    private RenderCommandSpec(String owner, String name, String descriptor) throws IOException {
        if (!owner.matches("org/lwjgl/opengl/(MCGL)?(GL[0-9]+|ARB[A-Za-z0-9_]+|EXT[A-Za-z0-9_]+|APPLE[A-Za-z0-9_]+)")
                || !name.matches("gl[A-Za-z0-9_]+") || !descriptor.startsWith("("))
            throw new IOException("Invalid render signature: " + owner + " " + name + " " + descriptor);
        this.owner = normalize(owner); this.name = name; this.descriptor = descriptor;
        family = this.owner.substring(this.owner.lastIndexOf('/') + 1);
        bridge = "local/mcgl/render/legacy/" + family;
        dispatch = family + "_" + name;
        try {
            arguments = Type.getArgumentTypes(descriptor); result = Type.getReturnType(descriptor);
            if (!Type.getMethodDescriptor(result, arguments).equals(descriptor)) throw new IllegalArgumentException("descriptor tail");
            for (Type argument : arguments) validate(argument, false);
            validate(result, true);
        } catch (RuntimeException invalid) { throw new IOException("Invalid render descriptor: " + descriptor, invalid); }
    }
    private static void validate(Type type, boolean result) {
        if (type.getSort() == Type.VOID && !result) throw new IllegalArgumentException("void parameter");
        if (type.getSort() == Type.ARRAY) { validate(type.getElementType(), false); return; }
        if (type.getSort() == Type.OBJECT && !type.getInternalName().matches(
                "java/(nio/(Byte|Short|Int|Long|Float|Double)Buffer|lang/(String|CharSequence))"))
            throw new IllegalArgumentException("Non-JDK render contract type: " + type);
    }
    public static String normalize(String owner) {
        if (owner.startsWith("org/lwjgl/opengl/MCGL"))
            return "org/lwjgl/opengl/" + owner.substring("org/lwjgl/opengl/MCGL".length());
        return owner;
    }
    public static String key(String owner, String name, String descriptor) { return normalize(owner) + " " + name + " " + descriptor; }
    public String key() { return key(owner, name, descriptor); }
    public static SortedMap<String, RenderCommandSpec> read(Path file) throws IOException {
        SortedMap<String, RenderCommandSpec> commands = new TreeMap<String, RenderCommandSpec>();
        int line = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line++; String text = raw.trim();
            if (text.isEmpty() || text.startsWith("#")) continue;
            String[] fields = text.split("\\s+");
            if (fields.length != 3) throw new IOException("Invalid render command at line " + line);
            RenderCommandSpec command = new RenderCommandSpec(fields[0], fields[1], fields[2]);
            if (commands.put(command.key(), command) != null) throw new IOException("Duplicate render command: " + command.key());
        }
        if (commands.isEmpty()) throw new IOException("Empty render command manifest");
        return commands;
    }
}
