package local.mcgl.render;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Owner/generation checks wrap programs and uniforms as well as the factory. */
final class GuardedShaderPipeline implements ShaderPipeline {
    private final RenderContext context;
    private final ShaderPipeline delegate;
    GuardedShaderPipeline(RenderContext context, ShaderPipeline delegate) {
        this.context = context; this.delegate = delegate;
    }
    public ShaderProgram create(ShaderSources sources) {
        context.checkOwner();
        if (sources == null) throw new NullPointerException("shader sources");
        ShaderProgram program = delegate.create(sources);
        if (program == null) throw new IllegalStateException("Backend did not create a shader program");
        try { return new Program(program); }
        catch (Throwable failure) {
            try { program.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public void unbind() { context.checkOwner(); delegate.unbind(); }

    private final class Program implements ShaderProgram {
        private final ShaderProgram target;
        private final Map<String, ShaderUniform> uniforms = new HashMap<String, ShaderUniform>();
        Program(ShaderProgram target) { this.target = target; }
        private void check() {
            context.checkOwner();
            if (target.isClosed()) throw new IllegalStateException("Shader program is closed: " + target.label());
        }
        public String label() { return target.label(); }
        public boolean isClosed() { return context.isClosed() || target.isClosed(); }
        public Set<String> uniformNames() { check(); return target.uniformNames(); }
        public ShaderUniform findUniform(String name) {
            check();
            if (name == null || name.isEmpty() || name.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid uniform name");
            ShaderUniform cached = uniforms.get(name);
            if (cached != null) return cached;
            ShaderUniform found = target.findUniform(name);
            if (found == null) return null;
            ShaderUniform guarded = new Uniform(found);
            uniforms.put(name, guarded);
            return guarded;
        }
        public void bind() { check(); target.bind(); }
        public void close() {
            if (isClosed()) return;
            context.checkOwner(); target.close(); uniforms.clear();
        }
        private final class Uniform implements ShaderUniform {
            private final ShaderUniform targetUniform;
            Uniform(ShaderUniform targetUniform) { this.targetUniform = targetUniform; }
            public String name() { return targetUniform.name(); }
            public Type type() { return targetUniform.type(); }
            public void setInt(int value) { check(); targetUniform.setInt(value); }
            public void setFloat(float value) { check(); targetUniform.setFloat(value); }
            public void setVec2(float x, float y) { check(); targetUniform.setVec2(x, y); }
            public void setVec3(float x, float y, float z) { check(); targetUniform.setVec3(x, y, z); }
            public void setVec4(float x, float y, float z, float w) { check(); targetUniform.setVec4(x, y, z, w); }
            public void setMatrix3(FloatBuffer value) { check(); targetUniform.setMatrix3(value); }
            public void setMatrix4(FloatBuffer value) { check(); targetUniform.setMatrix4(value); }
            public void setMatrix4Array(FloatBuffer value) { check(); targetUniform.setMatrix4Array(value); }
        }
    }
}
