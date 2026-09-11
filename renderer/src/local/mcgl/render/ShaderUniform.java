package local.mcgl.render;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Typed uniform in one linked program. Updates do not require that program to be bound. */
public interface ShaderUniform {
    enum Type { INT, BOOL, SAMPLER_2D, FLOAT, VEC2, VEC3, VEC4, MAT3, MAT4, MAT4_ARRAY, INT_ARRAY, SAMPLER_2D_ARRAY }
    String name();
    Type type();
    void setInt(int value);
    void setFloat(float value);
    void setVec2(float x, float y);
    void setVec3(float x, float y, float z);
    void setVec4(float x, float y, float z, float w);
    /** Exactly one column-major matrix in a native-order direct buffer; position/limit are preserved. */
    void setMatrix3(FloatBuffer value);
    void setMatrix4(FloatBuffer value);
    /** One complete bounded mat4 array, in declaration order. Same buffer rules as a matrix. */
    default void setMatrix4Array(FloatBuffer value) { throw new UnsupportedOperationException("Matrix arrays are not supported by this uniform"); }
    /** One complete bounded plain int array. The native-order direct buffer cursor is preserved. */
    default void setIntArray(IntBuffer value) { throw new UnsupportedOperationException("Integer arrays are not supported by this uniform"); }
}
