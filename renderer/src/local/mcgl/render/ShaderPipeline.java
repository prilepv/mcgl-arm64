package local.mcgl.render;

/** Core program factory; creating/linking a program does not bind it or change another program. */
public interface ShaderPipeline {
    ShaderProgram create(ShaderSources sources);
    void unbind();
}
