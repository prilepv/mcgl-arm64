package local.mcgl.render;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/** CPU transforms at the original game boundary; column-major, post-multiplied. No GL state. */
public final class GameMatrices {
    private final Deque<double[]> model = stack(), projection = stack();
    @SuppressWarnings("unchecked")
    private final Deque<double[]>[] textures = new Deque[] { stack(), stack(), stack(), stack() };
    private int mode = 5888, textureUnit;

    private static Deque<double[]> stack() {
        Deque<double[]> result = new ArrayDeque<double[]>(); result.push(identity()); return result;
    }
    public static double[] identity() {
        double[] result = new double[16]; for (int i = 0; i < 4; i++) result[i * 5] = 1; return result;
    }
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
        stack.push(stack.peek().clone());
    }
    public void pop() {
        if (current().size() <= 1) throw new IllegalStateException("Game matrix stack underflow");
        current().pop();
    }
    public void loadIdentity() { load(identity()); }
    public void load(double[] matrix) {
        validate(matrix); current().pop(); current().push(matrix.clone());
    }
    public void multiply(double[] matrix) {
        validate(matrix); double[] value = multiply(current().peek(), matrix); current().pop(); current().push(value);
    }
    public double[] modelView() { return model.peek().clone(); }
    public double[] projection() { return projection.peek().clone(); }
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
        double[] value = identity(); value[12] = x; value[13] = y; value[14] = z; multiply(value);
    }
    public void scale(double x, double y, double z) {
        double[] value = identity(); value[0] = x; value[5] = y; value[10] = z; multiply(value);
    }
    public void rotate(double angle, double x, double y, double z) {
        double norm = Math.sqrt(x * x + y * y + z * z);
        if (!Double.isFinite(norm) || !Double.isFinite(angle)) throw new IllegalArgumentException("Non-finite rotation");
        if (norm == 0) return;
        x /= norm; y /= norm; z /= norm;
        double c = Math.cos(Math.toRadians(angle)), s = Math.sin(Math.toRadians(angle)), t = 1 - c;
        multiply(new double[] { t*x*x+c, t*x*y+s*z, t*x*z-s*y, 0,
                t*x*y-s*z, t*y*y+c, t*y*z+s*x, 0, t*x*z+s*y, t*y*z-s*x, t*z*z+c, 0, 0, 0, 0, 1 });
    }
    public void ortho(double left, double right, double bottom, double top, double near, double far) {
        if (left == right || bottom == top || near == far) throw new IllegalArgumentException("Degenerate orthographic projection");
        double[] value = identity();
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
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++)
            for (int k = 0; k < 4; k++) result[column * 4 + row] += left[k * 4 + row] * right[column * 4 + k];
        validate(result); return result;
    }
    public static float[] transform(double[] matrix, float[] vector) {
        validate(matrix);
        if (vector == null || vector.length != 4) throw new IllegalArgumentException("Expected vec4");
        float[] result = new float[4];
        for (int row = 0; row < 4; row++) {
            double value = 0; for (int k = 0; k < 4; k++) value += matrix[k * 4 + row] * vector[k];
            result[row] = (float)value;
        }
        return result;
    }
    /** Inverse-transpose of the linear transform. Singular scales have no defined normal transform. */
    public static float[] normal(double[] matrix) {
        validate(matrix);
        double a = matrix[0], b = matrix[4], c = matrix[8], d = matrix[1], e = matrix[5], f = matrix[9];
        double g = matrix[2], h = matrix[6], i = matrix[10];
        double det = a*(e*i-f*h) - b*(d*i-f*g) + c*(d*h-e*g);
        if (det == 0) return new float[9];
        return new float[] { (float)((e*i-f*h)/det), (float)((c*h-b*i)/det), (float)((b*f-c*e)/det),
                (float)((f*g-d*i)/det), (float)((a*i-c*g)/det), (float)((c*d-a*f)/det),
                (float)((d*h-e*g)/det), (float)((b*g-a*h)/det), (float)((a*e-b*d)/det) };
    }
    private static void validate(double[] matrix) {
        if (matrix == null || matrix.length != 16) throw new IllegalArgumentException("Expected mat4");
        for (double value : matrix) if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite matrix");
    }
}
