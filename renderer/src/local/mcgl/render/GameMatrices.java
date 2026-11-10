package local.mcgl.render;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Arrays;

/** CPU transforms at the original game boundary; column-major, post-multiplied. No GL state. */
public final class GameMatrices {
    private final Deque<double[]> model = stack(), projection = stack();
    @SuppressWarnings("unchecked")
    private final Deque<double[]>[] textures = new Deque[] { stack(), stack(), stack(), stack() };
    private int mode = 5888, textureUnit;
    private final double[] operand = new double[16], product = new double[16];
    private final Deque<double[]> spare = new ArrayDeque<double[]>();

    private static Deque<double[]> stack() {
        Deque<double[]> result = new ArrayDeque<double[]>(); result.push(identity()); return result;
    }
    public static double[] identity() {
        double[] result = new double[16]; identity(result); return result;
    }
    private static void identity(double[] result) { Arrays.fill(result, 0); for (int i = 0; i < 4; i++) result[i * 5] = 1; }
    public void mode(int value) {
        if (value != 5888 && value != 5889 && value != 5890) throw new IllegalArgumentException("Matrix mode: " + value);
        mode = value;
    }
    public int mode() { return mode; }
    public void textureUnit(int value) {
        if (value < 0 || value >= textures.length) throw new IllegalArgumentException("Texture matrix unit: " + value);
        textureUnit = value;
    }
    private Deque<double[]> current() { return mode == 5888 ? model : mode == 5889 ? projection : textures[textureUnit]; }
    public void push() {
        Deque<double[]> stack = current();
        if (stack.size() >= 64) throw new IllegalStateException("Game matrix stack overflow");
        double[] value = spare.isEmpty() ? new double[16] : spare.pop();
        System.arraycopy(stack.peek(), 0, value, 0, 16); stack.push(value);
    }
    public void pop() {
        if (current().size() <= 1) throw new IllegalStateException("Game matrix stack underflow");
        double[] value = current().pop(); if (spare.size() < 64) spare.push(value);
    }
    public void loadIdentity() { identity(current().peek()); }
    public void load(double[] matrix) {
        validate(matrix); System.arraycopy(matrix, 0, current().peek(), 0, 16);
    }
    public void multiply(double[] matrix) {
        double[] top = current().peek(); validate(top); validate(matrix);
        multiply(top, matrix, product); System.arraycopy(product, 0, top, 0, 16);
    }
    public double[] modelView() { return model.peek().clone(); }
    public double[] projection() { return projection.peek().clone(); }
    /** Copies into caller-owned storage; public snapshots remain independent arrays. */
    public void copyModelView(double[] target) { copy(model.peek(), target); }
    public void copyProjection(double[] target) { copy(projection.peek(), target); }
    public void copyTexture(int unit, double[] target) {
        if (unit < 0 || unit >= textures.length) throw new IllegalArgumentException("Texture matrix unit");
        copy(textures[unit].peek(), target);
    }
    private static void copy(double[] source, double[] target) {
        if (target == null || target.length != 16) throw new IllegalArgumentException("Expected mat4 destination");
        System.arraycopy(source, 0, target, 0, 16);
    }
    public Snapshot snapshot() { return new Snapshot(this); }
    public static final class Snapshot {
        private final double[][] model, projection;
        private final double[][][] textures = new double[4][][];
        private final int mode, unit;
        private Snapshot(GameMatrices source) {
            model = copy(source.model); projection = copy(source.projection); mode = source.mode; unit = source.textureUnit;
            for (int i = 0; i < 4; i++) textures[i] = copy(source.textures[i]);
        }
        private static double[][] copy(Deque<double[]> stack) {
            double[][] result = new double[stack.size()][]; int i = 0;
            for (double[] value : stack) result[i++] = value.clone(); return result;
        }
        private static void restore(Deque<double[]> stack, double[][] values) {
            while (stack.size() > values.length) stack.pop();
            while (stack.size() < values.length) stack.addLast(new double[16]);
            int i = 0; for (double[] matrix : stack) System.arraycopy(values[i++], 0, matrix, 0, 16);
        }
        public void restore(GameMatrices target) {
            restore(target.model, model); restore(target.projection, projection);
            for (int i = 0; i < 4; i++) restore(target.textures[i], textures[i]); target.mode = mode; target.textureUnit = unit;
        }
    }
    public double[] texture(int unit) {
        if (unit < 0 || unit >= textures.length) throw new IllegalArgumentException("Texture matrix unit");
        return textures[unit].peek().clone();
    }
    public double[] get(int parameter) {
        if (parameter == 2982) return modelView();
        if (parameter == 2983) return projection();
        if (parameter == 2984) return texture(textureUnit);
        throw new IllegalArgumentException("Not a game matrix: " + parameter);
    }
    public void translate(double x, double y, double z) {
        identity(operand); operand[12] = x; operand[13] = y; operand[14] = z; multiply(operand);
    }
    public void scale(double x, double y, double z) {
        identity(operand); operand[0] = x; operand[5] = y; operand[10] = z; multiply(operand);
    }
    public void rotate(double angle, double x, double y, double z) {
        double norm = Math.sqrt(x * x + y * y + z * z);
        if (!Double.isFinite(norm) || !Double.isFinite(angle)) throw new IllegalArgumentException("Non-finite rotation");
        if (norm == 0) return;
        x /= norm; y /= norm; z /= norm;
        double c = Math.cos(Math.toRadians(angle)), s = Math.sin(Math.toRadians(angle)), t = 1 - c;
        identity(operand);
        operand[0]=t*x*x+c; operand[1]=t*x*y+s*z; operand[2]=t*x*z-s*y;
        operand[4]=t*x*y-s*z; operand[5]=t*y*y+c; operand[6]=t*y*z+s*x;
        operand[8]=t*x*z+s*y; operand[9]=t*y*z-s*x; operand[10]=t*z*z+c; multiply(operand);
    }
    public void ortho(double left, double right, double bottom, double top, double near, double far) {
        if (left == right || bottom == top || near == far) throw new IllegalArgumentException("Degenerate orthographic projection");
        identity(operand); double[] value = operand;
        value[0] = 2 / (right - left); value[5] = 2 / (top - bottom); value[10] = -2 / (far - near);
        value[12] = -(right + left) / (right - left); value[13] = -(top + bottom) / (top - bottom);
        value[14] = -(far + near) / (far - near); multiply(value);
    }
    public static double[] read(FloatBuffer value) {
        if (value == null || value.remaining() < 16) throw new IllegalArgumentException("Matrix requires 16 floats");
        double[] result = new double[16]; for (int i = 0; i < 16; i++) result[i] = value.get(value.position() + i);
        validate(result); return result;
    }
    public static double[] multiply(double[] left, double[] right) {
        validate(left); validate(right); double[] result = new double[16];
        multiply(left, right, result); return result;
    }
    private static void multiply(double[] left, double[] right, double[] result) {
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
            double value = 0;
            for (int k = 0; k < 4; k++) value += left[k * 4 + row] * right[column * 4 + k];
            result[column * 4 + row] = value;
        }
        validate(result);
    }
    public static float[] transform(double[] matrix, float[] vector) {
        float[] result = new float[4]; transform(matrix, vector, result); return result;
    }
    public static void transform(double[] matrix, float[] vector, float[] result) {
        validate(matrix);
        if (vector == null || vector.length != 4) throw new IllegalArgumentException("Expected vec4");
        if (result == null || result.length != 4) throw new IllegalArgumentException("Expected vec4 destination");
        float x=vector[0],y=vector[1],z=vector[2],w=vector[3];
        for (int row = 0; row < 4; row++) {
            double value = 0; value += matrix[row]*x; value += matrix[4+row]*y;
            value += matrix[8+row]*z; value += matrix[12+row]*w;
            result[row] = (float)value;
        }
    }
    /** Inverse-transpose of the linear transform. Singular scales have no defined normal transform. */
    public static float[] normal(double[] matrix) {
        float[] result = new float[9]; normal(matrix, result); return result;
    }
    public static void normal(double[] matrix, float[] result) {
        validate(matrix);
        if (result == null || result.length != 9) throw new IllegalArgumentException("Expected normal matrix destination");
        double a = matrix[0], b = matrix[4], c = matrix[8], d = matrix[1], e = matrix[5], f = matrix[9];
        double g = matrix[2], h = matrix[6], i = matrix[10];
        double det = a*(e*i-f*h) - b*(d*i-f*g) + c*(d*h-e*g);
        if (det == 0) { Arrays.fill(result, 0); return; }
        result[0]=(float)((e*i-f*h)/det); result[1]=(float)((c*h-b*i)/det); result[2]=(float)((b*f-c*e)/det);
        result[3]=(float)((f*g-d*i)/det); result[4]=(float)((a*i-c*g)/det); result[5]=(float)((c*d-a*f)/det);
        result[6]=(float)((d*h-e*g)/det); result[7]=(float)((b*g-a*h)/det); result[8]=(float)((a*e-b*d)/det);
    }
    static void validate(double[] matrix) {
        if (matrix == null || matrix.length != 16) throw new IllegalArgumentException("Expected mat4");
        for (double value : matrix) if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite matrix");
    }
}
