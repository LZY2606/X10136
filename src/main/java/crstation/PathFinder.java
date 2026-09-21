package crstation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects transformation chains between two registered CRSs for one geometry.
 *
 * <p>Selection rules, in order:
 * <ol>
 *   <li><b>coverage first</b>: a chain is usable only when every vertex of the
 *       geometry lies inside every traversed edge's effective coverage;</li>
 *   <li><b>error</b>: among covering chains pick the smallest accumulated
 *       upper-bound error (simple sum of edge accuracy bounds);</li>
 *   <li><b>stable tie break</b>: identical error is broken by the lexicographic
 *       sequence of {@code edgeId} values, then direction symbols, giving the
 *       same answer on every machine and every run.</li>
 * </ol>
 *
 * Only ACTIVE edges participate; PENDING edges are never part of a default
 * path but are reported so the UI can explain why.
 */
public final class PathFinder {

    public static final int MAX_HOPS = 6;
    private static final int CANDIDATE_CAP = 200;

    public final Registry registry;

    public PathFinder(Registry registry) {
        this.registry = registry;
    }

    /** One edge traversal in a chosen or examined chain. */
    public static final class Step {
        public final Edge edge;
        public final boolean forward;

        public Step(Edge edge, boolean forward) {
            this.edge = edge;
            this.forward = forward;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("edgeId", edge.id);
            m.put("fromCrs", forward ? edge.fromCrs : edge.toCrs);
            m.put("toCrs", forward ? edge.toCrs : edge.fromCrs);
            m.put("direction", forward ? "forward" : "reverse");
            m.put("accuracyBound", edge.edgeAccuracy(forward));
            m.put("transform", edge.transform.spec());
            m.put("invertible", edge.invertible);
            return m;
        }
    }

    public static final class Path {
        public final List<Step> steps;
        public final double errorBound;

        Path(List<Step> steps, double errorBound) {
            this.steps = steps;
            this.errorBound = errorBound;
        }

        public String endpointAfter(String start) {
            String cur = start;
            for (Step s : steps) {
                cur = s.forward ? s.edge.toCrs : s.edge.fromCrs;
            }
            return cur;
        }

        public List<String> edgeKey() {
            List<String> key = new ArrayList<>();
            for (Step s : steps) {
                key.add(s.edge.id);
            }
            return key;
        }

        public List<String> directionKey() {
            List<String> key = new ArrayList<>();
            for (Step s : steps) {
                key.add(s.forward ? "F" : "R");
            }
            return key;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Object> ss = new ArrayList<>();
            for (Step s : steps) {
                ss.add(s.toJson());
            }
            m.put("steps", ss);
            m.put("errorBound", errorBound);
            m.put("hopCount", steps.size());
            return m;
        }
    }

    public static final class Rejected {
        public final List<String> edgeIds;
        public final List<String> directions;
        public final String reason;
        public final String detail;
        public final String failingEdge;
        public final int failingPoint;
        public final double[] failingCoordinate;

        Rejected(List<Step> prefix, String reason, String detail,
                 String failingEdge, int failingPoint, double[] failingCoordinate) {
            this.edgeIds = new ArrayList<>();
            this.directions = new ArrayList<>();
            for (Step s : prefix) {
                edgeIds.add(s.edge.id);
                directions.add(s.forward ? "forward" : "reverse");
            }
            this.reason = reason;
            this.detail = detail;
            this.failingEdge = failingEdge;
            this.failingPoint = failingPoint;
            this.failingCoordinate = failingCoordinate;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("edgeIds", edgeIds);
            m.put("directions", directions);
            m.put("reason", reason);
            m.put("detail", detail);
            if (failingEdge != null) {
                m.put("failingEdge", failingEdge);
            }
            if (failingPoint >= 0) {
                m.put("failingPoint", failingPoint);
                if (failingCoordinate != null) {
                    m.put("failingCoordinate", List.of(failingCoordinate[0], failingCoordinate[1]));
                }
            }
            return m;
        }
    }

    public static final class Analysis {
        public final List<Path> accepted;
        public final List<Rejected> rejected;
        public final boolean truncated;
        public final List<Map<String, Object>> pendingEdges;

        Analysis(List<Path> accepted, List<Rejected> rejected, boolean truncated,
                 List<Map<String, Object>> pendingEdges) {
            this.accepted = accepted;
            this.rejected = rejected;
            this.truncated = truncated;
            this.pendingEdges = pendingEdges;
        }
    }

