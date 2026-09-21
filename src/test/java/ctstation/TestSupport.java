package ctstation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Helpers for building temporary services and submitting jobs. */
final class TestSupport {

    private TestSupport() {}

    static Service freshService() {
        try {
            Path dir = Files.createTempDirectory("ctr-test-");
            dir.toFile().deleteOnExit();
            return new Service(dir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void crs(Service svc, String id, String kind) {
        svc.registerCrs(Json.object("id", id, "kind", kind));
    }

    static void edge(Service svc, String id, String from, String to, double[] matrix,
                     Coverage cov, double ax, double ay, Boolean invertible) {
        Map<String, Object> body = Json.object(
                "id", id, "sourceCrs", from, "targetCrs", to,
                "transform", Json.object("type", "affine", "matrix", toList(matrix)),
                "coverage", cov == null ? Coverage.WHOLE_WORLD.toMap() : cov.toMap(),
                "accuracyX", ax, "accuracyY", ay,
                "invertible", invertible,
                "loopTolerance", 1.0e-6d);
        svc.registerEdge(body);
    }

    static List<Double> toList(double[] v) {
        return Arrays.asList(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    static Map<String, Object> pointGeo(double x, double y) {
        return Json.object("type", "Point", "coordinates", Arrays.asList(x, y));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> job(Service svc, String from, String to, Object... geos) {
        return svc.runJob(Json.object("sourceCrs", from, "targetCrs", to,
                "objects", Arrays.asList(geos)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> item(Map<String, Object> job, int i) {
        return (Map<String, Object>) ((List<Object>) job.get("items")).get(i);
    }
}
