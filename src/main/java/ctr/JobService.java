package ctr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class JobService {
    private final Path directory;
    private final RegistryService registryService;

    public JobService(Path dataDirectory, RegistryService registryService) {
        this.directory = dataDirectory.resolve("jobs");
        this.registryService = registryService;
        try {
            Files.createDirectories(directory);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create job directory", e);
        }
    }

    public synchronized Map<String, Object> submit(Map<String, Object> request) {
        Integer requestedVersion = request.containsKey("registryVersion")
                ? (int) Json.number(Json.required(request, "registryVersion"), "registryVersion")
                : null;
        RegistrySnapshot snapshot = requestedVersion == null
                ? registryService.snapshot()
                : registryService.snapshot(requestedVersion);
        String defaultSource = request.containsKey("sourceCrs") ? Json.string(request, "sourceCrs") : null;
        String defaultTarget = request.containsKey("targetCrs") ? Json.string(request, "targetCrs") : null;
        List<Object> rawItems = Json.arrayField(request, "items");
        if (rawItems.isEmpty()) {
            throw new ApiException(400, "Batch must contain at least one item");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int index = 0;
        for (Object rawItem : rawItems) {
            items.add(processItem(index++, rawItem, defaultSource, defaultTarget, snapshot));
        }
        String label = Json.optionalString(request, "label", "");
        Map<String, Object> canonical = canonicalJob(defaultSource, defaultTarget, rawItems, label, snapshot);
        String fingerprint = RegistryService.fingerprint(canonical);
        String id = fingerprint.substring(0, 16);
        int success = (int) items.stream().filter(item -> "SUCCESS".equals(item.get("status"))).count();
        int failed = items.size() - success;
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", id);
        job.put("fingerprint", fingerprint);
        job.put("label", label);
        job.put("submittedAt", Instant.now().toString());
        job.put("status", failed == 0 ? "SUCCESS" : success == 0 ? "FAILED" : "PARTIAL_SUCCESS");
        job.put("successCount", success);
        job.put("failureCount", failed);
        job.put("registryVersion", snapshot.version());
        job.put("registryFingerprint", snapshot.fingerprint());
        job.put("items", items);
        job.put("registrySnapshot", snapshot.portableJson());
        job.put("request", canonicalRequest(defaultSource, defaultTarget, rawItems, label));
        persist(id, job);
        return job;
    }

    public synchronized Map<String, Object> get(String id) {
        Path file = directory.resolve(id + ".json");
        if (!Files.exists(file)) {
            throw new ApiException(404, "Job not found: " + id);
        }
        try {
            return Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)), "job file");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read job " + id, e);
        }
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            paths.filter(path -> path.toString().endsWith(".json")).sorted().forEach(path -> {
                try {
                    Map<String, Object> job = Json.object(Json.parse(Files.readString(path, StandardCharsets.UTF_8)), "job");
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("id", job.get("id"));
                    summary.put("fingerprint", job.get("fingerprint"));
                    summary.put("label", job.get("label"));
                    summary.put("status", job.get("status"));
                    summary.put("submittedAt", job.get("submittedAt"));
                    summary.put("successCount", job.get("successCount"));
                    summary.put("failureCount", job.get("failureCount"));
                    summary.put("registryVersion", job.get("registryVersion"));
                    jobs.add(summary);
                } catch (Exception e) {
                    throw new IllegalStateException("Unable to read stored job " + path, e);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Unable to list jobs", e);
        }
        return jobs;
    }

    private Map<String, Object> processItem(int index, Object rawItem, String defaultSource,
                                            String defaultTarget, RegistrySnapshot snapshot) {
        Map<String, Object> item = Json.object(rawItem, "batch item " + index);
        String itemId = Json.optionalString(item, "id", "item-" + index);
        String source = Json.optionalString(item, "sourceCrs", defaultSource);
        String target = Json.optionalString(item, "targetCrs", defaultTarget);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("id", itemId);
        if (source == null || target == null) {
            return failure(result, source, target, item.get("geometry"), "VALIDATION",
                    "Each item requires sourceCrs and targetCrs (as item fields or batch defaults)");
        }
        try {
            if (snapshot.crs(source) == null) {
                throw new ApiException(400, "Unknown source CRS: " + source);
            }
            if (snapshot.crs(target) == null) {
                throw new ApiException(400, "Unknown target CRS: " + target);
            }
            Geometry input = GeoJson.parseGeometry(Json.required(item, "geometry"));
            GeometryService.validateForCrs(input, snapshot.crs(source));
            PathPlanner.PlanResult plan = PathPlanner.plan(snapshot, source, target, input);
            if (!plan.eligible()) {
                result.put("sourceCrs", source);
                result.put("targetCrs", target);
                result.put("input", input.toJson());
                result.put("status", "FAILED");
                result.put("errorCode", "NO_ELIGIBLE_PATH");
                result.put("diagnostic", plan.diagnostic());
                result.put("pathDecision", plan.toJson());
                return result;
            }
            Geometry output = GeometryService.apply(input, plan.chosen(), snapshot.crs(target));
            result.put("sourceCrs", source);
            result.put("targetCrs", target);
            result.put("status", "SUCCESS");
            result.put("input", input.toJson());
            result.put("output", output.toJson());
            result.put("pathDecision", plan.toJson());
            result.put("errorBound", plan.chosen().errorBound());
            result.put("explanation", PathPlanner.explain(plan.chosen().arcs(), plan.chosen().errorBound()));
            return result;
        } catch (ApiException e) {
            return failure(result, source, target, item.get("geometry"),
                    e.status() == 400 ? "VALIDATION" : "TRANSFORM", e.getMessage());
        }
    }

    private Map<String, Object> failure(Map<String, Object> result, String source, String target,
                                        Object input, String code, String diagnostic) {
        result.put("sourceCrs", source);
        result.put("targetCrs", target);
        result.put("input", input);
        result.put("status", "FAILED");
        result.put("errorCode", code);
        result.put("diagnostic", diagnostic);
        return result;
    }

    private Map<String, Object> canonicalJob(String defaultSource, String defaultTarget, List<Object> rawItems,
                                             String label, RegistrySnapshot snapshot) {
        Map<String, Object> map = new TreeMap<>();
        map.put("schema", "coordinate-transform-registry/job-fingerprint/v1");
        map.put("label", label == null ? "" : label);
        map.put("sourceCrs", defaultSource == null ? "" : defaultSource);
        map.put("targetCrs", defaultTarget == null ? "" : defaultTarget);
        map.put("items", canonicalize(rawItems));
        Map<String, Object> registry = new TreeMap<>();
        registry.put("version", snapshot.version());
        registry.put("fingerprint", snapshot.fingerprint());
        map.put("registry", registry);
        return map;
    }

    private Map<String, Object> canonicalRequest(String defaultSource, String defaultTarget, List<Object> rawItems,
                                                 String label) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        if (defaultSource != null) {
            map.put("sourceCrs", defaultSource);
        }
        if (defaultTarget != null) {
            map.put("targetCrs", defaultTarget);
        }
        map.put("items", canonicalize(rawItems));
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Object canonicalize(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                sorted.put(entry.getKey(), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List) {
            List<Object> list = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                list.add(canonicalize(item));
            }
            return list;
        }
        return value;
    }

    private void persist(String id, Map<String, Object> job) {
        Path file = directory.resolve(id + ".json");
        try {
            Path temp = directory.resolve("." + id + ".json.tmp");
            Files.writeString(temp, Json.pretty(job), StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist job to " + file, e);
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
