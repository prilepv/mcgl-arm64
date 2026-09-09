import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Regression checks for link-only adaptation; uses local, original client inputs. */
public final class LWJGL3PatchTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("original JAR inputs required");
        Path directory = Files.createTempDirectory("mcgl-lwjgl3-patch-test-");
        int untouched = 0, adapted = 0;
        for (int index = 0; index < args.length; index++) {
            Path first = directory.resolve(index + "-first.jar");
            Path second = directory.resolve(index + "-second.jar");
            PatchMCGLLwjgl3.main(new String[] {args[index], first.toString()});
            PatchMCGLLwjgl3.main(new String[] {first.toString(), second.toString()});
            Map<String, byte[]> before = readJar(args[index]);
            Map<String, byte[]> after = readJar(first.toString());
            Map<String, byte[]> again = readJar(second.toString());
            require(before.keySet().equals(after.keySet()), "client/resource entry inventory");
            require(after.keySet().equals(again.keySet()), "idempotent inventory");
            for (Map.Entry<String, byte[]> entry : before.entrySet()) {
                String name = entry.getKey(); byte[] original = entry.getValue(); byte[] patched = after.get(name);
                require(Arrays.equals(patched, again.get(name)), "idempotent bytes: " + name);
                boolean equal = Arrays.equals(original, patched);
                if (!name.endsWith(".class") || !new String(original, StandardCharsets.ISO_8859_1).contains("org/lwjgl/"))
                    require(equal, "unrelated class/resource changed: " + name);
                if (equal) { untouched++; continue; }
                adapted++;
                require(flow(original).equals(flow(patched)), "control flow/instruction opcode changed: " + name);
            }
            // Refusing overwrite is part of the staging safety contract.
            try {
                PatchMCGLLwjgl3.main(new String[] {args[index], first.toString()});
                throw new AssertionError("existing output accepted");
            } catch (IOException expected) { /* no overwrite */ }
        }
        require(untouched > 0 && adapted > 0, "both unchanged and adapted entries exercised");
        System.out.println("LWJGL3_PATCH_PASS untouched=" + untouched + " adapted=" + adapted
                + " idempotence/resources/control-flow/overwrite-protection");
    }
    private static List<String> flow(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> result = new ArrayList<String>();
        for (MethodNode method : node.methods) {
            result.add("METHOD " + method.name);
            Map<LabelNode, Integer> labels = new IdentityHashMap<LabelNode, Integer>();
            int location = 0;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LabelNode) labels.put((LabelNode) insn, location);
                if (insn.getOpcode() >= 0) location++;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() < 0) continue;
                String detail = "";
                if (insn instanceof JumpInsnNode) detail = "target=" + labels.get(((JumpInsnNode) insn).label);
                if (insn instanceof VarInsnNode) detail = "local=" + ((VarInsnNode) insn).var;
                if (insn instanceof IntInsnNode) detail = "operand=" + ((IntInsnNode) insn).operand;
                if (insn instanceof IincInsnNode) detail = "increment=" + ((IincInsnNode) insn).var + ":" + ((IincInsnNode) insn).incr;
                if (insn instanceof LdcInsnNode && !(((LdcInsnNode) insn).cst instanceof Type)) detail = "constant=" + ((LdcInsnNode) insn).cst;
                if (insn instanceof TableSwitchInsnNode) {
                    TableSwitchInsnNode table = (TableSwitchInsnNode) insn;
                    detail = table.min + ":" + table.max + ":" + labels.get(table.dflt);
                    for (LabelNode target : table.labels) detail += ":" + labels.get(target);
                }
                if (insn instanceof LookupSwitchInsnNode) {
                    LookupSwitchInsnNode table = (LookupSwitchInsnNode) insn;
                    detail = table.keys + ":" + labels.get(table.dflt);
                    for (LabelNode target : table.labels) detail += ":" + labels.get(target);
                }
                result.add(insn.getOpcode() + " " + detail);
            }
            for (TryCatchBlockNode block : method.tryCatchBlocks)
                result.add("CATCH " + labels.get(block.start) + ":" + labels.get(block.end) + ":"
                        + labels.get(block.handler) + ":" + block.type);
        }
        return result;
    }
    private static Map<String, byte[]> readJar(String path) throws IOException {
        Map<String, byte[]> entries = new TreeMap<String, byte[]>();
        try (JarFile jar = new JarFile(path)) {
            for (Enumeration<JarEntry> iterator = jar.entries(); iterator.hasMoreElements();) {
                JarEntry entry = iterator.nextElement(); if (entry.isDirectory()) continue;
                try (InputStream stream = jar.getInputStream(entry)) {
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = stream.read(buffer)) != -1) data.write(buffer, 0, count);
                    entries.put(entry.getName(), data.toByteArray());
                }
            }
        }
        return entries;
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
