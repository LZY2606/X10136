package ctr.tests;

import ctr.AffineTransform2D;
import ctr.Crs;
import ctr.Edge;
import ctr.GeoJson;
import ctr.PathPlanner;
import ctr.Region;
import ctr.RegistryService;
import ctr.RegistrySnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class RegistryTests {
    private final Region region = Region.box(0, 0, 10, 10, false);
    private final AffineTransform2D identity = new AffineTransform2D(1, 0, 0, 0, 1, 0);

    private RegistryService fresh() throws Exception {
        Path directory = Files.createTempDirectory("ctr-registry-");
        RegistryService service = new RegistryService(directory);
        service.registerCrs(new Crs("A", "A", "projected", "m", ""));
        service.registerCrs(new Crs("B", "B", "projected", "m", ""));
        service.registerCrs(new Crs("C", "C", "projected", "m", ""));
        return service;
    }

    private Edge direct(String id, double shift, double accuracy) {
        return new Edge(id, "A", "B", new AffineTransform2D(1, 0, shift, 0, 1, 0),
                true, accuracy, accuracy, region, region, "test", "", "1");
    }

    public void testConsistentLoopActivatesImmediately() throws Exception {
        RegistryService service = fresh();
        Edge edge = direct("A_B_SMALL", 0.001, 1.0);
        Map<String, Object> response = service.registerEdge(edge, 3.0);
        Asserts.equal(response.get("status"), "ACTIVE", "within tolerance active");
        Asserts.equal(service.snapshot().version() >= 2, true, "active edge makes a new version");
    }

    public void testInconsistentLoopIsPendingAndExcludedFromDefaultPath() throws Exception {
        RegistryService service = fresh();
        service.registerEdge(direct("A_B_BASE", 1, 0.01), 3.0);
        int before = service.snapshot().version();
        Map<String, Object> response = service.registerEdge(direct("A_B_BAD", 50, 0.01), 3.0);
        Asserts.equal(response.get("status"), "PENDING", "large discrepancy pending");
        Asserts.equal(service.snapshot().version(), before, "pending does not change active version");
        var geometry = GeoJson.parseGeometry(Map.of("type", "Point", "coordinates", List.of(5.0, 5.0)));
        var plan = PathPlanner.plan(service.snapshot(), "A", "B", geometry);
        Asserts.equal(plan.chosen().arcs().get(0).edgeId(), "A_B_BASE", "pending not selected");
        Asserts.check(plan.rejectedArcs().stream().anyMatch(arc -> "A_B_BAD".equals(arc.get("edgeId"))),
                "pending shown as rejected");
    }

    public void testConfirmationRequiresReasonAndCreatesNewVersion() throws Exception {
        RegistryService service = fresh();
        service.registerEdge(direct("A_B_BASE", 1, 0.01), 3.0);
        service.registerEdge(direct("A_B_BAD", 50, 0.01), 3.0);
        int before = service.snapshot().version();
        Asserts.throwsApi(() -> service.confirmPending("A_B_BAD", "  "), 400,
                "reason", "blank confirmation reason");
        RegistrySnapshot confirmed = service.confirmPending("A_B_BAD", "Field campaign supersedes old edge");
        Asserts.check(confirmed.version() == before + 1, "confirmation increments version");
        Asserts.check(confirmed.edge("A_B_BAD") != null, "confirmed edge active");
    }

    public void testUnknownCrsAndDuplicateRejected() throws Exception {
        RegistryService service = fresh();
        Edge bad = new Edge("X_Y", "A", "MISSING", identity, false, 1, null, region, null, "t", "", "1");
        Asserts.throwsApi(() -> service.registerEdge(bad, 3.0), 400, "Unknown", "missing target");
        service.registerEdge(direct("A_B_UNIQUE", 0, 1), 3.0);
        Asserts.throwsApi(() -> service.registerEdge(direct("A_B_UNIQUE", 0, 1), 3.0), 409,
                "already exists", "duplicate edge");
    }

    public void testPendingEdgeCannotBeConfirmedTwice() throws Exception {
        RegistryService service = fresh();
        service.registerEdge(direct("A_B_BASE", 1, 0.01), 3.0);
        service.registerEdge(direct("A_B_BAD", 50, 0.01), 3.0);
        service.confirmPending("A_B_BAD", "accepted manually");
        Asserts.throwsApi(() -> service.confirmPending("A_B_BAD", "again"), 404,
                "Pending edge not found", "double confirmation");
    }
}
