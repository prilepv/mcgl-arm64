package org.lwjgl.opengl;

/** Retains the legacy client-array unit tracking without a driver query per pointer. */
public final class MCGLGL13 extends GL13 {
    private MCGLGL13() {}
    public static void glClientActiveTexture(int texture) {
        local.mcgl.render.legacy.GL13.glClientActiveTexture(texture);
    }
}
