package ctstation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Antimeridian-spanning domains must not be misread by naive min/max boxes. */
public final class AntimeridianTest {

    public static void run(Asserts a) {
        a.group("antimeridian");

        Coverage.Bbox wrap = new Coverage.Bbox(170, 40, -160, 65, true);
        a.check(wrap.contains(180, 50), "lon 180 is inside the wrapping box");
        a.check(wrap.contains(-170, 50), "lon -170 is inside the wrapping box");
        a.check(wrap.contains(175, 65), "lon 175 on latitude boundary is inside");
        a.check(!wrap.contains(0, 50), "Greenwich is outside the Aleutian box");
        a.check(!wrap.contains(169, 50), "lon 169 is just outside to the west");
        a.check(!wrap.contains(-159, 50), "lon -159 is just outside to the east");

        Coverage.Bbox normal = new Coverage.Bbox(-10, -10, 10, 10, true);
        a.check(normal.contains(-10, 0) && normal.contains(10, 0), "ordinary box edges inside");
        a.check(!normal.contains(180, 0), "antipode not in ordinary small box");

        // Polygon domain crossing the antimeridian: a 20-degree-wide strip
        // centred on lon 180.
        Map<String, Object> polySpec = Json.object(
                "type", "polygon",
                "coordinates", Arrays.asList(Arrays.asList(
                        Arrays.asList(170.0, 40.0),
                        Arrays.asList(-170.0, 40.0),
                        Arrays.asList(-170.0, 60.0),
                        Arrays.asList(170.0, 60.0),
                        Arrays.asList(170.0, 40.0))));
        Coverage poly = Coverage.fromMap(polySpec);
        a.check(poly.contains(180, 50), "polygon contains lon 180");
        a.check(poly.contains(-175, 55), "polygon contains -175");
        a.check(!poly.contains(0, 50), "polygon excludes lon 0");

        // End-to-end: identity edge with wrapping coverage selects path only
        // for covered points; uncovered points fail independently in a batch.
        Service svc = TestSupport.freshService();
        TestSupport.crs(svc, "W", "geographic");
        TestSupport.crs(svc, "A", "projected");
        double[] id = {1, 0, 0, 1, 0, 0};
        TestSupport.edge(svc, "E1", "W", "A", id, wrap, 0, 0, Boolean.TRUE);
        Map<String, Object> j = TestSupport.job(svc, "W", "A",
                TestSupport.pointGeo(180, 50), TestSupport.pointGeo(0, 50));
        Map<String, Object> sum = (Map<String, Object>) j.get("summary");
        a.eq(sum.get("succeeded"), 1, "only covered point transforms");
        a.eq(sum.get("failed"), 1, "uncovered point fails in the batch");
        Map<String, Object> fail = TestSupport.item(j, 1);
        Map<String, Object> err = (Map<String, Object>) fail.get("error");
        a.eq(err.get("code"), "no-chain", "diagnostic code is no-chain");
    }
}
