package local.mcgl.render.tests;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.jar.*;
import local.mcgl.render.*;

/** Test-only access to ORIGINAL client emission methods. Production uses the typed injected sink API. */
public final class OriginalChunkEmitter {
    private final Object value;
    private final Class<?> type;
    public final ChunkTessellator adapter;
    public OriginalChunkEmitter(File jarPath, int capacityWords) throws Exception {
        String name = null;
        try (JarFile jar = new JarFile(jarPath)) {
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
                String entry = entries.nextElement().getName();
                if (entry.matches("net/A/for/oOOOoO+\\.class")) {
                    if (name != null) throw new AssertionError("ambiguous accumulator");
                    name = entry.substring(0, entry.length() - 6).replace('/', '.');
                }
            }
        }
        if (name == null) throw new AssertionError("missing accumulator");
        type = Class.forName(name);
        Constructor<?> constructor = type.getDeclaredConstructor(int.class); constructor.setAccessible(true);
        value = constructor.newInstance(capacityWords); adapter = (ChunkTessellator)value;
    }
    public void bind(ChunkMeshBuilder builder, ChunkMaterial material) {
        adapter.mcglBindChunkBatch(builder.sink(material, -1, 0, 0, 0x007f0000, 0));
    }
    public void begin() { call("return", new Class<?>[0]); }
    public void beginMode(int mode) { call("Ó00000", new Class<?>[] {int.class}, mode); }
    public int draw() { return (Integer)call("new", new Class<?>[0]); }
    public void unbind() { adapter.mcglBindChunkBatch(null); }
    public void color(int r, int g, int b, int a) { call("o00000", new Class<?>[] {int.class, int.class, int.class, int.class}, r, g, b, a); }
    public void uv(double u, double v) { call("o00000", new Class<?>[] {double.class, double.class}, u, v); }
    public void lightmap(int value) { call("o00000", new Class<?>[] {int.class}, value); }
    public void normal(float x, float y, float z) { call("Ô00000", new Class<?>[] {float.class, float.class, float.class}, x, y, z); }
    public void translate(double x, double y, double z) { call("o00000", new Class<?>[] {double.class, double.class, double.class}, x, y, z); }
    public void vertex(double x, double y, double z) { call("new", new Class<?>[] {double.class, double.class, double.class}, x, y, z); }
    public void cube(float x, float y, float z) { call("new", new Class<?>[] {float.class, float.class, float.class}, x, y, z); }
    public void sort(double x,double y,double z){call("Ó00000",new Class<?>[]{double.class,double.class,double.class},x,y,z);}
    public int[] snapshot(){return (int[])call("Õ00000",new Class<?>[0]);}
    public void restore(int[] data){call("o00000",new Class<?>[]{int[].class},(Object)data);}
    public void quad(double x0, double y0, double x1, double y1, double z) {
        uv(0, 0); vertex(x0, y0, z); uv(1, 0); vertex(x1, y0, z);
        uv(1, 1); vertex(x1, y1, z); uv(0, 1); vertex(x0, y1, z);
    }
    public int[] raw() {
        try { Field field = type.getDeclaredField("Õ00000"); field.setAccessible(true); return ((int[])field.get(value)).clone(); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private Object call(String name, Class<?>[] signature, Object... arguments) {
        try { return type.getMethod(name, signature).invoke(value, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException)cause;
            if (cause instanceof Error) throw (Error)cause;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
