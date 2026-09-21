package ctr;

import java.util.List;

public final class SeedData {
    private SeedData() {
    }

    public static List<Crs> crs() {
        return List.of(
                new Crs("WGS84_GEO", "WGS 84 geographic", "geographic", "degree",
                        "Longitude/latitude in degrees, EPSG-style lon-lat order."),
                new Crs("JGD2011_GEO", "JGD 2011 geographic", "geographic", "degree",
                        "Japanese modern geographic reference frame."),
                new Crs("TOKYO97_GEO", "Tokyo Datum 1892/1997 geographic", "geographic", "degree",
                        "Legacy Japanese geographic reference frame."),
                new Crs("LOCAL_GRID", "Tokyo local demonstration grid", "projected", "metre",
                        "Synthetic projected metre grid for deterministic local calculations."),
                new Crs("SURVEY_GRID", "Surveyor-only local grid", "projected", "metre",
                        "A grid whose published edge is safe only in the local-to-survey direction."),
                new Crs("PACIFIC_GEO", "Pacific demonstration geographic", "geographic", "degree",
                        "Geographic coordinates around the antimeridian."));
    }

    public static List<Edge> edges() {
        Region japan = Region.box(138.0, 34.0, 142.0, 37.0, false);
        Region tokyo = Region.box(139.2, 35.0, 140.3, 36.0, false);
        Region local = Region.box(0.0, 0.0, 200000.0, 150000.0, false);
        Region survey = Region.box(1000.0, 2000.0, 190000.0, 140000.0, false);
        Region pacific = Region.box(170.0, -25.0, -160.0, 25.0, true);
        return List.of(
                new Edge("WGS84_TO_JGD2011", "WGS84_GEO", "JGD2011_GEO",
                        new AffineTransform2D(1, 0, 0.00008, 0, 1, 0.00005),
                        true, 0.00002, 0.00002, japan, japan,
                        "seed", "Deterministic local datum offset approximation", "1"),
                new Edge("JGD2011_TO_TOKYO97", "JGD2011_GEO", "TOKYO97_GEO",
                        new AffineTransform2D(1, 0, -0.00072, 0, 1, -0.00044),
                        true, 0.00012, 0.00012, japan, japan,
                        "seed", "Deterministic legacy datum approximation", "1"),
                new Edge("WGS84_TO_LOCAL", "WGS84_GEO", "LOCAL_GRID",
                        new AffineTransform2D(91000, 0, -12_686_000, 0, 111000, -3_890_000),
                        true, 2.0, 2.2, tokyo, local,
                        "seed", "Synthetic Tokyo planar projection", "1"),
                new Edge("LOCAL_TO_SURVEY_FWD", "LOCAL_GRID", "SURVEY_GRID",
                        new AffineTransform2D(1.0001, 0, 12, 0, 0.9998, -8),
                        false, 0.35, null, local, null,
                        "seed", "Inverse withheld: legacy survey coefficients are not safely reversible", "1"),
                new Edge("PACIFIC_DATELINE_REF", "WGS84_GEO", "PACIFIC_GEO",
                        new AffineTransform2D(1, 0, 0, 0, 1, 0),
                        true, 0.0001, 0.0001, pacific, pacific,
                        "seed", "Identity edge with an antimeridian-aware valid region", "1"));
    }
}
