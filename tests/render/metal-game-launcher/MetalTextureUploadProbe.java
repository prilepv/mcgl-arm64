package local.mcgl.render.backend;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;

/** Compares the bytes forwarded to Metal against the existing OpenGL readback. */
public final class MetalTextureUploadProbe {
    private MetalTextureUploadProbe() {}

    public static void verify() {
        int name = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, name);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        MetalTextureUploads uploads = new MetalTextureUploads(TerrainGLReadback::uploadAlignment);
        try {
            check(name, uploads, GL11C.GL_RGBA8, GL12C.GL_BGRA, GL12C.GL_UNSIGNED_INT_8_8_8_8_REV,
                    ByteBuffer.allocateDirect(8).put(new byte[]{17, 39, 91, 112, 30, 61, 72, 93}).flip());
            check(name, uploads, GL11C.GL_RGB8, GL11C.GL_RGB, GL11C.GL_UNSIGNED_BYTE,
                    ByteBuffer.allocateDirect(8).put(new byte[]{11, 29, 73, 0, 31, 81, 92, 0}).flip());
            checkPartialUpdate(name, uploads);
            uploads.invalidateLevel(name, 0);
            if (uploads.pixels(name, 0, 1, 2) != null) throw new AssertionError("Stale upload survived invalidation");
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL11C.glDeleteTextures(name);
        }
    }

    private static void check(int name, MetalTextureUploads uploads, int internal, int format, int type, ByteBuffer pixels) {
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internal, 1, 2, 0, format, type, pixels);
        uploads.upload(name, 0, internal, 1, 2, 0, format, type, pixels);
        TerrainGLReadback.Texture direct = TerrainGLReadback.texture(name, 32, uploads);
        TerrainGLReadback.Texture readback = TerrainGLReadback.texture(name, 32);
        if (direct == null || readback == null || direct.uploadedLevels != 1
                || !direct.levels[0].equals(readback.levels[0]))
            throw new AssertionError("Texture upload differs from GPU readback");
    }

    private static void checkPartialUpdate(int name, MetalTextureUploads uploads) {
        ByteBuffer original = ByteBuffer.allocateDirect(24);
        for (int i = 0; i < original.capacity(); i++) original.put((byte)(i + 1));
        original.flip();
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 3, 2, 0,
                GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, original);
        uploads.upload(name, 0, GL11C.GL_RGBA8, 3, 2, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, original);
        ByteBuffer previous = uploads.pixels(name, 0, 3, 2);
        ByteBuffer patch = ByteBuffer.allocateDirect(8).put(new byte[]{9, 87, 48, 0, 90, 72, 11, 0}).flip();
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 1, 0, 1, 2,
                GL11C.GL_RGB, GL11C.GL_UNSIGNED_BYTE, patch);
        uploads.update(name, 0, 1, 0, 1, 2, GL11C.GL_RGB, GL11C.GL_UNSIGNED_BYTE, patch);
        TerrainGLReadback.Texture current = TerrainGLReadback.texture(name, 32, uploads);
        TerrainGLReadback.Texture readback = TerrainGLReadback.texture(name, 32);
        if (current == null || readback == null || current.uploadedLevels != 1
                || !current.levels[0].equals(readback.levels[0]) || !previous.equals(original)
                || current.levels[0].equals(previous))
            throw new AssertionError("Partial texture update lost pixels or changed a queued frame");
        uploads.update(name, 0, 1, 0, 1, 2, GL11C.GL_RGB, GL11C.GL_FLOAT, patch);
        if (uploads.pixels(name, 0, 3, 2) != null)
            throw new AssertionError("Unsupported partial upload kept a stale snapshot");
    }
}
