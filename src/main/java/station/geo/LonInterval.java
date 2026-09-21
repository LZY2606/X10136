package station.geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A longitude interval on the circle, expressed as west/east bounds in [-180, 180].
 * When west &lt;= east the interval is normal; when west &gt; east it wraps across
 * the antimeridian (e.g. west=170, east=-170 covers 170..180 and -180..-170).
 */
public record LonInterval(double west, double east) {
    public static final double EPS = 1e-9;

    public static LonInterval full() {
        return new LonInterval(-180.0, 180.0);
    }

    /** Width of the interval going east from west, in [0, 360]. */
    public double width() {
        double w = east - west;
        if (w < 0) w += 360.0;
        return w;
    }

    public boolean isFull() {
        return width() >= 360.0 - EPS;
    }

    public boolean containsLon(double lon) {
        if (isFull()) return true;
        double shift = Position.normalizeLon(lon - west);
        if (shift < 0) shift += 360.0;
        return shift <= width() + EPS;
    }

    /** Circular containment: every longitude of {@code other} lies inside this interval. */
    public boolean contains(LonInterval other) {
        if (isFull()) return true;
        double shift = Position.normalizeLon(other.west - west);
        if (shift < 0) shift += 360.0;
        return shift + other.width() <= width() + EPS;
    }

    /**
     * Minimal circular arc covering all given longitudes. Never falls back to a
     * naive min/max box, so point sets straddling the antimeridian get a small
     * wrapping interval instead of a near-global one.
     */
    public static LonInterval coveringArc(List<Double> lons) {
        if (lons.isEmpty()) return full();
        List<Double> sorted = new ArrayList<>(lons.size());
        for (double lon : lons) sorted.add(Position.normalizeLon(lon));
        Collections.sort(sorted);
        int n = sorted.size();
        double maxGap = -1;
        int gapIndex = 0;
        for (int i = 0; i < n; i++) {
            double a = sorted.get(i);
            double b = sorted.get((i + 1) % n) + (i + 1 == n ? 360.0 : 0.0);
            double gap = b - a;
            if (gap > maxGap) {
                maxGap = gap;
                gapIndex = i;
            }
        }
        double west = sorted.get((gapIndex + 1) % n);
        double east = sorted.get(gapIndex);
        return new LonInterval(west, east);
    }
}
