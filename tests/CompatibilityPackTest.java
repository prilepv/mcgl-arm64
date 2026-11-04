import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Real pinned-publisher fixture plus independently malformed, authenticated-payload parser cases. */
public final class CompatibilityPackTest {
    interface Attempt { void run() throws Exception; }
    static int checks;
    static void rejects(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("Invalid pack accepted");
    }
    static byte[] zip(Map<String,byte[]> files) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream out=new ZipOutputStream(bytes)) {
            for(Map.Entry<String,byte[]> file:files.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));out.write(file.getValue());out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        Path input=Paths.get(args[0]);String client=args[1];
        MCGLCompatibilityPack.load(input,client);checks++;
        rejects(()->MCGLCompatibilityPack.load(input,client.equals(new String(new char[64]).replace('\0','0'))?client.replace('0','1'):new String(new char[64]).replace('\0','0')));
        byte[] envelope=Files.readAllBytes(input);
        Path temp=Files.createTempFile("mcgl-pack-negative-",".pack");
        try {
            byte[] tampered=envelope.clone();tampered[72]^=1;Files.write(temp,tampered);
            rejects(()->MCGLCompatibilityPack.load(temp,client));
            tampered=envelope.clone();tampered[8]^=1;Files.write(temp,tampered);
            rejects(()->MCGLCompatibilityPack.load(temp,client));
            Files.write(temp,Arrays.copyOf(envelope,72));rejects(()->MCGLCompatibilityPack.load(temp,client));
            Files.write(temp,new byte[MCGLCompatibilityPack.LIMIT+1]);rejects(()->MCGLCompatibilityPack.load(temp,client));
        } finally { Files.delete(temp); }
        Map<String,byte[]> files=new LinkedHashMap<String,byte[]>();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(Arrays.copyOfRange(envelope,72,envelope.length)))) {
            for(ZipEntry entry;(entry=in.getNextEntry())!=null;)files.put(entry.getName(),MCGLCompatibilityPack.read(in,MCGLCompatibilityPack.LIMIT));
        }
        MCGLCompatibilityPack.parsePayload(zip(files),client);checks++;
        files.put("../payload.class",new byte[]{0});rejects(()->MCGLCompatibilityPack.parsePayload(zip(files),client));files.remove("../payload.class");
        byte[] manifest=files.get("manifest.txt");
        files.put("manifest.txt",new String(manifest,StandardCharsets.UTF_8).replace("abi="+MCGLCompatibilityPack.ABI,"abi=unknown").getBytes(StandardCharsets.UTF_8));
        rejects(()->MCGLCompatibilityPack.parsePayload(zip(files),client));files.put("manifest.txt",manifest);
        byte[] mapping=files.get("mapping.tsv");files.put("mapping.tsv",new byte[]{42});
        rejects(()->MCGLCompatibilityPack.parsePayload(zip(files),client));files.put("mapping.tsv",mapping);
        byte[] aliases=files.get("aliases.tsv");files.remove("aliases.tsv");
        rejects(()->MCGLCompatibilityPack.parsePayload(zip(files),client));files.put("aliases.tsv",aliases);
        files.put("mapping.tsv",new byte[MCGLCompatibilityPack.LIMIT+1]);
        rejects(()->MCGLCompatibilityPack.parsePayload(zip(files),client));
        System.out.println("COMPATIBILITY_PACK_TEST_PASS checks="+checks);
    }
}
