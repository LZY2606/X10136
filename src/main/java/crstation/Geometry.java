package crstation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 2D geometry primitives used by the station. Coordinates are kept as
 * {@code [x, y]} pairs; for geographic CRSs that is {@code [longitude, latitude]}.
 *
 * <p>Validation rules enforced before any computation:
 * <ul>
 *   <li>every coordinate must be a finite number;</li>
 *   <li>for geographic axes the latitude must be within [-90, 90];</li>
 *   <li>polygon rings must be explicitly closed (first == last position) and
 *       contain at least four positions.</li>
 * </ul>
 */
public sealed abstract class Geometry permits Geometry.Point, Geometry.Line, Geometry.Polygon {

    public final String kind;

    Geometry(String kind) {
        this.kind = kind;
    }

    /** All coordinate positions (rings flattened for polygons). */
    public abstract List<double[]> positions();

    /** Apply a coordinate-wise mapping, producing a new geometry of the same type. */
    public abstract Geometry map(Mapper mapper);

    public abstract Object toGeoJson();

    @FunctionalInterface
    public interface Mapper {
        double[] map(double[] p);
    }

    public static final class Point extends Geometry {
        public final double[] p;

        public Point(double[] p) {
            super("Point");
            this.p = p;
        }

        @Override
        public List<double[]> positions() {
            return List.of(p);
        }

        @Override
        public Geometry map(Mapper mapper) {
            return new Point(mapper.map(p));
        }

        @Override
        public Object toGeoJson() {
            return java.util.Map.of("type", "Point", "coordinates", java.util.List.of(p[0], p[1]));
        }
    }

    public static final class Line extends Geometry {
        public final List<double[]> pts;

        public Line(List<double[]> pts) {
            super("LineString");
            this.pts = pts;
        }

        @Override
        public List<double[]> positions() {
            return pts;
        }

        @Override
        public Geometry map(Mapper mapper) {
            List<double[]> out = new ArrayList<>(pts.size());
            for (double[] q : pts) {
                out.add(mapper.map(q));
            }
            return new Line(out);
        }

        @Override
        public Object toGeoJson() {
            return java.util.Map.of("type", "LineString", "coordinates", toCoordList(pts));
        }
    }

    public static final class Polygon extends Geometry {
        /** Outer ring first, holes afterwards. Every ring is closed. */
        public final List<List<double[]>> rings;

        public Polygon(List<List<double[]>> rings) {
            super("Polygon");
            this.rings = rings;
        }

        @Override
        public List<double[]> positions() {
            List<double[]> all = new ArrayList<>();
            for (List<double[]> ring : rings) {
                all.addAll(ring);
            }
            return all;
        }

        @Override
        public Geometry map(Mapper mapper) {
            List<List<double[]>> out = new ArrayList<>(rings.size());
            for (List<double[]> ring : rings) {
                List<double[]> nr = new ArrayList<>(ring.size());
                for (double[] q : ring) {
                    nr.add(mapper.map(q));
                }
                out.add(nr);
            }
            return new Polygon(out);
        }

        @Override
        public Object toGeoJson() {
            List<Object> coords = new ArrayList<>();
            for (List<double[]> ring : rings) {
                coords.add(toCoordList(ring));
            }
            return java.util.Map.of("type", "Polygon", "coordinates", coords);
        }
    }

