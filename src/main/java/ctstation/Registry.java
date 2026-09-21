package ctstation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * In-memory CRS graph and chain selector.
 *
 * <p>Selection order for every geometry point:
 * <ol>
 *   <li><b>coverage</b>: every step's domain must contain the point
 *       (boundary points count as inside);</li>
 *   <li><b>error</b>: among feasible chains pick the smallest propagated
 *       per-axis error bound;</li>
 *   <li><b>tie break</b>: lexicographically smaller sequence of edge ids
 *       (forward and inverse of one edge share an id, so the shorter chain
 *       naturally wins first by error equality rules below).</li>
 * </ol>
 *
 * <p>Only edges in state {@code active} participate. Edges whose matrix (or
 * explicit flag) forbids inversion have no reverse arc.
 */
public final class Registry {

    /** Hard guard against path enumeration blow-ups. */
    public static final int MAX_SIMPLE_PATHS = 2000;

    public final Map<String, Crs> crs = new TreeMap<>();
    public final Map<String, Edge> edges = new TreeMap<>();
    /** adjacency: CRS -> arcs leaving that CRS, sorted by edge id. */
    private final Map<String, List<Edge.Arc>> graph = new TreeMap<>();

    // ----------------------------------------------------------- mutations

    public void addCrs(Crs c) {
        if (crs.containsKey(c.id)) {
            throw new AppException("crs-exists", "CRS '" + c.id + "' is already registered");
        }
        crs.put(c.id, c);
    }

    public void putEdge(Edge e) {
        if (!crs.containsKey(e.sourceCrs) || !crs.containsKey(e.targetCrs)) {
            throw new AppException("unknown-crs",
                    "edge " + e.id + " references unregistered CRS");
        }
        if (edges.containsKey(e.id)) {
            throw new AppException("edge-exists", "edge '" + e.id + "' already exists");
        }
        edges.put(e.id, e);
    }

    public Edge edge(String id) {
        Edge e = edges.get(id);
        if (e == null) throw new AppException("edge-not-found", "no edge '" + id + "'");
        return e;
    }

    public Crs requireCrs(String id) {
        Crs c = crs.get(id);
        if (c == null) throw new AppException("crs-not-found", "no CRS '" + id + "'");
        return c;
    }

    private void rebuildGraph() {
        graph.clear();
        for (String id : crs.keySet()) graph.put(id, new ArrayList<>());
        for (Edge e : edges.values()) {
            if (!Edge.ACTIVE.equals(e.state)) continue;
            graph.get(e.sourceCrs).add(e.arc(e.sourceCrs));
            if (e.invertible()) graph.get(e.targetCrs).add(e.arc(e.targetCrs));
        }
        for (List<Edge.Arc> arcs : graph.values()) {
            arcs.sort((p, q) -> p.edge.id.compareTo(q.edge.id));
        }
    }

    private List<Edge.Arc> arcsLeaving(String node) {
        return graph.getOrDefault(node, Collections.emptyList());
    }

    // ------------------------------------------------------- path finding

    /** All simple chains from {@code from} to {@code to}, evaluated lazily. */
    public List<Chain> enumerate(String from, String to) {
        requireCrs(from);
        requireCrs(to);
        rebuildGraph();
        List<Chain> out = new ArrayList<>();
        if (from.equals(to)) {
            out.add(Chain.identity(from));
            return out;
        }
        List<Edge.Arc> path = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(from);
        dfs(from, to, visited, path, out);
        return out;
    }

    private void dfs(String node, String target, Set<String> visited,
                     List<Edge.Arc> path, List<Chain> out) {
        if (out.size() >= MAX_SIMPLE_PATHS) return;
        for (Edge.Arc arc : arcsLeaving(node)) {
            String next = arc.toCrs();
            if (visited.contains(next)) continue;
            path.add(arc);
            if (next.equals(target)) {
                out.add(new Chain(new ArrayList<>(path)));
                if (out.size() >= MAX_SIMPLE_PATHS) {
                    path.remove(path.size() - 1);
                    return;
                }
            } else {
                visited.add(next);
                dfs(next, target, visited, path, out);
                visited.remove(next);
            }
            path.remove(path.size() - 1);
        }
    }

    /**
     * Evaluate every chain for one point. The first failing step records why
     * the candidate is excluded; feasible chains carry cumulative error.
     */
    public List<Candidate> evaluate(List<Chain> chains, double x, double y, Crs target) {
        List<Candidate> result = new ArrayList<>();
        for (Chain chain : chains) {
            result.add(chain.evaluate(x, y, target));
        }
        return result;
    }

