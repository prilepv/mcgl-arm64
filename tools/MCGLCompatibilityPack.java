import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.*;

/** Signed data only: no extraction, class loading, executable updates or URLs in packs. */
public final class MCGLCompatibilityPack {
    static final String ABI = "core-17";
    static final String PUBLISHER_KEY = "6bd7980e19d22ca3a68b0af6810fe06df4f6cd4d2f0e2d33291db276860e3c6e";
    static final int LIMIT = 8 * 1024 * 1024;
    final String client;
    final int classes;
    final byte[] mapping;
    final Map<String, Long> aliases;
    final Set<String> sorters;

    private MCGLCompatibilityPack(String client, int classes, byte[] mapping,
            Map<String, Long> aliases, Set<String> sorters) {
        this.client = client; this.classes = classes; this.mapping = mapping;
        this.aliases = Collections.unmodifiableMap(aliases);
        this.sorters = Collections.unmodifiableSet(sorters);
    }

    private static final class Selected {
        static final MCGLCompatibilityPack VALUE = selected();
    }
    static MCGLCompatibilityPack active() { return Selected.VALUE; }
    private static MCGLCompatibilityPack selected() {
        String pack = System.getProperty("mcgl.compat.pack", "");
        String local = System.getProperty("mcgl.compat.local", "");
        if (!local.isEmpty() && pack.isEmpty()) return selectLocal(local);
        if (pack.isEmpty()) return null;
        try {
            String original = System.getProperty("mcgl.compat.official", "");
            require(!original.isEmpty(), "Missing official input for signed pack");
            return load(Paths.get(pack), MCGLBytecodeFingerprint.sha256(Files.readAllBytes(Paths.get(original))));
        } catch (Exception failure) { throw new IllegalStateException("MCGL_COMPAT_PACK_REJECTED: " + failure.getMessage(), failure); }
    }

    private static MCGLCompatibilityPack selectLocal(String path) {
        // The installer only passes files generated in its private staging directory.
        try (InputStream input = Files.newInputStream(Paths.get(path))) {
            Path original = Paths.get(System.getProperty("mcgl.compat.official"));
            String sha = MCGLBytecodeFingerprint.sha256(Files.readAllBytes(original));
            return parsePayload(read(input, LIMIT), sha);
        } catch (Exception failure) {
            throw new IllegalStateException("Local compatibility profile rejected", failure);
        }
    }

    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384];
        for (int n; (n = input.read(buffer)) != -1;) {
            require(n <= limit - out.size(), "Pack exceeds size limit"); out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    static byte[] hex(String value) throws IOException {
        require(value.matches("[0-9a-f]+") && value.length() % 2 == 0, "Invalid hex");
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    static MCGLCompatibilityPack load(Path path, String expectedClient) throws Exception {
        require(expectedClient.matches("[0-9a-f]{64}"), "Invalid client SHA-256");
        require(Files.isRegularFile(path) && Files.size(path) <= LIMIT && Files.size(path) > 72, "Invalid pack size/type");
        byte[] envelope;
        try (InputStream input = Files.newInputStream(path)) { envelope = read(input, LIMIT); }
        require(Arrays.equals(Arrays.copyOf(envelope, 8), "MCGLPK1\n".getBytes(StandardCharsets.US_ASCII)), "Invalid pack envelope");
        byte[] payload = Arrays.copyOfRange(envelope, 72, envelope.length);
        byte[] raw = hex(PUBLISHER_KEY), prefix = hex("302a300506032b6570032100");
        byte[] encoded = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, encoded, 0, prefix.length); System.arraycopy(raw, 0, encoded, prefix.length, raw.length);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded)));
        verifier.update(payload);
        require(verifier.verify(Arrays.copyOfRange(envelope, 8, 72)), "Invalid publisher signature");
        return parsePayload(payload, expectedClient);
    }
    /** Called only after publisher authentication by load; exposed to parser contract tests. */
    static MCGLCompatibilityPack parsePayload(byte[] payload, String expectedClient) throws Exception {
        Map<String, byte[]> entries = new HashMap<String, byte[]>(); int total = 0;
        Set<String> allowed = new HashSet<String>(Arrays.asList("manifest.txt", "mapping.tsv", "aliases.tsv"));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                require(!entry.isDirectory() && allowed.contains(name) && !entries.containsKey(name), "Unexpected or duplicate pack entry");
                int cap = name.equals("manifest.txt") ? 4096 : name.equals("aliases.tsv") ? 65536 : LIMIT;
                byte[] data = read(zip, Math.min(cap, LIMIT - total)); total += data.length; entries.put(name, data);
            }
        }
        require(entries.keySet().equals(allowed), "Incomplete pack");
        Map<String, String> manifest = new HashMap<String, String>();
        for (String line : new String(entries.get("manifest.txt"), StandardCharsets.UTF_8).split("\n")) {
            int equal = line.indexOf('='); require(equal > 0, "Invalid manifest row");
            require(manifest.put(line.substring(0, equal), line.substring(equal + 1)) == null, "Duplicate manifest key");
        }
        require(manifest.keySet().equals(new HashSet<String>(Arrays.asList("schema", "abi", "client", "classes", "mapping-sha256", "aliases-sha256"))), "Invalid manifest fields");
        require("1".equals(manifest.get("schema")) && ABI.equals(manifest.get("abi")), "Unsupported pack schema/ABI");
        require(expectedClient.equals(manifest.get("client")), "Pack is for another official client");
        require(MCGLBytecodeFingerprint.sha256(entries.get("mapping.tsv")).equals(manifest.get("mapping-sha256")), "Changed mapping");
        require(MCGLBytecodeFingerprint.sha256(entries.get("aliases.tsv")).equals(manifest.get("aliases-sha256")), "Changed method aliases");
        int classes = Integer.parseInt(manifest.get("classes"));
        require(classes > 0 && classes <= 10000, "Invalid class count");
        Map<String, Long> aliases = new HashMap<String, Long>(); Set<String> sorters = new HashSet<String>();
        String text = new String(entries.get("aliases.tsv"), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            require(parts.length == 2 && parts[0].matches("[0-9a-f]{64}"), "Invalid method alias");
            require(!aliases.containsKey(parts[0]) && !sorters.contains(parts[0]), "Duplicate method alias");
            if (parts[1].equals("quad-sort")) sorters.add(parts[0]);
            else {
                require(parts[1].matches("[0-9]{1,10}"), "Invalid method guard");
                long target = Long.parseLong(parts[1]); require(target <= 0xffffffffL, "Invalid guard range"); aliases.put(parts[0], target);
            }
            require(aliases.size() + sorters.size() <= 256, "Too many method aliases");
        }
        return new MCGLCompatibilityPack(expectedClient, classes, entries.get("mapping.tsv"), aliases, sorters);
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 3 && args[0].equals("verify"), "verify PACK CLIENT_SHA256");
        MCGLCompatibilityPack pack = load(Paths.get(args[1]), args[2]);
        System.out.println("SIGNED_COMPAT_PACK_PASS client=" + pack.client + " classes=" + pack.classes);
    }
    static void require(boolean value, String message) throws IOException { if (!value) throw new IOException(message); }
    private MCGLCompatibilityPack() { throw new AssertionError(); }
}
