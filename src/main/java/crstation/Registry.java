package crstation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory coordinate registry: CRSs, transformation edges, versioning and
 * loop-consistency checking.
 *
 * <p>Versioning: adding a CRS, registering an ACTIVE edge, or confirming an
 * edge each produce a new registry version. Pending edges do not affect the
 * version and never participate in default path selection.
 */
public final class Registry {

    /** Absolute tolerance added to accuracy-based loop consistency thresholds. */
    public static final double LOOP_TOLERANCE = 1e-6;

    private final Map<String, Crs> crss = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private int version = 0;
    private long eventSeq = 0;
    private EventSink sink = event -> { };

    @FunctionalInterface
    public interface EventSink {
        void append(Map<String, Object> event);
    }

    public void setEventSink(EventSink sink) {
        this.sink = sink == null ? e -> { } : sink;
    }

    public int version() {
        return version;
    }

    public List<Crs> crsList() {
        return List.copyOf(crss.values());
    }

    public List<Edge> edges() {
        return List.copyOf(edges.values());
    }

    public Crs crs(String code) {
        return crss.get(code);
    }

    public Edge edge(String id) {
        return edges.get(id);
    }

    /** Adopt an edge reconstructed from an export bundle (status authoritative). */
    public void adopt(Edge edge) {
        if (edges.containsKey(edge.id)) {
            throw new IllegalArgumentException("edge id already exists: " + edge.id);
        }
        edges.put(edge.id, edge);
        version++;
        for (Edge.Confirmation c : edge.history()) {
            eventSeq = Math.max(eventSeq, c.seq());
        }
        emit(event("edge_registered", Map.of("edge", edge.toJson())));
    }

    // ------------------------------------------------------------------
    // Mutation
    // ------------------------------------------------------------------

    public Crs addCrs(Map<String, Object> input) {
        Crs crs = Crs.fromMap(input);
        if (crss.containsKey(crs.code())) {
            throw new IllegalArgumentException("CRS already registered: " + crs.code());
        }
        crss.put(crs.code(), crs);
        version++;
        emit(event("crs_added", Map.of("crs", crs.toJson())));
        return crs;
    }

    /**
     * Register an edge. When an already-confirmed alternative chain between
     * the same two CRSs disagrees at sampled coverage points beyond the
     * combined accuracy bounds, the new edge enters PENDING state.
     */
    public Edge registerEdge(Map<String, Object> input) {
        Edge edge = Edge.create(input, this);
        if (edges.containsKey(edge.id)) {
            throw new IllegalArgumentException("edge id already exists: " + edge.id);
        }

        LoopCheck check = checkLoopConsistency(edge);
        if (!check.consistent) {
            edge.markPending(check.describe());
        }
        edges.put(edge.id, edge);
        version++;
        emit(event("edge_registered", Map.of("edge", edge.toJson())));
        return edge;
    }

    /**
     * Confirm a PENDING edge with a mandatory human-supplied reason.
     * Creates a new registry version and records the decision on the edge.
     */
    public Edge confirmEdge(String id, String reason) {
        Edge edge = edges.get(id);
        if (edge == null) {
            throw new IllegalArgumentException("unknown edge: " + id);
        }
        if (edge.status() != Edge.Status.PENDING) {
            throw new IllegalArgumentException("edge '" + id + "' is not pending");
        }
        LoopCheck check = checkLoopConsistency(edge);
        long seq = ++eventSeq;
        Edge.Confirmation c = edge.confirm(seq, reason, check.maxDiscrepancy,
                check.detail(), java.time.Instant.now().toString());
        version++;
        emit(event("edge_confirmed", Map.of(
                "edgeId", id,
                "confirmation", c.toJson())));
        return edge;
    }

    // ------------------------------------------------------------------
    // Loop consistency via sampled coverage points
    // ------------------------------------------------------------------

    static final class LoopCheck {
        boolean consistent = true;
        double maxDiscrepancy = 0;
        String failingSample = "";
        double threshold = 0;
        String existingPath = "";

        String describe() {
            return "loop mismatch at " + failingSample + ": discrepancy "
                    + fmt(maxDiscrepancy) + " m exceeds combined bound " + fmt(threshold)
                    + " m along existing chain [" + existingPath + "]";
        }

        String detail() {
            return failingSample.isEmpty() ? ""
                    : "max discrepancy " + fmt(maxDiscrepancy) + " m vs bound "
                            + fmt(threshold) + " m at " + failingSample;
        }
    }

