package ctstation;

import java.util.Arrays;
import java.util.Map;

/** Up-front validation: latitude range, closed rings, 2D positions. */
public final class ValidationTest {

    public static void run(Asserts a) {
        a.group("validation");
        Service svc = TestSupport.freshService();
        TestSupport.crs(svc, "G", "geographic");
        TestSupport.crs(svc, "P", "projected");
        TestSupport.edge(svc, "E", "G", "P", new double[]{1, 0, 0, 1, 0, 0},
                null, 0, 0, Boolean.TRUE);

        Map<String, Object> latJob = TestSupport.job(svc, "G", "P",
                TestSupport.pointGeo(0, 90.0001), TestSupport.pointGeo(0, -90.0001));
        Map<String, Object> e1 = (Map<String, Object>) TestSupport.item(latJob, 0).get("error");
        a.eq(e1.get("code"), "latitude-out-of-range", "latitude 90.0001 rejected (item diag)");
        Map<String, Object> e2 = (Map<String, Object>) TestSupport.item(latJob, 1).get("error");
        a.eq(e2.get("code"), "latitude-out-of-range", "latitude -90.0001 rejected (item diag)");

        // Exactly 90 / -90 are valid boundary latitudes.
        Map<String, Object> j = TestSupport.job(svc, "G", "P",
                TestSupport.pointGeo(120, 90), TestSupport.pointGeo(120, -90));
        a.eq(((Map<?, ?>) TestSupport.item(j, 0)).get("status"), "success", "lat +90 accepted");
        a.eq(((Map<?, ?>) TestSupport.item(j, 1)).get("status"), "success", "lat -90 accepted");

        // Projected CRS has no latitude restriction.
        TestSupport.crs(svc, "Q", "projected");
        TestSupport.edge(svc, "EP", "P", "Q", new double[]{1, 0, 0, 1, 0, 0},
                null, 0, 0, Boolean.TRUE);
        Map<String, Object> j2 = TestSupport.job(svc, "P", "Q", TestSupport.pointGeo(0, 1e9));
        a.eq(((Map<?, ?>) TestSupport.item(j2, 0)).get("status"), "success",
                "projected accepts any finite y");

        // 3D position rejected.
        Object threeD = Json.object("type", "Point", "coordinates", Arrays.asList(1.0, 2.0, 3.0));
        a.throwsCode(() -> TestSupport.job(svc, "G", "P", threeD),
                "bad-geojson", "3D position rejected");

        // Short ring rejected.
        Object shortRing = Json.object("type", "Polygon",
                "coordinates", Arrays.asList(Arrays.asList(
                        Arrays.asList(0.0, 0.0), Arrays.asList(1.0, 0.0), Arrays.asList(1.0, 1.0))));
        a.throwsCode(() -> TestSupport.job(svc, "G", "P", shortRing),
                "ring-not-closed", "short polygon ring rejected");
    }
}
