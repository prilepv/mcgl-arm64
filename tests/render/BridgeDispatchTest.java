import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import local.mcgl.render.*;
import org.objectweb.asm.Type;

/** Execute every built adapter and command guard through an injected test-only composition root. */
public final class BridgeDispatchTest {
    private static int checks, invocations;
    private static RenderCommandSpec expected;
    private static Object[] expectedArguments;
    private static Object expectedResult;
    private static RuntimeException failure;
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("manifest candidate-lwjgl.jar fixture-classes");
        LegacyRenderCommands fake = (LegacyRenderCommands)java.lang.reflect.Proxy.newProxyInstance(BridgeDispatchTest.class.getClassLoader(),
                new Class<?>[] {LegacyRenderCommands.class}, (proxy, method, arguments) -> {
                    invocations++;
                    check(method.getName().equals(expected.dispatch) && Type.getMethodDescriptor(method).equals(expected.descriptor), "exact dispatched signature");
                    Object[] actual = arguments == null ? new Object[0] : arguments;
                    check(actual.length == expectedArguments.length, "argument count");
                    for (int i = 0; i < actual.length; i++) {
                        if (expected.arguments[i].getSort() == Type.OBJECT || expected.arguments[i].getSort() == Type.ARRAY)
                            check(actual[i] == expectedArguments[i], "buffer/string/array identity and argument order");
                        else check(Objects.equals(actual[i], expectedArguments[i]), "primitive bits/order/value");
                    }
                    if (failure != null) throw failure;
                    return expectedResult;
                });
        RenderDevice device = new RenderDevice(forward -> new RenderBackend() {
            public void attach() {} public void detach() {} public void close() {}
            public RenderCapabilities capabilities() { return new RenderCapabilities("test", "fake", "test", Collections.emptySet()); }
            public LegacyRenderCommands commands() { return fake; }
            public boolean insideBeginEnd() { return false; }
        });
        Object key = new Object(); RenderContext context = device.attach(key, false, () -> {});
        URL[] urls = {Paths.get(args[2]).toUri().toURL(), Paths.get(args[1]).toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, BridgeDispatchTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("local.mcgl.render.RenderSystem") || name.startsWith("local.mcgl.render.legacy.")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> root = Class.forName("local.mcgl.render.RenderSystem", true, loader);
            root.getField("target").set(null, context.commands());
            Collection<RenderCommandSpec> commands = RenderCommandSpec.read(Paths.get(args[0])).values();
            for (RenderCommandSpec command : commands) {
                expected = command; expectedArguments = values(command.arguments); expectedResult = value(command.result);
                Method method = method(loader, command);
                int before = invocations;
                Object result = method.invoke(null, expectedArguments);
                check(invocations == before + 1, "one backend call per legacy call");
                if (command.result.getSort() == Type.OBJECT || command.result.getSort() == Type.ARRAY)
                    check(result == expectedResult, "unchanged reference return");
                else check(Objects.equals(result, expectedResult), "unchanged primitive/void return");
                for (Object argument : expectedArguments) if (argument instanceof Buffer) {
                    Buffer buffer = (Buffer)argument;
                    check(buffer.position() == 1 && buffer.limit() == buffer.capacity() - 1, "adapter preserves buffer position/limit");
                }
            }
            check(root.getField("recordedLists").getInt(null) == 1, "display-list profiler event retained exactly once");
            expected = RenderCommandSpec.read(Paths.get(args[0])).get("org/lwjgl/opengl/GL11 glClear (I)V");
            expectedArguments = values(expected.arguments); expectedResult = null;
            failure = new IllegalArgumentException("backend-sentinel");
            try { method(loader, expected).invoke(null, expectedArguments); throw new AssertionError("backend failure swallowed"); }
            catch (InvocationTargetException propagated) { check(propagated.getCause() == failure, "backend exception identity retained"); }
            failure = null;
            device.detach(); int before = invocations;
            for (RenderCommandSpec command : commands) {
                try { method(loader, command).invoke(null, values(command.arguments)); throw new AssertionError("detached command accepted"); }
                catch (InvocationTargetException rejected) { check(rejected.getCause() instanceof IllegalStateException, "every command guard rejects detached use"); }
            }
            check(invocations == before, "all detached adapters rejected before backend call");
            device.destroy(key);
        }
        System.out.println("RENDER_BRIDGE_DISPATCH_PASS checks=" + checks + " commands=179 exact-signatures/arguments/returns/buffers/guards/exceptions/no-native");
    }
    private static Method method(ClassLoader loader, RenderCommandSpec command) throws Exception {
        Class<?>[] types = new Class<?>[command.arguments.length];
        for (int i = 0; i < types.length; i++) types[i] = javaType(command.arguments[i]);
        return Class.forName(command.bridge.replace('/', '.'), true, loader).getMethod(command.name, types);
    }
    private static Class<?> javaType(Type type) throws ClassNotFoundException {
        switch (type.getSort()) {
            case Type.VOID: return void.class; case Type.BOOLEAN: return boolean.class; case Type.BYTE: return byte.class;
            case Type.CHAR: return char.class; case Type.SHORT: return short.class; case Type.INT: return int.class;
            case Type.LONG: return long.class; case Type.FLOAT: return float.class; case Type.DOUBLE: return double.class;
            case Type.ARRAY: return Class.forName(type.getDescriptor().replace('/', '.'));
            default: return Class.forName(type.getClassName());
        }
    }
    private static Object[] values(Type[] types) throws Exception {
        Object[] result = new Object[types.length];
        for (int i = 0; i < result.length; i++) result[i] = value(types[i]);
        // Distinct values catch argument permutation; keep a negative zero in the first float slot.
        for (int i = 0; i < result.length; i++) switch (types[i].getSort()) {
            case Type.INT: result[i] = 91 + i; break;
            case Type.FLOAT: result[i] = i == 0 ? -0.0f : (float)(i + 0.25); break;
            case Type.DOUBLE: result[i] = -91.125d - i; break;
            case Type.LONG: result[i] = 0x12345678ABCDEF01L + i; break;
            case Type.BYTE: result[i] = (byte)(-7 + i); break;
            case Type.SHORT: result[i] = (short)(-301 + i); break;
            case Type.CHAR: result[i] = (char)('\u0444' + i); break;
            case Type.BOOLEAN: result[i] = (i & 1) == 0; break;
            default: break;
        }
        return result;
    }
    private static Object value(Type type) throws Exception {
        switch (type.getSort()) {
            case Type.VOID: return null; case Type.BOOLEAN: return true; case Type.BYTE: return (byte)-7;
            case Type.CHAR: return '\u0444'; case Type.SHORT: return (short)-301; case Type.INT: return -1789;
            case Type.LONG: return 0x12345678ABCDEF01L; case Type.FLOAT: return -0.0f; case Type.DOUBLE: return -91.125d;
            case Type.ARRAY: return Array.newInstance(javaType(type.getElementType()), 2);
            default:
                if (type.getClassName().equals("java.lang.String")) return new String("renderer-test");
                if (type.getClassName().equals("java.lang.CharSequence")) return new StringBuilder("renderer-test");
                ByteBuffer storage = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
                Buffer buffer;
                switch (type.getClassName()) {
                    case "java.nio.ByteBuffer": buffer = storage; break;
                    case "java.nio.ShortBuffer": buffer = storage.asShortBuffer(); break;
                    case "java.nio.IntBuffer": buffer = storage.asIntBuffer(); break;
                    case "java.nio.LongBuffer": buffer = storage.asLongBuffer(); break;
                    case "java.nio.FloatBuffer": buffer = storage.asFloatBuffer(); break;
                    case "java.nio.DoubleBuffer": buffer = storage.asDoubleBuffer(); break;
                    default: throw new AssertionError("unsupported test type " + type);
                }
                buffer.position(1); buffer.limit(buffer.capacity() - 1); return buffer;
        }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
