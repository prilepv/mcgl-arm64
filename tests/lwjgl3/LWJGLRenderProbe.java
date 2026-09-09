import java.nio.*;
import java.security.MessageDigest;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;

/** Same bytecode runs against the baseline and, after link adaptation, LWJGL 3. */
public final class LWJGLRenderProbe {
    private static int checks;
    public static void main(String[] args) throws Exception {
        ContextCapabilities previous = null;
        for (int lifecycle = 0; lifecycle < 2; lifecycle++) {
            Display.setDisplayMode(new DisplayMode(320, 240));
            Display.setTitle("MCGL binding comparison (no account)");
            Display.create(new PixelFormat().withDepthBits(24).withStencilBits(8));
            System.out.println("RENDER_FORMAT rgba=" + GL11.glGetInteger(GL11.GL_RED_BITS)
                    + "/" + GL11.glGetInteger(GL11.GL_GREEN_BITS) + "/" + GL11.glGetInteger(GL11.GL_BLUE_BITS)
                    + "/" + GL11.glGetInteger(GL11.GL_ALPHA_BITS)
                    + " depth=" + GL11.glGetInteger(GL11.GL_DEPTH_BITS)
                    + " stencil=" + GL11.glGetInteger(GL11.GL_STENCIL_BITS));
            try {
                ContextCapabilities caps = GLContext.getCapabilities();
                require(caps != previous, "new capabilities for a replacement context");
                previous = caps;
                drawFixture(lifecycle);
                shaderFixture();
                framebufferFixture();
                arrayFixture();
                textureArrayFixture();
                Display.setDisplayMode(new DisplayMode(400, 300));
                Display.update();
                require(Display.getWidth() == 400 && Display.getHeight() == 300, "window resize");
                require(GLContext.getCapabilities() == caps, "resize preserves the current context identity");
                require(GL11.glGetError() == GL11.GL_NO_ERROR, "final GL state");
            } finally { Display.destroy(); }
            require(!Display.isCreated(), "window destroyed");
        }
        for (int lifecycle = 0; lifecycle < 2; lifecycle++) {
            AL.create();
            try {
                require(AL.isCreated(), "audio context created");
                IntBuffer buffers = BufferUtils.createIntBuffer(1);
                AL10.alGenBuffers(buffers);
                int buffer = buffers.get(0);
                ByteBuffer samples = BufferUtils.createByteBuffer(80);
                AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO8, samples, 8000);
                require(AL10.alGetBufferi(buffer, AL10.AL_SIZE) == 80, "audio sample upload");
                AL10.alDeleteBuffers(buffers);
                require(AL10.alGetError() == AL10.AL_NO_ERROR, "audio cleanup");
                final java.util.concurrent.atomic.AtomicReference<Throwable> error =
                        new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread audioWorker = new Thread(new Runnable() {
                    public void run() {
                        try {
                            if (AL10.alGetError() != AL10.AL_NO_ERROR) throw new AssertionError("worker audio error");
                        } catch (Throwable failure) { error.set(failure); }
                    }
                }, "LWJGL audio context test");
                audioWorker.start(); audioWorker.join();
                require(error.get() == null, "process-wide audio context on another thread: " + error.get());
            } finally { AL.destroy(); }
            require(!AL.isCreated(), "audio context destroyed");
        }
        System.out.println("LWJGL_RENDER_PROBE_PASS checks=" + checks);
    }

    private static void drawFixture(int lifecycle) throws Exception {
        GL11.glViewport(0, 0, 64, 64);
        GL11.glDisable(GL11.GL_DITHER);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
        GL11.glOrtho(0, 64, 0, 64, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
        GL11.glClearColor(0.125f, 0.25f, 0.5f, 1); GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        int list = GL11.glGenLists(1);
        GL11.glNewList(list, GL11.GL_COMPILE);
        GL11.glColor4f(1, 0.25f, 0, 0.5f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(8, 8, 0); GL11.glVertex3f(56, 8, 0);
        GL11.glVertex3f(56, 56, 0); GL11.glVertex3f(8, 56, 0);
        GL11.glEnd(); GL11.glEndList(); GL11.glCallList(list);
        GL11.glDeleteLists(list, 1);
        GL11.glDisable(GL11.GL_BLEND);
        FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrix);
        require(matrix.get(0) == 1 && matrix.get(15) == 1, "matrix readback");
        GL11.glMultMatrix(matrix); GL11.glLoadMatrix(matrix);
        ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
        GL11.glReadPixels(0, 0, 64, 64, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        byte[] bytes = new byte[pixels.remaining()]; pixels.get(bytes);
        System.out.println("RENDER_SAMPLE corner=" + sample(bytes, 0)
                + " center=" + sample(bytes, (32 * 64 + 32) * 4));
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", value & 255));
        System.out.println("RENDER_FIXTURE lifecycle=" + lifecycle + " sha256=" + hash);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "fixed-function rendering");
    }

    private static String sample(byte[] bytes, int offset) {
        return (bytes[offset] & 255) + "/" + (bytes[offset + 1] & 255) + "/"
                + (bytes[offset + 2] & 255) + "/" + (bytes[offset + 3] & 255);
    }

    private static void shaderFixture() {
        int shader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(shader, "#version 120\nvoid main(){gl_Position=ftransform();}\n");
        GL20.glCompileShader(shader);
        require(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE, "core shader compile");
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader); GL20.glLinkProgram(program);
        IntBuffer status = BufferUtils.createIntBuffer(1);
        GL20.glGetProgram(program, GL20.GL_LINK_STATUS, status);
        require(status.get(0) == GL11.GL_TRUE, "core program link");
        GL20.glDeleteProgram(program); GL20.glDeleteShader(shader);
        int arb = ARBShaderObjects.glCreateShaderObjectARB(GL20.GL_VERTEX_SHADER);
        ARBShaderObjects.glShaderSourceARB(arb, "#version 120\nvoid main(){gl_Position=ftransform();}\n");
        ARBShaderObjects.glCompileShaderARB(arb);
        status.clear(); ARBShaderObjects.glGetObjectParameterARB(arb, ARBShaderObjects.GL_OBJECT_COMPILE_STATUS_ARB, status);
        require(status.get(0) == GL11.GL_TRUE, "ARB shader compile");
        ARBShaderObjects.glDeleteObjectARB(arb);
    }

    private static void framebufferFixture() {
        int texture = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        int framebuffer = EXTFramebufferObject.glGenFramebuffersEXT();
        EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
        EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, texture, 0);
        require(EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT)
                == EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT, "framebuffer attachment");
        EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0);
        EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer); GL11.glDeleteTextures(texture);
    }

    private static void arrayFixture() {
        FloatBuffer vertices = BufferUtils.createFloatBuffer(12);
        vertices.put(new float[] {0,0,0, 4,0,0, 4,4,0, 0,4,0}).flip();
        GL11.glVertexPointer(3, 0, vertices);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        int buffer = GL15.glGenBuffers();
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 0, 0L);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, 4);
        GL11.glPopClientAttrib();
        require(GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) == 0, "client-state VBO restoration");
        GL11.glDrawArrays(GL11.GL_QUADS, 0, 4);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY); GL15.glDeleteBuffers(buffer);
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "client arrays and VBO");
    }

    private static void textureArrayFixture() {
        FloatBuffer first = BufferUtils.createFloatBuffer(8);
        ShortBuffer second = BufferUtils.createShortBuffer(8);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glTexCoordPointer(2, 0, first);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        ARBMultitexture.glClientActiveTextureARB(GL13.GL_TEXTURE1);
        GL11.glTexCoordPointer(2, 0, second);
        GL11.glPopClientAttrib();
        require(GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE) == GL13.GL_TEXTURE0, "client texture unit restore");
        // A pixel-store-only push/pop must NOT restore vertex-array state.
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        GL13.glClientActiveTexture(GL13.GL_TEXTURE1);
        GL11.glPopClientAttrib();
        require(GL11.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE) == GL13.GL_TEXTURE1, "client attribute mask respected");
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
        GL11.glColorPointer(4, true, 0, BufferUtils.createByteBuffer(16));
        GL11.glNormalPointer(0, BufferUtils.createByteBuffer(12));
        require(GL11.glGetError() == GL11.GL_NO_ERROR, "legacy color/normal/texture pointer overloads");
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}
