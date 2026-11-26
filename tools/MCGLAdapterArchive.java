import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bundled bootstrap. Extracts an authenticated tools archive without loading its classes. */
public final class MCGLAdapterArchive {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
        "ClassBytePatch.class", "MCGLCompatibilityPack.class", "MCGLClientCompatibility.class",
        "LocalCompatibility.class", "GuardDelta.class", "CreateCompatibilityPack.class", "ClientLinkageTest.class",
        "PatchMCGLFullscreen.class", "PatchMCGLPerformance.class", "PatchMCGLLwjgl3.class",
        "PatchMCGLRenderer.class", "PatchMCGLChunks.class", "PatchMCGLGame.class", "LWJGL3LinkageAudit.class",
        "render-commands.txt", "compatibility-guards.txt"));

    public static void main(String[] args) throws Exception {
        Path archive = Path.of(args[0]), root = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(root)) throw new IllegalArgumentException("Adapter output already exists");
        Files.createDirectory(root);
        int total = 0;
        Set<String> names = new HashSet<>();
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (!name.matches("[A-Za-z0-9_$/.-]+") || name.startsWith("/") ||
                        Arrays.asList(name.split("/", -1)).contains("..") ||
                        Arrays.asList(name.split("/", -1)).contains(".") || name.contains("//") ||
                        !names.add(name) || names.size() > 2048) throw new IllegalArgumentException("Invalid adapter entry: " + name);
                if (entry.isDirectory()) continue;
                boolean resource = name.equals("render-commands.txt") || name.equals("compatibility-guards.txt") ||
                        name.startsWith("client-profiles/") && name.endsWith(".tsv");
                if ((!name.endsWith(".class") && !resource) || name.startsWith("org/") || name.startsWith("java/")) {
                    throw new IllegalArgumentException("Unexpected adapter entry: " + name);
                }
                Path destination = root.resolve(name).normalize();
                if (!destination.startsWith(root)) throw new IllegalArgumentException("Adapter entry escaped root");
                Files.createDirectories(destination.getParent());
                try (var out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[16384];
                    for (int count; (count = zip.read(buffer)) != -1;) {
                        if (count > MAX_BYTES - total) throw new IllegalArgumentException("Expanded adapter exceeds size limit");
                        total += count;
                        out.write(buffer, 0, count);
                    }
                }
            }
        }
        if (!names.containsAll(REQUIRED)) throw new IllegalArgumentException("Incomplete adapter");
        System.out.println("ADAPTER_ARCHIVE_PASS entries=" + names.size() + " bytes=" + total);
    }
}