    /** Feasible candidate with smallest error, with stable tie breaking. */
    public static Candidate bestOf(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (!c.feasible) continue;
            if (best == null || c.compareTo(best) < 0) best = c;
        }
        return best;
    }

    // --------------------------------------------------- loop consistency

    /**
     * Compare a prospective edge against every loop it would close in the
     * existing graph. {@code tolerance} (in target-CRS units) flags clear
     * disagreement. Returns null when consistent, otherwise evidence.
     */
    public Map<String, Object> checkLoopConsistency(Edge candidate, double tolerance) {
        rebuildGraph();
        if (candidate.sourceCrs.equals(candidate.targetCrs)) return null;

        // Existing path source -> target compared with the new forward edge.
        Map<String, Object> disagreement = compareAlong(candidate.sourceCrs,
                candidate.targetCrs, candidate.forward, candidate.coverage,
                tolerance, "forward");
        if (disagreement != null) return disagreement;

        // Existing path target -> source compared with the new inverse.
        // The matrix itself must be safely invertible: comparing against the
        // formal inverse of a (near-)singular matrix just amplifies rounding
        // noise and produces false loop disagreements.
        if (candidate.invertible() && candidate.forward.safelyInvertible()) {
            Affine inv;
            try {
                inv = candidate.forward.inverse();
            } catch (AppException ae) {
                return null;
            }
            disagreement = compareAlong(candidate.targetCrs, candidate.sourceCrs,
                    inv, candidate.coverage, tolerance, "inverse");
            if (disagreement != null) return disagreement;
        }
        return null;
    }

    private Map<String, Object> compareAlong(String from, String to, Affine candidateFwd,
                                             Coverage domain, double tolerance, String dir) {
        List<Chain> chains = enumerate(from, to);
        // identity chain only occurs when from == to, which the caller skips.
        List<Point2> samples = domain.samples();
        double worst = 0;
        Point2 worstAt = null;
        Chain worstChain = null;
        double[] ref = null;
        double[] got = null;
        for (Point2 s : samples) {
            double[] direct = candidateFwd.apply(s.x, s.y);
            if (!Double.isFinite(direct[0]) || !Double.isFinite(direct[1])) continue;
            for (Chain chain : chains) {
                Candidate cand = chain.evaluate(s.x, s.y, crs.get(to));
                if (!cand.feasible) continue;
                double dx = cand.output[0] - direct[0];
                double dy = cand.output[1] - direct[1];
                double d = Math.max(Math.abs(dx), Math.abs(dy));
                if (d > worst) {
                    worst = d;
                    worstAt = s;
                    worstChain = chain;
                    ref = direct;
                    got = cand.output;
                }
            }
        }
        if (worst > tolerance) {
            List<String> via = new ArrayList<>();
            for (Edge.Arc a : worstChain.arcs) via.add(a.edge.id);
            LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("direction", dir);
            evidence.put("samplePoint", worstAt.toMap());
            evidence.put("existingPath", via);
            evidence.put("existingResult", java.util.Arrays.asList(got[0], got[1]));
            evidence.put("newEdgeResult", java.util.Arrays.asList(ref[0], ref[1]));
            evidence.put("maxAbsDiff", worst);
            evidence.put("tolerance", tolerance);
            return evidence;
        }
        return null;
    }

    // ------------------------------------------------------------- serial

    public Map<String, Object> snapshot() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        List<Object> cs = new ArrayList<>();
        for (Crs c : crs.values()) cs.add(c.toMap());
        m.put("crs", cs);
        List<Object> es = new ArrayList<>();
        for (Edge e : edges.values()) es.add(e.toMap());
        m.put("edges", es);
        return m;
    }

    // ------------------------------------------------------------- types

    /** A simple chain of arcs (identity chain has zero arcs). */
    public static final class Chain {
        public final List<Edge.Arc> arcs;

        Chain(List<Edge.Arc> arcs) { this.arcs = arcs; }

        static Chain identity(String crs) {
            return new Chain(new ArrayList<>());
        }

        public List<String> edgeIds() {
            List<String> ids = new ArrayList<>();
            for (Edge.Arc a : arcs) ids.add(a.edge.id);
            return ids;
        }

        public String key() {
            return String.join(">", edgeIds());
        }

        /**
         * Walk the chain at the given point. Stops at the first infeasible
         * step and records a structured exclusion reason.
         */
        public Candidate evaluate(double x, double y, Crs targetCrs) {
            if (arcs.isEmpty()) {
                Candidate c = new Candidate(this, true);
                c.output = new double[] {x, y};
                c.errorX = 0;
                c.errorY = 0;
                c.totalTransform = Affine.identity();
                return c;
            }
            double cx = x, cy = y;
            Affine total = Affine.identity();
            double errX = 0, errY = 0;
            List<Map<String, Object>> steps = new ArrayList<>();
            for (int i = 0; i < arcs.size(); i++) {
                Edge.Arc arc = arcs.get(i);
                if (!arc.domain.contains(cx, cy)) {
                    Candidate rejected = new Candidate(this, false);
                    rejected.excludedAtStep = i;
                    rejected.exclusionReason = "out-of-coverage";
                    rejected.exclusionDetail = Json.object(
                            "edgeId", arc.edge.id,
                            "direction", arc.forwardDirection ? "forward" : "inverse",
                            "point", new Point2(cx, cy).toMap(),
                            "coverage", arc.domain.toMap());
                    rejected.partialSteps = steps;
                    return rejected;
                }
                // Previous steps' error lives in the current CRS; propagate
                // it through this edge's linear part, then add this edge's
                // own accuracy (declared in the edge target-CRS units).
                double nextErrX = Math.abs(arc.transform.a) * errX
                        + Math.abs(arc.transform.b) * errY + arc.accuracyX;
                double nextErrY = Math.abs(arc.transform.c) * errX
                        + Math.abs(arc.transform.d) * errY + arc.accuracyY;
                Affine next = arc.transform.compose(total);
                double[] out = arc.transform.apply(cx, cy);
                if (!Double.isFinite(out[0]) || !Double.isFinite(out[1])) {
                    Candidate rejected = new Candidate(this, false);
                    rejected.excludedAtStep = i;
                    rejected.exclusionReason = "non-finite-output";
                    rejected.exclusionDetail = Json.object("edgeId", arc.edge.id);
                    rejected.partialSteps = steps;
                    return rejected;
                }
                cx = out[0]; cy = out[1];
                total = next;
                errX = nextErrX;
                errY = nextErrY;
                Map<String, Object> step = arc.toMap();
                LinkedHashMap<String, Object> after = new LinkedHashMap<>();
                after.put("x", cx);
                after.put("y", cy);
                LinkedHashMap<String, Object> cumErr = new LinkedHashMap<>();
                cumErr.put("x", errX);
                cumErr.put("y", errY);
                step.put("cumulativeError", cumErr);
                step.put("pointAfterStep", after);
                steps.add(step);
            }
            if (targetCrs != null && targetCrs.geographic()
                    && (cy < -90.0d || cy > 90.0d)) {
                Candidate rejected = new Candidate(this, false);
                rejected.excludedAtStep = arcs.size() - 1;
                rejected.exclusionReason = "latitude-out-of-range";
                rejected.exclusionDetail = Json.object("latitude", cy);
                rejected.partialSteps = steps;
                return rejected;
            }
            Candidate ok = new Candidate(this, true);
            ok.output = new double[] {cx, cy};
            ok.errorX = errX;
            ok.errorY = errY;
            ok.totalTransform = total;
            ok.partialSteps = steps;
            return ok;
        }
    }

    /** Evaluated chain: either feasible (output + error) or excluded. */
    public static final class Candidate implements Comparable<Candidate> {
        public final Chain chain;
        public final boolean feasible;
        public double[] output;
        public double errorX;
        public double errorY;
        public Affine totalTransform;
        public int excludedAtStep = -1;
        public String exclusionReason;
        public Map<String, Object> exclusionDetail;
        public List<Map<String, Object>> partialSteps = new ArrayList<>();

        Candidate(Chain chain, boolean feasible) {
            this.chain = chain;
            this.feasible = feasible;
        }

        public double maxError() { return Math.max(errorX, errorY); }

        @Override
        public int compareTo(Candidate o) {
            int c = Double.compare(maxError(), o.maxError());
            if (c != 0) return c;
            c = Double.compare(errorX, o.errorX);
            if (c != 0) return c;
            c = Double.compare(errorY, o.errorY);
            if (c != 0) return c;
            c = Integer.compare(chain.arcs.size(), o.chain.arcs.size());
            if (c != 0) return c;
            return key().compareTo(o.key());
        }

        public String key() { return chain.key(); }

        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("edgeIds", chain.edgeIds());
            m.put("path", chain.key().isEmpty() ? "(identity)" : chain.key());
            m.put("feasible", feasible);
            if (feasible) {
                m.put("output", java.util.Arrays.asList(output[0], output[1]));
                LinkedHashMap<String, Object> err = new LinkedHashMap<>();
                err.put("x", errorX);
                err.put("y", errorY);
                err.put("max", maxError());
                m.put("cumulativeError", err);
                m.put("steps", partialSteps);
            } else {
                m.put("excludedAtStep", excludedAtStep);
                m.put("exclusionReason", exclusionReason);
                m.put("exclusionDetail", exclusionDetail);
                m.put("steps", partialSteps);
            }
            return m;
        }
    }
}
