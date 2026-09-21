package ctstation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Application service: registry operations, jobs, versions, persistence. */
public final class Service {

    public final Store store;

    public Service(Path dataDir) {
        this.store = new Store(dataDir);
        store.loadIfPresent();
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new AppException("hash-error", e.getMessage());
        }
    }

    /**
     * Hash of the exact CRS/edge graph content (including edge states).
     * Survives export/import because the content itself is restored, unlike
     * the monotonically increasing version sequence number. Used inside job
     * fingerprints; the sequence stays available as snapshot metadata.
     */
    public synchronized String registryContentHash() {
        Map<String, Object> graph = registry().snapshot();
        return sha256Hex(Json.canonical(graph));
    }

    // ------------------------------------------------------------- reads

    public synchronized Registry registry() {
        Registry r = new Registry();
        r.crs.putAll(store.crs);
        r.edges.putAll(store.edges);
        return r;
    }

    public synchronized Map<String, Object> state() {
        Registry r = registry();
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("registryVersion", store.versionSeq);
        m.put("seeded", store.seeded);
        m.put("registry", r.snapshot());
        m.put("versions", store.versions);
        return m;
    }

    // ---------------------------------------------------------- mutations

    public synchronized Map<String, Object> registerCrs(Map<String, Object> body) {
        Crs c = Crs.fromMap(body);
        if (store.crs.containsKey(c.id)) {
            throw new AppException("crs-exists", "CRS '" + c.id + "' already exists");
        }
        store.crs.put(c.id, c);
        bumpVersion("register-crs", Json.object("crsId", c.id));
        store.save();
        return c.toMap();
    }

    public synchronized Map<String, Object> registerEdge(Map<String, Object> body) {
        Edge e = Edge.fromMap(body);
        if (store.edges.containsKey(e.id)) {
            throw new AppException("edge-exists", "edge '" + e.id + "' already exists");
        }
        if (!store.crs.containsKey(e.sourceCrs) || !store.crs.containsKey(e.targetCrs)) {
            throw new AppException("unknown-crs", "edge references unregistered CRS");
        }
        double tolerance = body.containsKey("loopTolerance")
                ? Json.dbl(body, "loopTolerance") : 1.0e-3d;
        Registry r = registry();
        Map<String, Object> evidence = r.checkLoopConsistency(e, tolerance);
        if (evidence != null) {
            e.state = Edge.PENDING;
            e.stateReason = "auto-pending: loop inconsistency at registration";
            e.inconsistency = evidence;
        }
        store.edges.put(e.id, e);
        bumpVersion("register-edge", Json.object(
                "edgeId", e.id,
                "state", e.state,
                "inconsistency", e.inconsistency == null ? null : e.inconsistency));
        store.save();
        return e.toMap();
    }

    public synchronized Map<String, Object> confirmEdge(String edgeId, Map<String, Object> body) {
        Edge e = store.edges.get(edgeId);
        if (e == null) throw new AppException("edge-not-found", "no edge '" + edgeId + "'");
        if (!Edge.PENDING.equals(e.state)) {
            throw new AppException("not-pending", "edge " + edgeId + " is in state " + e.state);
        }
        String reason = Json.str(body, "reason");
        if (reason.trim().isEmpty()) {
            throw new AppException("bad-confirm", "confirmation reason must not be empty");
        }
        String newState = Json.optStr(body, "state", Edge.ACTIVE);
        if (!Edge.ACTIVE.equals(newState) && !Edge.DISABLED.equals(newState)) {
            throw new AppException("bad-confirm", "state must be 'active' or 'disabled'");
        }
        e.state = newState;
        e.stateReason = "confirmed: " + reason;
        bumpVersion("confirm-edge", Json.object(
                "edgeId", edgeId, "reason", reason, "newState", newState));
        store.save();
        return e.toMap();
    }

    // ---------------------------------------------------------------- jobs

    /**
     * Run a batch job. Each object succeeds or fails independently; a failed
     * item keeps a structured diagnostic and never aborts the batch.
     */
    public synchronized Map<String, Object> runJob(Map<String, Object> body) {
        String sourceCrsId = Json.str(body, "sourceCrs");
        String targetCrsId = Json.str(body, "targetCrs");
        Crs source = store.crs.get(sourceCrsId);
        Crs target = store.crs.get(targetCrsId);
        if (source == null) throw new AppException("crs-not-found", "unknown sourceCrs " + sourceCrsId);
        if (target == null) throw new AppException("crs-not-found", "unknown targetCrs " + targetCrsId);

        List<ObjectInput> inputs = ObjectInput.parse(body.get("objects"));
        int registryVersion = store.versionSeq;
        String registryContentHash = registryContentHash();

        Registry r = registry();
        List<Registry.Chain> chains = r.enumerate(sourceCrsId, targetCrsId);

        List<Map<String, Object>> items = new ArrayList<>();
        int okCount = 0;
        int failCount = 0;
        for (int i = 0; i < inputs.size(); i++) {
            ObjectInput in = inputs.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            if (in.id != null) item.put("id", in.id);
            item.put("input", in.geometry.toMap());
            try {
                Map<String, Object> transformed = transformOne(r, chains, in.geometry, source, target);
                item.put("status", "success");
                item.putAll(transformed);
                okCount++;
            } catch (AppException ae) {
                item.put("status", "failed");
                item.put("error", Json.object("code", ae.code, "message", ae.getMessage()));
                failCount++;
            }
            items.add(item);
        }

        // Fingerprint covers only the request inputs and the registry version
        // it ran against, so identical data after import yields an identical
        // fingerprint. Exact raw doubles feed the canonical serialiser.
        Map<String, Object> fpPayload = new TreeMap<>();
        fpPayload.put("v", 1);
        fpPayload.put("registryContentHash", registryContentHash);
        fpPayload.put("sourceCrs", sourceCrsId);
        fpPayload.put("targetCrs", targetCrsId);
        List<Object> objSigs = new ArrayList<>();
        for (ObjectInput in : inputs) {
            Map<String, Object> sig = new LinkedHashMap<>();
            if (in.id != null) sig.put("id", in.id);
            sig.put("geometry", in.rawGeometry);
            objSigs.add(sig);
        }
        fpPayload.put("objects", objSigs);
        String fingerprint = sha256Hex(Json.canonical(fpPayload));

        String jobId = "J" + String.format("%04d", store.nextJobSeq);
        store.nextJobSeq++;

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("jobId", jobId);
        record.put("registryVersion", registryVersion);
        record.put("registryContentHash", registryContentHash);
        record.put("sourceCrs", sourceCrsId);
        record.put("targetCrs", targetCrsId);
        record.put("fingerprint", fingerprint);
        record.put("summary", Json.object("total", inputs.size(),
                "succeeded", okCount, "failed", failCount));
        record.put("items", items);
        // Preserve exact original input for historical binding.
        record.put("request", Json.object("objects", body.get("objects")));

        store.jobs.add(record);
        store.jobById.put(jobId, record);
        store.save();
        return record;
    }

    private Map<String, Object> transformOne(Registry r, List<Registry.Chain> chains,
                                             Geometry geometry, Crs source, Crs target) {
        geometry.validateIn(source);
        List<Point2> pts = geometry.points();

        List<List<Registry.Candidate>> perPoint = new ArrayList<>();
        for (Point2 p : pts) {
            perPoint.add(r.evaluate(chains, p.x, p.y, target));
        }

        Registry.Candidate chosen = chooseWorstCase(perPoint);
        if (chosen == null) {
            throw new AppException("no-chain",
                    "no active transform chain covers every point from "
                            + source.id + " to " + target.id);
        }

        Affine total = chosen.totalTransform;
        List<Point2> outPts = new ArrayList<>();
        for (Point2 p : pts) {
            Point2 q = total.apply(p);
            if (target.geographic() && (q.y < -90.0d || q.y > 90.0d)) {
                throw new AppException("latitude-out-of-range",
                        "transformed latitude " + Json.num(q.y) + " out of range");
            }
            outPts.add(q);
        }
        Geometry out = geometry.rebuild(outPts);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", out.toMap());
        LinkedHashMap<String, Object> err = new LinkedHashMap<>();
        err.put("x", chosen.errorX);
        err.put("y", chosen.errorY);
        err.put("max", chosen.maxError());
        result.put("cumulativeError", err);
        result.put("selectedPath", chosen.key().isEmpty() ? "(identity)" : chosen.key());
        result.put("selectedEdgeIds", chosen.chain.edgeIds());
        result.put("steps", chosen.partialSteps);
        result.put("candidates", candidateReport(perPoint));
        return result;
    }

    /** One report row per simple chain: why it was excluded or its error. */
    private List<Map<String, Object>> candidateReport(List<List<Registry.Candidate>> perPoint) {
        List<Map<String, Object>> report = new ArrayList<>();
        if (perPoint.isEmpty()) return report;
        for (int ci = 0; ci < perPoint.get(0).size(); ci++) {
            Registry.Candidate first = perPoint.get(0).get(ci);
            Map<String, Object> row = first.toMap();
            boolean all = true;
            double wX = first.feasible ? first.errorX : Double.NaN;
            double wY = first.feasible ? first.errorY : Double.NaN;
            Map<String, Object> firstFailure = null;
            for (int i = 0; i < perPoint.size(); i++) {
                Registry.Candidate c = perPoint.get(i).get(ci);
                if (!c.feasible) {
                    all = false;
                    if (firstFailure == null) {
                        firstFailure = Json.object(
                                "pointIndex", i,
                                "reason", c.exclusionReason,
                                "detail", c.exclusionDetail);
                    }
                } else {
                    if (!Double.isNaN(wX)) {
                        wX = Math.max(wX, c.errorX);
                        wY = Math.max(wY, c.errorY);
                    }
                }
            }
            row.put("feasibleAtAllPoints", all);
            if (all) {
                row.put("worstCumulativeError",
                        Json.object("x", wX, "y", wY, "max", Math.max(wX, wY)));
            } else if (firstFailure != null) {
                row.put("firstFailure", firstFailure);
            }
            report.add(row);
        }
        return report;
    }

    private Registry.Candidate findCandidate(List<Registry.Candidate> list, String key) {
        for (Registry.Candidate c : list) if (c.key().equals(key)) return c;
        return null;
    }

    /**
     * A chain must be feasible at every vertex; its score is the worst
     * cumulative error across vertices. Tie breaking follows
     * {@link Registry.Candidate#compareTo}.
     */
    private Registry.Candidate chooseWorstCase(List<List<Registry.Candidate>> perPoint) {
        if (perPoint.isEmpty()) return null;
        Registry.Candidate best = null;
        double bestX = 0, bestY = 0;
        for (Registry.Candidate first : perPoint.get(0)) {
            if (!first.feasible) continue;
            double wX = first.errorX, wY = first.errorY;
            boolean feasibleEverywhere = true;
            for (int i = 1; i < perPoint.size(); i++) {
                Registry.Candidate other = findCandidate(perPoint.get(i), first.key());
                if (other == null || !other.feasible) { feasibleEverywhere = false; break; }
                wX = Math.max(wX, other.errorX);
                wY = Math.max(wY, other.errorY);
            }
            if (!feasibleEverywhere) continue;
            if (best == null) {
                best = first; bestX = wX; bestY = wY;
            } else {
                int c = Double.compare(Math.max(wX, wY), Math.max(bestX, bestY));
                if (c == 0) c = Double.compare(wX, bestX);
                if (c == 0) c = Double.compare(wY, bestY);
                if (c == 0) c = first.compareTo(best);
                if (c < 0) { best = first; bestX = wX; bestY = wY; }
            }
        }
        if (best != null) { best.errorX = bestX; best.errorY = bestY; }
        return best;
    }

    // ---------------------------------------------------------- versions

    private void bumpVersion(String action, Map<String, Object> detail) {
        store.versionSeq++;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("seq", store.versionSeq);
        v.put("action", action);
        v.put("detail", detail);
        store.versions.add(v);
    }


    // ------------------------------------------------------- maintenance

    public synchronized Map<String, Object> reseed() {
        store.crs.clear();
        store.edges.clear();
        store.versions.clear();
        store.jobs.clear();
        store.jobById.clear();
        store.versionSeq = 0;
        store.nextJobSeq = 1;
        store.seeded = true;
        Seed.apply(this);
        store.save();
        return state();
    }

    public synchronized List<Map<String, Object>> listJobs() {
        return store.jobs;
    }

    public synchronized Map<String, Object> getJob(String id) {
        Map<String, Object> j = store.getJob(id);
        if (j == null) throw new AppException("job-not-found", "no job '" + id + "'");
        return j;
    }

    // ------------------------------------------------------- object input

    static final class ObjectInput {
        final String id;
        final Geometry geometry;
        final Object rawGeometry;

        ObjectInput(String id, Geometry geometry, Object rawGeometry) {
            this.id = id;
            this.geometry = geometry;
            this.rawGeometry = rawGeometry;
        }

        @SuppressWarnings("unchecked")
        static List<ObjectInput> parse(Object raw) {
            if (!(raw instanceof List)) {
                throw new AppException("bad-request", "'objects' must be an array");
            }
            List<Object> list = (List<Object>) raw;
            if (list.isEmpty()) throw new AppException("bad-request", "'objects' must not be empty");
            List<ObjectInput> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                Geometry geo;
                String id = null;
                Object rawGeo;
                if (item instanceof Map && ((Map<String, Object>) item).containsKey("geometry")) {
                    Map<String, Object> wrapper = (Map<String, Object>) item;
                    Object gid = wrapper.get("id");
                    if (gid != null) id = gid.toString();
                    rawGeo = wrapper.get("geometry");
                    geo = Geometry.parse(rawGeo);
                } else {
                    rawGeo = item;
                    geo = Geometry.parse(item);
                }
                out.add(new ObjectInput(id, geo, rawGeo));
            }
            return out;
        }
    }
}