    public LoopCheck checkLoopConsistency(Edge edge) {
        LoopCheck result = new LoopCheck();
        Crs target = crss.get(edge.toCrs);
        List<double[]> samples = sampleDomain(edge.domain);

        PathFinder finder = new PathFinder(this);
        for (double[] p : samples) {
            Geometry.Point probe = new Geometry.Point(p);
            PathFinder.Analysis analysis = finder.analyze(edge.fromCrs, edge.toCrs, probe);
            if (analysis.accepted.isEmpty()) {
                continue;
            }
            PathFinder.Path best = analysis.accepted.get(0);
            double[] viaNew = edge.traverse(true, p);
            double[] viaExisting = p.clone();
            for (PathFinder.Step step : best.steps) {
                viaExisting = step.edge.traverse(step.forward, viaExisting);
            }
            double discrepancy = planarMetres(
                    viaNew[0] - viaExisting[0],
                    viaNew[1] - viaExisting[1],
                    target.geographic(), viaExisting[1]);
            double threshold = edge.accuracy + best.errorBound + LOOP_TOLERANCE;
            if (discrepancy > result.maxDiscrepancy) {
                result.maxDiscrepancy = discrepancy;
                result.threshold = threshold;
                result.failingSample = "(" + fmt(p[0]) + ", " + fmt(p[1]) + ")";
                result.existingPath = String.join(",", best.edgeKey());
            }
            if (discrepancy > threshold) {
                result.consistent = false;
            }
        }
        return result;
    }

    /**
     * Convert a coordinate-space delta into approximate metres. Geographic
     * axes use a standard per-degree conversion at the local latitude.
     */
    static double planarMetres(double dx, double dy, boolean geographic, double lat) {
        if (!geographic) {
            return Math.hypot(dx, dy);
        }
        double mx = dx * 111320.0 * Math.cos(Math.toRadians(Math.max(-89.9, Math.min(89.9, lat))));
        double my = dy * 111320.0;
        return Math.hypot(mx, my);
    }

    /** Deterministic 5x5 sample grid covering the domain, boundaries included. */
    static List<double[]> sampleDomain(Domain domain) {
        List<double[]> pts = new ArrayList<>();
        if (domain instanceof Domain.Bbox box) {
            for (int gy = 0; gy <= 4; gy++) {
                double y = box.minY + (box.maxY - box.minY) * gy / 4.0;
                for (int gx = 0; gx <= 4; gx++) {
                    double lon = box.minX + (lonSpan(box) * gx / 4.0);
                    if (box.wraparound && lon > 180.0) {
                        lon -= 360.0;
                    }
                    pts.add(new double[] {lon, y});
                }
            }
        } else {
            Domain.PolygonDomain pd = (Domain.PolygonDomain) domain;
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (List<double[]> ring : pd.rings) {
                for (double[] p : ring) {
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxY = Math.max(maxY, p[1]);
                }
            }
            for (int gy = 0; gy <= 4; gy++) {
                double y = minY + (maxY - minY) * gy / 4.0;
                for (int gx = 0; gx <= 4; gx++) {
                    double x = minX + (maxX - minX) * gx / 4.0;
                    if (pd.contains(x, y)) {
                        pts.add(new double[] {x, y});
                    }
                }
            }
        }
        return pts;
    }

    private static double lonSpan(Domain.Bbox box) {
        if (!box.wraparound) {
            return box.maxX - box.minX;
        }
        double span = box.maxX - box.minX + 360.0;
        return span >= 360.0 ? 360.0 : span;
    }

    private static String fmt(double v) {
        return Json.formatNumber(v);
    }

    // ------------------------------------------------------------------
    // Events / replay
    // ------------------------------------------------------------------

    private Map<String, Object> event(String type, Map<String, Object> payload) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("seq", eventSeq);
        ev.put("type", type);
        ev.put("at", java.time.Instant.now().toString());
        ev.putAll(payload);
        return ev;
    }

    private void emit(Map<String, Object> ev) {
        eventSeq++;
        sink.append(ev);
    }

    /** Replay a persisted event without re-running consistency checks. */
    public void replay(Map<String, Object> ev) {
        long seq = ((Number) ev.getOrDefault("seq", 0)).longValue();
        eventSeq = Math.max(eventSeq, seq);
        switch (Json.str(ev, "type")) {
            case "crs_added" -> {
                Crs crs = Crs.fromMap(Json.obj(ev.get("crs")));
                crss.put(crs.code(), crs);
                version++;
            }
            case "edge_registered" -> {
                Edge e = Edge.fromMap(Json.obj(ev.get("edge")), this);
                edges.put(e.id, e);
                version++;
            }
            case "edge_confirmed" -> {
                String edgeId = Json.str(ev, "edgeId");
                Edge e = edges.get(edgeId);
                Edge.Confirmation confirmation =
                        Edge.Confirmation.fromMap(Json.obj(ev.get("confirmation")));
                eventSeq = Math.max(eventSeq, confirmation.seq());
                e.confirmReplay(confirmation);
                version++;
            }
            default -> throw new IllegalArgumentException("unknown event: " + ev.get("type"));
        }
    }

    public long nextEventSeq() {
        return eventSeq;
    }
}
