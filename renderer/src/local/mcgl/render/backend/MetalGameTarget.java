package local.mcgl.render.backend;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import java.nio.IntBuffer;
import static org.lwjgl.opengl.CGL.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;

/** A Cocoa Metal window, with an optional offscreen source for older preview builds. */
public final class MetalGameTarget implements AutoCloseable {
    private static final int CGL_PROFILE_41 = 0x4100;
    private static final boolean INDEPENDENT = "1".equals(System.getenv("MCGL_METAL_INDEPENDENT"))
            && "metal".equals(System.getenv("MCGL_RENDERER"));
    public static boolean independent() { return INDEPENDENT; }
    public boolean usesOpenGL() { return context != 0; }
    private static final ThreadLocal<MetalGameTarget> CURRENT = new ThreadLocal<>();
    private final long window;
    private final long context;
    private int framebuffer, color, depth, width, height;

    public MetalGameTarget(long window) {
        if (window == 0) throw new IllegalArgumentException("Missing Cocoa game window");
        this.window = window;
        if (INDEPENDENT) {
            context = 0;
            System.load(System.getenv("MCGL_BGFX_LIVE_LIBRARY"));
            installStartupCover(window);
            System.out.println("[MCGL bgfx] game window has no OpenGL context");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attributes = stack.ints(kCGLPFAOpenGLProfile, CGL_PROFILE_41, kCGLPFAAccelerated, 0);
            PointerBuffer format = stack.callocPointer(1), result = stack.callocPointer(1);
            if (CGLChoosePixelFormat(attributes, format, stack.callocInt(1)) != kCGLNoError || format.get(0) == 0)
                throw new IllegalStateException("Could not choose the offscreen Core pixel format");
            try {
                if (CGLCreateContext(format.get(0), 0, result) != kCGLNoError || result.get(0) == 0)
                    throw new IllegalStateException("Could not create the offscreen Core context");
                context = result.get(0);
            } finally { CGLDestroyPixelFormat(format.get(0)); }
        }
    }

    private static native void installStartupCover(long window);

    public void makeCurrent() {
        if (usesOpenGL() && CGLSetCurrentContext(context) != kCGLNoError) throw new IllegalStateException("Could not attach the offscreen context");
        CURRENT.set(this);
    }

    public void release() {
        if (usesOpenGL() && CGLSetCurrentContext(0) != kCGLNoError) throw new IllegalStateException("Could not detach the offscreen context");
        CURRENT.remove();
    }

    public boolean isCurrent() { return CURRENT.get() == this && (!usesOpenGL() || CGLGetCurrentContext() == context); }

    public void prepare(int width, int height) {
        if (width < 1 || height < 1 || (this.width == width && this.height == height)) return;
        if (!usesOpenGL()) {
            if (this.width == 0) BgfxGameState.glViewport(0, 0, width, height);
            this.width = width;
            this.height = height;
            return;
        }
        boolean initial = framebuffer == 0;
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previous = glGetInteger(GL_RENDERBUFFER_BINDING);
        if (initial) {
            framebuffer = glGenFramebuffers();
            color = glGenRenderbuffers();
            depth = glGenRenderbuffers();
        }
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glBindRenderbuffer(GL_RENDERBUFFER, color);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color);
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Could not allocate the offscreen game framebuffer");
            this.width = width;
            this.height = height;
            if (initial) glViewport(0, 0, width, height);
        } finally {
            glBindRenderbuffer(GL_RENDERBUFFER, previous);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, initial && read == 0 ? framebuffer : read);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, initial && draw == 0 ? framebuffer : draw);
        }
    }

    @Override public void close() { if (usesOpenGL()) CGLDestroyContext(context); }

    public static int physicalFramebuffer(int logical) {
        MetalGameTarget target = CURRENT.get();
        return logical == 0 && target != null ? target.framebuffer : logical;
    }

    static int logicalFramebuffer(int physical) {
        MetalGameTarget target = CURRENT.get();
        return target != null && physical == target.framebuffer ? 0 : physical;
    }

    static long cocoaWindow() {
        MetalGameTarget target = CURRENT.get();
        return target == null ? 0 : target.window;
    }

    static int drawableWidth() {
        MetalGameTarget target = CURRENT.get();
        return target == null ? 0 : target.width;
    }

    static int drawableHeight() {
        MetalGameTarget target = CURRENT.get();
        return target == null ? 0 : target.height;
    }
}
