package local.mcgl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import local.mcgl.platform.Platform;
import local.mcgl.platform.glfw.GlfwWindow;
import local.mcgl.render.GameRenderCommands;
import local.mcgl.render.RenderSystem;
import local.mcgl.render.ShaderProgram;
import local.mcgl.render.ShaderSources;
import local.mcgl.render.backend.MetalTextureUploadProbe;
import local.mcgl.render.backend.BgfxTurnStress;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.CGL;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.MCGLCoreDisplay;
import static org.lwjgl.glfw.GLFW.*;

/** Exercises the production game window, offscreen source and Metal presentation without a login. */
public final class DirectLauncher {
    public static void main(String[] args) throws Exception {
        String stressDirectory = System.getenv("MCGL_TEST_BGFX_TURN");
        if (stressDirectory != null) {
            BgfxTurnStress.verify(java.nio.file.Paths.get(System.getenv("MCGL_BGFX_LIVE_LIBRARY")),
                    java.nio.file.Paths.get(stressDirectory));
            return;
        }
        System.setProperty("mcgl.metal.capture.warmup", "0");
        boolean bgfx = System.getenv("MCGL_BGFX_LIVE_LIBRARY") != null;
        boolean independent = "1".equals(System.getenv("MCGL_METAL_INDEPENDENT"));
        String limit = System.getenv("MCGL_FPS_LIMIT");
        if (limit != null && !limit.equals(System.getProperty("mcgl.fps.limit")))
            throw new AssertionError("Metal changed the configured FPS limit");
        Display.setDisplayMode(new DisplayMode(420, 280));
        Display.setResizable(true);
        MCGLCoreDisplay.create();
        try {
            GlfwWindow platformWindow = (GlfwWindow)Platform.window();
            long window = platformWindow.nativeHandle();
            platformWindow.host().invoke(() -> glfwSetWindowAttrib(window, GLFW_FLOATING, GLFW_TRUE));
            if (glfwGetWindowAttrib(window, GLFW_CLIENT_API) != GLFW_NO_API || glfwGetCurrentContext() != 0)
                throw new AssertionError("Metal created a GLFW OpenGL context");
            if (!independent) {
                int[] drawable = {-1};
                if (CGL.CGLGetParameter(CGL.CGLGetCurrentContext(), CGL.kCGLCPHasDrawable, drawable) != CGL.kCGLNoError
                        || drawable[0] != 0) throw new AssertionError("The offscreen source has an onscreen drawable");
            }
            if (!Mouse.isCreated() || !Keyboard.isCreated()) throw new AssertionError("Game input is unavailable");
            GameRenderCommands g = RenderSystem.game();
            if (g.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) != 0)
                throw new AssertionError("Wrong logical default framebuffer");
            int internal = independent ? 0 : GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            if (!independent && internal == 0) throw new AssertionError("Missing offscreen framebuffer");
            IntBuffer custom = org.lwjgl.BufferUtils.createIntBuffer(1);
            g.GL30_glGenFramebuffers(custom);
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER, custom.get(0));
            if (g.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) != custom.get(0))
                throw new AssertionError("Custom framebuffer binding changed");
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            if (!independent && GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) != internal)
                throw new AssertionError("Binding the logical default lost the offscreen target");
            g.GL30_glDeleteFramebuffers(custom);
            if (!independent) MetalTextureUploadProbe.verify();
            int texture = g.glGenTextures();
            g.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            g.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            g.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 1, 1, 0,
                    GL12C.GL_BGRA, GL12C.GL_UNSIGNED_INT_8_8_8_8_REV, pixel(90, 120, 200));
            g.glEnable(GL11C.GL_TEXTURE_2D);
            int chunk = bgfx ? terrain(g, 0) : 0;
            int alternate = independent ? terrain(g, .05f) : chunk;
            ShaderProgram effect = independent
                    ? RenderSystem.shaders().create(new ShaderSources("test/high-graphics", "vertex", "fragment")) : null;
            int[] map = bgfx ? renderTarget(g, texture) : null;
            for (int i = 0; i < (bgfx ? 155 : 115); i++) {
                if (i == 10) g.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 1, 1,
                        GL12C.GL_BGRA, GL12C.GL_UNSIGNED_INT_8_8_8_8_REV, pixel(10, 50, 80));
                if (i == 40 && !independent) g.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 20, 20, 1, 1);
                if (i == 100) Display.setDisplayMode(new DisplayMode(480, 320));
                boolean world = bgfx && i >= 50;
                boolean worldTarget = independent && i >= 120;
                int visible = i % 2 == 0 ? chunk : alternate;
                if (world) {
                    g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER, map[0]);
                    if (independent) {
                        // The game can reattach and define a same-size texture for every map pass.
                        g.glBindTexture(GL11C.GL_TEXTURE_2D, map[1]);
                        g.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 8, 8, 0,
                                GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, null);
                        g.GL30_glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                                GL11C.GL_TEXTURE_2D, map[1], 0);
                        g.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
                    }
                    g.glViewport(0, 0, 8, 8);
                    g.glClearColor(.8f, (i % 8) / 8f, .4f, 1);
                    g.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
                    if (independent) {
                        if (worldTarget) {
                            g.glEnable(GL11C.GL_DEPTH_TEST);
                            g.selectEffect(effect);
                            g.glCallList(visible);
                            g.selectEffect(null);
                        } else {
                            g.glDisable(GL11C.GL_TEXTURE_2D);
                            quad(g, .15f, .95f, .3f);
                            g.glEnable(GL11C.GL_TEXTURE_2D);
                        }
                    }
                    g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
                    g.glEnable(GL11C.GL_DEPTH_TEST);
                }
                g.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                g.glViewport(0, 0, Display.getWidth(), Display.getHeight());
                g.glClearColor(.1f, .1f, .2f, 1);
                g.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
                if (world) {
                    int scope = g.beginOriginalTerrain();
                    try {
                        if (worldTarget) {
                            g.glDisable(GL11C.GL_DEPTH_TEST);
                            g.glBindTexture(GL11C.GL_TEXTURE_2D, map[1]);
                            quad(g, 1, 1, 1);
                            g.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
                        } else g.glCallList(visible);
                    }
                    finally { g.endOriginalTerrain(scope); }
                    if (independent) {
                        g.glEnable(GL11C.GL_POLYGON_OFFSET_FILL);
                        g.glPolygonOffset(-3, -3);
                        quad(g, .9f, .6f, .15f);
                        g.glDisable(GL11C.GL_POLYGON_OFFSET_FILL);
                        g.glBegin(GL11C.GL_LINES);
                        g.glVertex2f(-.7f, 0);
                        g.glVertex2f(.7f, 0);
                        g.glEnd();
                    }
                    if (i == 75) g.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
                    g.glClear(GL11C.GL_DEPTH_BUFFER_BIT | GL11C.GL_STENCIL_BUFFER_BIT);
                    g.glEnable(GL11C.GL_STENCIL_TEST);
                    g.glStencilFunc(GL11C.GL_ALWAYS, 1, 255);
                    g.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_REPLACE);
                    g.glColorMask(false, false, false, false);
                    quad(g, .4f, .8f, .3f);
                    g.glColorMask(true, true, true, true);
                    g.glStencilFunc(GL11C.GL_EQUAL, 1, 255);
                    g.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
                    g.glDisable(GL11C.GL_DEPTH_TEST);
                    g.glBindTexture(GL11C.GL_TEXTURE_2D, map[1]);
                    g.glEnable(GL11.GL_LIGHTING);
                    g.glEnable(GL11.GL_LIGHT0);
                    g.glViewport(16, 12, Display.getWidth() - 32, Display.getHeight() - 24);
                    if (!independent) {
                        GL11C.glScissor(32, 24, 160, 100);
                        g.glEnable(GL11C.GL_SCISSOR_TEST);
                    }
                    quad(g, .9f, .9f, .9f);
                    g.glDisable(GL11C.GL_SCISSOR_TEST);
                    g.glViewport(0, 0, Display.getWidth(), Display.getHeight());
                    g.glDisable(GL11.GL_LIGHTING);
                    g.glDisable(GL11C.GL_STENCIL_TEST);
                    g.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
                } else quad(g, .4f, .8f, .3f);
                int text = g.beginText();
                try { quad(g, .9f, .6f, .15f); }
                finally { g.endText(text); }
                if (g.glGetError() != 0) throw new AssertionError("Offscreen draw generated an OpenGL error");
                Display.update();
                Thread.sleep(24);
            }
            if (effect != null) effect.close();
            if (Display.getWidth() != 480 || Display.getHeight() != 320)
                throw new AssertionError("Game window resize did not reach the renderer");
            System.out.println("METAL_GAME_WINDOW_PASS input=ready framebuffer="
                    + (independent ? "metal" : "offscreen") + " resized=480x320");
        } finally {
            Display.shutdown();
        }
    }

    private static int terrain(GameRenderCommands g, float shift) {
        int id = g.glGenLists(1);
        g.beginOriginalChunk(id, 1);
        g.glNewList(id, GL11.GL_COMPILE);
        float[][] positions = {{-.7f, -.6f}, {.7f, -.6f}, {.7f, .6f}, {-.7f, .6f}};
        int[] vertices = new int[32];
        for (int i = 0; i < positions.length; i++) {
            int at = i * 8;
            vertices[at] = Float.floatToIntBits(positions[i][0] + shift);
            vertices[at + 1] = Float.floatToIntBits(positions[i][1]);
            vertices[at + 3] = Float.floatToIntBits(i == 1 || i == 2 ? 1 : 0);
            vertices[at + 4] = Float.floatToIntBits(i >= 2 ? 1 : 0);
            vertices[at + 5] = 0xffe6994c;
        }
        g.raw(vertices, vertices.length, 4, GL11C.GL_QUADS, false, true, true, true, false, false);
        g.glEndList();
        g.finishOriginalChunk();
        return id;
    }

    private static int[] renderTarget(GameRenderCommands g, int original) {
        IntBuffer names = org.lwjgl.BufferUtils.createIntBuffer(1);
        g.GL30_glGenFramebuffers(names);
        int framebuffer = names.get(0), texture = g.glGenTextures();
        g.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        g.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        g.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 8, 8, 0,
                GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(8 * 8 * 4));
        g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        g.GL30_glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, texture, 0);
        if (g.GL30_glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE)
            throw new AssertionError("Minimap fixture framebuffer");
        g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        g.glBindTexture(GL11C.GL_TEXTURE_2D, original);
        return new int[]{framebuffer, texture};
    }

    private static ByteBuffer pixel(int b, int g, int r) {
        return ByteBuffer.allocateDirect(4).put((byte)b).put((byte)g).put((byte)r).put((byte)255).flip();
    }

    private static void quad(GameRenderCommands g, float r, float green, float b) {
        g.glBegin(GL11C.GL_QUADS);
        g.glColor4f(r, green, b, 1);
        g.glVertex2f(-.7f, -.6f);
        g.glVertex2f(.7f, -.6f);
        g.glVertex2f(.7f, .6f);
        g.glVertex2f(-.7f, .6f);
        g.glEnd();
    }
}
