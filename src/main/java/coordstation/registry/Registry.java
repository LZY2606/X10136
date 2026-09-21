package coordstation.registry;

import coordstation.json.Json;
import coordstation.model.Affine;
import coordstation.model.Crs;
import coordstation.model.Edge;
import coordstation.model.Geometry;
import coordstation.model.LonLatBox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Versioned coordinate-transform registry. Every mutation bumps the version and
 * appends to an immutable log; finished jobs keep the registry version and the
 * full path they used, so later registry updates never rewrite history.
 */
public final class Registry {

    public static final double CYCLE_TOLERANCE = 1e-6;
    public static final int MAX_PATH_DEPTH = 8;

    public static final class RegistryException extends RuntimeException {
        public RegistryException(String msg) { super(msg); }
    }

    private final Map<String, Crs> crsMap = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private final List<Map<String, Object>> jobs = new ArrayList<>();
    private final List<Map<String, Object>> log = new ArrayList<>();
    private int version = 0;
    private int jobSeq = 0;

    // ---------- queries ----------

    public int version() { return version; }
    public List<Map<String, Object>> log() { return log; }
    public List<Map<String, Object>> jobs() { return jobs; }

    public Map<String, Object> job(String id) {
        for (Map<String, Object> j : jobs) {
            if (id.equals(j.get("jobId"))) return j;
        }
        return null;
    }

    public List<Crs> crsList() { return new ArrayList<>(crsMap.values()); }

    public List<Edge> edgeList() { return new ArrayList<>(edges.values()); }

    public List<Edge> activeEdges() {
        List<Edge> out = new ArrayList<>();
        for (Edge e : edges.values()) {
            if (e.status == Edge.Status.ACTIVE) out.add(e);
        }
        return out;
    }

