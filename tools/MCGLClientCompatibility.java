import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** Reversible, hash-pinned staging names for the reviewed October MCGL update.
 * Never load game classes or rewrite reflection strings. Only staging JARs use
 * canonical names; restore the official namespace before publishing any files.
 */
public final class MCGLClientCompatibility {
    static final String ORIGINAL_SHA = "d014597ec18c5cda9622b2e5964eb2871e04b3e267d944bbbbb06e7f31f08786";
    static final String UPDATED_SHA = "be89b9beca87d446c67321f2d74881cc11146fc2c92703693de360729e204ede";
    static final String PROFILE = "/client-profiles/official-1ef465d6.tsv";
    static final String PROFILE_SHA = "3b6c5d1ce8c3063f66da904705c74ece4c3718cc8294194291e3df6f12a6b60f";

    static final class Names extends Remapper {
        final Map<String, String> classes = new HashMap<String, String>();
        final Map<String, String> members = new HashMap<String, String>();
        final Map<String, ClassNode> hierarchy;
        Names(Map<String, ClassNode> hierarchy) { this.hierarchy = hierarchy; }
        public String map(String name) { String result = classes.get(name); return result == null ? name : result; }
        static String key(String kind, String owner, String name, String desc) { return kind + " " + owner + " " + name + " " + desc; }
        String member(String kind, String owner, String name, String desc, Set<String> visited) {
            if (!visited.add(owner)) return null;
            String result = members.get(key(kind, owner, name, desc));
            if (result != null) return result;
            ClassNode node = hierarchy.get(owner);
            if (node == null) return null;
            if (node.superName != null) {
                result = member(kind, node.superName, name, desc, visited);
                if (result != null) return result;
            }
            for (String parent : node.interfaces) {
                result = member(kind, parent, name, desc, visited);
                if (result != null) return result;
            }
            return null;
        }
        public String mapFieldName(String owner, String name, String desc) {
            String result = member("F", owner, name, desc, new HashSet<String>());
            return result == null ? name : result;
        }
        public String mapMethodName(String owner, String name, String desc) {
            if (name.startsWith("<")) return name;
            String result = member("M", owner, name, desc, new HashSet<String>());
            return result == null ? name : result;
        }
    }

