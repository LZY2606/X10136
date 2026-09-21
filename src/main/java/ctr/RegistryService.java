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

public final class RegistryService {
    private final Path file;
    private RegistrySnapshot snapshot;
    private List<Map<String, Object>> pending = new ArrayList<>();
    private String createdAt;
    private String updatedAt;

    public RegistryService(Path dataDirectory) {
        this.file = dataDirectory.resolve("registry.json");
        load();
    }

    public synchronized RegistrySnapshot snapshot() {
        return snapshot;
    }

    public synchronized RegistrySnapshot snapshot(int version) {
        if (version != snapshot.version()) {
            throw new ApiException(409, "Registry version " + version + " is not the current version "
                    + snapshot.version() + "; historical snapshots are embedded in each saved job");
        }
        return snapshot;
    }

    public synchronized RegistrySnapshot registerCrs(Crs requested) {
        if (snapshot.crs(requested.id()) != null) {
            throw new ApiException(409, "CRS already exists: " + requested.id());
        }
        if (pendingId(requested.id()) != null) {
            throw new ApiException(409, "A pending edge already uses this id: " + requested.id());
        }
        List<Crs> crsList = new ArrayList<>(snapshot.crsList());
        crsList.add(requested);
        return commit(crsList, snapshot.edges(), pending, "CRS " + requested.id() + " registered");
    }

