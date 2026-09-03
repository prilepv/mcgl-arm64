package local.mcgl.perf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Rendering cache with synchronized invalidation for resource reloads.
 * Simulation always advances; only byte-identical uploads are skipped.
 * Overlapping writes and texture reloads invalidate data. */
public final class AnimationCache {
    private static final Map<Object, List<Entry>> caches = new WeakHashMap<Object, List<Entry>>();
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;

    private static final class Entry {
        int sheet, x, y, size, repeats;
        String atlas;
        byte[] bytes;
        Entry(int sheet, int x, int y, int size, int repeats, byte[] bytes, String atlas) {
            this.atlas = atlas;
            this.sheet = sheet; this.x = x; this.y = y;
            this.size = size; this.repeats = repeats; this.bytes = bytes.clone();
        }
        boolean overlaps(int s, int px, int py, int extent, String path) {
            int edge = size * repeats;
            return sheet == s && Objects.equals(atlas, path) && x < px + extent && px < x + edge &&
                    y < py + extent && py < y + edge;
        }
    }

    public static synchronized void invalidate(Object owner) { caches.remove(owner); }
    public static void invalidateAll() {
        synchronized (AnimationCache.class) { caches.clear(); }
        // Do not hold the animation lock while acquiring the lighting lock.
        LightmapCache.invalidateAll();
    }

    public static synchronized boolean upload(Object owner, byte[] data, int sheet,
                                 int x, int y, int size, int repeats) {
        return upload(owner, data, sheet, x, y, size, repeats, "default");
    }

    public static synchronized boolean upload(Object owner, byte[] data, int sheet,
                                 int x, int y, int size, int repeats, String atlas) {
        // Unknown extension formats fail open, without changing rendering.
        if (data == null || atlas == null || sheet < 0 || sheet > 1 || size <= 0 || size > 1024 ||
                repeats <= 0 || repeats > 64 || data.length != size * size * 4) {
            invalidate(owner);
            return true;
        }
        List<Entry> entries = caches.get(owner);
        if (entries == null) {
            entries = new ArrayList<Entry>();
            caches.put(owner, entries);
        }
        int bytes = data.length;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.sheet == sheet && e.x == x && e.y == y && e.size == size &&
                    e.repeats == repeats && Objects.equals(e.atlas, atlas)) {
                if (Arrays.equals(e.bytes, data)) {
                    RenderDiagnostics.skippedAnimation(data.length, repeats);
                    return false;
                }
                // Entries for one atlas never overlap: insertion evicts all
                // intersecting rectangles. An exact-size update therefore
                // needs neither another overlap scan nor removal/reinsertion.
                System.arraycopy(data, 0, e.bytes, 0, data.length);
                return true;
            }
        }
        for (int i = 0; i < entries.size();) {
            Entry e = entries.get(i);
            if (e.overlaps(sheet, x, y, size * repeats, atlas)) {
                entries.remove(i);
                continue;
            }
            else bytes += e.bytes.length;
            i++;
        }
        if (bytes > MAX_BYTES || entries.size() >= MAX_ENTRIES) entries.clear();
        entries.add(new Entry(sheet, x, y, size, repeats, data, atlas));
        return true;
    }
}
