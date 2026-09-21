package station.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import station.model.LonArc;
import station.model.Region;
import station.model.TransformEdge;

/**
 * 链路选择：先满足覆盖范围，再比较累计误差上界，最后以路径键字典序稳定打破平局。
 * 只使用 ACTIVE 状态的边；可逆边提供反向弧。
 */
public final class PathFinder {

    /** 一条有向可用弧（正向或反向）。 */
    public static final class Arc {
        public final TransformEdge edge;
        public final boolean inverse;
        public final String from;
        public final String to;
        public final Affine affine;

        Arc(TransformEdge edge, boolean inverse, Affine affine) {
            this.edge = edge;
            this.inverse = inverse;
            this.affine = affine;
            this.from = inverse ? edge.toCrs : edge.fromCrs;
            this.to = inverse ? edge.fromCrs : edge.toCrs;
        }

        public String label() {
            return edge.id + (inverse ? "(逆)" : "");
        }
    }

    public static final class Path {
        public final List<Arc> arcs;
        public final double totalError;

        Path(List<Arc> arcs) {
            this.arcs = arcs;
            double sum = 0;
            for (Arc a : arcs) sum += a.edge.accuracy;
            this.totalError = sum;
        }

        /** 稳定平局用的路径键：弧标签按字典序连接。 */
        public String key() {
            StringBuilder sb = new StringBuilder();
            for (Arc a : arcs) {
                if (sb.length() > 0) sb.append('/');
                sb.append(a.label());
            }
            return sb.toString();
        }

        public List<Object> describe() {
            List<Object> out = new ArrayList<>();
            for (Arc a : arcs) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("edge", a.edge.id);
                m.put("inverse", a.inverse);
                m.put("from", a.from);
                m.put("to", a.to);
                m.put("accuracy", a.edge.accuracy);
                out.add(m);
            }
            return out;
        }
    }

    /** 一条被评估过的候选路径及其结论。 */
    public static final class Candidate {
        public String key;
        public double totalError;
        public boolean accepted;
        public boolean chosen;
        public String reason;
        public Path path;
    }

    public static final class Result {
        public Path best;
        public final List<Candidate> candidates = new ArrayList<>();
        public String failure;
    }

    public static Result find(List<TransformEdge> edges, String from, String to,
                              LonArc arc, double latMin, double latMax) {
        Result result = new Result();
        if (from.equals(to)) {
            Path identity = new Path(new ArrayList<>());
            Candidate c = new Candidate();
            c.key = "(恒等)";
            c.totalError = 0;
            c.accepted = true;
            c.chosen = true;
            c.reason = "源与目标参考系相同，无需变换";
            c.path = identity;
            result.candidates.add(c);
            result.best = identity;
            return result;
        }

        List<Path> paths = new ArrayList<>();
        List<String> coverageFailures = new ArrayList<>();
        dfs(edges, from, to, arc, latMin, latMax, new ArrayList<>(), new HashSet<>(), paths, coverageFailures);

        if (paths.isEmpty()) {
            StringBuilder sb = new StringBuilder("不存在覆盖对象范围的可用链路");
            if (!coverageFailures.isEmpty()) {
                sb.append("；");
                sb.append(String.join("；", coverageFailures));
            }
            result.failure = sb.toString();
            return result;
        }

        paths.sort(Comparator.comparingDouble((Path p) -> p.totalError).thenComparing(Path::key));
        Path best = paths.get(0);
        result.best = best;
        for (int i = 0; i < paths.size(); i++) {
            Path p = paths.get(i);
            Candidate c = new Candidate();
            c.key = p.key();
            c.totalError = p.totalError;
            c.path = p;
            if (i == 0) {
                c.accepted = true;
                c.chosen = true;
                c.reason = "覆盖范围满足，累计误差上界最小";
            } else if (p.totalError == best.totalError) {
                c.accepted = false;
                c.reason = "与已选路径误差相同（" + p.totalError + "），平局按路径键字典序稳定裁决，"
                        + p.key() + " 排在 " + best.key() + " 之后";
            } else {
                c.accepted = false;
                c.reason = "覆盖范围满足，但累计误差上界 " + p.totalError + " 高于已选路径 " + best.totalError;
            }
            result.candidates.add(c);
        }
        return result;
    }

    private static void dfs(List<TransformEdge> edges, String current, String target,
                            LonArc arc, double latMin, double latMax,
                            List<Arc> prefix, Set<String> visited,
                            List<Path> out, List<String> coverageFailures) {
        if (current.equals(target)) {
            out.add(new Path(new ArrayList<>(prefix)));
            return;
        }
        if (visited.size() > edges.size() + 1) return; // 保险
        List<TransformEdge> sorted = new ArrayList<>(edges);
        sorted.sort(Comparator.comparing(e -> e.id));
        for (TransformEdge e : sorted) {
            for (boolean inv : new boolean[]{false, true}) {
                String a = inv ? e.toCrs : e.fromCrs;
                String b = inv ? e.fromCrs : e.toCrs;
                if (!a.equals(current)) continue;
                if (visited.contains(b)) continue;
                if (inv) {
                    if (!e.invertible) continue;
                    if (e.affine.inverse() == null) continue;
                }
                Region region = e.region;
                if (!region.covers(arc, latMin, latMax)) {
                    String msg = "边 " + e.id + (inv ? "(逆)" : "") + " 的有效范围 " + region
                            + " 不覆盖对象范围 经度 " + arc + " 纬度 [" + latMin + ", " + latMax + "]";
                    if (!coverageFailures.contains(msg)) coverageFailures.add(msg);
                    continue;
                }
                Affine affine = inv ? e.affine.inverse() : e.affine;
                prefix.add(new Arc(e, inv, affine));
                visited.add(b);
                dfs(edges, b, target, arc, latMin, latMax, prefix, visited, out, coverageFailures);
                visited.remove(b);
                prefix.remove(prefix.size() - 1);
            }
        }
    }
}
