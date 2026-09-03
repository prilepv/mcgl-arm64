package local.mcgl.perf;

import java.util.concurrent.atomic.AtomicLong;

/** Stable far-to-near quad ordering, bit-for-bit equivalent to the client's
 * bubble sort. Sort indices before copying vertices once, with per-thread
 * bounded scratch buffers. NaN distances are barriers in the original DCMPG
 * comparator: no quad is allowed to cross them. */
public final class QuadSort {
    private static final boolean ENABLED = !"false".equals(System.getProperty("mcgl.quad.sort"));
    private static final boolean PROFILE = Boolean.getBoolean("mcgl.graphics.profile");
    private static final int MAX_WORDS = 2097152;
    private static final ThreadLocal<Scratch> scratch = new ThreadLocal<Scratch>();
    private static final AtomicLong batches = new AtomicLong(), quads = new AtomicLong(), ordered = new AtomicLong();

    public static boolean sort(int[] raw, int words, double x, double y, double z) {
        if (!ENABLED || raw == null || words < 0 || words > raw.length || words > MAX_WORDS || (words & 31) != 0) return false;
        int count = words / 32;
        if (PROFILE) { batches.incrementAndGet(); quads.addAndGet(count); }
        if (count < 2) return true;
        Scratch work = scratch.get();
        if (work == null) { work = new Scratch(); scratch.set(work); }
        work.ensure(count);
        double[] distance = work.distance;
        boolean alreadyOrdered = true;
        for (int i = 0; i < count; i++) {
            int p = i * 32;
            // Deliberately retain float addition/division before conversion to
            // double. A 'more accurate' formula could reorder close distances.
            double dx = x - (Float.intBitsToFloat(raw[p]) + Float.intBitsToFloat(raw[p + 16])) / 2.0f;
            double dy = y - (Float.intBitsToFloat(raw[p + 1]) + Float.intBitsToFloat(raw[p + 17])) / 2.0f;
            double dz = z - (Float.intBitsToFloat(raw[p + 2]) + Float.intBitsToFloat(raw[p + 18])) / 2.0f;
            distance[i] = dx * dx + dy * dy + dz * dz;
            work.order[i] = i;
            if (i > 0 && distance[i - 1] < distance[i]) alreadyOrdered = false;
        }
        if (alreadyOrdered) { if (PROFILE) ordered.incrementAndGet(); return true; }
        for (int begin = 0; begin < count;) {
            if (Double.isNaN(distance[begin])) { begin++; continue; }
            int end = begin + 1;
            while (end < count && !Double.isNaN(distance[end])) end++;
            mergeSort(work, begin, end);
            begin = end;
        }
        if (work.vertices.length < words) work.vertices = new int[work.order.length * 32];
        for (int i = 0; i < count; i++) System.arraycopy(raw, work.order[i] * 32, work.vertices, i * 32, 32);
        System.arraycopy(work.vertices, 0, raw, 0, words);
        return true;
    }
    private static void mergeSort(Scratch w, int begin, int end) {
        for (int width = 1; width < end - begin; width *= 2) {
            for (int left = begin; left < end - width; left += width * 2) {
                int middle = left + width, right = Math.min(left + width * 2, end);
                // Already ordered adjacent runs need neither a merge nor a copy.
                if (w.distance[w.order[middle - 1]] >= w.distance[w.order[middle]]) continue;
                int a = left, b = middle;
                for (int target = left; target < right; target++) {
                    if (b == right || (a < middle && w.distance[w.order[a]] >= w.distance[w.order[b]])) w.temp[target] = w.order[a++];
                    else w.temp[target] = w.order[b++];
                }
                System.arraycopy(w.temp, left, w.order, left, right - left);
            }
        }
    }
    public static void report() {
        if (!PROFILE) return;
        long n = batches.getAndSet(0), q = quads.getAndSet(0), ready = ordered.getAndSet(0);
        if (n != 0) System.out.println("[MCGL QuadSort] batches=" + n + " quads=" + q + " alreadyOrdered=" + ready);
    }
    private static final class Scratch {
        Scratch() { }
        double[] distance = new double[0];
        int[] order = new int[0], temp = new int[0], vertices = new int[0];
        void ensure(int count) {
            if (order.length >= count) return;
            int capacity = 16;
            while (capacity < count) capacity *= 2;
            distance = new double[capacity]; order = new int[capacity]; temp = new int[capacity];
        }
    }
}
