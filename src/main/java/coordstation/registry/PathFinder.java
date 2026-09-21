package coordstation.registry;

import coordstation.model.Edge;
import coordstation.model.LonLatBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Enumerates simple transform paths and ranks them deterministically. */
public final class PathFinder {

    public static final class Step {
        public final Edge edge;
        public final boolean inverse;

        public Step(Edge edge, boolean inverse) {
            this.edge = edge;
            this.inverse = inverse;
        }

        public String from() { return inverse ? edge.dst : edge.src; }
        public String to() { return inverse ? edge.src : edge.dst; }
        public String label() { return (inverse ? "-" : "+") + edge.id; }
    }

    public static final class Path {
        public final List<Step> steps;
        public final boolean covered;

        Path(List<Step> steps, boolean covered) {
            this.steps = steps;
            this.covered = covered;
        }

        public double error() {
            double e = 0;
            for (Step s : steps) e += s.edge.accuracy;
            return e;
        }

        /** Stable identity used for tie-breaking: joined step labels. */
        public String signature() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(steps.get(i).label());
            }
            return sb.toString();
        }
    }

    /**
     * All simple paths from src to dst over ACTIVE edges (inverse traversal only
     * when the edge declares itself invertible), each flagged for region coverage.
     */
    public static List<Path> findAll(List<Edge> activeEdges, String src, String dst,
                                     LonLatBox geomRegion, int maxDepth) {
        List<Path> out = new ArrayList<>();
        if (src.equals(dst)) {
            out.add(new Path(new ArrayList<>(), true));
            return out;
        }
        List<Edge> sorted = new ArrayList<>(activeEdges);
        sorted.sort(Comparator.comparing(e -> e.id));
        boolean[] used = new boolean[sorted.size()];
        dfs(sorted, src, dst, geomRegion, maxDepth, used, new ArrayList<>(), out);
        return out;
    }

    private static void dfs(List<Edge> edges, String node, String dst, LonLatBox region,
                            int depthLeft, boolean[] used, List<Step> current, List<Path> out) {
        if (depthLeft == 0) return;
        for (int i = 0; i < edges.size(); i++) {
            if (used[i]) continue;
            Edge e = edges.get(i);
            Boolean inverse = null;
            if (e.src.equals(node)) inverse = Boolean.FALSE;
            else if (e.dst.equals(node) && e.invertible) inverse = Boolean.TRUE;
            if (inverse == null) continue;
            Step step = new Step(e, inverse);
            used[i] = true;
            current.add(step);
            if (step.to().equals(dst)) {
                boolean covered = true;
                for (Step s : current) {
                    if (!s.edge.region.covers(region)) { covered = false; break; }
                }
                out.add(new Path(new ArrayList<>(current), covered));
            } else {
                dfs(edges, step.to(), dst, region, depthLeft - 1, used, current, out);
            }
            current.remove(current.size() - 1);
            used[i] = false;
        }
    }

    /** Order: coverage first, then cumulative error, then stable signature. */
    public static final Comparator<Path> RANK =
            Comparator.comparing((Path p) -> !p.covered)
                    .thenComparingDouble(Path::error)
                    .thenComparing(Path::signature);
}
