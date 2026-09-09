import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Merge explicit modules for the original fixed MCGLClassLoader URL list. */
public final class MergeLwjgl3Jar {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("usage: NEW-output.jar lwjgl-core.jar other.jar...");
        Map<String, byte[]> files = new TreeMap<String, byte[]>();
        Manifest manifest;
        try (JarFile core = new JarFile(args[1])) { manifest = core.getManifest(); }
        if (manifest == null || !"3.4.3".equals(manifest.getMainAttributes().getValue("Specification-Version")))
            throw new IOException("Expected the pinned LWJGL 3.4.3 core manifest");
        manifest.getMainAttributes().putValue("MCGL-Window-Backend", System.getProperty("mcgl.window.backend", "Cocoa-legacy"));
        manifest.getMainAttributes().putValue("MCGL-Render-Backend", "core41/game");
        manifest.getMainAttributes().putValue("MCGL-Render-Profiles", "core41,compatibility21-reference");
        manifest.getMainAttributes().putValue("MCGL-Shader-Pipeline", "glsl410/materials-v1");
        manifest.getMainAttributes().putValue("MCGL-Geometry-Pipeline", "core41/vao-vbo-indexed-v1");
        manifest.getMainAttributes().putValue("MCGL-Chunk-Renderer", "core41/game-world-v1");
        manifest.getMainAttributes().putValue("MCGL-Game-Renderer", "core41/game-passes-v1");
        for (int index = 1; index < args.length; index++) {
            try (JarFile input = new JarFile(args[index])) {
                for (Enumeration<JarEntry> entries = input.entries(); entries.hasMoreElements();) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || name.equalsIgnoreCase("META-INF/MANIFEST.MF")
                            || name.equals("META-INF/INDEX.LIST") || name.equals("module-info.class")
                            || name.matches("META-INF/versions/[0-9]+/module-info.class")) continue;
                    byte[] bytes;
                    try (InputStream stream = input.getInputStream(entry)) {
                        ByteArrayOutputStream data = new ByteArrayOutputStream();
                        byte[] buffer = new byte[16384]; int count;
                        while ((count = stream.read(buffer)) != -1) data.write(buffer, 0, count);
                        bytes = data.toByteArray();
                    }
                    byte[] previous = files.put(name, bytes);
                    if (previous != null && !Arrays.equals(previous, bytes))
                        throw new IOException("Conflicting module entry " + name + " from " + args[index]);
                }
            }
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(
                Paths.get(args[0]), StandardOpenOption.CREATE_NEW))) {
            ByteArrayOutputStream data = new ByteArrayOutputStream(); manifest.write(data);
            write(output, "META-INF/MANIFEST.MF", data.toByteArray());
            for (Map.Entry<String, byte[]> file : files.entrySet()) write(output, file.getKey(), file.getValue());
        }
        System.out.println("LWJGL3_MERGE_PASS entries=" + files.size() + " multi-release metadata preserved");
    }
    private static void write(JarOutputStream output, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name); entry.setTime(0);
        output.putNextEntry(entry); output.write(bytes); output.closeEntry();
    }
}
