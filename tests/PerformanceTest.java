import java.util.*;
import local.mcgl.perf.*;

public final class PerformanceTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        Random r = new Random(421);
        List<Integer> expected = new ArrayList<Integer>(), actual = new ParticleList<Integer>();
        for (int i = 0; i < 100000; i++) {
            int op = r.nextInt(5), index = r.nextInt(expected.size() + 1);
            if (op < 2) { expected.add(index, i); actual.add(index, i); }
            else if (!expected.isEmpty()) {
                index %= expected.size();
                if (op == 2) check(Objects.equals(expected.remove(index), actual.remove(index)));
                if (op == 3) check(Objects.equals(expected.set(index, null), actual.set(index, null)));
                if (op == 4 && i % 100 == 0) { expected.clear(); actual.clear(); }
            }
            check(expected.equals(actual));
        }
        // Same tick order, including callbacks that append effects while iterating.
        expected.clear(); actual.clear();
        for (int i = 0; i < 4000; i++) { expected.add(i); actual.add(i); }
        tick(expected); tick(actual); check(expected.equals(actual));
        Object owner = new Object(); byte[] a = new byte[16], b = new byte[16]; b[0] = 1;
        check(AnimationCache.upload(owner, a, 0, 0, 0, 2, 1));
        check(!AnimationCache.upload(owner, a, 0, 0, 0, 2, 1));
        check(AnimationCache.upload(owner, b, 0, 0, 0, 2, 1));
        check(AnimationCache.upload(owner, a, 0, 0, 0, 2, 1));
        check(AnimationCache.upload(owner, a, 0, 1, 0, 2, 1)); // overlapping write
        check(AnimationCache.upload(owner, a, 0, 0, 0, 2, 1));
        AnimationCache.invalidateAll();
        check(AnimationCache.upload(owner, a, 0, 0, 0, 2, 1));
        a[0] = 3; check(AnimationCache.upload(owner, a, 0, 0, 0, 2, 1));
        check(AnimationCache.upload(owner, a, 2, 0, 0, 2, 1)); // unknown atlas
        check(AnimationCache.upload(owner, a, 0, 0, 0, 2, 1, "terrain-A.png"));
        check(AnimationCache.upload(owner, a, 0, 0, 0, 2, 1, "terrain-B.png"));
        check(!AnimationCache.upload(owner, a, 0, 0, 0, 2, 1, "terrain-A.png"));
        compareAtlasWrites();
        for (int i = 0; i < 10; i++) { bench(false); bench(true); }
        System.out.println("PASS: 100000 differential list operations, tick callbacks, 20000 pixel-equivalent atlas writes/reloads");
        System.out.println("Particle expiry microbenchmark (not game FPS): ArrayList=" + bench(false) + " ns; gap=" + bench(true) + " ns");
    }
    static void compareAtlasWrites() {
        Random random = new Random(711);
        Object owner = new Object();
        byte[][] baseline = new byte[4][64 * 64 * 4], optimized = new byte[4][64 * 64 * 4];
        AnimationCache.invalidateAll();
        for (int frame = 0; frame < 20000; frame++) {
            int texture = random.nextInt(4), size = 1 << random.nextInt(3), repeat = 1 + random.nextInt(3);
            int x = random.nextInt(12) * 2, y = random.nextInt(12) * 2;
            byte[] pixels = new byte[size * size * 4];
            Arrays.fill(pixels, (byte)random.nextInt(3));
            if (frame % 133 == 0) {
                random.nextBytes(baseline[texture]);
                System.arraycopy(baseline[texture], 0, optimized[texture], 0, baseline[texture].length);
                AnimationCache.invalidateAll();
            }
            putTile(baseline[texture], pixels, x, y, size, repeat);
            if (AnimationCache.upload(owner, pixels, texture % 2, x, y, size, repeat, "pack-" + texture / 2))
                putTile(optimized[texture], pixels, x, y, size, repeat);
            check(Arrays.equals(baseline[texture], optimized[texture]));
        }
    }
    static void putTile(byte[] atlas, byte[] pixels, int x, int y, int size, int repeat) {
        for (int tx = 0; tx < repeat; tx++) for (int ty = 0; ty < repeat; ty++)
            for (int row = 0; row < size; row++)
                System.arraycopy(pixels, row * size * 4, atlas,
                    ((y + ty * size + row) * 64 + x + tx * size) * 4, size * 4);
    }
    static void tick(List<Integer> l) {
        for (int i = 0; i < l.size(); i++) {
            int value = l.get(i);
            if (value == 10) l.add(9999);
            if (value % 3 != 0) l.remove(i--);
        }
    }
    static long bench(boolean gap) {
        long total = 0;
        for (int repeat = 0; repeat < 100; repeat++) {
            List<Integer> l = gap ? new ParticleList<Integer>() : new ArrayList<Integer>();
            for (int i = 0; i < 4000; i++) l.add(i);
            long start = System.nanoTime();
            for (int i = 0; i < l.size(); i++) if (l.get(i) % 4 != 0) l.remove(i--);
            total += System.nanoTime() - start;
        }
        return total / 100;
    }
}
