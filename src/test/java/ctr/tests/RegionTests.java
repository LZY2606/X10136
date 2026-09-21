package ctr.tests;

import ctr.Coordinate;
import ctr.Region;

public class RegionTests {
    public void testAntimeridianRegionBothSidesAndBoundaryHit() {
        Region region = Region.box(170.0, -20.0, -170.0, 20.0, true);
        Asserts.check(region.contains(Coordinate.geo(170.0, 0.0)), "east exact boundary");
        Asserts.check(region.contains(Coordinate.geo(-170.0, 0.0)), "west exact boundary");
        Asserts.check(region.contains(Coordinate.geo(180.0, 20.0)), "180 included");
        Asserts.check(!region.contains(Coordinate.geo(0.0, 0.0)), "Greenwich excluded");
        Asserts.check(!region.contains(Coordinate.geo(180.0, 21.0)), "latitude boundary enforced");
    }

    public void testInvalidAntimeridianShapeRejected() {
        Asserts.throwsApi(() -> Region.box(-170.0, -1.0, 170.0, 1.0, true), 400,
                "minX > maxX", "antimeridian orientation");
    }
}
