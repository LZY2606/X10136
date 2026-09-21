package coordstation;

import coordstation.json.Json;
import coordstation.model.Edge;
import coordstation.registry.Registry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CycleAndBatchTest {

    private Registry consistentTriangle() {
        Registry r = TestSupport.withCrs("A", "B", "C");
        // A -> B: translate (1, 0)
        r.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.WORLD, 0.1, "[1,0,1,0,1,0]", true)));
        // B -> C: translate (0, 2)
        r.registerEdge(TestSupport.j(TestSupport.edge("e2", "B", "C",
                TestSupport.WORLD, 0.2, "[1,0,0,0,1,2]", true)));
        // A -> C agreeing with e2 o e1: translate (1, 2)
        r.registerEdge(TestSupport.j(TestSupport.edge("e3", "A", "C",
                TestSupport.WORLD, 0.05, "[1,0,1,0,1,2]", true)));
        return r;
    }

    @Test
    void consistentParallelEdgeBecomesActive() {
        Registry r = consistentTriangle();
        assertEquals(Edge.Status.ACTIVE,
                r.edgeList().stream().filter(e -> e.id.equals("e3")).findFirst().orElseThrow().status);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inconsistentEdgeBecomesPendingAndIsExcludedFromPaths() {
        Registry r = consistentTriangle();
        int vBefore = r.version();
        // Disagrees with both the direct edge e3 and the e1+e2 route.
        Map<String, Object> resp = r.registerEdge(TestSupport.j(TestSupport.edge("e4", "A", "C",
                TestSupport.WORLD, 0.05, "[1,0,5,0,1,5]", true)));
        assertEquals("PENDING", resp.get("status"));
        assertTrue(String.valueOf(resp.get("note")).contains("inconsistent"));
        assertEquals(vBefore + 1, r.version());

        Map<String, Object> out = r.transform(
                Geometry2Point(), "A", "C");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) out.get("candidates");
        for (Map<String, Object> c : candidates) {
            assertFalse(String.valueOf(c.get("signature")).contains("e4"),
                    "pending edge must not appear in default paths");
        }
        assertEquals("+e3", ((Map<String, Object>) out.get("chosen")).get("signature"));
    }

    @Test
    void confirmRequiresReasonThenActivatesAndVersions() {
        Registry r = consistentTriangle();
        r.registerEdge(TestSupport.j(TestSupport.edge("e4", "A", "C",
                TestSupport.WORLD, 0.05, "[1,0,5,0,1,5]", true)));
        int v = r.version();
        assertThrows(Registry.RegistryException.class,
                () -> r.confirmEdge("e4", "  "));
        assertEquals(v, r.version(), "rejected confirmation must not bump version");
        Map<String, Object> resp = r.confirmEdge("e4", "实地测量复核，偏移已确认");
        assertEquals("ACTIVE", resp.get("status"));
        assertEquals("实地测量复核，偏移已确认", resp.get("confirmReason"));
        assertEquals(v + 1, r.version());

        // e4 has equal error to e3 but signature "+e4" > "+e3": tie still stable
        Map<String, Object> out = r.transform(Geometry2Point(), "A", "C");
        assertEquals("+e3", ((Map<String, Object>) out.get("chosen")).get("signature"));
    }

    private static coordstation.model.Geometry Geometry2Point() {
        return coordstation.model.Geometry.fromGeoJson(TestSupport.j(TestSupport.point(10, 10)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchItemsSucceedOrFailIndependently() {
        Registry r = TestSupport.withCrs("A", "B");
        r.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.WORLD, 0.1, "[1,0,1,0,1,1]", true)));
        String job = "{\"items\":["
                + "{\"id\":\"good\",\"geometry\":" + TestSupport.point(1, 2) + ",\"src\":\"A\",\"dst\":\"B\"},"
                + "{\"id\":\"bad-lat\",\"geometry\":" + TestSupport.point(1, 95) + ",\"src\":\"A\",\"dst\":\"B\"},"
                + "{\"id\":\"non-finite\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[1e999,1]},"
                + "\"src\":\"A\",\"dst\":\"B\"},"
                + "{\"id\":\"unknown-crs\",\"geometry\":" + TestSupport.point(1, 2)
                + ",\"src\":\"A\",\"dst\":\"Z\"}"
                + "]}";
        Map<String, Object> result = r.runJob(TestSupport.j(job));
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(4, items.size());
        assertEquals("ok", items.get(0).get("status"));
        for (int i : new int[]{1, 2, 3}) {
            assertEquals("failed", items.get(i).get("status"), "item " + i);
            assertFalse(String.valueOf(items.get(i).get("diagnostics")).isBlank(),
                    "failed items keep diagnostics");
        }
        assertTrue(String.valueOf(items.get(2).get("diagnostics")).contains("finite"));
        assertEquals("good", items.get(0).get("id"));
        assertNotNull(result.get("fingerprint"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void registryUpdatesDoNotChangeHistoricalJobs() {
        Registry r = TestSupport.withCrs("A", "B");
        r.registerEdge(TestSupport.j(TestSupport.edge("e1", "A", "B",
                TestSupport.WORLD, 0.5, "[1,0,1,0,1,1]", true)));
        String jobBody = "{\"items\":[{\"id\":\"x\",\"geometry\":"
                + TestSupport.point(3, 4) + ",\"src\":\"A\",\"dst\":\"B\"}]}";
        Map<String, Object> job1 = r.runJob(TestSupport.j(jobBody));
        String fp1 = (String) job1.get("fingerprint");
        int v1 = (int) job1.get("registryVersion");
        List<Map<String, Object>> items1 = (List<Map<String, Object>>) job1.get("items");
        String serializedJob = Json.writeCanonical(job1);

        // Registry evolves: a more accurate parallel edge becomes the default.
        r.registerEdge(TestSupport.j(TestSupport.edge("e2", "A", "B",
                TestSupport.WORLD, 0.1, "[1,0,1,0,1,1]", true)));
        Map<String, Object> fresh = r.runJob(TestSupport.j(jobBody));
        List<Map<String, Object>> freshItems = (List<Map<String, Object>>) fresh.get("items");
        assertEquals("e2",
                ((List<Map<String, Object>>) freshItems.get(0).get("path")).get(0).get("edge"));
        assertNotEquals(fp1, fresh.get("fingerprint"));

        // Historical job-1 is byte-for-byte unchanged.
        Map<String, Object> stored = r.job("job-1");
        assertEquals(serializedJob, Json.writeCanonical(stored));
        assertEquals(v1, stored.get("registryVersion"));
        List<Map<String, Object>> storedItems = (List<Map<String, Object>>) stored.get("items");
        assertEquals(0.5, ((Number) storedItems.get(0).get("cumulativeError")).doubleValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportImportReproducesPathsErrorsAndFingerprints() {
        Registry r1 = consistentTriangle();
        String jobBody = "{\"items\":["
                + "{\"id\":\"p1\",\"geometry\":" + TestSupport.point(10, 10) + ",\"src\":\"A\",\"dst\":\"C\"},"
                + "{\"id\":\"p2\",\"geometry\":" + TestSupport.point(-10, -5) + ",\"src\":\"C\",\"dst\":\"A\"}"
                + "]}";
        Map<String, Object> job1a = r1.runJob(TestSupport.j(jobBody));
        String export1 = Json.writeCanonical(r1.exportStore());

        Registry r2 = new Registry();
        r2.importStore(Json.parseObject(export1));
        assertEquals(export1, Json.writeCanonical(r2.exportStore()),
                "round-tripped store must serialize identically");

        Map<String, Object> job2a = r1.runJob(TestSupport.j(jobBody));
        Map<String, Object> job2b = r2.runJob(TestSupport.j(jobBody));
        assertEquals(job2a.get("fingerprint"), job2b.get("fingerprint"),
                "identical input + state must yield identical fingerprint");
        assertEquals(
                Json.writeCanonical(job2a.get("items")),
                Json.writeCanonical(job2b.get("items")),
                "path selection and error serialization must match after import");
    }

    @Test
    void exportRoundTripsPendingEdgesAndHistory() {
        Registry r1 = consistentTriangle();
        r1.registerEdge(TestSupport.j(TestSupport.edge("e4", "A", "C",
                TestSupport.WORLD, 0.05, "[1,0,5,0,1,5]", true)));
        r1.runJob(TestSupport.j("{\"items\":[]}"));
        String export = Json.writeCanonical(r1.exportStore());
        Registry r2 = new Registry();
        r2.importStore(Json.parseObject(export));
        assertEquals(r1.version(), r2.version());
        assertEquals(Edge.Status.PENDING,
                r2.edgeList().stream().filter(e -> e.id.equals("e4")).findFirst().orElseThrow().status);
        assertNotNull(r2.job("job-1"));
    }
}
