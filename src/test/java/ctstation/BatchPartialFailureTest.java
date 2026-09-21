package ctstation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Every batch object succeeds or fails independently with diagnostics. */
public final class BatchPartialFailureTest {

    public static void run(Asserts a) {
        a.group("batch-partial-failure");
        Service svc = TestSupport.freshService();
        TestSupport.crs(svc, "G", "geographic");
        TestSupport.crs(svc, "P", "projected");
        Coverage.Bbox box = new Coverage.Bbox(0, 0, 10, 10, false);
        TestSupport.edge(svc, "E", "G", "P", new double[]{1, 0, 0, 1, 0, 0},
                box, 0.2, 0.2, Boolean.TRUE);

        Object covered = TestSupport.pointGeo(5, 5);
        Object outside = TestSupport.pointGeo(50, 50);
        Object badLat = TestSupport.pointGeo(5, 91);
        Object line = Json.object("type", "LineString",
                "coordinates", Arrays.asList(Arrays.asList(1.0, 1.0), Arrays.asList(2.0, 2.0)));

        Map<String, Object> job = TestSupport.job(svc, "G", "P", covered, outside, badLat, line);
        Map<String, Object> summary = (Map<String, Object>) job.get("summary");
        a.eq(summary.get("total"), 4, "four items processed");
        a.eq(summary.get("succeeded"), 2, "covered point and line succeed");
        a.eq(summary.get("failed"), 2, "two items fail independently");

        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) job.get("items");
        a.eq(((Map<?, ?>) items.get(0)).get("status"), "success", "item 0 success");
        Map<String, Object> failOutside = (Map<String, Object>) ((Map<?, ?>) items.get(1)).get("error");
        a.eq(failOutside.get("code"), "no-chain", "outside box -> no-chain diagnostic");
        Map<String, Object> failLat = (Map<String, Object>) ((Map<?, ?>) items.get(2)).get("error");
        a.eq(failLat.get("code"), "latitude-out-of-range", "bad latitude diagnostic");
        a.eq(((Map<?, ?>) items.get(3)).get("status"), "success", "line success");

        // Unclosed polygon is rejected before chain selection.
        Object unclosed = Json.object("type", "Polygon",
                "coordinates", Arrays.asList(Arrays.asList(
                        Arrays.asList(1.0, 1.0),
                        Arrays.asList(2.0, 1.0),
                        Arrays.asList(2.0, 2.0),
                        Arrays.asList(1.0, 2.0))));
        a.throwsCode(() -> TestSupport.job(svc, "G", "P", unclosed),
                "ring-not-closed", "unclosed polygon rejected");
    }
}
