package ctstation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic demo content created on first start (or via POST /api/reseed).
 *
 * <p>The graph contains:
 * <ul>
 *   <li>WGS84 (geographic), LOCAL-M and GRID-B (projected metre grids),
 *       ALEUT-M (projected metre grid anchored near the antimeridian);</li>
 *   <li>three active edges forming a consistent WGS84 &harr; LOCAL-M
 *       &harr; GRID-B loop (used to show error-ordered path selection and
 *       stable ties);</li>
 *   <li>a near-singular, effectively non-invertible edge;</li>
 *   <li>an antimeridian-spanning WGS84 coverage box;</li>
 *   <li>an intentionally inconsistent loop-closing edge that lands in
 *       pending state.</li>
 * </ul>
 */
public final class Seed {

    private Seed() {}

    public static void apply(Service svc) {
        register(svc, new Crs("WGS84", "WGS 84 lon/lat", "geographic", "degrees"));
        register(svc, new Crs("LOCAL-M", "Local metre grid", "projected", "metres"));
        register(svc, new Crs("GRID-B", "Grid B metre grid", "projected", "metres"));
        register(svc, new Crs("ALEUT-M", "Aleutian metre grid", "projected", "metres"));
        register(svc, new Crs("FLAT-Q", "Flat squash grid", "projected", "metres"));

        // LOCAL-M -> WGS84 : metres to degrees around origin (lon 139.7, lat 35.6)
        // x(north)=lat degrees *111320 ; x(east)=lon degrees *111320*cos(lat)
        double lat0 = 35.6d;
        double lon0 = 139.7d;
        double mPerDegLat = 111320.0d;
        double mPerDegLon = 111320.0d * Math.cos(Math.toRadians(lat0));
        Affine localToWgs = new Affine(
                0.0d, 1.0d / mPerDegLon,
                1.0d / mPerDegLat, 0.0d,
                lon0, lat0);
        edge(svc, "E_LOCAL_WGS", "LOCAL-M", "WGS84", localToWgs,
                Coverage.WHOLE_WORLD, 2.0e-7d, 2.0e-7d, null);

        // LOCAL-M -> GRID-B : small rotation-free offset grid.
        Affine localToGridB = new Affine(1.0001d, 0.0d, 0.0d, 0.9999d, 12.0d, -7.0d);
        edge(svc, "E_LOCAL_GRIDB", "LOCAL-M", "GRID-B", localToGridB,
                bbox(-500000, -500000, 500000, 500000, false),
                0.05d, 0.05d, null);

        // GRID-B -> WGS84 consistent with the two edges above (composed).
        Affine gridBToLocal = localToGridB.inverse();
        Affine gridBToWgs = localToWgs.compose(gridBToLocal);
        edge(svc, "E_GRIDB_WGS", "GRID-B", "WGS84", gridBToWgs,
                Coverage.WHOLE_WORLD, 5.0e-7d, 5.0e-7d, null);

        // Antimeridian-spanning WGS84 coverage: box 170 .. -160 (wraps east).
        Affine wgsToAleut = new Affine(1.0d, 0.0d, 0.0d, 1.0d, 0.0d, 0.0d);
        edge(svc, "E_WGS_ALEUT_WRAP", "WGS84", "ALEUT-M", wgsToAleut,
                bbox(170.0d, 40.0d, -160.0d, 65.0d, true),
                0.0d, 0.0d, null);

        // Near-singular edge onto its own grid: no loop to disagree with,
        // and it must never gain a reverse arc.
        Affine singular = new Affine(1.0d, 0.0d, 1.0e-14d, 0.0d, 3.0d, 4.0d);
        edge(svc, "E_FLAT_SQUASH", "LOCAL-M", "FLAT-Q", singular,
                bbox(-1000, -1000, 1000, 1000, false), 0.01d, 0.01d, false);

        // Inconsistent loop-closing edge WGS84 -> GRID-B, deliberately wrong.
        Affine wgsToGridBad = new Affine(
                0.0d, mPerDegLon * 1.07d,
                mPerDegLat * 0.94d, 0.0d,
                -139.7d * mPerDegLon * 1.07d + 12.0d,
                -35.6d * mPerDegLat * 0.94d - 7.0d);
        // Bounded box around Tokyo so registration samples land inside the
        // existing loop's coverage and the disagreement is detectable.
        edge(svc, "E_WGS_GRIDB_BAD", "WGS84", "GRID-B", wgsToGridBad,
                bbox(139.0d, 35.0d, 140.5d, 36.5d, false), 0.1d, 0.1d, null);
    }

    private static Coverage bbox(double minX, double minY, double maxX, double maxY, boolean wrap) {
        Map<String, Object> spec = Json.object(
                "type", "bbox",
                "bbox", java.util.Arrays.asList(minX, minY, maxX, maxY),
                "wrapX", wrap);
        return Coverage.fromMap(spec);
    }

    private static void register(Service svc, Crs c) {
        svc.registerCrs(c.toMap());
    }

    private static void edge(Service svc, String id, String from, String to,
                             Affine affine, Coverage cov, double ax, double ay, Boolean inv) {
        Map<String, Object> body = Json.object(
                "id", id,
                "sourceCrs", from,
                "targetCrs", to,
                "transform", affine.toMap(),
                "coverage", cov.toMap(),
                "accuracyX", ax,
                "accuracyY", ay,
                "invertible", inv,
                "loopTolerance", 1.0e-2d);
        svc.registerEdge(body);
    }

    /** Slight helper exposed for API documentation. */
    public static Map<String, Object> info() {
        return Json.object("seed", "demo-v1");
    }
}
