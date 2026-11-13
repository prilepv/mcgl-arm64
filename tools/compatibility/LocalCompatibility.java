import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Computes a local staging profile. No network or game classes are loaded here. */
public final class LocalCompatibility {
    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]);
        Path work = Path.of(args[1]);
        String reference = args[2];
        Path inferred = work.resolve("inferred.tsv");
        Path refined = work.resolve("refined.tsv");
        Path mapping = work.resolve("mapping.tsv");
        BuildClientMapping.main(new String[] {reference, original.toString(), inferred.toString()});
        MCGLCompatibilityPack.require(Files.size(work.resolve("inferred.tsv.conflicts")) == 0,
                "Conflicting MCGL name mappings");
        RefineMembers.main(new String[] {reference, original.toString(), inferred.toString(), refined.toString()});
        CompleteMapping.main(new String[] {refined.toString(), original.toString(), mapping.toString()});
        String sha = MCGLBytecodeFingerprint.sha256(Files.readAllBytes(original));
        Path aliases = work.resolve("aliases.tsv");
        Files.createFile(aliases);
        Path payload = work.resolve("mapping.zip");
        CreateCompatibilityPack.main(new String[] {sha, mapping.toString(), aliases.toString(), payload.toString()});

        MCGLCompatibilityPack pack = MCGLCompatibilityPack.parsePayload(Files.readAllBytes(payload), sha);
        MCGLClientCompatibility.Profile profile = new MCGLClientCompatibility.Profile(pack);
        var source = MCGLClientCompatibility.readJar(original);
        var normalized = MCGLClientCompatibility.normalize(source, profile);
        var restored = MCGLClientCompatibility.restore(source, normalized, profile);
        MCGLCompatibilityPack.require(source.keySet().equals(restored.keySet()), "JAR round-trip entry mismatch");
        for (String name : source.keySet()) {
            MCGLCompatibilityPack.require(Arrays.equals(source.get(name), restored.get(name)), "JAR round-trip mismatch: " + name);
        }
        MCGLClientCompatibility.write(work.resolve("normalized.jar"), normalized);
    }
}
