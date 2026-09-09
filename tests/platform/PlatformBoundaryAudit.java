import java.io.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Check the built artifact, not just source folder names. */
public final class PlatformBoundaryAudit implements Opcodes {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("baseline-lwjgl.jar candidate-lwjgl.jar");
        int platforms = 0, adapters = 0;
        try (JarFile baseline = new JarFile(args[0]); JarFile candidate = new JarFile(args[1])) {
            require(candidate.getEntry("local/mcgl/platform/WindowBackend.class") != null, "No platform contract in candidate");
            require(candidate.getEntry("local/mcgl/platform/glfw/GlfwWindow.class") != null, "No native window implementation");
            require(candidate.getEntry("local/mcgl/platform/macos/MacOSMainThread.class") != null, "No macOS JNI implementation");
            require(candidate.getEntry("local/mcgl/glfw/MainThread.class") == null, "Old unscoped native entry point remains");
            for (Enumeration<JarEntry> entries = candidate.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                boolean platform = name.startsWith("local/mcgl/platform/");
                boolean adapter = name.matches("org/lwjgl/(Sys|opengl/(Display|GLFWInput))(\\$[^/]*)?\\.class");
                if (!name.endsWith(".class") || !(platform || adapter)) continue;
                ClassNode type = read(candidate, name);
                for (String reference : references(type)) {
                    if (platform) {
                        require(!reference.startsWith("java/awt/") && !reference.startsWith("java/applet/")
                                && !reference.startsWith("net/minecraft/") && !reference.startsWith("net/mcgl/"),
                                name + " imports legacy/game type " + reference);
                        require(!reference.startsWith("org/lwjgl/") || reference.startsWith("org/lwjgl/glfw/")
                                || reference.startsWith("org/lwjgl/system/"), name + " imports renderer/legacy LWJGL type " + reference);
                    }
                    if (adapter) {
                        require(!reference.startsWith("org/lwjgl/glfw/") && !reference.startsWith("local/mcgl/platform/glfw/")
                                && !reference.startsWith("local/mcgl/platform/macos/") && !reference.equals("java/lang/ProcessBuilder"),
                                name + " bypasses platform contracts: " + reference);
                    }
                }
                if (adapter) for (Object value : type.methods) {
                    MethodNode method = (MethodNode)value;
                    require((method.access & ACC_NATIVE) == 0, "Native method remains in client adapter");
                    for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode)instruction;
                        require(!(call.owner.equals("java/lang/System") && (call.name.equals("load") || call.name.equals("loadLibrary"))),
                                name + " loads native code directly");
                    }
                }
                if (platform) platforms++;
                if (adapter) adapters++;
            }
            require(platforms >= 14 && adapters >= 3, "Incomplete platform/adapter inventory");
            for (String name : new String[] {"org/lwjgl/opengl/Display.class", "org/lwjgl/Sys.class", "org/lwjgl/openal/MCGLAL.class"})
                require(publicMethods(read(baseline, name)).equals(publicMethods(read(candidate, name))), "Client public ABI changed: " + name);
        }
        System.out.println("PLATFORM_BOUNDARY_PASS platformClasses=" + platforms + " adapterClasses=" + adapters
                + " publicABIs=3 legacy/native-dependency-direction=checked");
    }
    private static ClassNode read(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        require(entry != null, "Missing class: " + name);
        ClassNode type = new ClassNode();
        try (InputStream input = jar.getInputStream(entry)) { new ClassReader(input).accept(type, ClassReader.SKIP_DEBUG); }
        return type;
    }
    private static Set<String> publicMethods(ClassNode type) {
        Set<String> result = new TreeSet<String>();
        for (Object value : type.methods) {
            MethodNode method = (MethodNode)value;
            if ((method.access & ACC_PUBLIC) != 0) result.add(method.name + method.desc + ":" + (method.access & ACC_STATIC));
        }
        return result;
    }
    private static Set<String> references(ClassNode type) {
        Set<String> result = new TreeSet<String>();
        if (type.superName != null) result.add(type.superName);
        for (Object face : type.interfaces) result.add((String)face);
        for (Object value : type.fields) descriptor(result, ((FieldNode)value).desc);
        for (Object value : type.methods) {
            MethodNode method = (MethodNode)value; descriptor(result, method.desc);
            for (Object exception : method.exceptions) result.add((String)exception);
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof TypeInsnNode) {
                    String name = ((TypeInsnNode)instruction).desc;
                    if (name.startsWith("[")) descriptor(result, name); else result.add(name);
                } else if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode)instruction; result.add(call.owner); descriptor(result, call.desc);
                } else if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode)instruction; result.add(field.owner); descriptor(result, field.desc);
                } else if (instruction instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode call = (InvokeDynamicInsnNode)instruction;
                    descriptor(result, call.desc); handle(result, call.bsm);
                    for (Object argument : call.bsmArgs) {
                        if (argument instanceof MethodHandle) handle(result, (MethodHandle)argument);
                        if (argument instanceof MethodType) descriptor(result, ((MethodType)argument).getDescriptor());
                        if (argument instanceof Type) addType(result, (Type)argument);
                    }
                } else if (instruction instanceof LdcInsnNode && ((LdcInsnNode)instruction).cst instanceof Type)
                    addType(result, (Type)((LdcInsnNode)instruction).cst);
            }
        }
        return result;
    }
    private static void handle(Set<String> result, MethodHandle handle) { result.add(handle.getOwner()); descriptor(result, handle.getDesc()); }
    private static void descriptor(Set<String> result, String descriptor) {
        if (descriptor.startsWith("(")) {
            for (Type argument : Type.getArgumentTypes(descriptor)) addType(result, argument);
            addType(result, Type.getReturnType(descriptor));
        } else addType(result, Type.getType(descriptor));
    }
    private static void addType(Set<String> result, Type type) {
        if (type.getSort() == Type.ARRAY) addType(result, type.getElementType());
        else if (type.getSort() == Type.OBJECT) result.add(type.getInternalName());
    }
    private static void require(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
}
