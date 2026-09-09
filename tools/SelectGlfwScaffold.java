import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Keep the small platform-neutral legacy API shell; no old window/context backends. */
public final class SelectGlfwScaffold {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input-window.jar NEW-output.jar");
        int retained = 0;
        try (JarFile source = new JarFile(args[0]);
             JarOutputStream target = new JarOutputStream(Files.newOutputStream(Paths.get(args[1]), StandardOpenOption.CREATE_NEW))) {
            for (Enumeration<JarEntry> entries = source.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                if (!keep(entry.getName())) continue;
                JarEntry next = new JarEntry(entry.getName()); next.setTime(0);
                target.putNextEntry(next);
                try (InputStream in = source.getInputStream(entry)) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = in.read(buffer)) != -1) target.write(buffer, 0, count);
                }
                target.closeEntry(); retained++;
            }
        }
        System.out.println("GLFW_SCAFFOLD_PASS classes=" + retained + " legacy-platform-backends=absent");
    }
    private static boolean keep(String path) {
        if (!path.endsWith(".class")) return false;
        if (path.startsWith("org/lwjgl/input/")) return true;
        return path.matches("org/lwjgl/(BufferChecks|LWJGLException|LWJGLUtil|MemoryUtil|MemoryUtilSun|MCGLPointerBuffer|PointerWrapper|PointerWrapperAbstract)(\\$[^/]*)?\\.class")
            || path.matches("org/lwjgl/opengl/(DisplayMode|PixelFormat|PixelFormatLWJGL|OpenGLException|EventQueue|GlobalLock|InputImplementation|Sync)(\\$[^/]*)?\\.class");
    }
}
