import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** No game classes are loaded. Both JARs must be explicit, local official inputs. */
public final class ClientCompatibilityTest {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        require(args.length == 2, "original.jar updated.jar");
        Path original = Paths.get(args[0]), updated = Paths.get(args[1]);
        require(!MCGLClientCompatibility.updated(original), "Original profile");
        require(MCGLClientCompatibility.updated(updated), "Updated profile");
        Map<String, byte[]> input = MCGLClientCompatibility.readJar(updated);
        Map<String, byte[]> normalized = MCGLClientCompatibility.normalize(input);
        Map<String, byte[]> restored = MCGLClientCompatibility.restore(input, normalized);
        require(input.keySet().equals(restored.keySet()), "Roundtrip entries");
        for (String path : input.keySet()) require(Arrays.equals(input.get(path), restored.get(path)), "Changed original bytes: " + path);
        int aliases = 0, sorters = 0;
        for (Map.Entry<String, byte[]> entry : normalized.entrySet()) if (entry.getKey().endsWith(".class")) {
            ClassNode type = new ClassNode(); new ClassReader(entry.getValue()).accept(type, 0);
            for (MethodNode method : type.methods) {
                long accepted = MCGLBytecodeFingerprint.of(method);
                CRC32 crc = new CRC32(); crc.update(MCGLBytecodeFingerprint.bytes(method));
                boolean sorter = MCGLBytecodeFingerprint.updatedQuadSort(method);
                if (accepted != crc.getValue() || sorter) {
                    method.instructions.insert(new InsnNode(Opcodes.NOP));
                    require(MCGLBytecodeFingerprint.of(method) != accepted, "Changed method passed a reviewed fingerprint");
                    require(!MCGLBytecodeFingerprint.updatedQuadSort(method), "Changed sorter accepted");
                    if (sorter) sorters++; else aliases++;
                }
            }
        }
        require(aliases == 4 && sorters == 1, "Expected exact reviewed raw-method guards");
        Path directory = Files.createTempDirectory("mcgl-compat-test-");
        try {
            Path unknown = directory.resolve("unknown.jar"), output = directory.resolve("result.jar");
            byte[] modified = Files.readAllBytes(updated); modified[modified.length - 1] ^= 1;
            Files.write(unknown, modified);
            try { MCGLClientCompatibility.main(new String[]{"normalize", unknown.toString(), output.toString()}); throw new AssertionError("Unknown input accepted"); }
            catch (IOException expected) { require(expected.getMessage().contains("MCGL_UNSUPPORTED_CLIENT:"), "Clear unsupported-client diagnostic"); }
            require(!Files.exists(output), "Failure wrote an output");
            Files.write(output, new byte[]{42});
            try { MCGLClientCompatibility.main(new String[]{"normalize", original.toString(), output.toString()}); throw new AssertionError("Existing output overwritten"); }
            catch (FileAlreadyExistsException expected) { }
            require(Arrays.equals(Files.readAllBytes(output), new byte[]{42}), "Output sentinel changed");
            Files.delete(unknown); Files.delete(output);
        } finally { Files.delete(directory); }
        System.out.println("CLIENT_COMPATIBILITY_TEST_PASS all official entries byte-exact, duplicate guards, unknown input/output protection, changed-method rejection");
    }
}
