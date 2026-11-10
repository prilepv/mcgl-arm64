package local.mcgl.render.tests;

import java.io.File;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import local.mcgl.render.GameRenderCommands;
import local.mcgl.render.RenderSystem;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.OffscreenCoreContext;

/** Exercises the live batch hook through the official client's accumulator. */
public final class MetalTerrainLiveProbe {
    private static final int SIZE = 128;

    public static void main(String[] args) throws Exception {
        OffscreenCoreContext.open();
        try (DataOutputStream capture = args.length == 2
                ? new DataOutputStream(Files.newOutputStream(Paths.get(args[1]))) : null) {
            GameRenderCommands g = RenderSystem.game();
            int fbo = GL30C.glGenFramebuffers();
            g.GL30_glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            attach(g, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_RGBA8);
            boolean sceneFixture = Boolean.getBoolean("mcgl.metal.live.fixture")
                    && "1".equals(System.getenv("MCGL_METAL_FULL_SCENE"));
            if (sceneFixture) attach(g, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_DEPTH32F_STENCIL8);
            else attach(g, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_DEPTH_COMPONENT32F);
            if (g.GL30_glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new AssertionError("Probe framebuffer");
            }
            g.glViewport(0, 0, SIZE, SIZE);
            g.glDisable(GL11C.GL_DITHER);
            g.glEnable(GL11C.GL_DEPTH_TEST);
            g.glDepthFunc(GL11C.GL_LEQUAL);
            g.glEnable(GL11C.GL_CULL_FACE);
            g.glCullFace(GL11C.GL_BACK);
            g.glFrontFace(GL11C.GL_CCW);
            g.glEnable(3008);
            g.glAlphaFunc(GL11C.GL_GREATER, .2f);
            g.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
            g.glClearColor(0, 0, 0, 0);
            g.glActiveTexture(GL13C.GL_TEXTURE1);
            texture(g, 0, true);
            g.glEnable(3553);
            g.glMatrixMode(5890);
            g.glScalef(1f / 16, 1f / 16, 1);
            g.glActiveTexture(GL13C.GL_TEXTURE0);
            int firstTexture = texture(g, 1, false), secondTexture = texture(g, 2, false);
            g.glEnable(3553);
            g.glMatrixMode(5888);
            boolean liveFixture = Boolean.getBoolean("mcgl.metal.live.fixture");
            boolean bgfxFixture = liveFixture && System.getenv("MCGL_BGFX_LIVE_LIBRARY") != null;
            int listCount = liveFixture ? 16 : 8;
            int first = g.glGenLists(listCount);
            OriginalChunkEmitter emitter = new OriginalChunkEmitter(new File(args[0]), 1024);
            for (int i = 0; i < listCount; i++) {
                g.beginOriginalChunk(first + i, 1);
                g.glNewList(first + i, 4864);
                g.glPushMatrix();
                g.glTranslatef(-.875f + (i % 4) * .375f, i < 4 ? -.5f : -.25f, i < 4 ? .25f : -.25f);
                emitter.begin();
                emitter.color(184 - i * 8, 152 + i * 8, 211, 224);
                emitter.lightmap(5 << 16 | 3);
                emitter.quad(0, 0, .75, .75, 0);
                if (emitter.draw() != 128) throw new AssertionError("Original quad emission");
                g.glPopMatrix();
                g.glEndList();
                g.finishOriginalChunk();
            }
            int size = SIZE;
            if (sceneFixture) {
                for (int frame = 0; frame < 3; frame++) {
                    quad(g, .75f, .1f, .15f, .5f);
                    int text = g.beginText();
                    try { quad(g, -.75f, .8f, .9f, .95f); }
                    finally { g.endText(text); }
                    RenderSystem.completeFrame(SIZE, SIZE, false);
                    Thread.sleep(270);
                }
            }
            for (int frame = 0; frame < (liveFixture ? 44 : capture == null ? 4 : 6); frame++) {
                if (bgfxFixture && frame == 12) update(g, firstTexture);
                if (capture != null && frame == 4) {
                    g.glDepthFunc(GL11C.GL_GREATER);
                    g.glClearDepth(0);
                }
                if (capture != null && frame == 5) {
                    g.glDepthFunc(GL11C.GL_LEQUAL);
                    g.glClearDepth(1);
                    size = SIZE / 2;
                    g.glViewport(0, 0, size, size);
                }
                if (!liveFixture || frame != 30)
                    g.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
                if (liveFixture ? frame == 40 : frame == 1) g.glEnable(GL11C.GL_BLEND);
                if (frame == 2) {
                    if (capture != null) {
                        g.glDisable(GL11C.GL_BLEND);
                        update(g, firstTexture);
                        overlay(emitter, -.5, 240);
                    }
                    g.glEnable(2912);
                    g.glFogi(2917, 9729);
                    g.glFogf(2915, 0);
                    g.glFogf(2916, 1);
                }
                if (frame == 3) {
                    g.glShadeModel(7424);
                    g.glTranslatef(.0625f, 0, 0);
                }
                if (liveFixture) {
                    g.glLoadIdentity();
                    g.glTranslatef((frame % 16) / 128f, 0, 0);
                    if (frame == 20) {
                        if (sceneFixture) g.glEnable(GL11C.GL_SCISSOR_TEST);
                        else g.glColorMask(false, true, true, true);
                    }
                }
                if (sceneFixture) quad(g, .8f, .15f, .2f, .6f);
                if (bgfxFixture) GL11C.glEnable(GL30C.GL_RASTERIZER_DISCARD);
                try {
                    if (liveFixture && frame == 2) {
                        terrain(g, first, 0, 4, firstTexture, secondTexture);
                        Thread.sleep(300);
                        terrain(g, first, 4, 8, firstTexture, secondTexture);
                    } else terrain(g, first, 0, 8, firstTexture, secondTexture);
                } finally {
                    if (bgfxFixture) GL11C.glDisable(GL30C.GL_RASTERIZER_DISCARD);
                }
                if (bgfxFixture && frame == 0) assertBlankSource(g);
                if (bgfxFixture && frame == 10) {
                    g.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
                    GL11C.glEnable(GL30C.GL_RASTERIZER_DISCARD);
                    try { terrain(g, first, 4, 8, firstTexture, secondTexture); }
                    finally { GL11C.glDisable(GL30C.GL_RASTERIZER_DISCARD); }
                }
                if (liveFixture && frame == 20) {
                    if (sceneFixture) g.glDisable(GL11C.GL_SCISSOR_TEST);
                    else g.glColorMask(true, true, true, true);
                }
                if (liveFixture && frame == 40) g.glDisable(GL11C.GL_BLEND);
                if (sceneFixture) {
                    quad(g, -.6f, .75f, .1f, .15f);
                    g.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
                    g.glClearStencil(0);
                    g.glClear(GL11C.GL_STENCIL_BUFFER_BIT);
                    g.glEnable(GL11C.GL_STENCIL_TEST);
                    g.glStencilFunc(GL11C.GL_ALWAYS, 1, 255);
                    g.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_REPLACE);
                    g.glColorMask(false, false, false, false);
                    quad(g, .2f, .85f, .65f, .15f);
                    g.glColorMask(true, true, true, true);
                    g.glStencilFunc(GL11C.GL_EQUAL, 1, 255);
                    g.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
                    g.glEnable(GL11C.GL_BLEND);
                    g.glBlendFunc(GL11C.GL_ONE, GL11C.GL_ONE);
                    int text = g.beginText();
                    try { quad(g, -.9f, .18f, .7f, .3f); }
                    finally { g.endText(text); }
                    g.glDisable(GL11C.GL_BLEND);
                    g.glDisable(GL11C.GL_STENCIL_TEST);
                    g.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
                }
                if (frame == 0 && Boolean.getBoolean("mcgl.metal.capture.fixtureBlend")) {
                    g.glEnable(GL11C.GL_BLEND);
                    g.glDepthMask(false);
                    g.glPushMatrix();
                    g.glTranslatef(.125f, 0, -.125f);
                    int transparent = g.beginOriginalTerrain();
                    try {
                        for (int i = 0; i < 8; i++) {
                            g.glBindTexture(3553, i % 2 == 0 ? secondTexture : firstTexture);
                            g.glCallList(first + i);
                        }
                    } finally {
                        g.endOriginalTerrain(transparent);
                        g.glPopMatrix();
                        g.glDepthMask(true);
                        g.glDisable(GL11C.GL_BLEND);
                    }
                }
                if (capture != null) {
                    if (frame == 2) overlay(emitter, .5, 45);
                    snapshot(g, capture, size);
                }
                int error = g.glGetError();
                if (error != 0) throw new AssertionError("Metal hook left GL error 0x" + Integer.toHexString(error));
                RenderSystem.completeFrame(SIZE, SIZE, false);
                if (liveFixture && frame != 1) Thread.sleep(270);
            }
            g.glDeleteLists(first, listCount);
        } finally {
            OffscreenCoreContext.close();
        }
        System.out.println("METAL_TERRAIN_LIVE_PROBE_PASS");
    }

    private static void quad(GameRenderCommands g, float z, float r, float green, float b) {
        g.glBegin(GL11C.GL_QUADS);
        g.glColor4f(r, green, b, 1);
        g.glVertex3f(-.7f, -.7f, z);
        g.glVertex3f(.7f, -.7f, z);
        g.glVertex3f(.7f, .7f, z);
        g.glVertex3f(-.7f, .7f, z);
        g.glEnd();
    }

    private static void terrain(GameRenderCommands g, int first, int from, int to, int firstTexture, int secondTexture) {
        int scope = g.beginOriginalTerrain();
        try {
            for (int i = from; i < to; i++) {
                g.glBindTexture(3553, i % 2 == 0 ? firstTexture : secondTexture);
                g.glCallList(first + i);
            }
        } finally {
            g.endOriginalTerrain(scope);
        }
    }

    private static void overlay(OriginalChunkEmitter emitter, double z, int red) {
        emitter.begin();
        emitter.color(red, 160, 120, 255);
        emitter.lightmap(5 << 16 | 3);
        emitter.quad(-.25, -.75, .25, .5, z);
        emitter.draw();
    }

    private static void update(GameRenderCommands g, int texture) {
        g.glBindTexture(3553, texture);
        ByteBuffer pixel = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        pixel.putInt(0xff7bb521).flip();
        g.glTexSubImage2D(3553, 0, 1, 1, 1, 1, org.lwjgl.opengl.GL12C.GL_BGRA,
                org.lwjgl.opengl.GL12C.GL_UNSIGNED_INT_8_8_8_8_REV, pixel);
    }

    private static void assertBlankSource(GameRenderCommands g) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        g.glReadPixels(0, 0, SIZE, SIZE, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
        for (int i = 0; i < pixels.capacity(); i++)
            if (pixels.get(i) != 0) throw new AssertionError("OpenGL rasterized terrain in the bgfx fixture");
        System.out.println("BGFX_FIXTURE_OPENGL_TERRAIN_DISCARDED");
    }

    private static void snapshot(GameRenderCommands g, DataOutputStream out, int size) throws Exception {
        int bytes = size * size * 4;
        ByteBuffer color = ByteBuffer.allocateDirect(bytes);
        ByteBuffer depth = ByteBuffer.allocateDirect(bytes);
        g.glReadPixels(0, 0, size, size, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, color);
        g.glReadPixels(0, 0, size, size, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, depth);
        byte[] data = new byte[bytes];
        color.get(data);
        out.write(data);
        depth.get(data);
        out.write(data);
    }

    private static void attach(GameRenderCommands g, int attachment, int format) {
        int buffer = GL30C.glGenRenderbuffers();
        g.GL30_glBindRenderbuffer(GL30C.GL_RENDERBUFFER, buffer);
        g.GL30_glRenderbufferStorage(GL30C.GL_RENDERBUFFER, format, SIZE, SIZE);
        g.GL30_glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, attachment, GL30C.GL_RENDERBUFFER, buffer);
    }

    private static int texture(GameRenderCommands g, int variant, boolean light) {
        int id = g.glGenTextures();
        g.glBindTexture(3553, id);
        g.glTexParameteri(3553, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        g.glTexParameteri(3553, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        ByteBuffer pixels = ByteBuffer.allocateDirect(4 * 4 * 4);
        for (int i = 0; i < 16; i++) {
            pixels.put((byte)(160 + i * 3)).put((byte)(60 + variant * 60 + i * 3))
                .put((byte)(175 - i * 3)).put((byte)(light ? 255 : i % 5 == 0 ? 0 : 127 + (i % 3) * 52));
        }
        pixels.flip();
        g.glTexImage2D(3553, 0, GL11C.GL_RGBA8, 4, 4, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
        return id;
    }
}
