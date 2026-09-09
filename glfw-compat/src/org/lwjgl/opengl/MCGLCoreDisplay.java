package org.lwjgl.opengl;

import local.mcgl.render.RenderProfile;
import org.lwjgl.LWJGLException;

/** Core entry point used by the migrated game and independent GPU probes. No compatibility fallback. */
public final class MCGLCoreDisplay {
    private MCGLCoreDisplay() {}
    public static void create() throws LWJGLException { create(new PixelFormat()); }
    public static void create(PixelFormat format) throws LWJGLException {
        Display.create(format, RenderProfile.CORE_41);
    }
    // Window, input, presentation and destruction retain the existing Display lifecycle.
    // Rendering commands are obtained from RenderSystem.current(), not the old GL shims.
}
