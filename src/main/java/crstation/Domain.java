package crstation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Effective geographic/planar coverage of a transform edge.
 *
 * <p>A domain is either an axis-aligned box or a polygon. Bounding boxes for
 * geographic axes may cross the antimeridian: {@code minLon > maxLon} then
 * means "wraps through 180/-180", which a naive min/max comparison would get
 * wrong. The canonical test for a longitude interval is periodic containment.
 */
public sealed abstract class Domain permits Domain.Bbox, Domain.PolygonDomain {

    public final boolean wraparound;

    Domain(boolean wraparound) {
        this.wraparound = wraparound;
    }

    /** True when the point is on the boundary or inside the domain. */
    public abstract boolean contains(double x, double y);

    public abstract Map<String, Object> toJson();

    public static Domain fromJson(Object v) {
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("domain must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        String type = Json.optStr(m, "type", "bbox");
        boolean wrap = Json.optBool(m, "wraparound", false);
        return switch (type) {
            case "bbox" -> {
                List<Object> a = Json.arr(m.get("bbox"));
                if (a.size() != 4) {
                    throw new IllegalArgumentException("bbox must be [minX, minY, maxX, maxY]");
                }
                yield new Bbox(num(a, 0), num(a, 1), num(a, 2), num(a, 3), wrap);
            }
            case "polygon" -> {
                List<List<double[]>> rings = readPolygonCoordinates(
                        Json.obj(m.get("geometry")).get("coordinates"));
                yield new PolygonDomain(rings, wrap);
            }
            default -> throw new IllegalArgumentException("unknown domain type: " + type);
        };
    }

    private static double num(List<Object> a, int i) {
        if (!(a.get(i) instanceof Number n)) {
            throw new IllegalArgumentException("bbox elements must be numbers");
        }
        return n.doubleValue();
    }

    /**
     * Domain constructed from user input. A 4-element array is a bbox; an
     * object with a GeoJSON polygon is a polygon domain. Geographic axes
     * enable antimeridian handling.
     */
    public static Domain create(Object v, boolean geographic) {
        if (v instanceof List<?> list && list.size() == 4
                && list.get(0) instanceof Number
                && list.get(1) instanceof Number
                && list.get(2) instanceof Number
                && list.get(3) instanceof Number) {
            @SuppressWarnings("unchecked")
            List<Object> a = (List<Object>) list;
            return new Bbox(num(a, 0), num(a, 1), num(a, 2), num(a, 3), geographic);
        }
        if (v instanceof Map<?, ?> mm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) mm;
            if (m.containsKey("bbox")) {
                boolean wrap = Json.optBool(m, "wraparound", geographic);
                return fromJson(m instanceof Map ? withType(m, "bbox", wrap) : m);
            }
            String type = Json.optStr(m, "type", null);
            if ("Polygon".equals(type)) {
                List<List<double[]>> rings = readPolygonCoordinates(m.get("coordinates"));
                return new PolygonDomain(rings, geographic);
            }
            if ("polygon".equals(type) && m.get("geometry") != null) {
                return fromJson(m);
            }
        }
        throw new IllegalArgumentException(
                "domain must be [minX, minY, maxX, maxY] or a GeoJSON Polygon");
    }

    private static Map<String, Object> withType(Map<String, Object> m, String type, boolean wrap) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>(m);
        out.put("type", type);
        out.put("wraparound", wrap);
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<List<double[]>> readPolygonCoordinates(Object coords) {
        List<Object> ringsArr = Json.arr(coords);
        List<List<double[]>> rings = new ArrayList<>();
        for (Object ringObj : ringsArr) {
            List<double[]> ring = new ArrayList<>();
            for (Object pos : Json.arr(ringObj)) {
                List<Object> pa = Json.arr(pos);
                if (pa.size() < 2 || !(pa.get(0) instanceof Number) || !(pa.get(1) instanceof Number)) {
                    throw new IllegalArgumentException("polygon domain positions must be number pairs");
                }
                ring.add(new double[] {((Number) pa.get(0)).doubleValue(),
                        ((Number) pa.get(1)).doubleValue()});
            }
            rings.add(ring);
        }
        return rings;
    }

    // ------------------------------------------------------------------
    // Antimeridian math
    // ------------------------------------------------------------------

    /** Normalize a longitude into [-180, 180). */
    static double normalizeLon(double lon) {
        double x = ((lon + 180.0) % 360.0);
        if (x < 0) {
            x += 360.0;
        }
        return x - 180.0;
    }

