package ctstation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Input/output geometry. Supported GeoJSON geometry types:
 * Point, MultiPoint, LineString, MultiLineString, Polygon, MultiPolygon.
 *
 * <p>Validation performed up front, before any transform chain is chosen:
 * <ul>
 *   <li>all coordinates finite,</li>
 *   <li>latitude within [-90,90] for geographic source CRS,</li>
 *   <li>polygon rings have at least 4 positions and are explicitly closed,</li>
 *   <li>positions are 2D.</li>
 * </ul>
 */
public abstract class Geometry {

    public abstract String type();

    /** Every coordinate exactly once, in order. */
    public abstract List<Point2> points();

    /** Geometry rebuilt coordinate-wise from {@code pts} (same iteration order). */
    public abstract Geometry rebuild(List<Point2> pts);

    /** GeoJSON-style map. */
    public abstract Map<String, Object> toMap();

    // ------------------------------------------------------------- parsing

    @SuppressWarnings("unchecked")
    public static Geometry parse(Object geo) {
        if (!(geo instanceof Map)) {
            throw new AppException("bad-geojson", "geometry must be an object");
        }
        Map<String, Object> m = (Map<String, Object>) geo;
        String type = Json.str(m, "type");
        Object coords = m.get("coordinates");
        switch (type) {
            case "Point":
                return new GPoint(Point2.from(coords, "Point coordinates"));
            case "MultiPoint":
                return new GMultiPoint(parsePositions(coords));
            case "LineString":
                return new GLineString(parsePositions(coords));
            case "MultiLineString": {
                List<List<Point2>> lines = new ArrayList<>();
                for (Object line : Json.arr(m, "coordinates")) lines.add(parsePositions(line));
                return new GMultiLineString(lines);
            }
            case "Polygon":
                return new GPolygon(parseRings(coords));
            case "MultiPolygon": {
                List<List<List<Point2>>> polys = new ArrayList<>();
                for (Object poly : Json.arr(m, "coordinates")) {
                    polys.add(parseRings(poly));
                }
                return new GMultiPolygon(polys);
            }
            default:
                throw new AppException("bad-geojson", "unsupported geometry type '" + type + "'");
        }
    }

