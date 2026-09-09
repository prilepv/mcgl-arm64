package org.lwjgl.opengl;

import java.nio.*;

/** Historical overload ABI. Context state and execution belong to the renderer module. */
public final class MCGLGL11 extends GL11 {
    private MCGLGL11() {}
    public static void glBegin(int mode) { local.mcgl.render.legacy.GL11.glBegin(mode); }
    public static void glEnd() { local.mcgl.render.legacy.GL11.glEnd(); }
    static boolean insideBeginEnd() { return local.mcgl.render.RenderSystem.insideBeginEnd(); }
    public static void glNewList(int list, int mode) { local.mcgl.render.legacy.GL11.glNewList(list, mode); }
    public static void glFog(int parameter, FloatBuffer values) { local.mcgl.render.legacy.GL11.glFog(parameter, values); }
    public static void glGetFloat(int parameter, FloatBuffer values) { local.mcgl.render.legacy.GL11.glGetFloat(parameter, values); }
    public static void glGetInteger(int parameter, IntBuffer values) { local.mcgl.render.legacy.GL11.glGetInteger(parameter, values); }
    public static void glLight(int light, int parameter, FloatBuffer values) { local.mcgl.render.legacy.GL11.glLight(light, parameter, values); }
    public static void glLightModel(int parameter, FloatBuffer values) { local.mcgl.render.legacy.GL11.glLightModel(parameter, values); }
    public static void glLoadMatrix(FloatBuffer values) { local.mcgl.render.legacy.GL11.glLoadMatrix(values); }
    public static void glMultMatrix(FloatBuffer values) { local.mcgl.render.legacy.GL11.glMultMatrix(values); }
    public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer pointer) {
        local.mcgl.render.legacy.GL11.glColorPointer(size, unsigned, stride, pointer);
    }
    public static void glNormalPointer(int stride, ByteBuffer pointer) { local.mcgl.render.legacy.GL11.glNormalPointer(stride, pointer); }
    public static void glVertexPointer(int size, int stride, FloatBuffer pointer) { local.mcgl.render.legacy.GL11.glVertexPointer(size, stride, pointer); }
    public static void glTexCoordPointer(int size, int stride, FloatBuffer pointer) { local.mcgl.render.legacy.GL11.glTexCoordPointer(size, stride, pointer); }
    public static void glTexCoordPointer(int size, int stride, ShortBuffer pointer) { local.mcgl.render.legacy.GL11.glTexCoordPointer(size, stride, pointer); }
    public static void glPushClientAttrib(int mask) { local.mcgl.render.legacy.GL11.glPushClientAttrib(mask); }
    public static void glPopClientAttrib() { local.mcgl.render.legacy.GL11.glPopClientAttrib(); }
}
