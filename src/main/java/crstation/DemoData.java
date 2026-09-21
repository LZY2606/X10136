package crstation;

import java.util.List;
import java.util.Map;

/**
 * Deterministic demo registry seeded on first startup in an empty data dir.
 * Deliberately includes:
 * <ul>
 *   <li>an antimeridian-crossing edge (Tokyo local frame east of 180);</li>
 *   <li>a non-invertible degraded edge (forward only);</li>
 *   <li>two direct edges with identical error for a stable tie-break demo;</li>
 *   <li>a new edge that disagrees with the existing chain and lands PENDING.</li>
 * </ul>
 */
public final class DemoData {

    private DemoData() {
    }

    public static void seed(Registry r) {
        r.addCrs(Map.of(
                "code", "EPSG:4326",
                "name", "WGS 84 (lon/lat)",
                "geographic", true,
                "axes", "longitude, latitude (degrees)",
                "description", "Global geographic coordinates"));
        r.addCrs(Map.of(
                "code", "EPSG:3857",
                "name", "Web Mercator",
                "geographic", false,
                "axes", "x, y (metres)",
                "description", "Spherical Mercator, clamped at 85.06 latitude"));
        r.addCrs(Map.of(
                "code", "LOCAL:TOKYO",
                "name", "Tokyo local datum (demo)",
                "geographic", true,
                "axes", "longitude, latitude (degrees)",
                "description", "Fictional local geographic datum around Tokyo"));
        r.addCrs(Map.of(
                "code", "GRID:JP-PLAN",
                "name", "Japan demo planar grid",
                "geographic", false,
                "axes", "easting, northing (metres)",
                "description", "Fictional local projected grid"));

        // WGS84 -> Web Mercator, invertible, nearly worldwide.
        r.registerEdge(Map.of(
                "id", "wgs84-to-webmerc",
                "fromCrs", "EPSG:4326",
                "toCrs", "EPSG:3857",
                "transform", Map.of("type", "mercator"),
                "domain", List.of(-180.0, -85.0, 180.0, 85.0),
                "accuracy", 5.0,
                "invertible", true,
                "inverseAccuracy", 5.0,
                "description", "Spherical Web Mercator"));

        // Tokyo datum offset (0.001 deg lon, 0.0005 deg lat), invertible.
        r.registerEdge(Map.of(
                "id", "tokyo-from-wgs84",
                "fromCrs", "EPSG:4326",
                "toCrs", "LOCAL:TOKYO",
                "transform", Map.of(
                        "type", "affine",
                        "a", 1.0, "b", 0.0, "c", 0.001,
                        "d", 0.0, "e", 1.0, "f", 0.0005),
                "domain", List.of(138.0, 34.0, 141.0, 37.0),
                "accuracy", 1.0,
                "invertible", true,
                "inverseAccuracy", 1.2,
                "description", "Fictional local datum shift, invertible"));

        // Local Tokyo datum -> planar demo grid: metres = deg * 111000 + origin.
        r.registerEdge(Map.of(
                "id", "tokyo-to-grid",
                "fromCrs", "LOCAL:TOKYO",
                "toCrs", "GRID:JP-PLAN",
                "transform", Map.of(
                        "type", "affine",
                        "a", 111000.0, "b", 0.0, "c", -15432100.0,
                        "d", 0.0, "e", 111000.0, "f", -3918300.0),
                "domain", List.of(138.0, 34.0, 141.0, 37.0),
                "accuracy", 2.0,
                "invertible", true,
                "inverseAccuracy", 2.0,
                "description", "Degree-to-metres local grid"));

        // Degraded forward-only edge from Tokyo datum to planar grid.
        r.registerEdge(Map.of(
                "id", "tokyo-to-grid-degraded",
                "fromCrs", "LOCAL:TOKYO",
                "toCrs", "GRID:JP-PLAN",
                "transform", Map.of(
                        "type", "affine",
                        "a", 111001.0, "b", 0.0, "c", -15432200.0,
                        "d", 0.0, "e", 111001.0, "f", -3918400.0),
                "domain", List.of(139.5, 35.0, 140.2, 35.8),
                "accuracy", 120.0,
                "invertible", false,
                "description", "Forward-only degraded product; inverse unsafe"));

        // Antimeridian-crossing coverage: east frame from 170 to -170.
        r.registerEdge(Map.of(
                "id", "wgs84-pacific-frame",
                "fromCrs", "EPSG:4326",
                "toCrs", "LOCAL:TOKYO",
                "transform", Map.of(
                        "type", "affine",
                        "a", 1.0, "b", 0.0, "c", 0.002,
                        "d", 0.0, "e", 1.0, "f", 0.001),
                "domain", Map.of(
                        "type", "bbox",
                        "bbox", List.of(170.0, 30.0, -170.0, 50.0),
                        "wraparound", true),
                "accuracy", 6.0,
                "invertible", true,
                "inverseAccuracy", 6.0,
                "description", "Pacific frame crossing the antimeridian"));

        // Conflicting direct edge: deliberately disagrees with WGS->Tokyo->Grid
        // chain by ~300 m; loop check must park it in PENDING.
        r.registerEdge(Map.of(
                "id", "wgs84-direct-grid-off",
                "fromCrs", "EPSG:4326",
                "toCrs", "GRID:JP-PLAN",
                "transform", Map.of(
                        "type", "affine",
                        "a", 111000.0, "b", 0.0, "c", -15431670.0,
                        "d", 0.0, "e", 111000.0, "f", -3918140.0),
                "domain", List.of(139.6, 35.4, 139.9, 35.7),
                "accuracy", 3.0,
                "invertible", true,
                "inverseAccuracy", 3.0,
                "description", "Surveyor upload inconsistent with existing chain"));
    }
}
