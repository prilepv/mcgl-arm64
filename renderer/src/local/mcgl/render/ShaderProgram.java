package local.mcgl.render;

import java.util.Set;

/** Context-owned linked program. Native names and uniform locations never escape. */
public interface ShaderProgram extends AutoCloseable {
    String label();
    boolean isClosed();
    Set<String> uniformNames();
    /** Returns null for absent/optimized-out uniforms. */
    ShaderUniform findUniform(String name);
    default ShaderUniform uniform(String name) {
        ShaderUniform uniform = findUniform(name);
        if (uniform == null) throw new IllegalArgumentException("Missing uniform " + name + " in " + label());
        return uniform;
    }
    void bind();
    /** Delete on the render owner; already-closed programs may be closed again. */
    @Override void close();
}
