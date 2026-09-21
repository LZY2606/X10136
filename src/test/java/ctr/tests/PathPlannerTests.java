package ctr.tests;

import ctr.AffineTransform2D;
import ctr.Arc;
import ctr.Coordinate;
import ctr.Crs;
import ctr.Edge;
import ctr.GeoJson;
import ctr.PathPlanner;
import ctr.Region;
import ctr.RegistrySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PathPlannerTests {
    private final AffineTransform2D identity = new AffineTransform2D(1, 0, 0, 0, 1, 0);
    private final Region whole = Region.box(-180, -90, 180, 90, false);
    private final Region tokyo = Region.box(139, 35, 141, 36, false);

    @SuppressWarnings("unchecked")
    private RegistrySnapshot snapshot(List<Edge> edges) {
        List<Crs> crses = List.of(
                new Crs("A", "A", "geographic", "degree", ""),
                new Crs("B", "B", "geographic", "degree", ""),
                new Crs("C", "C", "geographic", "degree", ""));
        return new RegistrySnapshot(1, "test", crses, edges, List.of());
    }

    private Edge edge(String id, String from, String to, double accuracy, Region region, boolean inverse) {
        return new Edge(id, from, to, identity, inverse, accuracy, inverse ? accuracy : null,
                region, inverse ? region : null, "test", "", "1");
    }

    public void testBoundaryExactlyHitsCoverage() {
        var snapshot = snapshot(List.of(edge("A_C_EXACT", "A", "C", 1, tokyo, false)));
        var geometry = GeoJson.parseGeometry(Map.of("type", "Point", "coordinates", List.of(139.0, 35.0)));
        var result = PathPlanner.plan(snapshot, "A", "C", geometry);
        Asserts.check(result.eligible(), "exact min boundary is accepted");
    }

    public void testCoverageIsConsideredBeforeLowerError() {
        var outside = Region.box(0, 0, 10, 10, false);
        var lowErrorButOutside = edge("A_C_LOW_ERROR", "A", "C", 0.01, outside, false);
        var viaB1 = edge("A_B", "A", "B", 1, tokyo, false);
        var viaB2 = edge("B_C", "B", "C", 1, whole, false);
        var snapshot = snapshot(List.of(lowErrorButOutside, viaB1, viaB2));
        var geometry = GeoJson.parseGeometry(Map.of("type", "Point", "coordinates", List.of(140.0, 35.5)));
        var result = PathPlanner.plan(snapshot, "A", "C", geometry);
        Asserts.check(result.eligible(), "covered path exists");
        List<Arc> arcs = result.chosen().arcs();
        Asserts.equal(arcs.size(), 2, "selects two-hop covered path");
        Asserts.equal(arcs.get(0).edgeId(), "A_B", "first hop deterministic");
        Asserts.equal(result.candidates().get(0).status(), "ELIGIBLE", "best candidate eligible");
        Asserts.check(result.candidates().stream().anyMatch(c -> c.status().equals("EXCLUDED_COVERAGE")),
                "low-error short path is shown excluded");
    }

    public void testStableTieBreaksByLexicographicArcSignature() {
        var first = edge("A_B_TIE_A", "A", "B", 2.5, whole, false);
        var second = edge("A_B_TIE_B", "A", "B", 2.5, whole, false);
        var snapshot = snapshot(List.of(second, first));
        var geometry = GeoJson.parseGeometry(Map.of("type", "Point", "coordinates", List.of(0.0, 0.0)));
        var result = PathPlanner.plan(snapshot, "A", "B", geometry);
        Asserts.equal(result.chosen().arcs().get(0).edgeId(), "A_B_TIE_A", "lexicographic tie winner");
    }

    public void testIrreversibleEdgeIsExcludedAndExplained() {
        var onlyForward = edge("B_TO_A", "B", "A", 1, whole, false);
        var snapshot = snapshot(List.of(onlyForward));
        var geometry = GeoJson.parseGeometry(Map.of("type", "Point", "coordinates", List.of(0.0, 0.0)));
        var result = PathPlanner.plan(snapshot, "A", "B", geometry);
        Asserts.check(!result.eligible(), "unsafe inverse cannot be used");
        Asserts.check(result.rejectedArcs().stream().anyMatch(arc ->
                "IRREVERSIBLE".equals(arc.get("code")) && "B_TO_A".equals(arc.get("edgeId"))),
                "irreversible reason retained");
    }

    public void testAccumulatedErrorUsesOperatorNormPropagation() {
        AffineTransform2D scale = new AffineTransform2D(2, 0, 0, 0, 1, 0);
        Edge first = new Edge("A_B_SCALE", "A", "B", scale, true, 1.0, 1.0, whole, whole, "test", "", "1");
        AffineTransform2D secondTransform = new AffineTransform2D(2, 0, 0, 0, 1, 0);
        Edge second = new Edge("B_C_SCALE", "B", "C", secondTransform, true, 3.0, 3.0,
                whole, whole, "test", "", "1");
        var snapshot = snapshot(List.of(first, second));
        var geometry = GeoJson.parseGeometry(Map.of("type", "Point", "coordinates", List.of(0.0, 0.0)));
        var result = PathPlanner.plan(snapshot, "A", "C", geometry);
        Asserts.approx(result.chosen().errorBound(), 5.0, 1e-14,
                "second intrinsic error plus scaled upstream error: 3 + 2*1");
    }

    public void testIdentityCrsPathIsStableZeroEdge() {
        var snapshot = snapshot(new ArrayList<>());
        var geometry = GeoJson.parseGeometry(Map.of("type", "Point", "coordinates", List.of(1.0, 2.0)));
        var result = PathPlanner.plan(snapshot, "A", "A", geometry);
        Asserts.check(result.eligible(), "same CRS identity");
        Asserts.equal(result.chosen().arcs().size(), 0, "zero edges");
        Asserts.approx(result.chosen().errorBound(), 0, 0, "zero error");
    }
}