    public synchronized Map<String, Object> registerEdge(Edge edge, Double toleranceFactor) {
        validateReferences(edge);
        if (snapshot.edge(edge.id) != null || pendingId(edge.id) != null) {
            throw new ApiException(409, "Edge id already exists: " + edge.id);
        }
        double factor = toleranceFactor == null ? 3.0 : toleranceFactor;
        if (!(factor > 0) || !Double.isFinite(factor)) {
            throw new ApiException(400, "toleranceFactor must be a positive finite number");
        }
        ConsistencyCheck check = checkConsistency(edge, factor);
        Map<String, Object> stored = edge.toJson();
        stored.put("status", check.status());
        stored.put("submittedAt", Instant.now().toString());
        Map<String, Object> checkJson = new LinkedHashMap<>();
        checkJson.put("status", check.status());
        checkJson.put("maximumAbsoluteDifference", check.maxDifference());
        checkJson.put("tolerance", check.tolerance());
        checkJson.put("sampleCount", check.samples());
        checkJson.put("details", check.details());
        stored.put("consistencyCheck", checkJson);
        if (check.status().equals("PENDING")) {
            pending.add(stored);
            this.snapshot = new RegistrySnapshot(snapshot.version(), snapshot.fingerprint(),
                    snapshot.crsList(), snapshot.edges(), List.copyOf(pending));
            persist();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "PENDING");
            response.put("edge", stored);
            response.put("consistencyCheck", checkJson);
            response.put("currentVersion", snapshot.version());
            response.put("message", "Edge requires confirmation and is excluded from default paths");
            return response;
        }
        List<Edge> edges = new ArrayList<>(snapshot.edges());
        edges.add(edge);
        commit(snapshot.crsList(), edges, pending, "Edge " + edge.id + " registered");
        responseActive(stored, checkJson);
        return stored;
    }

    public synchronized RegistrySnapshot confirmPending(String id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, "A non-empty confirmation reason is required");
        }
        Map<String, Object> pendingMap = snapshot.pending(id);
        if (pendingMap == null) {
            throw new ApiException(404, "Pending edge not found: " + id);
        }
        Edge edge = Edge.fromJson(pendingMap);
        List<Map<String, Object>> remaining = new ArrayList<>();
        for (Map<String, Object> candidate : pending) {
            if (!id.equals(candidate.get("id"))) {
                remaining.add(candidate);
            }
        }
        List<Edge> edges = new ArrayList<>(snapshot.edges());
        edges.add(edge);
        RegistrySnapshot committed = commit(snapshot.crsList(), edges, remaining,
                "Pending edge " + id + " confirmed: " + reason);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("edgeId", id);
        audit.put("reason", reason);
        audit.put("confirmedAt", Instant.now().toString());
        audit.put("version", committed.version());
        appendAudit(audit);
        return committed;
    }

    public synchronized List<Map<String, Object>> pendingEdges() {
        return List.copyOf(pending);
    }

    public synchronized void replaceWithImport(Map<String, Object> data, String createdAtValue) {
        RegistrySnapshot parsed = parseSnapshot(data, new ArrayList<>());
        List<Map<String, Object>> importedPending = new ArrayList<>();
        Object rawPendingValue = data.get("pendingEdges");
        if (rawPendingValue != null) {
            for (Object item : Json.array(rawPendingValue, "pendingEdges")) {
                Map<String, Object> map = Json.object(item, "pending edge");
                importedPending.add(new LinkedHashMap<>(map));
            }
        }
        validateSnapshot(parsed);
        this.snapshot = new RegistrySnapshot(parsed.version(), parsed.fingerprint(),
                parsed.crsList(), parsed.edges(), List.copyOf(importedPending));
        this.pending = importedPending;
        this.createdAt = createdAtValue;
        this.updatedAt = Instant.now().toString();
        persist();
    }

    public synchronized Map<String, Object> exportBundle() {
        Map<String, Object> bundle = snapshot.toJson(true);
        bundle.put("createdAt", createdAt);
        bundle.put("exportedAt", Instant.now().toString());
        return bundle;
    }

    private void responseActive(Map<String, Object> stored, Map<String, Object> checkJson) {
        stored.put("status", "ACTIVE");
        stored.put("activeVersion", snapshot.version());
        stored.put("consistencyCheck", checkJson);
    }

    private void validateReferences(Edge edge) {
        if (snapshot.crs(edge.fromCrs) == null) {
            throw new ApiException(400, "Unknown fromCrs: " + edge.fromCrs);
        }
        if (snapshot.crs(edge.toCrs) == null) {
            throw new ApiException(400, "Unknown toCrs: " + edge.toCrs);
        }
    }

    private ConsistencyCheck checkConsistency(Edge candidate, double factor) {
        List<Map<String, Object>> details = new ArrayList<>();
        int samples = 0;
        double maxDifference = 0.0;
        double maxTolerance = 0.0;
        boolean parallel = false;
        for (Coordinate point : candidate.region.sample()) {
            samples++;
            PathPlanner.PlanResult plan = PathPlanner.plan(snapshot, candidate.fromCrs, candidate.toCrs,
                    new PointGeometry(point));
            if (!plan.eligible()) {
                continue;
            }
            parallel = true;
            Coordinate direct = candidate.forward.apply(point);
            Coordinate existing = plan.chosen().totalTransform().apply(point);
            double difference = distance(direct, existing, candidate.toCrs);
            double tolerance = factor * (candidate.forwardAccuracy + plan.chosen().errorBound());
            maxDifference = Math.max(maxDifference, difference);
            maxTolerance = Math.max(maxTolerance, tolerance);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("point", point.toMap());
            detail.put("direct", direct.toMap());
            detail.put("existingPath", existing.toMap());
            detail.put("difference", difference);
            detail.put("tolerance", tolerance);
            detail.put("withinTolerance", difference <= tolerance);
            details.add(detail);
        }
        String status = parallel && maxDifference > maxTolerance ? "PENDING" : "ACTIVE";
        return new ConsistencyCheck(status, maxDifference, maxTolerance, samples, details);
    }

    private double distance(Coordinate a, Coordinate b, String crsId) {
        Crs crs = snapshot.crs(crsId);
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        if (crs != null && "geographic".equals(crs.axes())) {
            dx = normalizeDelta(dx);
        }
        return Math.hypot(dx, dy);
    }

    private static double normalizeDelta(double value) {
        double result = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return result;
    }

    private String pendingId(String id) {
        for (Map<String, Object> map : pending) {
            if (id.equals(map.get("id"))) {
                return id;
            }
        }
        return null;
    }

    private RegistrySnapshot commit(List<Crs> crsList, List<Edge> edges,
                                    List<Map<String, Object>> pendingEdges, String note) {
        int version = snapshot == null ? 1 : snapshot.version() + 1;
        RegistrySnapshot next = buildSnapshot(version, crsList, edges, pendingEdges);
        this.snapshot = next;
        this.pending = new ArrayList<>(pendingEdges);
        this.updatedAt = Instant.now().toString();
        persist();
        appendAudit(Map.of("version", version, "at", updatedAt, "note", note));
        return next;
    }

    private RegistrySnapshot buildSnapshot(int version, List<Crs> crsList, List<Edge> edges,
                                           List<Map<String, Object>> pendingEdges) {
        validateList(crsList, edges);
        String fingerprint = fingerprint(canonicalSnapshot(version, crsList, edges));
        return new RegistrySnapshot(version, fingerprint, crsList, edges, pendingEdges);
    }

    private void validateList(List<Crs> crsList, List<Edge> edges) {
        for (Edge edge : edges) {
            boolean from = crsList.stream().anyMatch(crs -> crs.id().equals(edge.fromCrs));
            boolean to = crsList.stream().anyMatch(crs -> crs.id().equals(edge.toCrs));
            if (!from || !to) {
                throw new ApiException(400, "Active edge " + edge.id + " refers to a missing CRS");
            }
        }
    }

    private void validateSnapshot(RegistrySnapshot parsed) {
        validateList(parsed.crsList(), parsed.edges());
    }

    private static Map<String, Object> canonicalSnapshot(int version, List<Crs> crsList, List<Edge> edges) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema", "coordinate-transform-registry/registry/v1");
        map.put("version", version);
        List<Object> crses = new ArrayList<>();
        crsList.stream().map(Crs::toJson).sorted(new JsonComparator("id")).forEach(crses::add);
        map.put("crs", crses);
        List<Object> edgeList = new ArrayList<>();
        edges.stream().map(Edge::toJson).sorted(new JsonComparator("id")).forEach(edgeList::add);
        map.put("edges", edgeList);
        return map;
    }

    static String fingerprint(Map<String, Object> canonical) {
        String json = Json.write(canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RegistrySnapshot parseSnapshot(Map<String, Object> data, List<Map<String, Object>> pendingList) {
        int version = (int) Json.number(Json.required(data, "version"), "version");
        List<Crs> crsList = new ArrayList<>();
        for (Object rawCrs : Json.arrayField(data, "crs")) {
            crsList.add(Crs.fromJson(Json.object(rawCrs, "CRS")));
        }
        List<Edge> edges = new ArrayList<>();
        for (Object rawEdge : Json.arrayField(data, "edges")) {
            edges.add(Edge.fromJson(Json.object(rawEdge, "edge")));
        }
        String expected = String.valueOf(data.getOrDefault("fingerprint", ""));
        RegistrySnapshot parsed = buildSnapshot(version, crsList, edges, pendingList);
        if (!expected.isBlank() && !expected.equals("null") && !expected.equals(parsed.fingerprint())) {
            throw new ApiException(400, "Import fingerprint mismatch; data is not a portable export of this schema");
        }
        return parsed;
    }

    private synchronized void load() {
        try {
            if (Files.exists(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, Object> data = Json.object(Json.parse(text), "registry file");
                List<Map<String, Object>> loadedPending = new ArrayList<>();
                for (Object item : Json.arrayField(data, "pendingEdges")) {
                    loadedPending.add(new LinkedHashMap<>(Json.object(item, "pending edge")));
                }
                this.snapshot = parseSnapshot(data, loadedPending);
                this.pending = loadedPending;
                this.createdAt = Json.optionalString(data, "createdAt", Instant.now().toString());
                this.updatedAt = Json.optionalString(data, "updatedAt", createdAt);
                return;
            }
            Files.createDirectories(file.getParent());
            this.createdAt = Instant.now().toString();
            this.updatedAt = createdAt;
            this.snapshot = buildSnapshot(0, List.of(), List.of(), List.of());
            this.pending = new ArrayList<>();
            seed();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load registry from " + file, e);
        }
    }

    private void seed() {
        List<Crs> crsList = new ArrayList<>(SeedData.crs());
        List<Edge> edges = new ArrayList<>(SeedData.edges());
        RegistrySnapshot seeded = buildSnapshot(1, crsList, edges, List.of());
        this.snapshot = seeded;
        persist();
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> data = snapshot.toJson(true);
            data.put("createdAt", createdAt);
            data.put("updatedAt", updatedAt);
            Path temp = file.resolveSibling(".registry.json.tmp");
            Files.writeString(temp, Json.pretty(data), StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist registry", e);
        }
    }

    private void appendAudit(Map<String, Object> entry) {
        Path audit = file.resolveSibling("registry-audit.jsonl");
        try {
            Files.createDirectories(audit.getParent());
            Files.writeString(audit, Json.write(entry) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to append registry audit", e);
        }
    }

    private record ConsistencyCheck(String status, double maxDifference, double tolerance, int samples,
                                    List<Map<String, Object>> details) {
    }

    static final class JsonComparator implements java.util.Comparator<Object> {
        private final String key;

        JsonComparator(String key) {
            this.key = key;
        }

        @Override
        @SuppressWarnings("unchecked")
        public int compare(Object left, Object right) {
            Object lv = ((Map<String, Object>) left).get(key);
            Object rv = ((Map<String, Object>) right).get(key);
            return String.valueOf(lv).compareTo(String.valueOf(rv));
        }
    }
}
