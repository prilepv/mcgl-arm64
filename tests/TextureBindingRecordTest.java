import java.io.*;
import java.lang.reflect.*;
import java.util.List;
import org.objectweb.asm.*;

/** Exercise the real helper with only glBindTexture replaced by a recorder.
 * Large unsigned names are checked without allocating driver texture objects. */
public final class TextureBindingRecordTest implements Opcodes {
    static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    static byte[] read(String name) throws Exception {
        try (InputStream in = TextureBindingRecordTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            if (in == null) throw new AssertionError(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
        }
    }
    public static void main(String[] args) throws Exception {
        ClassWriter w = new ClassWriter(0);
        w.visit(V1_6, ACC_PUBLIC, "org/lwjgl/opengl/GL11", null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC | ACC_STATIC, "target", "I", null, null).visitEnd();
        w.visitField(ACC_PUBLIC | ACC_STATIC, "texture", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "glBindTexture", "(II)V", null, null);
        m.visitCode(); m.visitVarInsn(ILOAD, 0); m.visitFieldInsn(PUTSTATIC, "org/lwjgl/opengl/GL11", "target", "I");
        m.visitVarInsn(ILOAD, 1); m.visitFieldInsn(PUTSTATIC, "org/lwjgl/opengl/GL11", "texture", "I");
        m.visitInsn(RETURN); m.visitMaxs(1, 2); m.visitEnd(); w.visitEnd();
        final byte[] stub = w.toByteArray();
        ClassLoader loader = new ClassLoader(TextureBindingRecordTest.class.getClassLoader()) {
            protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("local.mcgl.perf.ChunkVbo") && !name.equals("org.lwjgl.opengl.GL11")) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) try {
                    byte[] b = name.equals("org.lwjgl.opengl.GL11") ? stub : read(name);
                    loaded = defineClass(name, b, 0, b.length);
                } catch (Exception e) { throw new ClassNotFoundException(name, e); }
                if (resolve) resolveClass(loaded); return loaded;
            }
        };
        Class<?> helper = loader.loadClass("local.mcgl.perf.ChunkVbo"), mesh = loader.loadClass("local.mcgl.perf.ChunkVbo$Mesh");
        Class<?> gl = loader.loadClass("org.lwjgl.opengl.GL11");
        Constructor<?> constructor = mesh.getDeclaredConstructor(int.class); constructor.setAccessible(true);
        Method bind = helper.getMethod("glBindTexture", int.class, int.class);
        boolean enabled = Boolean.getBoolean("mcgl.chunk.vbo"), bindings = !"false".equals(System.getProperty("mcgl.chunk.textureBindings"));
        int[] ids = {0, 1, 16777217, Integer.MIN_VALUE + 1, -1};
        for (int id : ids) {
            Object capture = constructor.newInstance(1); field(helper, "recording").set(null, capture);
            bind.invoke(null, 3553, id);
            if (gl.getField("target").getInt(null) != 3553 || gl.getField("texture").getInt(null) != id) throw new AssertionError("Native bind changed");
            List<?> commands = (List<?>)field(mesh, "commands").get(capture);
            if (enabled && bindings) {
                if (commands.size() != 1) throw new AssertionError("Bind not recorded");
                Object command = commands.get(0);
                if (field(command.getClass(), "op").getInt(command) != 7 || field(command.getClass(), "mode").getInt(command) != 3553 ||
                        field(command.getClass(), "count").getInt(command) != id) throw new AssertionError("GLuint name lost precision: " + id);
            } else if (!commands.isEmpty()) throw new AssertionError("Disabled capture recorded a bind");
            if (field(mesh, "valid").getBoolean(capture) != (!enabled || bindings)) throw new AssertionError("Fallback guard changed");
        }
        Object capture = constructor.newInstance(2); field(helper, "recording").set(null, capture);
        bind.invoke(null, 3552, 0); // unsupported 1D target
        if (field(mesh, "valid").getBoolean(capture) == enabled) throw new AssertionError("Non-2D target lost fallback");
        if (!((List<?>)field(mesh, "commands").get(capture)).isEmpty()) throw new AssertionError("Unexpected non-2D command");
        field(helper, "recording").set(null, null); bind.invoke(null, 3553, 123);
        if (gl.getField("texture").getInt(null) != 123) throw new AssertionError("Immediate bind swallowed");
        System.out.println("TEXTURE_BIND_RECORD_PASS vbo=" + enabled + " bindings=" + bindings + " exact GLuint names, disabled/non-2D/native paths");
    }
}