    static Object toCoordList(List<double[]> pts) {
        List<Object> out = new ArrayList<>(pts.size());
        for (double[] p : pts) {
            out.add(java.util.List.of(p[0], p[1]));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /**
     * Validate geometry against the axes of its coordinate reference system.
     *
     * @param geographic true when the CRS is geographic (lon/lat axes)
     * @throws IllegalArgumentException with a diagnostic message on rejection
     */
    public void validate(boolean geographic) {
        List<double[]> all = positions();
        int index = 0;
        for (double[] p : all) {
            if (p.length != 2) {
                throw new IllegalArgumentException("coordinate index " + index + " is not 2-dimensional");
            }
            if (!Double.isFinite(p[0]) || !Double.isFinite(p[1])) {
                throw new IllegalArgumentException("coordinate index " + index
                        + " contains a non-finite value: " + raw(p));
            }
            if (geographic && (p[1] < -90.0 || p[1] > 90.0)) {
                throw new IllegalArgumentException("coordinate index " + index
                        + " has latitude " + p[1] + " outside [-90, 90]");
            }
            index++;
        }
        if (this instanceof Polygon poly) {
            int ringNo = 0;
            for (List<double[]> ring : poly.rings) {
                if (ring.size() < 4) {
                    throw new IllegalArgumentException("polygon ring " + ringNo
                            + " has only " + ring.size() + " positions; a closed ring needs at least 4");
                }
                double[] first = ring.get(0);
                double[] last = ring.get(ring.size() - 1);
                if (first[0] != last[0] || first[1] != last[1]) {
                    throw new IllegalArgumentException("polygon ring " + ringNo
                            + " is not closed: first " + raw(first) + " != last " + raw(last));
                }
                ringNo++;
            }
        }
        if (this instanceof Line line && line.pts.size() < 2) {
            throw new IllegalArgumentException("LineString needs at least 2 positions");
        }
    }

    private static String raw(double[] p) {
        return "[" + p[0] + ", " + (p.length > 1 ? p[1] : "?") + "]";
    }

    // ------------------------------------------------------------------
    // GeoJSON parsing
    // ------------------------------------------------------------------

    public record Feature(String id, String name, Geometry geometry) {
    }

    /**
     * Parse pasted GeoJSON into a list of features. Accepts a single geometry,
     * Feature, FeatureCollection, or an array of those.
     */
    public static List<Feature> parseGeoJson(String text) {
        Object doc = Json.parse(text);
        List<Feature> out = new ArrayList<>();
        collect(doc, out);
        if (out.isEmpty()) {
            throw new IllegalArgumentException("GeoJSON contains no geometries");
        }
        return out;
    }

    private static void collect(Object doc, List<Feature> out) {
        if (doc instanceof List<?> list) {
            for (Object item : list) {
                collect(item, out);
            }
            return;
        }
        if (!(doc instanceof Map<?, ?> mm)) {
            throw new IllegalArgumentException("GeoJSON node must be an object or array");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) mm;
        String type = Json.str(m, "type");
        switch (type) {
            case "Feature" -> {
                Object geom = m.get("geometry");
                if (geom == null) {
                    throw new IllegalArgumentException("Feature has no geometry");
                }
                Map<?, ?> gm = Json.obj(geom);
                @SuppressWarnings("unchecked")
                Map<String, Object> gmm = (Map<String, Object>) gm;
                String id = m.get("id") == null ? null : String.valueOf(m.get("id"));
                String name = featureName(m.get("properties"));
                out.add(new Feature(id, name, parseGeometry(gmm)));
            }
            case "FeatureCollection" -> {
                for (Object f : Json.arr(m.get("features"))) {
                    collect(f, out);
                }
            }
            case "Point", "LineString", "Polygon" -> out.add(new Feature(null, null, parseGeometry(m)));
            default -> throw new IllegalArgumentException("unsupported GeoJSON type: " + type
                    + " (supported: Point, LineString, Polygon, Feature, FeatureCollection)");
        }
    }

    private static String featureName(Object props) {
        if (props instanceof Map<?, ?> pm) {
            Object name = pm.get("name");
            return name == null ? null : String.valueOf(name);
        }
        return null;
    }

    public static Geometry parseGeometry(Map<String, Object> m) {
        String type = Json.str(m, "type");
        Object coords = m.get("coordinates");
        if (coords == null) {
            throw new IllegalArgumentException(type + " geometry has no coordinates");
        }
        return switch (type) {
            case "Point" -> new Point(readPosition(Json.arr(coords)));
            case "LineString" -> {
                List<Object> arr = Json.arr(coords);
                List<double[]> pts = new ArrayList<>(arr.size());
                for (Object o : arr) {
                    pts.add(readPosition(Json.arr(o)));
                }
                yield new Line(pts);
            }
            case "Polygon" -> {
                List<Object> ringsArr = Json.arr(coords);
                if (ringsArr.isEmpty()) {
                    throw new IllegalArgumentException("Polygon has no rings");
                }
                List<List<double[]>> rings = new ArrayList<>(ringsArr.size());
                for (Object ringObj : ringsArr) {
                    List<double[]> ring = new ArrayList<>();
                    for (Object pos : Json.arr(ringObj)) {
                        ring.add(readPosition(Json.arr(pos)));
                    }
                    rings.add(ring);
                }
                yield new Polygon(rings);
            }
            default -> throw new IllegalArgumentException("unsupported geometry type: " + type);
        };
    }

    private static double[] readPosition(List<Object> pos) {
        if (pos.size() < 2) {
            throw new IllegalArgumentException("positions must have at least 2 elements");
        }
        if (!(pos.get(0) instanceof Number) || !(pos.get(1) instanceof Number)) {
            throw new IllegalArgumentException("position elements must be numbers");
        }
        double x = ((Number) pos.get(0)).doubleValue();
        double y = ((Number) pos.get(1)).doubleValue();
        return new double[] {x, y};
    }
}
