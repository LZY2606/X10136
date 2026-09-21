package ctr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PathPlanner {
    public static final int MAX_HOPS = 8;

    private PathPlanner() {
    }

    public record ChosenPath(List<Arc> arcs, double errorBound, AffineTransform2D totalTransform) {
    }

    public record CandidatePath(String status, List<Arc> arcs, Double errorBound, String reason,
                                List<Map<String, Object>> hops) implements Comparable<CandidatePath> {
        @Override
        public int compareTo(CandidatePath other) {
            int byError = Double.compare(errorBound, other.errorBound);
            if (byError != 0) {
                return byError;
            }
            int byLength = Integer.compare(arcs.size(), other.arcs.size());
            if (byLength != 0) {
                return byLength;
            }
            return signature().compareTo(other.signature());
        }

        public String signature() {
            List<String> parts = new ArrayList<>();
            for (Arc arc : arcs) {
                parts.add(arc.signature());
            }
            return String.join(">", parts);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.put("errorBound", errorBound);
            map.put("reason", reason);
            map.put("hops", hops);
            return map;
        }
    }

    public record PlanResult(boolean eligible, ChosenPath chosen, List<CandidatePath> candidates,
                             List<Map<String, Object>> rejectedArcs, String diagnostic) {
        public Map<String, Object> toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("eligible", eligible);
            if (chosen != null) {
                List<Object> path = new ArrayList<>();
                for (Arc arc : chosen.arcs) {
                    path.add(arc.toJson());
                }
                map.put("selectedPath", path);
                map.put("errorBound", chosen.errorBound);
                Map<String, Object> explanation = new LinkedHashMap<>();
                explanation.put("text", explain(chosen.arcs, chosen.errorBound));
                map.put("explanation", explanation);
            }
            List<Object> candidateList = new ArrayList<>();
            for (CandidatePath candidate : candidates) {
                candidateList.add(candidate.toJson());
            }
            map.put("candidates", candidateList);
            map.put("rejectedArcs", rejectedArcs);
            map.put("diagnostic", diagnostic);
            return map;
        }
    }

    public static PlanResult plan(RegistrySnapshot snapshot, String source, String target, Geometry geometry) {
        if (snapshot.crs(source) == null) {
            throw new ApiException(400, "Unknown source CRS: " + source);
        }
        if (snapshot.crs(target) == null) {
            throw new ApiException(400, "target CRS: " + target);
        }
        List<Map<String, Object>> rejectedArcs = collectRejectedArcs(snapshot, source);
        if (source.equals(target)) {
            ChosenPath empty = new ChosenPath(List.of(), 0.0, identity());
            CandidatePath candidate = new CandidatePath("ELIGIBLE", List.of(), 0.0,
                    "Source and target CRS are identical; zero-edge identity path.", List.of());
            return new PlanResult(true, empty, List.of(candidate), rejectedArcs, null);
        }
        List<CandidatePath> paths = new ArrayList<>();
        List<Arc> current = new ArrayList<>();
        List<String> visited = new ArrayList<>();
        visited.add(source);
        search(snapshot, source, target, current, visited, paths, geometry);
        List<CandidatePath> eligible = paths.stream()
                .filter(path -> path.status.equals("ELIGIBLE"))
                .sorted()
                .toList();
        List<CandidatePath> ordered = new ArrayList<>(eligible);
        paths.stream().filter(path -> !path.status.equals("ELIGIBLE")).sorted(Comparator.comparing(CandidatePath::signature)).forEach(ordered::add);
        if (eligible.isEmpty()) {
            long coverageFailures = paths.stream()
                    .filter(path -> path.status().equals("EXCLUDED_COVERAGE"))
                    .count();
            String diagnostic = "No eligible path from " + source + " to " + target
                    + ". Check CRS ids, edge coverage, pending confirmations and inverse-safety flags."
                    + (coverageFailures > 0 ? " valid region coverage check excluded " + coverageFailures
                    + (coverageFailures == 1 ? " candidate path." : " candidate paths.") : "");
            return new PlanResult(false, null, ordered, rejectedArcs, diagnostic);
        }
        CandidatePath best = eligible.get(0);
        ChosenPath chosen = materialize(best.arcs);
        return new PlanResult(true, chosen, ordered, rejectedArcs, null);
    }

    private static void search(RegistrySnapshot snapshot, String current, String target,
                               List<Arc> path, List<String> visited, List<CandidatePath> results,
                               Geometry geometry) {
        if (path.size() >= MAX_HOPS) {
            return;
        }
        List<Arc> outgoing = outgoingArcs(snapshot, current).stream()
                .sorted(Comparator.comparing(Arc::signature))
                .toList();
        for (Arc arc : outgoing) {
            String next = arc.toCrs();
            if (visited.contains(next)) {
                continue;
            }
            path.add(arc);
            visited.add(next);
            if (next.equals(target)) {
                addResult(results, new ArrayList<>(path), geometry);
            } else {
                search(snapshot, next, target, path, visited, results, geometry);
            }
            visited.remove(visited.size() - 1);
            path.remove(path.size() - 1);
        }
    }

    private static void addResult(List<CandidatePath> results, List<Arc> path, Geometry geometry) {
        double error = 0.0;
        AffineTransform2D transform = identity();
        String firstOutside = firstOutsidePoint(path, geometry);
        if (firstOutside != null) {
            results.add(new CandidatePath("EXCLUDED_COVERAGE", List.copyOf(path), null,
                    "Excluded because the geometry leaves an edge's declared valid region at " + firstOutside + ".",
                    hopDetails(path)));
            return;
        }
        for (Arc arc : path) {
            error = arc.accuracy() + arc.transform().operatorNorm() * error;
            transform = transform.compose(arc.transform());
        }
        String reason = "Accepted; coverage satisfied, propagated upper bound calculated, ties broken by hops then arc ids.";
        results.add(new CandidatePath("ELIGIBLE", List.copyOf(path), error, reason, hopDetails(path)));
    }

    private static String firstOutsidePoint(List<Arc> path, Geometry geometry) {
        List<Coordinate> carried = allCoordinates(geometry);
        for (Arc arc : path) {
            Region region = arc.region();
            for (Coordinate coordinate : carried) {
                if (!region.contains(coordinate)) {
                    return String.format(Locale.ROOT, "%.12g,%.12g on %s/%s",
                            coordinate.x(), coordinate.y(), arc.edgeId(), arc.reversed() ? "reverse" : "forward");
                }
            }
            AffineTransform2D transform = arc.transform();
            List<Coordinate> next = new ArrayList<>();
            for (Coordinate coordinate : carried) {
                next.add(transform.apply(coordinate));
            }
            carried = next;
        }
        return null;
    }

    private static List<Coordinate> allCoordinates(Geometry geometry) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (List<Coordinate> ring : geometry.rings()) {
            coordinates.addAll(ring);
        }
        return coordinates;
    }

    public static ChosenPath materialize(List<Arc> arcs) {
        double error = 0.0;
        AffineTransform2D transform = identity();
        for (Arc arc : arcs) {
            error = arc.accuracy() + arc.transform().operatorNorm() * error;
            transform = transform.compose(arc.transform());
        }
        return new ChosenPath(List.copyOf(arcs), error, transform);
    }

    public static AffineTransform2D identity() {
        return new AffineTransform2D(1, 0, 0, 0, 1, 0);
    }

    private static List<Arc> outgoingArcs(RegistrySnapshot snapshot, String crs) {
        List<Arc> arcs = new ArrayList<>();
        for (Edge edge : snapshot.edges()) {
            if (edge.fromCrs.equals(crs)) {
                arcs.add(new Arc(edge, false));
            }
            if (edge.toCrs.equals(crs) && edge.inverseSafe) {
                arcs.add(new Arc(edge, true));
            }
        }
        return arcs;
    }

    private static List<Map<String, Object>> collectRejectedArcs(RegistrySnapshot snapshot, String source) {
        List<String> reachable = new ArrayList<>();
        List<String> queue = new ArrayList<>();
        reachable.add(source);
        queue.add(source);
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            for (Arc arc : outgoingArcs(snapshot, current)) {
                if (!reachable.contains(arc.toCrs())) {
                    reachable.add(arc.toCrs());
                    queue.add(arc.toCrs());
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String node : reachable) {
            collectRejectedAt(snapshot, node, result);
        }
        result.sort(Comparator.comparing(map -> String.valueOf(map.get("edgeId")) + map.get("direction")));
        return result;
    }

    private static void collectRejectedAt(RegistrySnapshot snapshot, String node, List<Map<String, Object>> result) {
        for (Map<String, Object> pending : snapshot.pendingEdges()) {
            String from = String.valueOf(pending.get("fromCrs"));
            String to = String.valueOf(pending.get("toCrs"));
            String id = String.valueOf(pending.get("id"));
            if (from.equals(node)) {
                result.add(rejectedArc(id, "forward", from, to, "PENDING_CONFIRMATION",
                        "Edge is pending because sampled loop consistency was not accepted."));
            }
            if (to.equals(node) && Boolean.TRUE.equals(pending.get("inverseSafe"))) {
                result.add(rejectedArc(id, "reverse", to, from, "PENDING_CONFIRMATION",
                        "Inverse of an unconfirmed edge cannot participate in a default path."));
            }
        }
        for (Edge edge : snapshot.edges()) {
            if (edge.toCrs.equals(node) && !edge.inverseSafe) {
                result.add(rejectedArc(edge.id, "reverse", edge.toCrs, edge.fromCrs, "IRREVERSIBLE",
                        "The edge's inverse is not declared numerically or semantically safe."));
            }
        }
    }

    private static Map<String, Object> rejectedArc(String id, String direction, String from, String to,
                                                   String code, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("edgeId", id);
        map.put("direction", direction);
        map.put("fromCrs", from);
        map.put("toCrs", to);
        map.put("code", code);
        map.put("reason", reason);
        return map;
    }

    private static List<Map<String, Object>> hopDetails(List<Arc> path) {
        List<Map<String, Object>> hops = new ArrayList<>();
        double cumulative = 0.0;
        AffineTransform2D transform = identity();
        for (Arc arc : path) {
            AffineTransform2D current = arc.transform();
            cumulative = arc.accuracy() + current.operatorNorm() * cumulative;
            transform = transform.compose(current);
            Map<String, Object> hop = arc.toJson();
            hop.put("operatorNorm", current.operatorNorm());
            hop.put("cumulativeErrorBound", cumulative);
            hop.put("coverageCheck", arc.region().toJson());
            hops.add(hop);
        }
        return hops;
    }

    public static String explain(List<Arc> arcs, double error) {
        if (arcs.isEmpty()) {
            return "Use the zero-edge identity transform; accumulated error upper bound is 0.";
        }
        List<String> parts = new ArrayList<>();
        parts.add(arcs.get(0).fromCrs());
        for (Arc arc : arcs) {
            parts.add(arc.toCrs() + "[" + arc.edgeId() + (arc.reversed() ? "^-1" : "") + "]");
        }
        return "Path " + String.join(" -> ", parts)
                + String.format(Locale.ROOT, "; accumulated propagated error upper bound = %.12g.", error);
    }
}
