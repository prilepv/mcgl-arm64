package local.mcgl.render;

/** Binding-side lifecycle. The platform must bind the native context before attach. */
public interface RenderBackend {
    void attach();
    void detach();
    RenderCapabilities capabilities();
    /** Null only when this backend deliberately does not implement the legacy drawing API. */
    LegacyRenderCommands commands();
    /** Optional for historical/injected backends; mandatory for the Core backend. */
    default FrameCommands frameCommands() { return null; }
    default ShaderPipeline shaders() { return null; }
    default MeshPipeline meshes() { return null; }
    boolean insideBeginEnd();
    /** Release Java-owned state only; native GPU objects die with the platform context. */
    void close();
}
