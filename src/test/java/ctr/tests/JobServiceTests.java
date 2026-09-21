package ctr.tests;

import ctr.AffineTransform2D;
import ctr.Crs;
import ctr.Edge;
import ctr.JobService;
import ctr.Region;
import ctr.RegistryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class JobServiceTests {
    private final Region region = Region.box(0, 0, 100, 100, false);
    private RegistryService registry;
    private JobService jobs;

    private void setup() throws Exception {
        Path directory = Files.createTempDirectory("ctr-jobs-");
        registry = new RegistryService(directory);
        registry.registerCrs(new Crs("A", "A", "projected", "m", ""));
        registry.registerCrs(new Crs("B", "B", "projected", "m", ""));
        registry.registerCrs(new Crs("C", "C", "projected", "m", ""));
        registry.registerEdge(new Edge("A_B_OK", "A", "B",
                new AffineTransform2D(1, 0, 10, 0, 1, 20), true, 0.5, 0.5,
                region, region, "t", "", "1"), 3.0);
        registry.registerEdge(new Edge("B_C_OK", "B", "C",
                new AffineTransform2D(1, 0, -3, 0, 1, 4), false, 0.25, null,
                region, null, "t", "", "1"), 3.0);
        jobs = new JobService(directory, registry);
    }

    private Map<String, Object> item(String id, Object coordinates) {
        return Map.of("id", id, "geometry", Map.of("type", "Point", "coordinates", coordinates));
    }

    public void testBatchIndependentSuccessAndFailure() throws Exception {
        setup();
        Map<String, Object> request = Map.of("sourceCrs", "A", "targetCrs", "C", "items", List.of(
                item("good", List.of(10.0, 20.0)),
                item("outside", List.of(150.0, 20.0)),
                item("badCrs", Map.of("id", "badCrs", "sourceCrs", "A", "targetCrs", "NOPE",
                        "geometry", Map.of("type", "Point", "coordinates", List.of(1, 2))))));
        Map<String, Object> result = jobs.submit(request);
        Asserts.equal(result.get("status"), "PARTIAL_SUCCESS", "partial batch");
        Asserts.equal(result.get("successCount"), 1, "one success");
        Asserts.equal(result.get("failureCount"), 2, "two independent failures");
        List<?> items = (List<?>) result.get("items");
        Map<?, ?> good = (Map<?, ?>) items.get(0);
        Map<?, ?> outside = (Map<?, ?>) items.get(1);
        Asserts.equal(good.get("status"), "SUCCESS", "first item success");
        Asserts.equal(outside.get("status"), "FAILED", "second item failure retained");
        Asserts.check(String.valueOf(outside.get("diagnostic")).contains("valid region"),
                "coverage diagnostic retained");
    }

    @SuppressWarnings("unchecked")
    public void testJobFingerprintIsStableForIdenticalSubmission() throws Exception {
        setup();
        Map<String, Object> request = Map.of("label", "stable", "sourceCrs", "A", "targetCrs", "B",
                "items", List.of(item("one", List.of(1.0, 2.0))));
        Map<String, Object> first = jobs.submit(request);
        Map<String, Object> second = jobs.submit(request);
        Asserts.equal(first.get("id"), second.get("id"), "same job id");
        Asserts.equal(first.get("fingerprint"), second.get("fingerprint"), "same fingerprint");
        List<Map<String, Object>> firstItems = (List<Map<String, Object>>) first.get("items");
        Map<String, Object> output = (Map<String, Object>) firstItems.get(0).get("output");
        List<Object> coordinates = (List<Object>) output.get("coordinates");
        Asserts.approx(((Number) coordinates.get(0)).doubleValue(), 11.0, 0, "x transformed");
        Asserts.approx(((Number) coordinates.get(1)).doubleValue(), 22.0, 0, "y transformed");
    }

    public void testHistoryDoesNotChangeWhenRegistryLaterUpdates() throws Exception {
        setup();
        Map<String, Object> request = Map.of("sourceCrs", "A", "targetCrs", "B",
                "items", List.of(item("history", List.of(5.0, 6.0))));
        Map<String, Object> first = jobs.submit(request);
        int oldVersion = (Integer) first.get("registryVersion");
        registry.registerEdge(new Edge("A_B_NEWER_BUT_PENDING", "A", "B",
                new AffineTransform2D(1, 0, 999, 0, 1, 999), true, 0.01, 0.01,
                region, region, "t", "", "1"), 3.0);
        Map<String, Object> reloaded = jobs.get((String) first.get("id"));
        Asserts.approx(((Number) reloaded.get("registryVersion")).doubleValue(), oldVersion, 0, "stored historical version");
        Map<?, ?> snapshot = (Map<?, ?>) reloaded.get("registrySnapshot");
        Asserts.approx(((Number) snapshot.get("version")).doubleValue(), oldVersion, 0, "embedded snapshot old version");
        Asserts.check(!String.valueOf(snapshot).contains("A_B_NEWER_BUT_PENDING"), "new edge absent from history");
    }

    public void testValidationFailuresAreBatchDiagnostics() throws Exception {
        setup();
        Map<String, Object> unclosed = Map.of("type", "Polygon", "coordinates",
                List.of(List.of(List.of(0.0, 0.0), List.of(1.0, 0.0), List.of(1.0, 1.0), List.of(0.0, 0.001))));
        Map<String, Object> request = Map.of("sourceCrs", "A", "targetCrs", "B", "items", List.of(
                Map.of("id", "open", "geometry", unclosed),
                Map.of("id", "good", "geometry", Map.of("type", "Point", "coordinates", List.of(1.0, 2.0)))));
        Map<String, Object> result = jobs.submit(request);
        Asserts.equal(result.get("status"), "PARTIAL_SUCCESS", "validation and success coexist");
        List<?> items = (List<?>) result.get("items");
        Asserts.equal(((Map<?, ?>) items.get(0)).get("errorCode"), "VALIDATION", "validation code");
        Asserts.equal(((Map<?, ?>) items.get(1)).get("status"), "SUCCESS", "other item still succeeds");
    }

    public void testNonFininateNumberCannotEnterBatch() throws Exception {
        setup();
        try {
            ctr.Json.parse("{\"items\":[{\"geometry\":{\"type\":\"Point\",\"coordinates\":[Infinity,1]}}]}");
            throw new AssertionError("Infinity must be rejected");
        } catch (IllegalArgumentException e) {
            Asserts.check(e.getMessage().contains("Non-finite") || e.getMessage().contains("Invalid JSON"), "non-finite parse rejected");
        }
    }
}
