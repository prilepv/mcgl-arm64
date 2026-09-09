package local.mcgl.render;

/** Injected on original world chunks so the scheduler hands off stable handles without reflection. */
public interface GameChunkHandle {
    int mcglChunkHandle();
}
