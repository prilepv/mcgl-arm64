package local.mcgl.render.tests;

import java.lang.reflect.Field;
import java.nio.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import local.mcgl.render.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.PixelFormat;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;

/** Real GPU validation of renderer-owned state. No client, account, clipboard or external URL. */
public final class RenderLifecycleProbe {
    private static int checks;
    public static void main(String[] args) throws Exception {
        long previousGeneration = 0;
        for (int lifetime = 0; lifetime < 2; lifetime++) {
            Display.setDisplayMode(new DisplayMode(360, 260)); Display.setResizable(true);
            Display.setTitle("MCGL renderer context check");
            Display.create(new PixelFormat().withDepthBits(24).withStencilBits(8));
            RenderContext context = RenderSystem.current();
            LegacyRenderCommands commands = context.commands();
            Object state = field(field(context, "backend"), "state");
            try {
                check(context.generation() > previousGeneration && context.capabilities().version.startsWith("2.1"), "new generation on unchanged GL 2.1");
                previousGeneration = context.generation();
                ContextCapabilities legacy = GLContext.getCapabilities();
                check(legacy.OpenGL13 == context.capabilities().supports("OpenGL13")
                        && legacy.GL_ARB_shader_objects == context.capabilities().supports("GL_ARB_shader_objects"), "legacy flags reflect actual renderer capabilities");
                check(context.completedFrames() == 0, "new context has no inherited frame state");
                check(pointers(state).isEmpty(), "replacement context has no retained old pointers");
                pointerState(commands, state);
                drawThroughBoundary(commands);
                long frames = context.completedFrames();
                Display.update();
                check(context.completedFrames() == frames + 1 && context.presentedFrames() == 1
                        && context.surfaceWidth() == 360 && context.surfaceHeight() == 260, "Display presentation commits renderer frame metadata");
                Display.setDisplayMode(new DisplayMode(400, 300)); Display.update();
                check(RenderSystem.current() == context && context.surfaceWidth() == 400 && context.surfaceHeight() == 300,
                        "resize changes surface metadata, not context generation");
                IntBuffer names = BufferUtils.createIntBuffer(1);
                commands.GL11_glGenTextures(names); int texture = names.get(0);
                commands.GL11_glBindTexture(GL_TEXTURE_2D, texture);
                commands.GL11_glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);
                int retained = pointers(state).size();
                Display.releaseContext();
                rejected(() -> commands.GL11_glClear(GL_COLOR_BUFFER_BIT), "cached commands reject a released context");
                check(!context.isClosed(), "release is not destruction");
                Display.makeCurrent();
                check(RenderSystem.current() == context && GLContext.getCapabilities() == legacy && pointers(state).size() == retained,
                        "reattachment preserves legacy identity and pointer lifetimes");
                // A direct binding query is intentional white-box verification, not a client bypass.
                check(org.lwjgl.opengl.GL11.glIsTexture(texture), "native texture survives reattachment");
                foreign(commands);
                check(org.lwjgl.opengl.GL11.glIsTexture(texture), "rejected foreign draw cannot mutate native resources");
                commands.GL11_glDeleteTextures(texture);
                check(commands.GL11_glGetError() == GL_NO_ERROR, "no GL errors after lifecycle/state tests");
            } finally { Display.destroy(); }
            check(context.isClosed() && pointers(state).isEmpty(), "destruction releases all renderer-owned pointer references");
            rejected(() -> commands.GL11_glClear(GL_COLOR_BUFFER_BIT), "closed generation cannot issue commands");
            rejected(RenderSystem::current, "no stale current renderer after native destruction");
        }
        Display.shutdown();
        System.out.println("RENDER_LIFECYCLE_PASS checks=" + checks + " native-pointers/frames/ownership/reattach/closed-generations/pixels");
    }
    private static void pointerState(LegacyRenderCommands commands, Object state) throws Exception {
        FloatBuffer vertices = BufferUtils.createFloatBuffer(12), tex0 = BufferUtils.createFloatBuffer(8);
        ShortBuffer tex1 = BufferUtils.createShortBuffer(8);
        ByteBuffer colors = BufferUtils.createByteBuffer(16), normals = BufferUtils.createByteBuffer(12);
        commands.GL11_glVertexPointer(3, 0, vertices);
        commands.GL11_glNormalPointer(0, normals);
        commands.GL11_glColorPointer(4, true, 0, colors);
        check(commands.GL11_glGetInteger(GL_COLOR_ARRAY_TYPE) == GL_UNSIGNED_BYTE, "unsigned legacy color type");
        commands.GL11_glColorPointer(4, false, 0, colors);
        check(commands.GL11_glGetInteger(GL_COLOR_ARRAY_TYPE) == GL_BYTE, "signed legacy color type");
        commands.GL13_glClientActiveTexture(GL_TEXTURE0);
        commands.GL11_glTexCoordPointer(2, 0, tex0);
        commands.ARBMultitexture_glClientActiveTextureARB(GL_TEXTURE0 + 1);
        commands.GL11_glTexCoordPointer(2, 0, tex1);
        check(pointers(state).get(-3) == vertices && pointers(state).get(-2) == normals && pointers(state).get(-1) == colors,
                "vertex/normal/color buffers are retained by this context");
        check(pointers(state).get(GL_TEXTURE0) == tex0 && pointers(state).get(GL_TEXTURE0 + 1) == tex1,
                "core/ARB client texture selectors share the same pointer state");
        commands.GL11_glPushClientAttrib(GL_CLIENT_VERTEX_ARRAY_BIT);
        commands.GL13_glClientActiveTexture(GL_TEXTURE0);
        FloatBuffer replacement = BufferUtils.createFloatBuffer(12);
        commands.GL11_glVertexPointer(3, 0, replacement);
        check(pointers(state).get(-3) == replacement, "changed pointer recorded inside client attribute scope");
        commands.GL11_glPopClientAttrib();
        check(pointers(state).get(-3) == vertices && ((Integer)field(state, "textureUnit")) == GL_TEXTURE0 + 1,
                "client attribute pop restores retained pointers and selected unit");
        commands.GL11_glPushClientAttrib(GL_CLIENT_PIXEL_STORE_BIT);
        commands.GL11_glVertexPointer(3, 0, replacement);
        commands.GL11_glPopClientAttrib();
        check(pointers(state).get(-3) == replacement, "unrelated attribute mask does not rewind vertex state");
        commands.GL13_glClientActiveTexture(GL_TEXTURE0);
    }
    private static void drawThroughBoundary(LegacyRenderCommands commands) {
        commands.GL11_glViewport(0, 0, 32, 32);
        commands.GL11_glDisable(GL_DEPTH_TEST); commands.GL11_glDisable(GL_TEXTURE_2D);
        commands.GL11_glMatrixMode(GL_PROJECTION); commands.GL11_glLoadIdentity();
        commands.GL11_glMatrixMode(GL_MODELVIEW); commands.GL11_glLoadIdentity();
        commands.GL11_glClearColor(1, 0, 0, 1); commands.GL11_glClear(GL_COLOR_BUFFER_BIT);
        commands.GL11_glColor4f(0, 1, 0, 1);
        commands.GL11_glBegin(GL_QUADS);
        check(RenderSystem.insideBeginEnd(), "begin/end tracking belongs to active renderer");
        org.lwjgl.opengl.Util.checkGLError(); // Must not query GL inside begin/end.
        commands.GL11_glVertex2f(-1, -1); commands.GL11_glVertex2f(1, -1);
        commands.GL11_glVertex2f(1, 1); commands.GL11_glVertex2f(-1, 1); commands.GL11_glEnd();
        check(!RenderSystem.insideBeginEnd(), "end clears the context-local guard");
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        commands.GL11_glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        check((pixel.get(1) & 255) > 245 && (pixel.get(0) & 255) < 5, "typed commands draw expected GPU pixels");
    }
    private static void foreign(LegacyRenderCommands commands) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try { rejected(() -> commands.GL11_glClear(GL_COLOR_BUFFER_BIT), "cached commands reject a foreign thread before GL"); }
            catch (Throwable problem) { failure.set(problem); }
        }, "renderer GPU ownership probe");
        thread.start(); thread.join(2500);
        check(!thread.isAlive() && failure.get() == null, "foreign renderer check completes: " + failure.get());
    }
    @SuppressWarnings("unchecked") private static Map<Integer, Buffer> pointers(Object state) throws Exception { return (Map<Integer, Buffer>)field(state, "pointers"); }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void rejected(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (IllegalStateException expected) { check(true, message); }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