    /**
     * Periodic interval containment for a wrap-around axis.
     * Handles both wrap intervals ({@code min > max}) and normal ones.
     */
    static boolean inWrappingInterval(double v, double min, double max) {
        if (min <= max) {
            return v >= min && v <= max;
        }
        // interval wraps around the period boundary, e.g. [170, -170]
        return v >= min || v <= max;
    }

    // ------------------------------------------------------------------
    // Implementations
    // ------------------------------------------------------------------

    public static final class Bbox extends Domain {
        public final double minX;
        public final double minY;
        public final double maxX;
        public final double maxY;

        public Bbox(double minX, double minY, double maxX, double maxY, boolean wraparound) {
            super(wraparound);
            if (!Double.isFinite(minX) || !Double.isFinite(minY)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
                throw new IllegalArgumentException("bbox bounds must be finite");
            }
            if (!(maxY >= minY)) {
                throw new IllegalArgumentException(
                        "bbox maxY " + maxY + " must be >= minY " + minY);
            }
            if (!wraparound && !(maxX >= minX)) {
                throw new IllegalArgumentException(
                        "bbox maxX " + maxX + " must be >= minX " + minX
                                + " unless the domain crosses the antimeridian");
            }
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        @Override
        public boolean contains(double x, double y) {
            if (y < minY || y > maxY) {
                return false;
            }
            if (wraparound) {
                return inWrappingInterval(x, minX, maxX);
            }
            return x >= minX && x <= maxX;
        }

        @Override
        public Map<String, Object> toJson() {
            return new java.util.LinkedHashMap<>(Map.of(
                    "type", "bbox",
                    "bbox", List.of(minX, minY, maxX, maxY),
                    "wraparound", wraparound));
        }
    }

    public static final class PolygonDomain extends Domain {
        /** Closed rings; outer first. */
        public final List<List<double[]>> rings;

        public PolygonDomain(List<List<double[]>> rings, boolean wraparound) {
            super(wraparound);
            if (rings.isEmpty()) {
                throw new IllegalArgumentException("polygon domain needs at least one ring");
            }
            for (int ri = 0; ri < rings.size(); ri++) {
                List<double[]> ring = rings.get(ri);
                if (ring.size() < 4) {
                    throw new IllegalArgumentException("polygon domain ring " + ri
                            + " needs at least 4 positions");
                }
                double[] a = ring.get(0);
                double[] b = ring.get(ring.size() - 1);
                if (a[0] != b[0] || a[1] != b[1]) {
                    throw new IllegalArgumentException("polygon domain ring " + ri + " is not closed");
                }
                for (double[] p : ring) {
                    if (!Double.isFinite(p[0]) || !Double.isFinite(p[1])) {
                        throw new IllegalArgumentException("polygon domain contains non-finite coordinates");
                    }
                }
            }
            this.rings = rings;
        }

        @Override
        public boolean contains(double x, double y) {
            if (!pointInRing(x, y, rings.get(0))) {
                return false;
            }
            for (int ri = 1; ri < rings.size(); ri++) {
                if (pointInRing(x, y, rings.get(ri))) {
                    return false;
                }
            }
            return true;
        }

        private boolean pointInRing(double x, double y, List<double[]> ring) {
            boolean inside = rayCast(x, y, ring);
            if (!wraparound) {
                return inside;
            }
            // The ring may straddle the antimeridian; test longitude copies.
            return inside || rayCast(x + 360.0, y, ring) || rayCast(x - 360.0, y, ring);
        }

        private static boolean rayCast(double x, double y, List<double[]> ring) {
            boolean inside = false;
            int n = ring.size();
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = ring.get(i)[0], yi = ring.get(i)[1];
                double xj = ring.get(j)[0], yj = ring.get(j)[1];
                boolean intersect = ((yi > y) != (yj > y))
                        && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
                if (intersect) {
                    inside = !inside;
                }
            }
            return inside;
        }

        @Override
        public Map<String, Object> toJson() {
            List<Object> coords = new ArrayList<>();
            for (List<double[]> ring : rings) {
                coords.add(Geometry.toCoordList(ring));
            }
            Map<String, Object> geo = new java.util.LinkedHashMap<>();
            geo.put("type", "Polygon");
            geo.put("coordinates", coords);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("type", "polygon");
            out.put("wraparound", wraparound);
            out.put("geometry", geo);
            return out;
        }
    }
}
