import java.io.*;
import java.lang.reflect.*;
import java.util.Map;
import local.mcgl.perf.ChunkVbo;

/** Exercises diagnostic state without a GL context; never draws or launches the game. */
public final class VboDiagnosticsTest {
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    public static void main(String[] args) throws Exception {
        boolean profile = Boolean.getBoolean("mcgl.graphics.profile");
        boolean enabled = Boolean.getBoolean("mcgl.chunk.vbo");
        Class<?> meshType = Class.forName("local.mcgl.perf.ChunkVbo$Mesh");
        Constructor<?> constructor = meshType.getDeclaredConstructor(int.class); constructor.setAccessible(true);
        Field recording = field(ChunkVbo.class, "recording");
        Field reason = field(meshType, "reason"), valid = field(meshType, "valid");
        Method rejected = ChunkVbo.class.getDeclaredMethod("rejected", meshType, String.class); rejected.setAccessible(true);
        Map<?, ?> reasons = (Map<?, ?>)field(ChunkVbo.class, "rejectionReasons").get(null);
        Object first = constructor.newInstance(1);
        recording.set(null, first);
        ChunkVbo.unsupported("GL11.glBegin");
        ChunkVbo.unsupported("GL11.glEnd");
        if (valid.getBoolean(first) == enabled) throw new AssertionError("Guard enable/disable behavior");
        String expected = enabled && profile ? "GL11.glBegin" : null;
        if (expected == null ? reason.get(first) != null : !expected.equals(reason.get(first)))
            throw new AssertionError("First reason not retained / disabled path recorded a reason");
        recording.set(null, null);
        ChunkVbo.unsupported("ignored outside compilation");
        if (!reasons.isEmpty()) throw new AssertionError("Guard counted a compile before its completion");
        rejected.invoke(null, first, "empty or unsupported geometry");
        rejected.invoke(null, first, "empty or unsupported geometry");
        rejected.invoke(null, constructor.newInstance(2), "VBO budget");
        String key = expected == null ? "empty or unsupported geometry" : expected;
        if (profile) {
            if (reasons.size() != 2 || !Integer.valueOf(2).equals(reasons.get(key)) ||
                    !Integer.valueOf(1).equals(reasons.get("VBO budget"))) throw new AssertionError(reasons);
        } else if (!reasons.isEmpty()) throw new AssertionError("Disabled map updated");
        Method report = ChunkVbo.class.getDeclaredMethod("report"); report.setAccessible(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(bytes, true, "UTF-8"));
            report.invoke(null);
        } finally { System.setOut(original); }
        String log = bytes.toString("UTF-8");
        if (profile) {
            if (!log.contains("fallbackCompiles=3") || !log.contains("[MCGL VBO fallback]") ||
                    !log.contains(key + "=2") || !log.contains("VBO budget=1") || !reasons.isEmpty())
                throw new AssertionError(log);
        } else if (!log.isEmpty()) throw new AssertionError("Disabled diagnostics printed a report");
        System.out.println("VBO_DIAGNOSTICS_PASS profile=" + profile + " vbo=" + enabled);
    }
}
