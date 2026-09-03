package local.mcgl.perf;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.RandomAccess;

/** Ordered gap buffer: sequential particle expiry moves each survivor at most
 * once, instead of shifting the entire ArrayList suffix for every removal.
 * All list operations remain immediately visible to callbacks during a tick. */
public final class ParticleList<E> extends AbstractList<E> implements RandomAccess {
    private Object[] elements = new Object[16];
    private int gapStart;
    private int gapEnd = 16;

    public int size() { return elements.length - (gapEnd - gapStart); }

    private void check(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
    }

    @SuppressWarnings("unchecked")
    public E get(int index) {
        check(index);
        return (E)elements[index < gapStart ? index : index + gapEnd - gapStart];
    }

    @SuppressWarnings("unchecked")
    public E set(int index, E value) {
        check(index);
        int physical = index < gapStart ? index : index + gapEnd - gapStart;
        E old = (E)elements[physical];
        elements[physical] = value;
        return old;
    }

    private void moveGap(int index) {
        int width = gapEnd - gapStart;
        if (index < gapStart) {
            System.arraycopy(elements, index, elements, index + width, gapStart - index);
            Arrays.fill(elements, index, Math.min(gapStart, index + width), null);
        } else if (index > gapStart) {
            System.arraycopy(elements, gapEnd, elements, gapStart, index - gapStart);
            Arrays.fill(elements, Math.max(gapEnd, index), index + width, null);
        }
        gapStart = index;
        gapEnd = index + width;
    }

    public void add(int index, E value) {
        if (index < 0 || index > size()) throw new IndexOutOfBoundsException();
        moveGap(index);
        if (gapStart == gapEnd) {
            Object[] grown = new Object[elements.length * 2];
            System.arraycopy(elements, 0, grown, 0, gapStart);
            int tail = elements.length - gapEnd;
            System.arraycopy(elements, gapEnd, grown, grown.length - tail, tail);
            gapEnd = grown.length - tail;
            elements = grown;
        }
        elements[gapStart++] = value;
        modCount++;
    }

    @SuppressWarnings("unchecked")
    public E remove(int index) {
        check(index);
        moveGap(index);
        E old = (E)elements[gapEnd];
        elements[gapEnd++] = null;
        modCount++;
        return old;
    }

    public void clear() {
        Arrays.fill(elements, null);
        gapStart = 0;
        gapEnd = elements.length;
        modCount++;
    }
}