    /**
     * Enumerate and classify chains from {@code fromCrs} to {@code toCrs} for
     * the given geometry. The geometry is validated by the caller against its
     * declared source CRS.
     */
    public Analysis analyze(String fromCrs, String toCrs, Geometry geometry) {
        if (registry.crs(fromCrs) == null) {
            throw new IllegalArgumentException("unknown source CRS: " + fromCrs);
        }
        if (registry.crs(toCrs) == null) {
            throw new IllegalArgumentException("unknown target CRS: " + toCrs);
        }
        List<double[]> points = geometry.positions();

        List<Path> accepted = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        boolean[] truncated = {false};

        if (!fromCrs.equals(toCrs)) {
            dfs(fromCrs, toCrs, points, new ArrayList<>(), new ArrayList<>(List.of(fromCrs)),
                    new ArrayList<>(List.of(clonePoints(points))),
                    accepted, rejected, truncated);
        }

        accepted.sort(Comparator
                .comparingDouble((Path p) -> p.errorBound)
                .thenComparing(p -> String.join("\u0000", p.edgeKey()))
                .thenComparing(p -> String.join("\u0000", p.directionKey()))
                .thenComparingInt(p -> p.steps.size()));

        List<Map<String, Object>> pending = new ArrayList<>();
        for (Edge e : registry.edges()) {
            if (e.status() == Edge.Status.PENDING) {
                pending.add(e.toJson());
            }
        }
        return new Analysis(accepted, rejected, truncated[0], pending);
    }

    private static List<double[]> clonePoints(List<double[]> ps) {
        List<double[]> out = new ArrayList<>(ps.size());
        for (double[] p : ps) {
            out.add(p.clone());
        }
        return out;
    }

    private void dfs(String cur, String target, List<double[]> originalPoints,
                     List<Step> steps, List<String> visitedCrs,
                     List<List<double[]>> pointsAtDepth,
                     List<Path> accepted, List<Rejected> rejected, boolean[] truncated) {
        if (cur.equals(target)) {
            double err = 0;
            for (Step s : steps) {
                err += s.edge.edgeAccuracy(s.forward);
            }
            accepted.add(new Path(List.copyOf(steps), err));
            return;
        }
        if (steps.size() >= MAX_HOPS) {
            return;
        }
        if (accepted.size() + rejected.size() >= CANDIDATE_CAP) {
            truncated[0] = true;
            return;
        }

        List<double[]> currentPoints = pointsAtDepth.get(pointsAtDepth.size() - 1);

        for (Edge edge : sortedOutgoing(cur)) {
            if (visitedCrs.contains(edge.other(cur))) {
                continue;
            }
            boolean forward = edge.fromCrs.equals(cur);

            if (!forward && !edge.invertible) {
                rejected.add(new Rejected(appendStep(steps, edge, false),
                        "NOT_INVERTIBLE",
                        "edge '" + edge.id + "' is not declared safe to invert",
                        edge.id, -1, null));
                continue;
            }

            int badPoint = -1;
            double[] badCoord = null;
            for (int i = 0; i < currentPoints.size(); i++) {
                double[] p = currentPoints.get(i);
                if (!edge.covers(forward, p[0], p[1])) {
                    badPoint = i;
                    badCoord = p.clone();
                    break;
                }
            }
            List<Step> withStep = appendStep(steps, edge, forward);
            if (badPoint >= 0) {
                rejected.add(new Rejected(withStep, "OUT_OF_COVERAGE",
                        "coordinate index " + badPoint + " is outside edge '" + edge.id
                                + "' effective coverage in the "
                                + (forward ? "forward" : "reverse") + " direction",
                        edge.id, badPoint, badCoord));
                continue;
            }

            List<double[]> nextPoints = new ArrayList<>(currentPoints.size());
            boolean badTransform = false;
            for (double[] p : currentPoints) {
                double[] q = edge.traverse(forward, p);
                if (!Double.isFinite(q[0]) || !Double.isFinite(q[1])) {
                    rejected.add(new Rejected(withStep, "NON_FINITE_RESULT",
                            "edge '" + edge.id + "' produced a non-finite coordinate",
                            edge.id, nextPoints.size(), p.clone()));
                    badTransform = true;
                    break;
                }
                nextPoints.add(q);
            }
            if (badTransform) {
                continue;
            }

            steps.add(new Step(edge, forward));
            visitedCrs.add(edge.other(cur));
            pointsAtDepth.add(nextPoints);
            dfs(edge.other(cur), target, originalPoints, steps, visitedCrs,
                    pointsAtDepth, accepted, rejected, truncated);
            pointsAtDepth.remove(pointsAtDepth.size() - 1);
            visitedCrs.remove(visitedCrs.size() - 1);
            steps.remove(steps.size() - 1);
        }
    }

    private static List<Step> appendStep(List<Step> steps, Edge edge, boolean forward) {
        List<Step> out = new ArrayList<>(steps);
        out.add(new Step(edge, forward));
        return out;
    }

    private List<Edge> sortedOutgoing(String crs) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : registry.edges()) {
            if (e.status() != Edge.Status.ACTIVE) {
                continue;
            }
            if (e.fromCrs.equals(crs) || e.toCrs.equals(crs)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparing(e -> e.id));
        return out;
    }
}
