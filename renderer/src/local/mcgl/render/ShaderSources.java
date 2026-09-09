package local.mcgl.render;

/** Immutable, bounded shader inputs. Compilation happens on the owning render context. */
public final class ShaderSources {
    public final String label, vertex, fragment;
    public ShaderSources(String label, String vertex, String fragment) {
        if (label == null || !label.matches("[A-Za-z0-9_./-]{1,128}"))
            throw new IllegalArgumentException("Invalid shader label");
        this.label = label; this.vertex = source(vertex); this.fragment = source(fragment);
    }
    private static String source(String value) {
        if (value == null || value.isEmpty() || value.length() > 1024 * 1024 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Shader source must contain 1..1048576 characters, without NUL");
        return value;
    }
}
