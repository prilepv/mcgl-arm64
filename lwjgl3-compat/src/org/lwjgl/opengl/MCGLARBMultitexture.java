package org.lwjgl.opengl;

/** The ARB and core entry points update the same context-local client-array state. */
public final class MCGLARBMultitexture extends ARBMultitexture {
    private MCGLARBMultitexture() {}
    public static void glClientActiveTextureARB(int texture) {
        local.mcgl.render.legacy.ARBMultitexture.glClientActiveTextureARB(texture);
    }
}
