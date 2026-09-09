package local.mcgl.render;

/** Added to the original accumulator. Binding is explicit and only legal between begin/draw batches. */
public interface ChunkTessellator {
    /** Null restores ordinary drawing. While bound, explicit draws AND capacity flushes stay on CPU. */
    void mcglBindChunkBatch(ChunkMeshBuilder.Sink sink);
    /** Discard the unfinished batch and unbind after a failed build; never submits native work. */
    void mcglAbortChunkBatch();
}
