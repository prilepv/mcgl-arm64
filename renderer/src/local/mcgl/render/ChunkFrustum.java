package local.mcgl.render;

/** Immutable CPU frustum from an explicit column-major clip-from-world matrix (double world coordinates). */
public final class ChunkFrustum {
    public static final ChunkFrustum ALL = new ChunkFrustum(new double[0]);
    private final double[] planes;
    private ChunkFrustum(double[] planes) { this.planes = planes; }
    public static ChunkFrustum fromMatrix(double[] matrix) {
        if (matrix == null || matrix.length != 16) throw new IllegalArgumentException("Expected 4x4 matrix");
        for (double value : matrix) if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite frustum");
        double[] planes = new double[24];
        for (int axis = 0; axis < 3; axis++) for (int side = 0; side < 2; side++) {
            int offset = (axis * 2 + side) * 4;
            for (int component = 0; component < 4; component++)
                planes[offset + component] = matrix[component * 4 + 3] + (side == 0 ? 1 : -1) * matrix[component * 4 + axis];
            double norm = Math.hypot(Math.hypot(planes[offset], planes[offset + 1]), planes[offset + 2]);
            if (!Double.isFinite(norm) || norm == 0) throw new IllegalArgumentException("Degenerate frustum plane");
            for (int component = 0; component < 4; component++) {
                planes[offset + component] /= norm;
                if (!Double.isFinite(planes[offset + component])) throw new IllegalArgumentException("Unbounded frustum plane");
            }
        }
        return new ChunkFrustum(planes);
    }
    public boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid chunk bounds");
        for (int i = 0; i < planes.length; i += 4) {
            double distance = planes[i] * (planes[i] >= 0 ? maxX : minX)
                    + planes[i + 1] * (planes[i + 1] >= 0 ? maxY : minY)
                    + planes[i + 2] * (planes[i + 2] >= 0 ? maxZ : minZ) + planes[i + 3];
            // Conservative boundary avoids rejecting coplanar faces from rounding error.
            if (distance < -1e-7) return false;
        }
        return true;
    }
}
