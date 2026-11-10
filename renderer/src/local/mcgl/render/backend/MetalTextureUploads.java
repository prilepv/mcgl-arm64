package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/** Keeps a bounded copy of supported texture uploads for the Metal renderer. */
final class MetalTextureUploads {
    private static final long BUDGET = 32L << 20;
    // Texture format tokens from the Java client's legacy command interface.
    private static final int RGB = 0x1907, RGBA = 0x1908, RGB8 = 0x8051, RGBA8 = 0x8058, BGRA = 0x80e1;
    private static final int UNSIGNED_BYTE = 0x1401, UNSIGNED_INT_8_8_8_8_REV = 0x8367;
    private final IntSupplier unpackAlignment;
    private final Map<Long, Level> levels = new LinkedHashMap<Long, Level>(16, .75f, true);
    private long bytes;

    /** Returns the row alignment for tightly packed rows, or zero for unsupported unpack state. */
    MetalTextureUploads(IntSupplier unpackAlignment) {
        this.unpackAlignment = unpackAlignment;
    }

    private static final class Level {
        final int width, height;
        final boolean alpha;
        ByteBuffer rgba;
        boolean shared;

        Level(int width, int height, boolean alpha, ByteBuffer rgba) {
            this.width = width;
            this.height = height;
            this.alpha = alpha;
            this.rgba = rgba;
        }
    }

    void upload(int texture, int level, int internal, int width, int height, int border,
                int format, int type, ByteBuffer input) {
        invalidateLevel(texture, level);
        boolean alpha = internal == RGBA8 || internal == RGBA || internal == 4;
        boolean rgb = internal == RGB8 || internal == RGB || internal == 3;
        if (texture <= 0 || level < 0 || level > 12 || border != 0 || !(alpha || rgb)
                || width < 1 || height < 1 || width > 4096 || height > 4096) return;

        long size = (long)width * height * 4;
        if (size > BUDGET) return;
        int stride = sourceStride(width, height, format, type, input);
        if (stride == 0) return;

        Iterator<Level> oldest = levels.values().iterator();
        while (bytes + size > BUDGET) {
            bytes -= oldest.next().rgba.remaining();
            oldest.remove();
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect((int)size);
        writePixels(pixels, 0, width * 4, width, height, alpha, format, input, stride);
        levels.put(key(texture, level), new Level(width, height, alpha, pixels));
        bytes += size;
    }

    void update(int texture, int level, int x, int y, int width, int height,
                int format, int type, ByteBuffer input) {
        Level cached = levels.get(key(texture, level));
        if (cached == null || width == 0 || height == 0) return;
        if (x < 0 || y < 0 || width < 0 || height < 0 || x > cached.width - width || y > cached.height - height) {
            invalidateLevel(texture, level);
            return;
        }
        int stride = sourceStride(width, height, format, type, input);
        if (stride == 0) {
            invalidateLevel(texture, level);
            return;
        }
        if (cached.shared) {
            // Queued and presented frames can still refer to the previous pixels.
            ByteBuffer replacement = ByteBuffer.allocateDirect(cached.rgba.capacity());
            if (width != cached.width || height != cached.height) {
                replacement.put(cached.rgba.duplicate());
                replacement.clear();
            }
            cached.rgba = replacement;
            cached.shared = false;
        }
        writePixels(cached.rgba, (y * cached.width + x) * 4, cached.width * 4,
                width, height, cached.alpha, format, input, stride);
    }

    private int sourceStride(int width, int height, int format, int type, ByteBuffer input) {
        boolean bgra = format == BGRA;
        if (input == null || !(bgra || format == RGB || format == RGBA)
                || (type != UNSIGNED_BYTE && !(bgra && type == UNSIGNED_INT_8_8_8_8_REV))) return 0;
        int alignment = unpackAlignment.getAsInt();
        if (alignment != 1 && alignment != 2 && alignment != 4 && alignment != 8) return 0;
        int rowBytes = width * (format == RGB ? 3 : 4);
        int stride = (rowBytes + alignment - 1) & -alignment;
        return (long)(height - 1) * stride + rowBytes <= input.remaining() ? stride : 0;
    }

    private static void writePixels(ByteBuffer output, int offset, int pitch, int width, int height, boolean alpha,
                                    int format, ByteBuffer input, int stride) {
        if (format == RGBA && alpha) {
            int rowBytes = width * 4;
            ByteBuffer source = input.duplicate();
            ByteBuffer target = output.duplicate();
            if (stride == pitch && pitch == rowBytes) {
                source.limit(source.position() + height * rowBytes);
                target.position(offset);
                target.put(source);
            } else {
                for (int y = 0; y < height; y++) {
                    int row = input.position() + y * stride;
                    source.limit(row + rowBytes).position(row);
                    target.position(offset + y * pitch);
                    target.put(source);
                }
            }
            return;
        }
        boolean bgra = format == BGRA;
        int components = format == RGB ? 3 : 4;
        for (int y = 0; y < height; y++) {
            int row = input.position() + y * stride;
            int dest = offset + y * pitch;
            for (int x = 0; x < width; x++) {
                int at = row + x * components;
                output.put(dest++, input.get(at + (bgra ? 2 : 0)));
                output.put(dest++, input.get(at + 1));
                output.put(dest++, input.get(at + (bgra ? 0 : 2)));
                output.put(dest++, alpha && components == 4 ? input.get(at + 3) : (byte)255);
            }
        }
    }

    ByteBuffer pixels(int texture, int level, int width, int height) {
        Level cached = levels.get(key(texture, level));
        if (cached == null || cached.width != width || cached.height != height) return null;
        cached.shared = true;
        return cached.rgba.asReadOnlyBuffer();
    }

    void invalidateLevel(int texture, int level) {
        Level old = levels.remove(key(texture, level));
        if (old != null) bytes -= old.rgba.remaining();
    }

    void invalidate(int texture) {
        for (int level = 0; level <= 12; level++) invalidateLevel(texture, level);
    }

    void clear() {
        levels.clear();
        bytes = 0;
    }

    private static long key(int texture, int level) { return ((long)texture << 32) | (level & 0xffffffffL); }
}
