package ctstation;

import java.util.Arrays;
import java.util.Map;

/** Points exactly on coverage boundaries must count as inside. */
public final class BoundaryTest {

    public static void run(Asserts a) {
        a.group("boundary-hits");
        Service svc = TestSupport.freshService();
        TestSupport.crs(svc, "P", "projected");
        TestSupport.crs(svc, "Q", "projected");
        Coverage.Bbox box = new Coverage.Bbox(0, 0, 100, 100, false);
        double[] m = {1, 0, 0, 1, 1, 1};
        TestSupport.edge(svc, "E", "P", "Q", m, box, 0.5, 0.5, Boolean.TRUE);

        double[][] corners = {{0, 0}, {100, 100}, {0, 100}, {100, 0}, {50, 0}, {100, 50}};
        for (double[] c : corners) {
            Map<String, Object> j = TestSupport.job(svc, "P", "Q", TestSupport.pointGeo(c[0], c[1]));
            Map<String, Object> item = TestSupport.item(j, 0);
            a.eq(item.get("status"), "success",
                    "boundary point (" + c[0] + "," + c[1] + ") accepted");
        }
        Map<String, Object> out = TestSupport.job(svc, "P", "Q", TestSupport.pointGeo(100, 100));
        Map<String, Object> it = TestSupport.item(out, 0);
        Map<String, Object> g = (Map<String, Object>) it.get("output");
        double[] coords = (double[]) g.get("coordinates");
        a.approx(coords[0], 101, 1e-12, "corner x maps");
        a.approx(coords[1], 101, 1e-12, "corner y maps");
    }
}
