import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Publisher-side packaging of already reviewed mapping/alias data. Never discovers aliases. */
public final class CreateCompatibilityPack {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !args[0].matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("CLIENT_SHA256 MAPPING.tsv ALIASES.tsv NEW_PAYLOAD.zip");
        byte[] mapping = Files.readAllBytes(Paths.get(args[1])), aliases = Files.readAllBytes(Paths.get(args[2]));
        int classes = 0;
        for (String row : new String(mapping, StandardCharsets.UTF_8).split("\n")) if (row.startsWith("C\t")) classes++;
        String manifest = "schema=1\nabi=" + MCGLCompatibilityPack.ABI + "\nclient=" + args[0] + "\nclasses=" + classes
                + "\nmapping-sha256=" + MCGLBytecodeFingerprint.sha256(mapping)
                + "\naliases-sha256=" + MCGLBytecodeFingerprint.sha256(aliases) + "\n";
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        files.put("manifest.txt", manifest.getBytes(StandardCharsets.UTF_8)); files.put("mapping.tsv", mapping); files.put("aliases.tsv", aliases);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(Paths.get(args[3]), StandardOpenOption.CREATE_NEW))) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                ZipEntry target = new ZipEntry(e.getKey()); target.setTime(0); zip.putNextEntry(target); zip.write(e.getValue()); zip.closeEntry();
            }
        }
        System.out.println("REVIEWED_PACK_PAYLOAD classes=" + classes);
    }
}