    static List<Point2> parsePositions(Object raw) {
        if (!(raw instanceof List)) {
            throw new AppException("bad-geojson", "coordinates must be an array");
        }
        List<Object> list = (List<Object>) raw;
        if (list.isEmpty()) throw new AppException("bad-geojson", "coordinates must not be empty");
        List<Point2> pts = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object pos = list.get(i);
            if (pos instanceof List && ((List<?>) pos).size() != 2) {
                throw new AppException("bad-geojson",
                        "position " + i + " must be 2D [x, y]");
            }
            pts.add(Point2.from(pos, "position " + i));
        }
        return pts;
    }

    static List<List<Point2>> parseRings(Object raw) {
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw new AppException("bad-geojson", "polygon coordinates must be a non-empty array of rings");
        }
        List<List<Point2>> rings = new ArrayList<>();
        for (int ri = 0; ri < ((List<?>) raw).size(); ri++) {
            List<Point2> ring = parsePositions(((List<?>) raw).get(ri));
            if (ring.size() < 4) {
                throw new AppException("ring-not-closed",
                        "polygon ring " + ri + " must have at least 4 positions (explicitly closed)");
            }
            Point2 first = ring.get(0);
            Point2 last = ring.get(ring.size() - 1);
            if (first.x != last.x || first.y != last.y) {
                throw new AppException("ring-not-closed",
                        "polygon ring " + ri + " is not closed: first " + first + " != last " + last);
            }
            rings.add(ring);
        }
        return rings;
    }

    /** Range check applied before transform selection. */
    public void validateIn(Crs source) {
        for (Point2 p : points()) source.validatePoint(p);
    }

    // ------------------------------------------------------------ variants

    public static final class GPoint extends Geometry {
        public final Point2 p;
        public GPoint(Point2 p) { this.p = p; }
        @Override public String type() { return "Point"; }
        @Override public List<Point2> points() { List<Point2> l = new ArrayList<>(); l.add(p); return l; }
        @Override public Geometry rebuild(List<Point2> pts) { return new GPoint(pts.get(0)); }
        @Override public Map<String, Object> toMap() { return Json.object("type", "Point", "coordinates", p.arr()); }
    }

    public static final class GMultiPoint extends Geometry {
        public final List<Point2> pts;
        public GMultiPoint(List<Point2> pts) {
            if (pts.isEmpty()) throw new AppException("bad-geojson", "MultiPoint must not be empty");
            this.pts = pts;
        }
        @Override public String type() { return "MultiPoint"; }
        @Override public List<Point2> points() { return pts; }
        @Override public Geometry rebuild(List<Point2> pts) { return new GMultiPoint(pts); }
        @Override public Map<String, Object> toMap() {
            List<Object> c = new ArrayList<>();
            for (Point2 p : pts) c.add(p.arr());
            return Json.object("type", "MultiPoint", "coordinates", c);
        }
    }

    public static final class GLineString extends Geometry {
        public final List<Point2> pts;
        public GLineString(List<Point2> pts) {
            if (pts.size() < 2) throw new AppException("bad-geojson", "LineString needs at least 2 positions");
            this.pts = pts;
        }
        @Override public String type() { return "LineString"; }
        @Override public List<Point2> points() { return pts; }
        @Override public Geometry rebuild(List<Point2> pts) { return new GLineString(pts); }
        @Override public Map<String, Object> toMap() {
            List<Object> c = new ArrayList<>();
            for (Point2 p : pts) c.add(p.arr());
            return Json.object("type", "LineString", "coordinates", c);
        }
    }

    public static final class GMultiLineString extends Geometry {
        public final List<List<Point2>> lines;
        public GMultiLineString(List<List<Point2>> lines) {
            if (lines.isEmpty()) throw new AppException("bad-geojson", "MultiLineString must not be empty");
            for (List<Point2> l : lines) {
                if (l.size() < 2) throw new AppException("bad-geojson", "each LineString needs >= 2 positions");
            }
            this.lines = lines;
        }
        @Override public String type() { return "MultiLineString"; }
        @Override public List<Point2> points() {
            List<Point2> all = new ArrayList<>();
            for (List<Point2> l : lines) all.addAll(l);
            return all;
        }
        @Override public Geometry rebuild(List<Point2> pts) {
            List<List<Point2>> out = new ArrayList<>();
            int i = 0;
            for (List<Point2> l : lines) {
                out.add(new ArrayList<>(pts.subList(i, i + l.size())));
                i += l.size();
            }
            return new GMultiLineString(out);
        }
        @Override public Map<String, Object> toMap() {
            List<Object> c = new ArrayList<>();
            for (List<Point2> l : lines) {
                List<Object> line = new ArrayList<>();
                for (Point2 p : l) line.add(p.arr());
                c.add(line);
            }
            return Json.object("type", "MultiLineString", "coordinates", c);
        }
    }

    public static final class GPolygon extends Geometry {
        public final List<List<Point2>> rings;
        public GPolygon(List<List<Point2>> rings) {
            if (rings.isEmpty()) throw new AppException("bad-geojson", "Polygon must have an outer ring");
            this.rings = rings;
        }
        @Override public String type() { return "Polygon"; }
        @Override public List<Point2> points() {
            List<Point2> all = new ArrayList<>();
            for (List<Point2> r : rings) all.addAll(r);
            return all;
        }
        @Override public Geometry rebuild(List<Point2> pts) {
            List<List<Point2>> out = new ArrayList<>();
            int i = 0;
            for (List<Point2> r : rings) {
                out.add(new ArrayList<>(pts.subList(i, i + r.size())));
                i += r.size();
            }
            return new GPolygon(out);
        }
        @Override public Map<String, Object> toMap() {
            List<Object> c = new ArrayList<>();
            for (List<Point2> r : rings) {
                List<Object> rr = new ArrayList<>();
                for (Point2 p : r) rr.add(p.arr());
                c.add(rr);
            }
            return Json.object("type", "Polygon", "coordinates", c);
        }
    }

    public static final class GMultiPolygon extends Geometry {
        public final List<List<List<Point2>>> polys;
        public GMultiPolygon(List<List<List<Point2>>> polys) {
            if (polys.isEmpty()) throw new AppException("bad-geojson", "MultiPolygon must not be empty");
            this.polys = polys;
        }
        @Override public String type() { return "MultiPolygon"; }
        @Override public List<Point2> points() {
            List<Point2> all = new ArrayList<>();
            for (List<List<Point2>> poly : polys) for (List<Point2> r : poly) all.addAll(r);
            return all;
        }
        @Override public Geometry rebuild(List<Point2> pts) {
            List<List<List<Point2>>> out = new ArrayList<>();
            int i = 0;
            for (List<List<Point2>> poly : polys) {
                List<List<Point2>> po = new ArrayList<>();
                for (List<Point2> r : poly) {
                    po.add(new ArrayList<>(pts.subList(i, i + r.size())));
                    i += r.size();
                }
                out.add(po);
            }
            return new GMultiPolygon(out);
        }
        @Override public Map<String, Object> toMap() {
            List<Object> c = new ArrayList<>();
            for (List<List<Point2>> poly : polys) {
                List<Object> po = new ArrayList<>();
                for (List<Point2> r : poly) {
                    List<Object> rr = new ArrayList<>();
                    for (Point2 p : r) rr.add(p.arr());
                    po.add(rr);
                }
                c.add(po);
            }
            return Json.object("type", "MultiPolygon", "coordinates", c);
        }
    }
}
