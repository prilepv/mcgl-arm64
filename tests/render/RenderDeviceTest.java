package local.mcgl.render;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Headless tests for the actual device and generated command guards, with no native backend. */
public final class RenderDeviceTest {
    private static int checks;
    private static final Runnable NOOP = () -> {};
    public static void main(String[] args) throws Exception {
        Set<String> features = new HashSet<String>(Arrays.asList("OpenGL13", "test-feature"));
        RenderCapabilities capabilities = new RenderCapabilities("test-vendor", "fake", "test-version", features);
        features.clear();
        check(capabilities.supports("OpenGL13") && !capabilities.supports("OpenGL15"), "capabilities are copied, never guessed");
        try { capabilities.features().clear(); throw new AssertionError("mutable feature snapshot"); }
        catch (UnsupportedOperationException expected) { check(true, "immutable capabilities"); }
        List<FakeBackend> backends = new ArrayList<FakeBackend>();
        RenderDevice device = new RenderDevice(forward -> {
            FakeBackend backend = new FakeBackend(capabilities); backend.forward = forward; backends.add(backend); return backend;
        });
        rejected(device::current, "no initial context");
        Object key = new Object(); int[] lists = {0};
        RenderContext context = device.attach(key, false, () -> lists[0]++);
        FakeBackend backend = backends.get(0);
        check(device.current() == context && device.find(key) == context && backend.attaches == 1, "context registered and attached once");
        check(context.capabilities() == capabilities && !backend.forward, "actual backend facts and request retained");
        check(device.attach(key, false, NOOP) == context && backend.attaches == 1, "idempotent attachment preserves state");
        rejected(() -> device.attach(new Object(), false, NOOP), "explicit detach required before context switch");
        LegacyRenderCommands commands = context.commands();
        commands.GL11_glClear(123); check(backend.draws == 1 && backend.last == 123, "typed command reaches the backend unchanged");
        commands.GL11_glBegin(7); check(device.insideBeginEnd(), "begin/end state belongs to backend");
        commands.GL11_glEnd(); check(!device.insideBeginEnd(), "end state");
        device.recordDisplayList(); check(lists[0] == 1, "legacy profiler callback retained");
        device.completeFrame(900, 700, true); device.completeFrame(0, 0, false);
        check(context.completedFrames() == 2 && context.presentedFrames() == 1 && context.surfaceWidth() == 0,
                "frame boundaries record presentation without changing commands or rejecting minimized surfaces");
        try { device.completeFrame(-1, 700, true); throw new AssertionError("negative surface accepted"); }
        catch (IllegalArgumentException expected) { check(context.completedFrames() == 2, "invalid frame does not commit"); }
        foreign(() -> {
            rejected(() -> commands.GL11_glClear(9), "cached facade rejects foreign use");
            rejected(() -> device.attach(key, false, NOOP), "context cannot be stolen");
            rejected(() -> device.destroy(key), "foreign destruction rejected");
            rejected(device::current, "current context is thread-local");
        });
        check(backend.draws == 1 && backend.closes == 0 && device.current() == context, "foreign operations have no backend side effects");
        device.detach(); device.detach();
        rejected(() -> commands.GL11_glClear(9), "cached facade rejects detached use");
        check(backend.detaches == 1, "idempotent detach");
        foreign(() -> {
            check(device.attach(key, false, NOOP) == context, "detached context can be explicitly handed to a new owner");
            commands.GL11_glClear(456);
            device.detach();
        });
        check(backend.draws == 2 && backend.last == 456, "handoff retains command identity");
        check(device.attach(key, false, NOOP) == context && context.completedFrames() == 2, "reattachment preserves generation and frames");
        device.destroy(key); device.destroy(key);
        check(context.isClosed() && device.find(key) == null && backend.closes == 1, "explicit destruction releases context state once");
        rejected(() -> commands.GL11_glClear(9), "stale facade rejects closed context");
        rejected(device::current, "destroy clears current context");
        RenderContext replacement = device.attach(key, true, NOOP);
        check(replacement != context && replacement.generation() > context.generation() && backends.get(1).forward,
                "replacement context cannot reuse old generation/state");
        device.detach();
        Object other = new Object(); RenderContext otherContext = device.attach(other, false, NOOP);
        device.destroy(key);
        check(device.current() == otherContext && backends.get(2).detaches == 0, "closing a detached old context does not detach another context");
        device.destroy(other);
        failures(capabilities);
        // Merely selecting the production composition root must not initialize LWJGL/native libraries.
        check(RenderSystem.find(new Object()) == null, "production selection stays lazy");
        System.out.println("RENDER_DEVICE_PASS checks=" + checks + " ownership/lifecycle/generations/frame-boundaries/failure-rollback/no-native");
    }
    private static void failures(RenderCapabilities capabilities) {
        List<FakeBackend> backends = new ArrayList<FakeBackend>();
        RenderDevice device = new RenderDevice(forward -> {
            FakeBackend backend = new FakeBackend(capabilities);
            if (backends.isEmpty()) { backend.failAttach = true; backend.failDetach = true; backend.failClose = true; }
            backends.add(backend); return backend;
        });
        Object key = new Object();
        try { device.attach(key, false, NOOP); throw new AssertionError("failed binding accepted"); }
        catch (IllegalStateException expected) {
            check(expected.getMessage().equals("attach-failure") && expected.getSuppressed().length == 2,
                    "original binding failure retained with cleanup failures suppressed");
        }
        check(device.find(key) == null && backends.get(0).closes == 1 && backends.get(0).detaches == 1,
                "failed construction publishes no half-created context");
        rejected(device::current, "failed attachment leaves no current context");
        RenderContext context = device.attach(key, false, NOOP); FakeBackend backend = backends.get(1);
        device.detach(); backend.failAttach = true;
        rejected(() -> device.attach(key, false, NOOP), "reattachment failure propagated");
        rejected(() -> context.commands().GL11_glClear(1), "failed reattachment retains no owner");
        check(backend.detaches == 2 && device.find(key) == context, "failed reattachment unwinds without destroying retained resources");
        backend.failAttach = false; device.attach(key, false, NOOP);
        backend.failDetach = true; backend.failClose = true;
        try { device.destroy(key); throw new AssertionError("failed cleanup swallowed"); }
        catch (IllegalStateException expected) {
            check(expected.getMessage().equals("detach-failure") && expected.getSuppressed().length == 1,
                    "destroy preserves first cleanup failure");
        }
        check(context.isClosed() && device.find(key) == null, "failed cleanup still invalidates stale Java handles");
        rejected(device::current, "failed cleanup clears current context");
        RenderDevice missing = new RenderDevice(forward -> new FakeBackend(null));
        try { missing.attach(key, false, NOOP); throw new AssertionError("missing capabilities accepted"); }
        catch (NullPointerException expected) { check(missing.find(key) == null, "incomplete backend rejected before publication"); }
    }
    private static final class FakeBackend implements RenderBackend {
        final RenderCapabilities capabilities; final LegacyRenderCommands commands;
        int attaches, detaches, closes, draws, last;
        boolean forward, begin, failAttach, failDetach, failClose;
        FakeBackend(RenderCapabilities capabilities) {
            this.capabilities = capabilities;
            commands = (LegacyRenderCommands)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {LegacyRenderCommands.class},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "GL11_glClear": draws++; last = (Integer)arguments[0]; break;
                            case "GL11_glBegin": begin = true; break;
                            case "GL11_glEnd": begin = false; break;
                            default: throw new AssertionError("unexpected test command " + method.getName());
                        }
                        return null;
                    });
        }
        public void attach() { attaches++; if (failAttach) throw new IllegalStateException("attach-failure"); }
        public void detach() { detaches++; if (failDetach) throw new IllegalStateException("detach-failure"); }
        public void close() { closes++; if (failClose) throw new IllegalStateException("close-failure"); }
        public RenderCapabilities capabilities() { return capabilities; }
        public LegacyRenderCommands commands() { return commands; }
        public boolean insideBeginEnd() { return begin; }
    }
    private static void foreign(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable problem) { failure.set(problem); } }, "renderer ownership test");
        thread.start(); thread.join(2500);
        check(!thread.isAlive() && failure.get() == null, "foreign test completes: " + failure.get());
    }
    private static void rejected(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (IllegalStateException expected) { check(true, message); }
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
}
