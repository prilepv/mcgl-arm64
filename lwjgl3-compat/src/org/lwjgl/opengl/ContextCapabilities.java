package org.lwjgl.opengl;

import local.mcgl.render.RenderCapabilities;
import local.mcgl.render.RenderProfile;

/** Legacy feature flags backed by the actual LWJGL 3 context, never guessed. */
public final class ContextCapabilities {
    public final boolean OpenGL13, OpenGL15;
    public final boolean GL_ARB_fragment_shader, GL_ARB_framebuffer_object;
    public final boolean GL_ARB_multitexture, GL_ARB_occlusion_query;
    public final boolean GL_ARB_shader_objects, GL_ARB_vertex_buffer_object;
    public final boolean GL_ARB_vertex_shader, GL_EXT_framebuffer_object;
    public final boolean GL_NV_fog_distance, GL_APPLE_vertex_array_object;

    ContextCapabilities(RenderCapabilities caps) {
        // These are the original game's feature switches, not an extension-string replica.
        // In the migrated game their ARB entry points are typed adapters to promoted Core operations.
        boolean core = caps.profile == RenderProfile.CORE_41 && (caps.majorVersion > 4 || caps.majorVersion == 4 && caps.minorVersion >= 1);
        OpenGL13 = core || caps.supports("OpenGL13"); OpenGL15 = core || caps.supports("OpenGL15");
        GL_ARB_fragment_shader = core || caps.supports("GL_ARB_fragment_shader");
        GL_ARB_framebuffer_object = core || caps.supports("GL_ARB_framebuffer_object");
        GL_ARB_multitexture = core || caps.supports("GL_ARB_multitexture");
        GL_ARB_occlusion_query = core || caps.supports("GL_ARB_occlusion_query");
        GL_ARB_shader_objects = core || caps.supports("GL_ARB_shader_objects");
        GL_ARB_vertex_buffer_object = core || caps.supports("GL_ARB_vertex_buffer_object");
        GL_ARB_vertex_shader = core || caps.supports("GL_ARB_vertex_shader");
        GL_EXT_framebuffer_object = caps.supports("GL_EXT_framebuffer_object");
        GL_NV_fog_distance = caps.supports("GL_NV_fog_distance");
        GL_APPLE_vertex_array_object = caps.supports("GL_APPLE_vertex_array_object");
    }
}
