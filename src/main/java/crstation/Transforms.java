package crstation;

import java.util.List;
import java.util.Map;

/** Implementations of {@link Transform}. */
public final class Transforms {

    private Transforms() {
    }

    /**
     * Affine mapping {@code x' = a x + b y + c, y' = d x + e y + f}.
     */
    public static final class Affine implements Transform {
        public final double a, b, c, d, e, f;

        public Affine(double a, double b, double c, double d, double e, double f) {
            for (double v : new double[] {a, b, c, d, e, f}) {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("affine coefficients must be finite");
                }
            }
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.e = e;
            this.f = f;
        }

        @Override
        public double[] apply(double[] p) {
            double x = p[0], y = p[1];
            return new double[] {a * x + b * y + c, d * x + e * y + f};
        }

        @Override
        public double[] applyInverse(double[] p) {
            double det = a * e - b * d;
            double x = p[0] - c;
            double y = p[1] - f;
            return new double[] {(e * x - b * y) / det, (a * y - d * x) / det};
        }

        @Override
        public boolean mathematicallyInvertible() {
            return Math.abs(a * e - b * d) > DET_EPSILON;
        }

        @Override
        public Map<String, Object> spec() {
            return new java.util.LinkedHashMap<>(Map.of(
                    "type", "affine",
                    "a", a, "b", b, "c", c,
                    "d", d, "e", e, "f", f));
        }
    }

    double DET_EPSILON = 1e-12;

    /**
     * Spherical (EPSG:3857-style) Mercator: lon/lat degrees to metres.
     */
    public static final class Mercator implements Transform {
        private static final double MAX_LAT = 85.05112877980659;
        private final double radius;

        public Mercator(double radius) {
            if (!(radius > 0) || !Double.isFinite(radius)) {
                throw new IllegalArgumentException("mercator radius must be a positive finite number");
            }
            this.radius = radius;
        }

        @Override
        public double[] apply(double[] p) {
            double lon = p[0];
            double lat = Math.max(-MAX_LAT, Math.min(MAX_LAT, p[1]));
            double x = radius * Math.toRadians(lon);
            double y = radius * Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(lat) / 2.0));
            return new double[] {x, y};
        }

        @Override
        public double[] applyInverse(double[] p) {
            double lon = Math.toDegrees(p[0] / radius);
            double lat = Math.toDegrees(2.0 * Math.atan(Math.exp(p[1] / radius)) - Math.PI / 2.0);
            return new double[] {lon, lat};
        }

        @Override
        public boolean mathematicallyInvertible() {
            return true;
        }

        @Override
        public Map<String, Object> spec() {
            return new java.util.LinkedHashMap<>(Map.of(
                    "type", "mercator", "radius", radius));
        }
    }

    /** Convenience sample points inside a transformed bbox (4 corners, closed). */
    static List<double[]> bboxCorners(double minX, double minY, double maxX, double maxY) {
        return List.of(
                new double[] {minX, minY},
                new double[] {maxX, minY},
                new double[] {maxX, maxY},
                new double[] {minX, maxY},
                new double[] {minX, minY});
    }
}
