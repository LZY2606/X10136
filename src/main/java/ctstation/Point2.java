package ctstation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable 2D coordinate. Construction rejects non-finite values. */
public final class Point2 {
    public final double x;
    public final double y;

    public Point2(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new AppException("non-finite", "coordinates must be finite: (" + x + "," + y + ")");
        }
        this.x = x;
        this.y = y;
    }

    public double[] arr() { return new double[] {x, y}; }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    public static Point2 from(Object o, String what) {
        if (!(o instanceof java.util.List) || ((java.util.List<?>) o).size() != 2) {
            throw new AppException("bad-geojson", what + " must be a [x, y] pair");
        }
        java.util.List<?> l = (java.util.List<?>) o;
        return new Point2(Json.dbl(l.get(0), what + "[0]"), Json.dbl(l.get(1), what + "[1]"));
    }

    @Override
    public String toString() { return "(" + Json.num(x) + "," + Json.num(y) + ")"; }
}
