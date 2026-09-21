package crstation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a batch transformation job. Each pasted object is processed
 * independently: a validation or path failure fails only that object and the
 * diagnostic is retained on the item.
 *
 * <p>Jobs are permanently bound to the registry version and to frozen copies
 * of the edge specifications actually used, so later registry changes can
 * never alter a stored result.
 */
public final class JobProcessor {

    private final Registry registry;

    public JobProcessor(Registry registry) {
        this.registry = registry;
    }

    public static final class ItemResult {
        public final int index;
        public final String featureId;
        public final String name;
        public final boolean success;
        public final String error;
        public final Geometry input;
        public final Geometry output;
        public final PathFinder.Path path;
        public final PathFinder.Analysis analysis;

        ItemResult(int index, String featureId, String name, boolean success, String error,
                   Geometry input, Geometry output, PathFinder.Path path,
                   PathFinder.Analysis analysis) {
            this.index = index;
            this.featureId = featureId;
            this.name = name;
            this.success = success;
            this.error = error;
            this.input = input;
            this.output = output;
            this.path = path;
            this.analysis = analysis;
        }
    }

    public static final class Job {
        public String id;
        public String fingerprint;
        public int registryVersion;
        public String fromCrs;
        public String toCrs;
        public String createdAt;
        public List<ItemResult> items = new ArrayList<>();
        public int successCount;
        public int failureCount;

        public Map<String, Object> toJson(boolean includeCandidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("fingerprint", fingerprint);
            m.put("registryVersion", registryVersion);
            m.put("fromCrs", fromCrs);
            m.put("toCrs", toCrs);
            m.put("createdAt", createdAt);
            m.put("successCount", successCount);
            m.put("failureCount", failureCount);
            List<Object> its = new ArrayList<>();
            for (ItemResult it : items) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("index", it.index);
                if (it.featureId != null) {
                    im.put("featureId", it.featureId);
                }
                if (it.name != null) {
                    im.put("name", it.name);
                }
                im.put("success", it.success);
                if (!it.success) {
                    im.put("error", it.error);
                }
                im.put("input", it.input == null ? null : it.input.toGeoJson());
                if (it.success) {
                    im.put("output", it.output.toGeoJson());
                    im.put("selectedPath", it.path.toJson());
                }
                if (includeCandidates && it.analysis != null) {
                    im.put("candidates", analysisJson(it.analysis));
                }
                its.add(im);
            }
            m.put("items", its);
            return m;
        }
    }

    public static Map<String, Object> analysisJson(PathFinder.Analysis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> accepted = new ArrayList<>();
        for (PathFinder.Path p : a.accepted) {
            Map<String, Object> pm = p.toJson();
            pm.put("selected", false);
            accepted.add(pm);
        }
        m.put("accepted", accepted);
        List<Object> rejected = new ArrayList<>();
        for (PathFinder.Rejected r : a.rejected) {
            rejected.add(r.toJson());
        }
        m.put("rejected", rejected);
        m.put("truncated", a.truncated);
        m.put("pendingEdges", a.pendingEdges);
        return m;
    }

    /** Execute a batch; the job is not persisted (see {@link #runAndStore}). */
    public Job run(String fromCrs, String toCrs, String geoJsonText) {
        Crs src = registry.crs(fromCrs);
        if (src == null) {
            throw new IllegalArgumentException("unknown source CRS: " + fromCrs);
        }
        if (registry.crs(toCrs) == null) {
            throw new IllegalArgumentException("unknown target CRS: " + toCrs);
        }

        List<Geometry.Feature> features;
        Job job = new Job();
        job.fromCrs = fromCrs;
        job.toCrs = toCrs;
        job.registryVersion = registry.version();
        job.createdAt = java.time.Instant.now().toString();

        try {
            features = Geometry.parseGeoJson(geoJsonText);
        } catch (RuntimeException ex) {
            // whole-payload parse failure: fail the job as a single diagnostic item
            ItemResult only = new ItemResult(0, null, null, false,
                    "invalid GeoJSON: " + ex.getMessage(), null, null, null, null);
            job.items.add(only);
            job.failureCount = 1;
            assignFingerprint(job, geoJsonText);
            return job;
        }

        PathFinder finder = new PathFinder(registry);
        int idx = 0;
        for (Geometry.Feature feature : features) {
            Geometry geom = feature.geometry();
            try {
                geom.validate(src.geographic());
                PathFinder.Analysis analysis = finder.analyze(fromCrs, toCrs, geom);
                if (analysis.accepted.isEmpty()) {
                    String reason = noPathReason(analysis);
                    job.items.add(new ItemResult(idx, feature.id(), feature.name(),
                            false, reason, geom, null, null, analysis));
                } else {
                    PathFinder.Path best = analysis.accepted.get(0);
                    Geometry out = geom.map(p -> {
                        double[] q = p;
                        for (PathFinder.Step step : best.steps) {
                            q = step.edge.traverse(step.forward, q);
                        }
                        return q;
                    });
                    job.items.add(new ItemResult(idx, feature.id(), feature.name(),
                            true, null, geom, out, best, analysis));
                    job.successCount++;
                }
            } catch (RuntimeException ex) {
                job.items.add(new ItemResult(idx, feature.id(), feature.name(),
                        false, ex.getMessage(), geom, null, null, null));
            }
            idx++;
        }
        job.failureCount = job.items.size() - job.successCount;
        assignFingerprint(job, geoJsonText);
        return job;
    }

    private static String noPathReason(PathFinder.Analysis analysis) {
        boolean notInvertible = false;
        boolean coverage = false;
        for (PathFinder.Rejected r : analysis.rejected) {
            if ("NOT_INVERTIBLE".equals(r.reason)) {
                notInvertible = true;
            }
            if ("OUT_OF_COVERAGE".equals(r.reason)) {
                coverage = true;
            }
        }
        if (analysis.rejected.isEmpty()) {
            return "no transformation chain connects the two CRSs";
        }
        StringBuilder sb = new StringBuilder("no usable chain: ");
        if (coverage) {
            sb.append("all covering candidates failed coverage");
        }
        if (notInvertible) {
            if (coverage) {
                sb.append("; ");
            }
            sb.append("a required edge is not safely invertible");
        }
        if (!coverage && !notInvertible) {
            sb.append(analysis.rejected.get(0).detail);
        }
        return sb.toString();
    }

    /**
     * Fingerprint = SHA-256 over the canonical serialization of everything
     * that must determine the answer: endpoints, input GeoJSON (normalized),
     * registry version, and the full specs of every active edge.
     */
    private void assignFingerprint(Job job, String rawGeoJson) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("fromCrs", job.fromCrs);
        basis.put("toCrs", job.toCrs);
        basis.put("registryVersion", job.registryVersion);
        List<Object> edgeSpecs = new ArrayList<>();
        for (Edge e : registry.edges()) {
            edgeSpecs.add(e.toJson());
        }
        basis.put("edges", edgeSpecs);
        basis.put("input", normalizeInput(rawGeoJson));
        basis.put("v", 1);
        String canonical = Json.canonical(basis);
        job.fingerprint = sha256(canonical);
        job.id = job.fingerprint.substring(0, 16);
    }

    private static Object normalizeInput(String raw) {
        try {
            return Json.parse(raw);
        } catch (RuntimeException ex) {
            return Map.of("unparseable", raw == null ? "" : raw);
        }
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
