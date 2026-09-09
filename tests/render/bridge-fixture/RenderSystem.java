package local.mcgl.render;

/** Test-only composition root loaded with the REAL generated adapters in a child loader. */
public final class RenderSystem {
    public static LegacyRenderCommands target;
    public static int recordedLists;
    public static LegacyRenderCommands commands() { return target; }
    public static void recordDisplayList() { recordedLists++; }
}
