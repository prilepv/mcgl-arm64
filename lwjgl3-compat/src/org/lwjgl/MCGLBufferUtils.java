package org.lwjgl;

import java.nio.*;

/** Old utility signatures isolated from LWJGL 3's own BufferUtils/PointerBuffer. */
public final class MCGLBufferUtils {
    private MCGLBufferUtils() {}
    public static ByteBuffer createByteBuffer(int size) { return BufferUtils.createByteBuffer(size); }
    public static ShortBuffer createShortBuffer(int size) { return BufferUtils.createShortBuffer(size); }
    public static CharBuffer createCharBuffer(int size) { return BufferUtils.createCharBuffer(size); }
    public static IntBuffer createIntBuffer(int size) { return BufferUtils.createIntBuffer(size); }
    public static LongBuffer createLongBuffer(int size) { return BufferUtils.createLongBuffer(size); }
    public static FloatBuffer createFloatBuffer(int size) { return BufferUtils.createFloatBuffer(size); }
    public static DoubleBuffer createDoubleBuffer(int size) { return BufferUtils.createDoubleBuffer(size); }
    public static MCGLPointerBuffer createPointerBuffer(int size) { return MCGLPointerBuffer.allocateDirect(size); }
    public static int getElementSizeExponent(Buffer buffer) {
        if (buffer instanceof ByteBuffer) return 0;
        if (buffer instanceof ShortBuffer || buffer instanceof CharBuffer) return 1;
        if (buffer instanceof IntBuffer || buffer instanceof FloatBuffer) return 2;
        if (buffer instanceof LongBuffer || buffer instanceof DoubleBuffer) return 3;
        throw new IllegalArgumentException("Unsupported buffer: " + buffer.getClass());
    }
    public static int getOffset(Buffer buffer) { return buffer.position() << getElementSizeExponent(buffer); }
    public static long getBufferAddress(Buffer buffer) { return org.lwjgl.system.MemoryUtil.memAddress0(buffer); }
    public static void zeroBuffer(ByteBuffer buffer) { BufferUtils.zeroBuffer(buffer); }
    public static void zeroBuffer(ShortBuffer buffer) { BufferUtils.zeroBuffer(buffer); }
    public static void zeroBuffer(CharBuffer buffer) { BufferUtils.zeroBuffer(buffer); }
    public static void zeroBuffer(IntBuffer buffer) { BufferUtils.zeroBuffer(buffer); }
    public static void zeroBuffer(LongBuffer buffer) { BufferUtils.zeroBuffer(buffer); }
    public static void zeroBuffer(FloatBuffer buffer) { BufferUtils.zeroBuffer(buffer); }
    public static void zeroBuffer(DoubleBuffer buffer) { BufferUtils.zeroBuffer(buffer); }
}
