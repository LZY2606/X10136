package coordstation.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GeoJSON Point / LineString / Polygon with strict pre-computation validation. */
public final class Geometry {
    public enum Type { POINT, LINESTRING, POLYGON }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String msg) { super(msg); }
    }

    public final Type type;
    /** POINT: [[x,y]]; LINESTRING: [[x,y]...]; POLYGON: rings of [x,y]. */
    public final List<List<double[]>> coords;

    private Geometry(Type type, List<List<double[]>> coords) {
        this.type = type;
        this.coords = coords;
    }

    public static Geometry fromGeoJson(Map<String, Object> g) {
        Object t = g.get("type");
        if (!(t instanceof String)) throw new ValidationException("geometry missing 'type'");
        Object c = g.get("coordinates");
        if (!(c instanceof List)) throw new ValidationException("geometry missing 'coordinates'");
        List<Object> cl = castList(c);
        switch ((String) t) {
            case "Point": {
                double[] p = position(cl);
                validatePosition(p);
                return new Geometry(Type.POINT, wrap(wrap1(p)));
            }
            case "LineString": {
                List<double[]> line = positions(cl);
                if (line.size() < 2) throw new ValidationException("LineString needs at least 2 positions");
                return new Geometry(Type.LINESTRING, wrap(line));
            }
            case "Polygon": {
                List<List<double[]>> rings = new ArrayList<>();
                for (Object ring : cl) {
                    if (!(ring instanceof List)) throw new ValidationException("polygon ring must be an array");
                    rings.add(positions(castList(ring)));
                }
                if (rings.isEmpty()) throw new ValidationException("Polygon needs at least one ring");
                for (List<double[]> ring : rings) {
                    if (ring.size() < 4)
                        throw new ValidationException("polygon ring needs at least 4 positions (closed)");
                    double[] first = ring.get(0);
                    double[] last = ring.get(ring.size() - 1);
                    if (first[0] != last[0] || first[1] != last[1])
                        throw new ValidationException("polygon ring is not closed");
                }
                return new Geometry(Type.POLYGON, rings);
            }
            default:
                throw new ValidationException("unsupported geometry type: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) { return (List<Object>) o; }

    private static List<List<double[]>> wrap(List<double[]> line) {
        List<List<double[]>> out = new ArrayList<>();
        out.add(line);
        return out;
    }

    private static List<double[]> wrap1(double[] p) {
        List<double[]> out = new ArrayList<>();
        out.add(p);
        return out;
    }

    private static List<double[]> positions(List<Object> arr) {
        List<double[]> out = new ArrayList<>();
        for (Object o : arr) {
            if (!(o instanceof List)) throw new ValidationException("position must be an array");
            double[] p = position(castList(o));
            validatePosition(p);
            out.add(p);
        }
        return out;
    }

    private static double[] position(List<Object> arr) {
        if (arr.size() < 2) throw new ValidationException("position needs at least 2 numbers");
        Object x = arr.get(0), y = arr.get(1);
        if (!(x instanceof Number) || !(y instanceof Number))
            throw new ValidationException("position elements must be numbers");
        return new double[]{((Number) x).doubleValue(), ((Number) y).doubleValue()};
    }

    private static void validatePosition(double[] p) {
        if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]))
            throw new ValidationException("coordinate is not a finite number");
        if (p[0] < -180.0 || p[0] > 180.0)
            throw new ValidationException("longitude out of range [-180, 180]: " + p[0]);
        if (p[1] < -90.0 || p[1] > 90.0)
            throw new ValidationException("latitude out of range [-90, 90]: " + p[1]);
    }

    public List<double[]> allPoints() {
        List<double[]> out = new ArrayList<>();
        for (List<double[]> ring : coords) out.addAll(ring);
        return out;
    }

    public LonLatBox region() {
        return LonLatBox.enclosing(allPoints());
    }

    public Geometry transform(Affine f) {
        List<List<double[]>> out = new ArrayList<>();
        for (List<double[]> ring : coords) {
            List<double[]> r = new ArrayList<>();
            for (double[] p : ring) r.add(f.apply(p[0], p[1]));
            out.add(r);
        }
        return new Geometry(type, out);
    }

    public Map<String, Object> toGeoJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (type) {
            case POINT:
                m.put("type", "Point");
                m.put("coordinates", posToJson(coords.get(0).get(0)));
                break;
            case LINESTRING:
                m.put("type", "LineString");
                m.put("coordinates", lineToJson(coords.get(0)));
                break;
            case POLYGON:
                m.put("type", "Polygon");
                List<Object> rings = new ArrayList<>();
                for (List<double[]> ring : coords) rings.add(lineToJson(ring));
                m.put("coordinates", rings);
                break;
        }
        return m;
    }

    private static List<Object> posToJson(double[] p) {
        List<Object> l = new ArrayList<>();
        l.add(p[0]);
        l.add(p[1]);
        return l;
    }

    private static List<Object> lineToJson(List<double[]> line) {
        List<Object> out = new ArrayList<>();
        for (double[] p : line) out.add(posToJson(p));
        return out;
    }
}