    public Map<String, Object> stateJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        List<Object> crs = new ArrayList<>();
        for (Crs c : crsMap.values()) crs.add(c.toJson());
        m.put("crs", crs);
        List<Object> es = new ArrayList<>();
        for (Edge e : edges.values()) es.add(e.toJson());
        m.put("edges", es);
        List<Object> jobIds = new ArrayList<>();
        for (Map<String, Object> j : jobs) jobIds.add(j.get("jobId"));
        m.put("jobs", jobIds);
        return m;
    }

    // ---------- mutations ----------

    public synchronized Map<String, Object> registerCrs(Map<String, Object> body) {
        Crs c = Crs.fromJson(body);
        if (c.id.isEmpty()) throw new RegistryException("crs id must not be empty");
        if (crsMap.containsKey(c.id)) throw new RegistryException("crs already exists: " + c.id);
        crsMap.put(c.id, c);
        bump("register-crs", "registered CRS " + c.id);
        return c.toJson();
    }

    public synchronized Map<String, Object> registerEdge(Map<String, Object> body) {
        Edge e = Edge.fromJson(body);
        if (e.id.isEmpty()) throw new RegistryException("edge id must not be empty");
        if (edges.containsKey(e.id)) throw new RegistryException("edge already exists: " + e.id);
        if (!crsMap.containsKey(e.src)) throw new RegistryException("unknown src crs: " + e.src);
        if (!crsMap.containsKey(e.dst)) throw new RegistryException("unknown dst crs: " + e.dst);
        if (!Double.isFinite(e.accuracy) || e.accuracy < 0)
            throw new RegistryException("edge accuracy must be a finite number >= 0");
        validateBox(e.region);
        if (e.invertible && !e.forward.invertible())
            throw new RegistryException("edge declared invertible but affine determinant is zero");

        String inconsistency = firstCycleInconsistency(e);
        e.status = inconsistency == null ? Edge.Status.ACTIVE : Edge.Status.PENDING;
        if (inconsistency != null) e.note = inconsistency;
        edges.put(e.id, e);
        bump("register-edge", "registered edge " + e.id + " (" + e.status + ")");
        return e.toJson();
    }

    public synchronized Map<String, Object> confirmEdge(String edgeId, String reason) {
        Edge e = edges.get(edgeId);
        if (e == null) throw new RegistryException("unknown edge: " + edgeId);
        if (e.status != Edge.Status.PENDING)
            throw new RegistryException("edge is not pending: " + edgeId);
        if (reason == null || reason.trim().isEmpty())
            throw new RegistryException("confirmation requires a reason");
        e.status = Edge.Status.ACTIVE;
        e.confirmReason = reason;
        bump("confirm-edge", "confirmed edge " + edgeId + ": " + reason);
        return e.toJson();
    }

    private static void validateBox(LonLatBox b) {
        for (double v : new double[]{b.minLon, b.minLat, b.maxLon, b.maxLat}) {
            if (!Double.isFinite(v)) throw new RegistryException("region bounds must be finite");
        }
        if (b.minLat > b.maxLat) throw new RegistryException("region minLat > maxLat");
        if (b.minLat < -90 || b.maxLat > 90) throw new RegistryException("region latitude out of range");
        if (b.minLon < -180 || b.minLon > 180 || b.maxLon < -180 || b.maxLon > 180)
            throw new RegistryException("region longitude out of range");
    }

    private void bump(String action, String detail) {
        version++;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("version", version);
        entry.put("action", action);
        entry.put("detail", detail);
        log.add(entry);
    }

    // ---------- cycle consistency ----------

    /**
     * Compares the candidate edge against every existing ACTIVE route between the
     * same endpoints on a sample grid over the shared valid region. Returns a
     * diagnostic string when an obvious inconsistency is found, else null.
     */
    private String firstCycleInconsistency(Edge candidate) {
        List<PathFinder.Path> routes = PathFinder.findAll(
                activeEdges(), candidate.src, candidate.dst, candidate.region, MAX_PATH_DEPTH);
        for (PathFinder.Path route : routes) {
            if (route.steps.isEmpty()) continue;
            LonLatBox region = candidate.region;
            for (PathFinder.Step s : route.steps) {
                region = region.intersection(s.edge.region);
                if (region == null) break;
            }
            if (region == null) continue;
            Affine composed = Affine.identity();
            for (PathFinder.Step s : route.steps) {
                Affine a = s.inverse ? s.edge.forward.inverse() : s.edge.forward;
                composed = a.compose(composed);
            }
            double maxDev = 0;
            for (double[] p : sampleGrid(region)) {
                double[] viaRoute = composed.apply(p[0], p[1]);
                double[] viaEdge = candidate.forward.apply(p[0], p[1]);
                maxDev = Math.max(maxDev, Math.hypot(viaRoute[0] - viaEdge[0], viaRoute[1] - viaEdge[1]));
            }
            if (maxDev > CYCLE_TOLERANCE) {
                return "inconsistent with existing route " + route.signature()
                        + " (max deviation " + maxDev + " > tolerance " + CYCLE_TOLERANCE + ")";
            }
        }
        return null;
    }

    /** 3x3 sample grid over a region, antimeridian-aware. */
    static List<double[]> sampleGrid(LonLatBox b) {
        List<double[]> out = new ArrayList<>();
        double lonStart = b.minLon;
        double lonEnd = b.crossesAntimeridian() ? b.maxLon + 360.0 : b.maxLon;
        for (int i = 0; i < 3; i++) {
            double lon = lonStart + (lonEnd - lonStart) * i / 2.0;
            if (lon > 180.0) lon -= 360.0;
            for (int j = 0; j < 3; j++) {
                double lat = b.minLat + (b.maxLat - b.minLat) * j / 2.0;
                out.add(new double[]{lon, lat});
            }
        }
        return out;
    }

    // ---------- transform ----------

    /** Transform one geometry; throws RegistryException / ValidationException on failure. */
    public Map<String, Object> transform(Geometry geom, String src, String dst) {
        if (!crsMap.containsKey(src)) throw new RegistryException("unknown src crs: " + src);
        if (!crsMap.containsKey(dst)) throw new RegistryException("unknown dst crs: " + dst);
        LonLatBox geomRegion = geom.region();
        List<PathFinder.Path> candidates =
                PathFinder.findAll(activeEdges(), src, dst, geomRegion, MAX_PATH_DEPTH);
        candidates.sort(PathFinder.RANK);

        List<Object> candidateJson = new ArrayList<>();
        PathFinder.Path chosen = null;
        for (PathFinder.Path p : candidates) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("path", pathStepsJson(p));
            c.put("signature", p.signature());
            c.put("cumulativeError", p.error());
            c.put("covered", p.covered);
            boolean accepted = p.covered && chosen == null;
            c.put("accepted", accepted);
            c.put("reason", exclusionReason(p, chosen));
            if (accepted) chosen = p;
            candidateJson.add(c);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("src", src);
        out.put("dst", dst);
        out.put("geometryRegion", geomRegion.toJson());
        out.put("candidates", candidateJson);
        if (chosen == null) {
            out.put("chosen", null);
            throw new NoRouteException("no usable transform chain from " + src + " to " + dst
                    + " covering the geometry region", out);
        }
        Affine composed = Affine.identity();
        for (PathFinder.Step s : chosen.steps) {
            Affine a = s.inverse ? s.edge.forward.inverse() : s.edge.forward;
            composed = a.compose(composed);
        }
        Geometry result = geom.transform(composed);
        Map<String, Object> chosenJson = new LinkedHashMap<>();
        chosenJson.put("path", pathStepsJson(chosen));
        chosenJson.put("signature", chosen.signature());
        chosenJson.put("cumulativeError", chosen.error());
        out.put("chosen", chosenJson);
        out.put("result", result.toGeoJson());
        return out;
    }

    private static String exclusionReason(PathFinder.Path p, PathFinder.Path chosen) {
        if (!p.covered) {
            return "excluded: edge region does not cover the geometry";
        }
        if (chosen != null) {
            int cmp = PathFinder.RANK.compare(p, chosen);
            if (cmp > 0) {
                if (p.error() > chosen.error()) {
                    return "excluded: higher cumulative error than chosen path";
                }
                return "excluded: equal error, loses stable tie-break to " + chosen.signature();
            }
        }
        return "accepted: best coverage, lowest error, wins tie-break";
    }

    private static List<Object> pathStepsJson(PathFinder.Path p) {
        List<Object> steps = new ArrayList<>();
        for (PathFinder.Step s : p.steps) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("edge", s.edge.id);
            st.put("direction", s.inverse ? "inverse" : "forward");
            st.put("from", s.from());
            st.put("to", s.to());
            st.put("accuracy", s.edge.accuracy);
            steps.add(st);
        }
        return steps;
    }

    public static final class NoRouteException extends RuntimeException {
        public final Map<String, Object> explanation;
        public NoRouteException(String msg, Map<String, Object> explanation) {
            super(msg);
            this.explanation = explanation;
        }
    }

    // ---------- batch jobs ----------

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> runJob(Map<String, Object> body) {
        Object itemsObj = body.get("items");
        if (!(itemsObj instanceof List)) throw new RegistryException("job needs an 'items' array");
        List<Object> items = (List<Object>) itemsObj;
        int registryVersion = this.version;
        List<Object> results = new ArrayList<>();
        for (Object o : items) {
            if (!(o instanceof Map)) throw new RegistryException("job item must be an object");
            results.add(runItem((Map<String, Object>) o));
        }
        jobSeq++;
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobId", "job-" + jobSeq);
        job.put("registryVersion", registryVersion);
        job.put("items", results);
        job.put("fingerprint", fingerprint(job));
        jobs.add(job);
        return job;
    }

    private Map<String, Object> runItem(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        String id = item.get("id") instanceof String ? (String) item.get("id") : "?";
        out.put("id", id);
        try {
            Object g = item.get("geometry");
            if (!(g instanceof Map)) throw new RegistryException("item missing 'geometry'");
            Geometry geom = Geometry.fromGeoJson(castMap(g));
            String src = Json.str(item, "src");
            String dst = Json.str(item, "dst");
            Map<String, Object> t = transform(geom, src, dst);
            out.put("status", "ok");
            out.put("diagnostics", "");
            out.put("path", ((Map<String, Object>) t.get("chosen")).get("path"));
            out.put("cumulativeError", ((Map<String, Object>) t.get("chosen")).get("cumulativeError"));
            out.put("result", t.get("result"));
        } catch (NoRouteException e) {
            out.put("status", "failed");
            out.put("diagnostics", e.getMessage());
            out.put("explanation", e.explanation);
            out.put("path", null);
            out.put("cumulativeError", null);
            out.put("result", null);
        } catch (RuntimeException e) {
            out.put("status", "failed");
            out.put("diagnostics", e.getMessage());
            out.put("path", null);
            out.put("cumulativeError", null);
            out.put("result", null);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    /** SHA-256 over the canonical JSON of the job content (fingerprint excluded). */
    static String fingerprint(Map<String, Object> jobContent) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("registryVersion", jobContent.get("registryVersion"));
        canonical.put("items", jobContent.get("items"));
        String text = Json.writeCanonical(canonical);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- export / import ----------

    public synchronized Map<String, Object> exportStore() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "coord-station-store");
        m.put("formatVersion", 1);
        m.put("version", version);
        m.put("jobSeq", jobSeq);
        List<Object> crs = new ArrayList<>();
        for (Crs c : crsMap.values()) crs.add(c.toJson());
        m.put("crs", crs);
        List<Object> es = new ArrayList<>();
        for (Edge e : edges.values()) es.add(e.toJson());
        m.put("edges", es);
        m.put("jobs", new ArrayList<>(jobs));
        m.put("log", new ArrayList<>(log));
        return m;
    }

    /** Replaces the whole state with the imported store. */
    public synchronized void importStore(Map<String, Object> m) {
        if (!"coord-station-store".equals(m.get("format")))
            throw new RegistryException("not a coord-station-store document");
        crsMap.clear();
        edges.clear();
        jobs.clear();
        log.clear();
        for (Object o : Json.arr(m, "crs")) {
            Crs c = Crs.fromJson(castMap(o));
            crsMap.put(c.id, c);
        }
        for (Object o : Json.arr(m, "edges")) {
            Edge e = Edge.fromJson(castMap(o));
            edges.put(e.id, e);
        }
        for (Object o : Json.arr(m, "jobs")) {
            jobs.add(new LinkedHashMap<>(castMap(o)));
        }
        for (Object o : Json.arr(m, "log")) {
            log.add(new LinkedHashMap<>(castMap(o)));
        }
        version = (int) Json.num(m, "version");
        jobSeq = (int) Json.num(m, "jobSeq");
    }
}
