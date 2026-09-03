import java.io.*;
import java.lang.reflect.*;
import local.mcgl.perf.RenderDiagnostics;
import local.mcgl.perf.QuadSort;

public final class RenderDiagnosticsTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = Boolean.getBoolean("mcgl.graphics.profile");
        long start = RenderDiagnostics.begin();
        if (!enabled && start != 0) throw new AssertionError("Disabled clock path");
        Field window = RenderDiagnostics.class.getDeclaredField("windowStart"); window.setAccessible(true);
        if (enabled) window.setLong(null, System.nanoTime() - 6000000000L);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(bytes, true, "UTF-8"));
            RenderDiagnostics.skippedAnimation(64, 3);
            QuadSort.sort(new int[64], 64, 0, 0, 0);
            RenderDiagnostics.end(0, start);
            RenderDiagnostics.end(2, start);
            RenderDiagnostics.end(3, start);
            RenderDiagnostics.end(1, start);
        } finally { System.setOut(original); }
        String log = bytes.toString("UTF-8");
        if (enabled) {
            if (!log.contains("[MCGL Phases]") || !log.contains("avoided buffer copies 1") ||
                !log.contains("texture uploads 9") || !log.contains("[MCGL Chunks] builds=1") || !log.contains("[MCGL QuadSort] batches=1 quads=2 alreadyOrdered=1") || log.contains("NaN") || log.contains("Infinity"))
                throw new AssertionError(log);
        } else if (!log.isEmpty()) throw new AssertionError("Disabled diagnostics printed a log");
        Field counts = RenderDiagnostics.class.getDeclaredField("count"); counts.setAccessible(true);
        for (long n : (long[])counts.get(null)) if (n != 0) throw new AssertionError("Counters not reset/disabled");
        System.out.println("RENDER_DIAGNOSTICS_PASS enabled=" + enabled);
    }
}
