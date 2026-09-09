package local.mcgl.render;

import java.util.Map;
import java.util.WeakHashMap;

/** Context registry and explicit render-thread ownership, independent of the host or GPU API. */
public final class RenderDevice {
    public interface Factory { RenderBackend create(boolean forwardCompatible); }
    public interface ProfileFactory { RenderBackend create(RenderProfile profile, boolean forwardCompatible); }
    private final ProfileFactory factory;
    private final Map<Object, RenderContext> contexts = new WeakHashMap<Object, RenderContext>();
    private final ThreadLocal<RenderContext> current = new ThreadLocal<RenderContext>();
    private long nextGeneration;

    public RenderDevice(Factory factory) {
        if (factory == null) throw new NullPointerException("render backend factory");
        this.factory = (profile, forward) -> {
            if (profile != RenderProfile.COMPATIBILITY_21)
                throw new IllegalArgumentException("This factory only supplies compatibility rendering");
            return factory.create(forward);
        };
    }
    public RenderDevice(ProfileFactory factory) {
        if (factory == null) throw new NullPointerException("render backend factory");
        this.factory = factory;
    }
    public synchronized RenderContext attach(Object key, boolean forwardCompatible, Runnable displayListRecorder) {
        return attach(key, RenderProfile.COMPATIBILITY_21, forwardCompatible, displayListRecorder);
    }
    public synchronized RenderContext attach(Object key, RenderProfile profile, boolean forwardCompatible,
                                              Runnable displayListRecorder) {
        if (key == null || profile == null || displayListRecorder == null) throw new NullPointerException("render context arguments");
        if (profile == RenderProfile.CORE_41 && !forwardCompatible)
            throw new IllegalArgumentException("macOS Core 4.1 requires forward compatibility");
        RenderContext context = contexts.get(key), previous = current.get();
        if (context != null && context.profile() != profile)
            throw new IllegalStateException("A context generation cannot change render profile");
        if (previous != null) {
            if (previous != context) throw new IllegalStateException("Detach current render context before switching contexts");
            previous.checkOwner();
            return previous;
        }
        if (context == null) {
            RenderBackend backend = factory.create(profile, forwardCompatible);
            if (backend == null) throw new NullPointerException("render backend");
            try {
                backend.attach();
                context = new RenderContext(backend, profile, ++nextGeneration, displayListRecorder);
                contexts.put(key, context);
            } catch (Throwable failure) {
                try { backend.detach(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                try { backend.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } else {
            context.checkAvailable();
            try { context.attach(); }
            catch (Throwable failure) {
                // A failed binding must not leave this thread with half-attached GPU bindings.
                try { context.backendDetachAfterFailure(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        current.set(context);
        return context;
    }
    public synchronized void detach() {
        RenderContext context = current.get();
        if (context == null) return;
        try { context.detach(); } finally { current.remove(); }
    }
    public synchronized void destroy(Object key) {
        if (key == null) throw new NullPointerException("render context key");
        RenderContext context = contexts.get(key);
        if (context == null) return;
        context.checkAvailable();
        Throwable failure = null;
        if (current.get() == context) {
            try { detach(); } catch (Throwable problem) { failure = problem; }
        }
        try { context.close(); }
        catch (Throwable problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
        finally { contexts.remove(key); }
        if (failure != null) {
            if (failure instanceof RuntimeException) throw (RuntimeException)failure;
            if (failure instanceof Error) throw (Error)failure;
            throw new IllegalStateException("Render context cleanup failed", failure);
        }
    }
    public RenderContext current() {
        RenderContext context = current.get();
        if (context == null) throw new IllegalStateException("No render context is current");
        context.checkOwner();
        return context;
    }
    public synchronized RenderContext find(Object key) { return contexts.get(key); }
    public void completeFrame(int width, int height, boolean presented) { current().completeFrame(width, height, presented); }
    public boolean insideBeginEnd() { return current().insideBeginEnd(); }
    public void recordDisplayList() { current().recordDisplayList(); }
}
