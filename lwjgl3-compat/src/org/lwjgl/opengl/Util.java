package org.lwjgl.opengl;

/** Legacy error-reporting entry points, with no LWJGL 2 function pointers. */
public final class Util {
    private Util() {}
    public static void checkGLError() throws OpenGLException {
        if (MCGLGL11.insideBeginEnd()) return;
        int error = local.mcgl.render.legacy.GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) throw new OpenGLException(error);
    }
    public static String translateGLErrorString(int error) {
        switch (error) {
            case 0: return "No error";
            case 0x500: return "Invalid enum";
            case 0x501: return "Invalid value";
            case 0x502: return "Invalid operation";
            case 0x503: return "Stack overflow";
            case 0x504: return "Stack underflow";
            case 0x505: return "Out of memory";
            case 0x506: return "Invalid framebuffer operation";
            case 0x8031: return "Table too large";
            default: return null;
        }
    }
}
