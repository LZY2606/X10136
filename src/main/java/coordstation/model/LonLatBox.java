package coordstation.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Longitude/latitude region. A box with minLon &gt; maxLon crosses the
 * antimeridian (e.g. minLon=170, maxLon=-170 covers 170..180 and -180..-170).
 * Containment is inclusive on every boundary so exact boundary hits count.
 */
public final class LonLatBox {
    public final double minLon;
    public final double minLat;
    public final double maxLon;
    public final double maxLat;

    public LonLatBox(double minLon, double minLat, double maxLon, double maxLat) {
        this.minLon = minLon;
        this.minLat = minLat;
        this.maxLon = maxLon;
        this.maxLat = maxLat;
    }

    public boolean crossesAntimeridian() {
        return minLon > maxLon;
    }

    /** Longitude coverage as one or two plain intervals inside [-180, 180]. */
    public List<double[]> lonIntervals() {
        List<double[]> out = new ArrayList<>();
        if (!crossesAntimeridian()) {
            out.add(new double[]{minLon, maxLon});
        } else {
            out.add(new double[]{minLon, 180.0});
            out.add(new double[]{-180.0, maxLon});
        }
        return out;
    }

    public boolean contains(double lon, double lat) {
        if (lat < minLat || lat > maxLat) return false;
        if (!crossesAntimeridian()) return lon >= minLon && lon <= maxLon;
        return lon >= minLon || lon <= maxLon;
    }

    /** True when every point of {@code inner} is inside this box. */
    public boolean covers(LonLatBox inner) {
        if (inner.minLat < minLat || inner.maxLat > maxLat) return false;
        List<double[]> outer = lonIntervals();
        for (double[] in : inner.lonIntervals()) {
            if (!coveredByAny(in[0], in[1], outer)) return false;
        }
        return true;
    }

    private static boolean coveredByAny(double lo, double hi, List<double[]> outer) {
        // A single outer interval must contain the whole inner interval
        // (inner intervals never wrap, so one container suffices).
        for (double[] o : outer) {
            if (lo >= o[0] && hi <= o[1]) return true;
        }
        return false;
    }

    public boolean intersects(LonLatBox other) {
        if (other.maxLat < minLat || other.minLat > maxLat) return false;
        for (double[] a : lonIntervals()) {
            for (double[] b : other.lonIntervals()) {
                if (a[0] <= b[1] && b[0] <= a[1]) return true;
            }
        }
        return false;
    }

    /** Intersection, or null when disjoint. Result may itself cross the antimeridian. */
    public LonLatBox intersection(LonLatBox other) {
        double latLo = Math.max(minLat, other.minLat);
        double latHi = Math.min(maxLat, other.maxLat);
        if (latLo > latHi) return null;
        List<double[]> pieces = new ArrayList<>();
        for (double[] a : lonIntervals()) {
            for (double[] b : other.lonIntervals()) {
                double lo = Math.max(a[0], b[0]);
                double hi = Math.min(a[1], b[1]);
                if (lo <= hi) pieces.add(new double[]{lo, hi});
            }
        }
        if (pieces.isEmpty()) return null;
        if (pieces.size() == 1) {
            double[] p = pieces.get(0);
            return new LonLatBox(p[0], latLo, p[1], latHi);
        }
        // Two disjoint pieces (eastern + western side) => the intersection wraps.
        double lo = Math.max(pieces.get(0)[0], pieces.get(1)[0]);
        double hi = Math.min(pieces.get(0)[1], pieces.get(1)[1]);
        return new LonLatBox(lo, latLo, hi, latHi);
    }

    /**
     * Region enclosing the given longitudes/latitudes, antimeridian-aware:
     * when the naive span exceeds 180 degrees the lons are re-based to [0, 360)
     * and the result is expressed as a crossing box.
     */
    public static LonLatBox enclosing(List<double[]> points) {
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
        for (double[] p : points) {
            minLat = Math.min(minLat, p[1]);
            maxLat = Math.max(maxLat, p[1]);
            minLon = Math.min(minLon, p[0]);
            maxLon = Math.max(maxLon, p[0]);
        }
        if (maxLon - minLon <= 180.0) {
            return new LonLatBox(minLon, minLat, maxLon, maxLat);
        }
        double rMin = Double.POSITIVE_INFINITY, rMax = Double.NEGATIVE_INFINITY;
        for (double[] p : points) {
            double lon = p[0] < 0 ? p[0] + 360.0 : p[0];
            rMin = Math.min(rMin, lon);
            rMax = Math.max(rMax, lon);
        }
        // rMin in [180,360), rMax in (180,360]: crossing box.
        double hi = rMax > 180.0 ? rMax - 360.0 : rMax;
        return new LonLatBox(rMin, minLat, hi, maxLat);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minLon", minLon);
        m.put("minLat", minLat);
        m.put("maxLon", maxLon);
        m.put("maxLat", maxLat);
        return m;
    }

    public static LonLatBox fromJson(Map<String, Object> m) {
        return new LonLatBox(
                coordstation.json.Json.num(m, "minLon"),
                coordstation.json.Json.num(m, "minLat"),
                coordstation.json.Json.num(m, "maxLon"),
                coordstation.json.Json.num(m, "maxLat"));
    }
}
