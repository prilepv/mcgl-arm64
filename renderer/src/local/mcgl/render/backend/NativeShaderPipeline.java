package local.mcgl.render.backend;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ByteOrder;
import java.util.*;
import local.mcgl.render.*;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.system.MemoryStack;

/** Core 4.1 monolithic programs with transactional compilation and program-targeted uniforms. */
final class NativeShaderPipeline implements ShaderPipeline {
    private static final int LOG_LIMIT = 8192;
    private final Set<Program> programs = new HashSet<Program>();
    private boolean closed;

    private void checkAlive() {
        if (closed) throw new IllegalStateException("Shader backend is closed");
    }
    public ShaderProgram create(ShaderSources sources) {
        checkAlive();
        int vertex = 0, fragment = 0, program = 0;
        boolean vertexAttached = false, fragmentAttached = false, published = false;
        try {
            vertex = compile(sources.label, "vertex", GL20C.GL_VERTEX_SHADER, sources.vertex);
            fragment = compile(sources.label, "fragment", GL20C.GL_FRAGMENT_SHADER, sources.fragment);
            program = GL20C.glCreateProgram();
            if (program == 0) throw failure(sources.label, "link", "glCreateProgram returned zero");
            GL20C.glAttachShader(program, vertex); vertexAttached = true;
            GL20C.glAttachShader(program, fragment); fragmentAttached = true;
            GL20C.glLinkProgram(program);
            String log = GL20C.glGetProgramInfoLog(program, LOG_LIMIT);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE)
                throw failure(sources.label, "link", log);
            warning(sources.label, "link", log);
            Program result = new Program(program, sources.label);
            programs.add(result);
            published = true;
            return result;
        } finally {
            if (vertexAttached) GL20C.glDetachShader(program, vertex);
            if (fragmentAttached) GL20C.glDetachShader(program, fragment);
            if (vertex != 0) GL20C.glDeleteShader(vertex);
            if (fragment != 0) GL20C.glDeleteShader(fragment);
            if (!published && program != 0) GL20C.glDeleteProgram(program);
        }
    }
    private static int compile(String label, String stage, int kind, String source) {
        int shader = GL20C.glCreateShader(kind);
        if (shader == 0) throw failure(label, stage, "glCreateShader returned zero");
        boolean compiled = false;
        try {
            GL20C.glShaderSource(shader, source);
            GL20C.glCompileShader(shader);
            String log = GL20C.glGetShaderInfoLog(shader, LOG_LIMIT);
            if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE)
                throw failure(label, stage, log);
            warning(label, stage, log);
            compiled = true;
            return shader;
        } finally {
            if (!compiled) GL20C.glDeleteShader(shader);
        }
    }
    private static IllegalStateException failure(String label, String stage, String log) {
        return new IllegalStateException("Shader " + label + " failed at " + stage + ": " + log);
    }
    private static void warning(String label, String stage, String log) {
        if (!log.trim().isEmpty()) System.err.println("[MCGL Shader] " + label + " " + stage + ": " + log);
    }
    public void unbind() { checkAlive(); GL20C.glUseProgram(0); }

    /** No GL here: RenderDevice closes after detachment, then the platform destroys the context. */
    void abandon() {
        closed = true;
        for (Program program : programs) program.abandon();
        programs.clear();
    }

    private final class Program implements ShaderProgram {
        private final int id;
        private final String label;
        private final Map<String, ShaderUniform> uniforms = new HashMap<String, ShaderUniform>();
        private final Set<String> uniformNames;
        private volatile boolean deleted;
        Program(int id, String label) {
            this.id = id; this.label = label;
            int count = GL20C.glGetProgrami(id, GL20C.GL_ACTIVE_UNIFORMS);
            int nameLength = GL20C.glGetProgrami(id, GL20C.GL_ACTIVE_UNIFORM_MAX_LENGTH);
            if (count < 0 || count > 4096 || nameLength < 0 || nameLength > 4096)
                throw failure(label, "uniform reflection", "Uniform metadata exceeds supported bounds");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer size = stack.mallocInt(1), type = stack.mallocInt(1);
                for (int index = 0; index < count; index++) {
                    String name = GL20C.glGetActiveUniform(id, index, nameLength, size, type);
                    int length=size.get(0);boolean array=length!=1||name.indexOf('[')>=0;
                    if(array&&((type.get(0)!=GL20C.GL_FLOAT_MAT4&&type.get(0)!=GL11C.GL_INT)||length<1||length>((name.equals("uOriginalModelPalette[0]")||name.equals("uOriginalInputPalette[0]"))?128:32)||!name.matches("[A-Za-z_][A-Za-z_0-9]*\\[0\\]")))
                        throw failure(label, "uniform reflection", "Only bounded plain mat4/int arrays are supported: " + name);
                    int location = GL20C.glGetUniformLocation(id, name);
                    if (location < 0)
                        throw failure(label, "uniform reflection", "Uniform blocks are not supported by this contract: " + name);
                    ShaderUniform.Type mappedType;
                    try { mappedType = uniformType(type.get(0)); }
                    catch (IllegalArgumentException problem) { throw failure(label, "uniform reflection", name + ": " + problem.getMessage()); }
                    if(array){name=name.substring(0,name.length()-3);mappedType=type.get(0)==GL11C.GL_INT?ShaderUniform.Type.INT_ARRAY:ShaderUniform.Type.MAT4_ARRAY;}
                    uniforms.put(name, new Uniform(name, location, mappedType,length));
                }
            }
            uniformNames = Collections.unmodifiableSet(new TreeSet<String>(uniforms.keySet()));
        }
        private void check() {
            checkAlive();
            if (deleted) throw new IllegalStateException("Shader program is closed: " + label);
        }
        public String label() { return label; }
        public boolean isClosed() { return deleted; }
        public Set<String> uniformNames() { check(); return uniformNames; }
        public ShaderUniform findUniform(String name) { check(); return uniforms.get(name); }
        public void bind() { check(); GL20C.glUseProgram(id); }
        public void close() {
            if (deleted) return;
            checkAlive();
            if (GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) == id) GL20C.glUseProgram(0);
            GL20C.glDeleteProgram(id);
            abandon(); programs.remove(this);
        }
        private void abandon() { deleted = true; uniforms.clear(); }

        private final class Uniform implements ShaderUniform {
            private final String name;
            private final int location;
            private final Type type;
            // Only this owned uniform can write its program/location. Retain values per program,
            // including while unbound, so repeated material binds do not resend unchanged inputs.
            private boolean initialized;
            private int integer;
            private final int[] bits;
            private final int length;
            Uniform(String name, int location, Type type,int length) {
                this.name = name; this.location = location; this.type = type;
                this.length=length;bits=new int[type==Type.MAT4_ARRAY?length*16:type==Type.INT_ARRAY?length:16];
            }
            public String name() { return name; }
            public Type type() { return type; }
            private void require(Type expected) {
                check();
                if (type != expected) throw new IllegalArgumentException("Uniform " + name + " is " + type + ", not " + expected);
            }
            public void setInt(int value) {
                check();
                if (type != Type.INT && type != Type.BOOL && type != Type.SAMPLER_2D && type != Type.SAMPLER_2D_ARRAY)
                    throw new IllegalArgumentException("Uniform " + name + " is not an integer/bool/sampler");
                if ((type == Type.BOOL && value != 0 && value != 1) || ((type == Type.SAMPLER_2D || type == Type.SAMPLER_2D_ARRAY) && value < 0))
                    throw new IllegalArgumentException("Invalid bool/sampler value for " + name);
                if (initialized && integer == value) return;
                GL41C.glProgramUniform1i(id, location, value);
                integer = value; initialized = true;
            }
            public void setFloat(float value) { require(Type.FLOAT); if (!changed(1,value,0,0,0)) return; GL41C.glProgramUniform1f(id, location, value); }
            public void setVec2(float x, float y) { require(Type.VEC2); if (!changed(2,x,y,0,0)) return; GL41C.glProgramUniform2f(id, location, x, y); }
            public void setVec3(float x, float y, float z) { require(Type.VEC3); if (!changed(3,x,y,z,0)) return; GL41C.glProgramUniform3f(id, location, x, y, z); }
            public void setVec4(float x, float y, float z, float w) { require(Type.VEC4); if (!changed(4,x,y,z,w)) return; GL41C.glProgramUniform4f(id, location, x, y, z, w); }
            public void setMatrix3(FloatBuffer value) {
                require(Type.MAT3); matrix(value, 9); if (!changed(value)) return; GL41C.glProgramUniformMatrix3fv(id, location, false, value);
            }
            public void setMatrix4(FloatBuffer value) {
                require(Type.MAT4); matrix(value, 16); if (!changed(value)) return; GL41C.glProgramUniformMatrix4fv(id, location, false, value);
            }
            public void setMatrix4Array(FloatBuffer value) {
                require(Type.MAT4_ARRAY);matrix(value,Math.multiplyExact(length,16));if(!changed(value))return;GL41C.glProgramUniformMatrix4fv(id,location,false,value);
            }
            public void setIntArray(IntBuffer value) {
                require(Type.INT_ARRAY);
                if(value==null||!value.isDirect()||value.order()!=ByteOrder.nativeOrder()||value.remaining()!=length)
                    throw new IllegalArgumentException("Expected a native-order direct buffer containing exactly "+length+" integers");
                boolean changed=!initialized;
                for(int i=0;i<length;i++){int next=value.get(value.position()+i);if(bits[i]!=next)changed=true;bits[i]=next;}
                initialized=true;if(changed)GL41C.glProgramUniform1iv(id,location,value);
            }
            private boolean changed(int size,float x,float y,float z,float w) {
                int a=Float.floatToRawIntBits(x),b=Float.floatToRawIntBits(y),c=Float.floatToRawIntBits(z),d=Float.floatToRawIntBits(w);
                if(initialized&&bits[0]==a&&(size<2||bits[1]==b)&&(size<3||bits[2]==c)&&(size<4||bits[3]==d))return false;
                bits[0]=a;bits[1]=b;bits[2]=c;bits[3]=d;initialized=true;return true;
            }
            private boolean changed(FloatBuffer value) {
                boolean changed=!initialized;
                for(int i=0;i<value.remaining();i++){int next=Float.floatToRawIntBits(value.get(value.position()+i));if(bits[i]!=next)changed=true;bits[i]=next;}
                initialized=true;return changed;
            }
        }
    }
    private static void matrix(FloatBuffer value, int elements) {
        if (value == null || !value.isDirect() || value.order() != ByteOrder.nativeOrder() || value.remaining() != elements)
            throw new IllegalArgumentException("Expected a native-order direct buffer containing exactly " + elements + " matrix floats");
    }
    private static ShaderUniform.Type uniformType(int type) {
        switch (type) {
            case GL11C.GL_INT: return ShaderUniform.Type.INT;
            case GL20C.GL_BOOL: return ShaderUniform.Type.BOOL;
            case GL20C.GL_SAMPLER_2D: return ShaderUniform.Type.SAMPLER_2D;
            case org.lwjgl.opengl.GL30C.GL_SAMPLER_2D_ARRAY: return ShaderUniform.Type.SAMPLER_2D_ARRAY;
            case GL11C.GL_FLOAT: return ShaderUniform.Type.FLOAT;
            case GL20C.GL_FLOAT_VEC2: return ShaderUniform.Type.VEC2;
            case GL20C.GL_FLOAT_VEC3: return ShaderUniform.Type.VEC3;
            case GL20C.GL_FLOAT_VEC4: return ShaderUniform.Type.VEC4;
            case GL20C.GL_FLOAT_MAT3: return ShaderUniform.Type.MAT3;
            case GL20C.GL_FLOAT_MAT4: return ShaderUniform.Type.MAT4;
            default: throw new IllegalArgumentException("Unsupported shader uniform type: " + type);
        }
    }
}
