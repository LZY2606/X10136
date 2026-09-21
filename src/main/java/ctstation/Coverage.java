package ctstation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validity domain of a transform edge.
 *
 * <p>Two shapes are supported:
 * <ul>
 *   <li>{@code bbox}: axis aligned box {@code [minX,minY,maxX,maxY]}. For
 *       geographic CRS, boxes may cross the antimeridian by giving
 *       {@code minX > maxX} (or values outside [-180,180]); containment then
 *       uses 360-degree shifted longitude equivalence rather than a naive
 *       min/max test.</li>
 *   <li>{@code polygon}: a single closed ring. Containment runs in unwrapped
 *       longitude space so antimeridian-crossing rings work correctly.</li>
 * </ul>
 */
public abstract class Coverage {

    public abstract String type();

    /** True when the point is inside the domain. Point is already finite. */
    public abstract boolean contains(double x, double y);

    /** Representative finite sample points in the domain's native CRS. */
    public abstract List<Point2> samples();

    public abstract Map<String, Object> toMap();

    public static final Coverage WHOLE_WORLD = new Coverage() {
        @Override public String type() { return "whole-world"; }
        @Override public boolean contains(double x, double y) { return true; }
        @Override public List<Point2> samples() {
            List<Point2> s = new ArrayList<>();
            s.add(new Point2(0, 0));
            s.add(new Point2(180, 0));
            s.add(new Point2(-90, 45));
            return s;
        }
        @Override public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("type", "whole-world");
            return m;
        }
    };

    public static Coverage fromMap(Object spec) {
        if (spec == null) return WHOLE_WORLD;
        if (!(spec instanceof Map)) {
            throw new AppException("bad-coverage", "coverage must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) spec;
        String type = Json.optStr(m, "type", "bbox");
        switch (type) {
            case "whole-world": return WHOLE_WORLD;
            case "bbox": return bboxFrom(m);
            case "polygon": return polygonFrom(m);
            default:
                throw new AppException("bad-coverage", "unknown coverage type '" + type + "'");
        }
    }

    private static Bbox bboxFrom(Map<String, Object> m) {
        List<Object> b = Json.arr(m, "bbox");
        if (b.size() != 4) throw new AppException("bad-coverage", "bbox needs [minX,minY,maxX,maxY]");
        double minX = Json.dbl(b.get(0), "bbox[0]");
        double minY = Json.dbl(b.get(1), "bbox[1]");
        double maxX = Json.dbl(b.get(2), "bbox[2]");
        double maxY = Json.dbl(b.get(3), "bbox[3]");
        boolean wrap = Json.optBool(m, "wrapX", true);
        return new Bbox(minX, minY, maxX, maxY, wrap);
    }

    private static Polygon polygonFrom(Map<String, Object> m) {
        List<Object> rings = Json.arr(m, "coordinates");
        if (rings.size() != 1) {
            throw new AppException("bad-coverage", "coverage polygon supports a single outer ring");
        }
        Object ringObj = rings.get(0);
        if (!(ringObj instanceof List)) {
            throw new AppException("bad-coverage", "coverage polygon ring must be an array");
        }
        List<?> ringRaw = (List<?>) ringObj;
        if (ringRaw.size() < 4) {
            throw new AppException("bad-coverage", "coverage polygon ring needs at least 4 positions");
        }
        List<Point2> ring = new ArrayList<>();
        for (Object pos : ringRaw) ring.add(Point2.from(pos, "coverage position"));
        Point2 first = ring.get(0);
        Point2 last = ring.get(ring.size() - 1);
        if (first.x != last.x || first.y != last.y) {
            ring.add(first); // auto-close the domain ring
        }
        return new Polygon(ring);
    }

    /** Axis-aligned box with optional 360-degree longitude wrapping. */
    public static final class Bbox extends Coverage {
        public final double minX, minY, maxX, maxY;
        public final boolean wrapX;

        public Bbox(double minX, double minY, double maxX, double maxY, boolean wrapX) {
            if (!(minY <= maxY)) {
                throw new AppException("bad-coverage", "bbox minY must be <= maxY");
            }
            if (wrapX && minX <= maxX && (maxX - minX) > 360.0d + 1e-9d) {
                throw new AppException("bad-coverage", "bbox longitude span cannot exceed 360");
            }
            if (!wrapX && !(minX <= maxX)) {
                throw new AppException("bad-coverage", "bbox minX must be <= maxX when wrapX is false");
            }
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.wrapX = wrapX;
        }

        @Override public String type() { return "bbox"; }

        @Override
        public boolean contains(double x, double y) {
            if (y < minY || y > maxY) return false;
            if (!wrapX) return x >= minX && x <= maxX;
            if (minX <= maxX) {
                // Regular (or wider-than-nominal) box: test x modulo 360
                // against a 360-periodic repetition of [minX, maxX].
                double rel = mod360(x - minX);
                return rel <= maxX - minX;
            }
            // Wrapping box: equivalent regular box [minX, maxX + 360].
            double rel = mod360(x - minX);
            return rel <= maxX + 360.0d - minX;
        }

        private static double mod360(double v) {
            double r = v % 360.0d;
            if (r < 0) r += 360.0d;
            return r;
        }

        @Override
        public List<Point2> samples() {
            double cx = wrapCenter();
            double cy = (minY + maxY) / 2.0d;
            List<Point2> s = new ArrayList<>();
            s.add(new Point2(norm180(cx), cy));
            // corners and edge midpoints; boundary-exact samples matter for
            // the "boundary hits count as inside" rule.
            s.add(new Point2(norm180(minX), minY));
            s.add(new Point2(norm180(maxX), maxY));
            s.add(new Point2(norm180(minX), maxY));
            s.add(new Point2(norm180(maxX), minY));
            s.add(new Point2(norm180(minX), cy));
            s.add(new Point2(norm180(maxX), cy));
            s.add(new Point2(norm180(cx), minY));
            s.add(new Point2(norm180(cx), maxY));
            return s;
        }

        private double wrapCenter() {
            if (minX <= maxX) return (minX + maxX) / 2.0d;
            return (minX + maxX + 360.0d) / 2.0d;
        }

        @Override
        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("type", "bbox");
            List<Object> b = new ArrayList<>();
            b.add(minX); b.add(minY); b.add(maxX); b.add(maxY);
            m.put("bbox", b);
            m.put("wrapX", wrapX);
            return m;
        }
    }

    /** Single-ring polygon evaluated in unwrapped-longitude space. */
    public static final class Polygon extends Coverage {
        public final List<Point2> ring; // closed (first == last)
        final double[] ux;

        public Polygon(List<Point2> closedRing) {
            this.ring = closedRing;
            int n = closedRing.size();
            ux = new double[n];
            double[] uyArr = new double[n];
            ux[0] = closedRing.get(0).x;
            uyArr[0] = closedRing.get(0).y;
            for (int i = 1; i < n; i++) {
                double px = ux[i - 1];
                double cx = closedRing.get(i).x;
                while (cx - px > 180.0d) cx -= 360.0d;
                while (cx - px < -180.0d) cx += 360.0d;
                ux[i] = cx;
                uyArr[i] = closedRing.get(i).y;
            }
            this.uyArr = uyArr;
        }

        private final double[] uyArr;

        @Override public String type() { return "polygon"; }

        @Override
        public boolean contains(double x, double y) {
            // Shift the query longitude next to the unwrapped ring's mean.
            double mean = 0;
            for (double v : ux) mean += v;
            mean /= ux.length;
            double sx = x;
            while (sx - mean > 180.0d) sx -= 360.0d;
            while (sx - mean < -180.0d) sx += 360.0d;
            boolean inside = false;
            int n = ux.length;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = ux[i], yi = uyArr[i];
                double xj = ux[j], yj = uyArr[j];
                boolean intersect = ((yi > y) != (yj > y))
                        && (sx < (xj - xi) * (y - yi) / ((yj - yi) == 0 ? 1e-300 : (yj - yi)) + xi);
                if (intersect) inside = !inside;
            }
            return inside;
        }

        @Override
        public List<Point2> samples() {
            List<Point2> out = new ArrayList<>();
            for (int i = 0; i < ring.size() - 1; i++) out.add(ring.get(i));
            double mx = 0, my = 0;
            for (int i = 0; i < ring.size() - 1; i++) {
                mx += ux[i];
                my += uyArr[i];
            }
            int k = ring.size() - 1;
            mx /= k; my /= k;
            Point2 c = new Point2(norm180(mx), my);
            if (contains(c.x, c.y)) out.add(c);
            return out;
        }

        @Override
        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("type", "polygon");
            List<Object> coords = new ArrayList<>();
            List<Object> ringList = new ArrayList<>();
            for (Point2 p : ring) ringList.add(p.arr());
            coords.add(ringList);
            m.put("coordinates", coords);
            return m;
        }
    }

    /** Normalise a longitude into [-180, 180]. */
    public static double norm180(double lon) {
        double v = ((lon + 180.0d) % 360.0d + 360.0d) % 360.0d - 180.0d;
        return v;
    }
}
