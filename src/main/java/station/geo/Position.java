package station.geo;

/** A longitude/latitude position in degrees. */
public record Position(double lon, double lat) {
    /** Normalize a longitude into [-180, 180]. */
    public static double normalizeLon(double lon) {
        double r = lon % 360.0;
        if (r > 180.0) r -= 360.0;
        if (r < -180.0) r += 360.0;
        return r;
    }

    /** Shortest signed angular distance from a to b going east, in [-180, 180]. */
    public static double lonDiff(double from, double to) {
        return normalizeLon(to - from);
    }
}
