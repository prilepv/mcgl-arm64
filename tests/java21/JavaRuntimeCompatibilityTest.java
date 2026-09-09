import java.applet.Applet;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.lwjgl.MemoryUtil;
import org.lwjgl.Sys;
import org.lwjgl.util.WaveData;

/** Focused Java 8/21 compatibility check; no game, network or user data. */
public final class JavaRuntimeCompatibilityTest {
    public static void main(String[] args) throws Exception {
        require(Charset.defaultCharset().equals(StandardCharsets.UTF_8), "default charset");
        require(Toolkit.getDefaultToolkit().getClass().getName().endsWith("HeadlessToolkit"), "headless toolkit");
        try {
            headless(false);
            new Applet();
        } finally {
            headless(true);
        }
        require(GraphicsEnvironment.isHeadless(), "headless restoration");
        ByteBuffer direct = ByteBuffer.allocateDirect(16);
        require(MemoryUtil.getAddress(direct) != 0, "LWJGL native buffer address");
        require(Sys.getVersion().startsWith("2."), "LWJGL 2 retained");

        Path directory = Files.createTempDirectory("mcgl-java-compat-");
        Path wave = directory.resolve("звук.wav");
        try {
            AudioFormat format = new AudioFormat(8000, 8, 1, false, false);
            try (AudioInputStream input = new AudioInputStream(
                    new ByteArrayInputStream(new byte[80]), format, 80)) {
                AudioSystem.write(input, AudioFileFormat.Type.WAVE, wave.toFile());
            }
            WaveData decoded = WaveData.create(wave.toUri().toURL());
            require(decoded != null && decoded.samplerate == 8000 && decoded.data.remaining() == 80,
                    "legacy LWJGL URL WAV decoding and Unicode path");
            decoded.dispose();
        } finally {
            Files.deleteIfExists(wave);
            Files.deleteIfExists(directory);
        }
        System.out.println("JAVA_RUNTIME_COMPATIBILITY_PASS java=" + System.getProperty("java.version")
                + " arch=" + System.getProperty("os.arch") + " charset=" + Charset.defaultCharset()
                + " lwjgl=" + Sys.getVersion());
        for (java.lang.management.GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans())
            System.out.println("GC=" + gc.getName());
    }

    private static void headless(boolean value) throws Exception {
        System.setProperty("java.awt.headless", Boolean.toString(value));
        for (String name : new String[] {"headless", "defaultHeadless"}) {
            Field field = GraphicsEnvironment.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, Boolean.valueOf(value));
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
