package local.mcgl.perf;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

/** Only the fingerprinted, separately allocated 16x16 lighting texture uses this
 * path. Original binding, sampler settings, color conversion and simulation run
 * unchanged. General texture writes still invalidate both caches. */
public final class LightmapCache {
    private static final boolean ENABLED = !"false".equals(System.getProperty("mcgl.lightmap.cache"));
    private static final boolean PROFILE = Boolean.getBoolean("mcgl.graphics.profile");
    static final Cache cache = new Cache();
    private static long checks, skipped, uploads, invalidations, uploadNanos, maxUploadNanos;

    private LightmapCache() {}

    static final class Cache {
        private final Map<Object, Entry> entries = new WeakHashMap<Object, Entry>();
        boolean matches(Object owner, Object context, int texture, ByteBuffer pixels) {
            Entry e = entries.get(owner);
            if (e == null || e.context != context || e.texture != texture) return false;
            int start = pixels.position();
            for (int i = 0; i < 1024; i++) if (e.pixels[i] != pixels.get(start + i)) return false;
            return true;
        }
        void remember(Object owner, Object context, int texture, ByteBuffer pixels) {
            Entry e = entries.get(owner);
            if (e == null) {
                // A renderer normally has one live owner. Bound even unusual lifetimes.
                if (entries.size() >= 8) entries.clear();
                e = new Entry(); entries.put(owner, e);
            }
            e.context = context; e.texture = texture;
            int start = pixels.position();
            for (int i = 0; i < 1024; i++) e.pixels[i] = pixels.get(start + i);
        }
        void clear() { entries.clear(); }
    }
    static final class Entry {
        Object context;
        int texture;
        final byte[] pixels = new byte[1024];
    }

    public static synchronized void invalidateAll() {
        cache.clear();
        if (PROFILE) invalidations++;
    }

    public static synchronized void upload(int target, int level, int x, int y,
            int width, int height, int format, int type, ByteBuffer pixels,
            Object owner, int texture) {
        if (PROFILE) checks++;
        boolean eligible = ENABLED && owner != null && texture > 0 &&
            target == GL11.GL_TEXTURE_2D && level == 0 && x == 0 && y == 0 &&
            width == 16 && height == 16 && format == GL11.GL_RGBA &&
            type == GL11.GL_UNSIGNED_BYTE && pixels != null && pixels.remaining() == 1024;
        // Capabilities are per context, so recreated windows cannot inherit a hit
        // from a different GL context even if numeric texture names are reused.
        Object context = eligible ? GLContext.getCapabilities() : null;
        if (eligible && cache.matches(owner, context, texture, pixels)) {
            if (PROFILE) skipped++;
            return;
        }
        if (!eligible) {
            // Diagnostic opt-out and unknown formats preserve the former behavior.
            AnimationCache.invalidateAll();
        }
        long started = PROFILE ? System.nanoTime() : 0;
        try {
            GL11.glTexSubImage2D(target, level, x, y, width, height, format, type, pixels);
        } catch (RuntimeException failure) {
            cache.clear();
            throw failure;
        }
        if (PROFILE) {
            long elapsed = System.nanoTime() - started;
            uploads++; uploadNanos += elapsed; maxUploadNanos = Math.max(maxUploadNanos, elapsed);
        }
        if (eligible) cache.remember(owner, context, texture, pixels);
    }

    static synchronized void report() {
        if (!PROFILE) return;
        System.out.println(String.format(Locale.US,
            "[MCGL Lightmap] checks=%d uploads=%d skipped=%d invalidations=%d | upload avg/max %.3f/%.3f ms | cache=%s; upload wall time, not GPU time",
            checks, uploads, skipped, invalidations,
            uploads == 0 ? 0.0 : uploadNanos / (double)uploads / 1e6, maxUploadNanos / 1e6, ENABLED));
        checks = skipped = uploads = invalidations = uploadNanos = maxUploadNanos = 0;
    }
}

