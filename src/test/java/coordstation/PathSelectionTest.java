package coordstation;

import coordstation.model.Geometry;
import coordstation.registry.Registry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathSelectionTest {

    @Test
    void boundaryExactlyHitsCountAsCovered() {
        Registry r = TestSupport.withCrs("A", "B");
        // region starts exactly at longitude 100.
        r.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.box(100, 0, 120, 60), 0.1, "[1,0,1,0,1,1]", true)));
        Map<String, Object> out = r.transform(
                Geometry.fromGeoJson(TestSupport.j(TestSupport.point(100.0, 30.0))), "A", "B");
        assertNotNull(out.get("chosen"));
    }

    @Test
    void nonInvertibleEdgeCannotBeTraversedBackwards() {
        Registry r = TestSupport.withCrs("A", "B");
        r.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.WORLD, 0.1, "[1,0,1,0,1,1]", false)));
        Registry.NoRouteException e = assertThrows(Registry.NoRouteException.class,
                () -> r.transform(
                        Geometry.fromGeoJson(TestSupport.j(TestSupport.point(0, 0))), "B", "A"));
        // explanation enumerates zero candidates and survives to the caller
        @SuppressWarnings("unchecked")
        java.util.List<Object> candidates =
                (java.util.List<Object>) e.explanation.get("candidates");
        assertTrue(candidates.isEmpty());
    }

    @Test
    void inverseTraversalWorksForInvertibleEdge() {
        Registry r = TestSupport.withCrs("A", "B");
        r.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.WORLD, 0.1, "[1,0,1,0,1,1]", true)));
        Map<String, Object> out = r.transform(
                Geometry.fromGeoJson(TestSupport.j(TestSupport.point(5, 7))), "B", "A");
        @SuppressWarnings("unchecked")
        Map<String, Object> chosen = (Map<String, Object>) out.get("chosen");
        assertEquals("inverse", ((java.util.List<Map<String, Object>>)
                chosen.get("path")).get(0).get("direction"));
        @SuppressWarnings("unchecked")
        java.util.List<Object> result = (java.util.List<Object>)
                ((Map<String, Object>) out.get("result")).get("coordinates");
        assertEquals(4.0, ((Number) result.get(0)).doubleValue(), 1e-12);
        assertEquals(6.0, ((Number) result.get(1)).doubleValue(), 1e-12);
    }

    @Test
    void coverageBeatsLowerError() {
        Registry r = TestSupport.withCrs("A", "B");
        // very accurate edge covering only [100,120]
        r.registerEdge(TestSupport.j(TestSupport.edge("eGood", "A", "B",
                TestSupport.box(100, 0, 120, 60), 0.01, "[1,0,1,0,1,1]", true)));
        // less accurate edge covering the world
        r.registerEdge(TestSupport.j(TestSupport.edge("eWide", "A", "B",
                TestSupport.WORLD, 0.9, "[1,0,1,0,1,1]", true)));
        Map<String, Object> out = r.transform(
                Geometry.fromGeoJson(TestSupport.j(TestSupport.point(0, 0))), "A", "B");
        @SuppressWarnings("unchecked")
        Map<String, Object> chosen = (Map<String, Object>) out.get("chosen");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> path =
                (java.util.List<Map<String, Object>>) chosen.get("path");
        assertEquals("eWide", path.get(0).get("edge"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> candidates =
                (java.util.List<Map<String, Object>>) out.get("candidates");
        for (Map<String, Object> c : candidates) {
            if ("+eGood".equals(c.get("signature"))) {
                assertEquals(false, c.get("covered"));
                assertEquals(false, c.get("accepted"));
            }
        }
    }

    @Test
    void stableTieBreakBySignature() {
        Registry r = TestSupport.withCrs("A", "B");
        r.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.WORLD, 0.2, "[1,0,1,0,1,1]", true)));
        r.registerEdge(TestSupport.j(TestSupport.edge("e2", "A", "B",
                TestSupport.WORLD, 0.2, "[1,0,1,0,1,1]", true)));
        Map<String, Object> out1 = r.transform(
                Geometry.fromGeoJson(TestSupport.j(TestSupport.point(0, 0))), "A", "B");
        @SuppressWarnings("unchecked")
        Map<String, Object> chosen1 = (Map<String, Object>) out1.get("chosen");
        assertEquals("+e1", chosen1.get("signature"));
        // repeated selection is stable
        for (int i = 0; i < 5; i++) {
            Map<String, Object> outN = r.transform(
                    Geometry.fromGeoJson(TestSupport.j(TestSupport.point(0, 0))), "A", "B");
            assertEquals("+e1", ((Map<String, Object>) outN.get("chosen")).get("signature"));
        }
    }

    @Test
    void chainedPathAccumulatesErrorAndTransform() {
        Registry r = TestSupport.withCrs("A", "B", "C");
        r.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.WORLD, 0.1, "[1,0,1,0,1,0]", true)));
        r.registerEdge(TestSupport.j(TestSupport.edge("e2", "B", "C",
                TestSupport.WORLD, 0.2, "[1,0,0,0,1,2]", true)));
        Map<String, Object> out = r.transform(
                Geometry.fromGeoJson(TestSupport.j(TestSupport.point(10, 10))), "A", "C");
        @SuppressWarnings("unchecked")
        Map<String, Object> chosen = (Map<String, Object>) out.get("chosen");
        // 0.1 + 0.2 serialized deterministically
        assertEquals(0.30000000000000004, ((Number) chosen.get("cumulativeError")).doubleValue());
        @SuppressWarnings("unchecked")
        java.util.List<Object> result = (java.util.List<Object>)
                ((Map<String, Object>) out.get("result")).get("coordinates");
        assertEquals(11.0, ((Number) result.get(0)).doubleValue(), 1e-12);
        assertEquals(12.0, ((Number) result.get(1)).doubleValue(), 1e-12);
    }

    @Test
    void antimeridianGeometryCoveredByCrossingEdge() {
        Registry r = TestSupport.withCrs("A", "B");
        r.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.box(160, 0, -160, 40), 0.1, "[1,0,0,0,1,0]", true)));
        String line = "{\"type\":\"LineString\",\"coordinates\":[[175,10],[-175,12]]}";
        Map<String, Object> out = r.transform(
                Geometry.fromGeoJson(TestSupport.j(line)), "A", "B");
        assertNotNull(out.get("chosen"));
    }
}