    static LinkedHashMap<String, byte[]> readJar(Path path) throws IOException {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        try (JarFile jar = new JarFile(path.toFile())) {
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    require(result.put(entry.getName(), read(in)) == null, "Duplicate JAR entry");
                }
            }
        }
        return result;
    }

    static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[16384];
        for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    static Map<String, ClassNode> hierarchy(Map<String, byte[]> files) throws IOException {
        Map<String, ClassNode> result = new HashMap<String, ClassNode>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) if (entry.getKey().endsWith(".class")) {
            ClassNode node = new ClassNode();
            new ClassReader(entry.getValue()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            require(entry.getKey().equals(node.name + ".class"), "Class entry/name mismatch");
            require(result.put(node.name, node) == null, "Duplicate class");
            Set<String> fields = new HashSet<String>(), methods = new HashSet<String>();
            for (FieldNode field : node.fields) require(fields.add(field.name + ":" + field.desc), "Duplicate field: " + node.name);
            for (MethodNode method : node.methods) require(methods.add(method.name + method.desc), "Duplicate method: " + node.name);
        }
        return result;
    }

    static Names names(boolean restore, Map<String, ClassNode> hierarchy) throws IOException {
        byte[] profile;
        try (InputStream input = MCGLClientCompatibility.class.getResourceAsStream(PROFILE)) {
            require(input != null, "Missing official-client compatibility profile"); profile = read(input);
        }
        require(MCGLBytecodeFingerprint.sha256(profile).equals(PROFILE_SHA), "Changed compatibility profile");
        List<String[]> records = new ArrayList<String[]>(); Names forward = new Names(Collections.<String, ClassNode>emptyMap());
        Set<String> targets = new HashSet<String>();
        for (String line : new String(profile, StandardCharsets.UTF_8).split("\n")) {
            String[] parts = line.split("\t", -1);
            require(parts.length == (parts[0].equals("C") ? 3 : 5), "Invalid compatibility record");
            records.add(parts);
            if (parts[0].equals("C")) {
                require(forward.classes.put(parts[1], parts[2]) == null && targets.add(parts[2]), "Non-bijective class profile");
            }
        }
        require(forward.classes.size() == 2568, "Incomplete class profile");
        Names result = new Names(hierarchy);
        for (Map.Entry<String, String> entry : forward.classes.entrySet())
            result.classes.put(restore ? entry.getKey() : entry.getValue(), restore ? entry.getValue() : entry.getKey());
        for (String[] p : records) if (!p[0].equals("C")) {
            require(p[0].equals("M") || p[0].equals("F"), "Unknown member profile kind");
            String owner = restore ? p[1] : forward.map(p[1]);
            String name = restore ? p[2] : p[4];
            String desc = restore ? p[3] : p[0].equals("M") ? forward.mapMethodDesc(p[3]) : forward.mapDesc(p[3]);
            String target = restore ? p[4] : p[2];
            require(result.members.put(Names.key(p[0], owner, name, desc), target) == null, "Non-bijective member profile");
        }
        return result;
    }

    static byte[] transform(byte[] bytes, final Names names) {
        ClassReader reader = new ClassReader(bytes); ClassWriter writer = new ClassWriter(0);
        reader.accept(new RemappingClassAdapter(writer, names) {
            protected MethodVisitor createRemappingMethodAdapter(int access, String descriptor, MethodVisitor target) {
                // ASM's default LocalVariablesSorter changes slots/maxLocals and
                // expands frames. Preserve all three: strict patch guards inspect them.
                return new RemappingMethodAdapter(access, descriptor, target, names) {
                    public void visitVarInsn(int opcode, int index) { mv.visitVarInsn(opcode, index); }
                    public void visitIincInsn(int index, int increment) { mv.visitIincInsn(index, increment); }
                    public void visitMaxs(int stack, int locals) { mv.visitMaxs(stack, locals); }
                    public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
                        mv.visitLocalVariable(name, names.mapDesc(desc), names.mapSignature(signature, true), start, end, index);
                    }
                    Object[] entries(int size, Object[] values) {
                        if (values == null) return null;
                        Object[] result = values.clone();
                        for (int i = 0; i < size; i++) if (result[i] instanceof String) result[i] = names.mapType((String)result[i]);
                        return result;
                    }
                    public void visitFrame(int type, int locals, Object[] local, int stack, Object[] values) {
                        mv.visitFrame(type, locals, entries(locals, local), stack, entries(stack, values));
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    static byte[] canonical(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0); new ClassReader(bytes).accept(writer, 0); return writer.toByteArray();
    }

    static LinkedHashMap<String, byte[]> normalize(Map<String, byte[]> original) throws IOException {
        Names names = names(false, hierarchy(original));
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : original.entrySet()) {
            String path = entry.getKey(); byte[] bytes = entry.getValue();
            if (path.endsWith(".class")) { path = names.map(new ClassReader(bytes).getClassName()) + ".class"; bytes = transform(bytes, names); }
            require(result.put(path, bytes) == null, "Colliding normalized entry");
        }
        // Check every method, field, frame, constant and class attribute without
        // loading the game. Future changes to this adapter must still round-trip.
        Names inverse = names(true, hierarchy(result));
        for (Map.Entry<String, byte[]> entry : result.entrySet()) if (entry.getKey().endsWith(".class")) {
            byte[] restored = transform(entry.getValue(), inverse);
            String official = new ClassReader(restored).getClassName() + ".class";
            require(original.containsKey(official) && Arrays.equals(canonical(original.get(official)), canonical(restored)),
                    "Non-reversible compatibility mapping: " + official);
        }
        return result;
    }

    static LinkedHashMap<String, byte[]> restore(Map<String, byte[]> original, Map<String, byte[]> patched) throws IOException {
        Map<String, byte[]> normalized = normalize(original);
        require(patched.keySet().containsAll(normalized.keySet()), "Patched client lost official entries");
        Names names = names(true, hierarchy(patched));
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : patched.entrySet()) {
            String path = entry.getKey(); byte[] bytes = entry.getValue();
            if (path.endsWith(".class")) {
                String official = names.map(new ClassReader(bytes).getClassName()) + ".class";
                // Preserve untouched upstream classes byte-for-byte, including
                // constant-pool layout. Never change encrypted reflection strings.
                bytes = Arrays.equals(bytes, normalized.get(path)) ? original.get(official) : transform(bytes, names);
                path = official;
            } else if (original.containsKey(path)) {
                require(Arrays.equals(bytes, original.get(path)), "Changed official resource: " + path);
            }
            require(!path.startsWith("local/mcgl/staging/"), "Staging class escaped restoration");
            require(result.put(path, bytes) == null, "Colliding restored entry");
        }
        hierarchy(result);
        return result;
    }

    static boolean updated(Path official) throws IOException {
        String sha = MCGLBytecodeFingerprint.sha256(Files.readAllBytes(official));
        if (ORIGINAL_SHA.equals(sha)) return false;
        if (UPDATED_SHA.equals(sha)) return true;
        throw new IOException("MCGL_UNSUPPORTED_CLIENT: official mcgl.jar SHA-256=" + sha + "; launcher compatibility update required");
    }

    static void write(Path output, Map<String, byte[]> files) throws IOException {
        require(!Files.exists(output), "Output already exists: " + output);
        Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), ".mcgl-compat-", ".jar");
        try {
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(temporary))) {
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    JarEntry target = new JarEntry(entry.getKey()); target.setTime(0);
                    out.putNextEntry(target); out.write(entry.getValue()); out.closeEntry();
                }
            }
            Files.move(temporary, output);
        } finally { Files.deleteIfExists(temporary); }
    }

    public static void main(String[] args) throws Exception {
        boolean restoring = args.length == 4 && args[0].equals("restore");
        require(restoring || args.length == 3 && args[0].equals("normalize"),
                "Usage: normalize official.jar NEW-output.jar | restore official.jar patched.jar NEW-output.jar");
        Path official = Paths.get(args[1]); Path output = Paths.get(args[args.length - 1]);
        boolean updated = updated(official);
        if (!updated) Files.copy(restoring ? Paths.get(args[2]) : official, output);
        else {
            Map<String, byte[]> original = readJar(official);
            write(output, restoring ? restore(original, readJar(Paths.get(args[2]))) : normalize(original));
        }
        System.out.println("CLIENT_COMPATIBILITY_PASS mode=" + args[0] + " profile=" + (updated ? "official-1ef465d6" : "original"));
    }

    static void require(boolean ok, String reason) throws IOException { if (!ok) throw new IOException(reason); }
    private MCGLClientCompatibility() {}
}
