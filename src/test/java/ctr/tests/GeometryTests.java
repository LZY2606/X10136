package ctr.tests;

import ctr.ApiException;
import ctr.GeoJson;
import ctr.PolygonGeometry;
import java.util.List;
import java.util.Map;

public class GeometryTests {
    public void testAntimeridianPolygonIsDetectedAndNotFlattened() {
        Map<String, Object> geo = Map.of("type", "Polygon", "coordinates",
                List.of(List.of(List.of(176.0, -5.0), List.of(-176.0, -5.0), List.of(-176.0, 5.0),
                        List.of(176.0, 5.0), List.of(176.0, -5.0))));
        PolygonGeometry polygon = (PolygonGeometry) GeoJson.parseGeometry(geo);
        Asserts.check(polygon.crossesAntimeridian(), "antimeridian crossing detected");
        Asserts.check(polygon.bounds().crossesAntimeridian(), "bounds keep antimeridian split");
        Asserts.approx(polygon.bounds().minX(), 176.0, 0.0, "east edge is minX");
        Asserts.approx(polygon.bounds().maxX(), -176.0, 0.0, "west edge is maxX");
        Asserts.check(polygon.bounds().contains(ctr.Coordinate.geo(180.0, 0.0)), "180 is inside split bounds");
    }

    public void testOrdinaryBBoxDoesNotContainAcrossTheWorld() {
        Map<String, Object> geo = Map.of("type", "LineString", "coordinates",
                List.of(List.of(10.0, 0.0), List.of(20.0, 0.0)));
        var line = GeoJson.parseGeometry(geo);
        Asserts.check(!line.bounds().contains(ctr.Coordinate.geo(180.0, 0.0)), "ordinary bbox is narrow");
        Asserts.check(line.bounds().contains(ctr.Coordinate.geo(15.0, 0.0)), "ordinary bbox contains middle");
    }

    public void testBoundaryValuesAreAccepted() {
        Map<String, Object> geo = Map.of("type", "Point", "coordinates", List.of(180.0, 90.0));
        var point = (ctr.PointGeometry) GeoJson.parseGeometry(geo);
        ctr.Crs crs = new ctr.Crs("G", "G", "geographic", "degree", "");
        ctr.GeometryService.validateForCrs(point, crs);
        Asserts.approx(point.coordinate().x(), 180.0, 0.0, "longitude boundary exact");
        Asserts.approx(point.coordinate().y(), 90.0, 0.0, "latitude boundary exact");
    }

    public void testLatitudeOutsideRangeRejected() {
        Map<String, Object> geo = Map.of("type", "Point", "coordinates", List.of(120.0, 90.0000001));
        var point = (ctr.PointGeometry) GeoJson.parseGeometry(geo);
        ctr.Crs crs = new ctr.Crs("G", "G", "geographic", "degree", "");
        Asserts.throwsApi(() -> ctr.GeometryService.validateForCrs(point, crs), 400,
                "Latitude", "latitude out of range");
    }

    public void testNonClosedPolygonRejected() {
        Map<String, Object> geo = Map.of("type", "Polygon", "coordinates",
                List.of(List.of(List.of(0.0, 0.0), List.of(1.0, 0.0), List.of(1.0, 1.0), List.of(0.0, 0.0001))));
        Asserts.throwsApi(() -> GeoJson.parseGeometry(geo), 400, "not closed", "polygon closure");
    }

    public void testNonFiniteInputRejectedByJsonParser() {
        try {
            ctr.Json.parse("{\"x\":NaN}");
            throw new AssertionError("NaN should be rejected");
        } catch (IllegalArgumentException e) {
            Asserts.check(e.getMessage().contains("Invalid JSON"), "NaN rejected as invalid JSON");
        }
        try {
            ctr.Json.parse("[1e999]");
            throw new AssertionError("Infinity should be rejected");
        } catch (IllegalArgumentException e) {
            Asserts.check(e.getMessage().contains("Non-finite"), "Infinity rejected");
        }
    }
}
