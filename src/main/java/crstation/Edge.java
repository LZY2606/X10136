package crstation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A directed transform edge from one CRS to another.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code domain} — effective coverage in <b>source</b> coordinates;</li>
 *   <li>{@code accuracy} — advertised upper bound of point displacement
 *       error in metres-equivalent when traversing the edge forward;</li>
 *   <li>{@code invertible} — explicit safety flag; even when the math has an
 *       inverse, an edge may declare itself unsafe to invert;</li>
 *   <li>{@code inverseDomain} — coverage in target coordinates for the
 *       reverse direction; derived automatically when not supplied;</li>
 *   <li>{@code inverseAccuracy} — reverse error bound, defaults to forward.</li>
 * </ul>
 */
public final class Edge {

    public enum Status { ACTIVE, PENDING }

    public final String id;
    public final String fromCrs;
    public final String toCrs;
    public final Transform transform;
    public final Domain domain;
    public final double accuracy;
    public final boolean invertible;
    public final Domain inverseDomain;
    public final double inverseAccuracy;
    public final String description;

    private Status status;
    private String pendingReason;
    private final List<Confirmation> history = new ArrayList<>();

    public record Confirmation(long seq, String reason, String priorStatus, String at,
                               double maxDiscrepancy, String detail) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("reason", reason);
            m.put("priorStatus", priorStatus);
            m.put("at", at);
            m.put("maxDiscrepancy", maxDiscrepancy);
            m.put("detail", detail == null ? "" : detail);
            return m;
        }

        static Confirmation fromMap(Map<String, Object> m) {
            return new Confirmation(
                    (long) Json.optNum(m, "seq", 0),
                    Json.optStr(m, "reason", ""),
                    Json.optStr(m, "priorStatus", "PENDING"),
                    Json.optStr(m, "at", ""),
                    Json.optNum(m, "maxDiscrepancy", 0),
                    Json.optStr(m, "detail", ""));
        }
    }

    public Edge(String id, String fromCrs, String toCrs, Transform transform,
                Domain domain, double accuracy, boolean invertible,
                Domain inverseDomain, double inverseAccuracy,
                String description, Status status, String pendingReason) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("edge id is required");
        }
        if (fromCrs == null || toCrs == null || fromCrs.isBlank() || toCrs.isBlank()) {
            throw new IllegalArgumentException("edge must name fromCrs and toCrs");
        }
        if (fromCrs.equals(toCrs)) {
            throw new IllegalArgumentException("edge from/to CRS must differ");
        }
        if (!Double.isFinite(accuracy) || accuracy < 0) {
            throw new IllegalArgumentException("accuracy must be a finite non-negative number");
        }
        if (!Double.isFinite(inverseAccuracy) || inverseAccuracy < 0) {
            throw new IllegalArgumentException("inverseAccuracy must be a finite non-negative number");
        }
        if (invertible && !transform.mathematicallyInvertible()) {
            throw new IllegalArgumentException("edge '" + id
                    + "' is declared invertible but its transform is singular/non-invertible");
        }
        this.id = id.trim();
        this.fromCrs = fromCrs;
        this.toCrs = toCrs;
        this.transform = transform;
        this.domain = domain;
        this.accuracy = accuracy;
        this.invertible = invertible;
        this.inverseDomain = inverseDomain;
        this.inverseAccuracy = inverseAccuracy;
        this.description = description == null ? "" : description;
        this.status = status;
        this.pendingReason = pendingReason == null ? "" : pendingReason;
    }

    public Status status() {
        return status;
    }

    public String pendingReason() {
        return pendingReason;
    }

    public List<Confirmation> history() {
        return List.copyOf(history);
    }

    void markPending(String reason) {
        this.status = Status.PENDING;
        this.pendingReason = reason;
    }

    Confirmation confirm(long seq, String reason, double maxDiscrepancy, String detail, String at) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a confirmation reason is required");
        }
        Confirmation c = new Confirmation(seq, reason.trim(), status.name(), at,
                maxDiscrepancy, detail);
        history.add(c);
        this.status = Status.ACTIVE;
        this.pendingReason = "";
        return c;
    }

    /** Restore a historical confirmation during event replay. */
    void confirmReplay(Confirmation c) {
        history.add(c);
        this.status = Status.ACTIVE;
        this.pendingReason = "";
    }

    /** Coverage test in traversal order. */
    public boolean covers(boolean forward, double x, double y) {
        Domain d = forward ? domain : inverseDomain;
        return d != null && d.contains(x, y);
    }

    public double edgeAccuracy(boolean forward) {
        return forward ? accuracy : inverseAccuracy;
    }

    public double[] traverse(boolean forward, double[] p) {
        return forward ? transform.apply(p) : transform.applyInverse(p);
    }

    /** The CRS at the other end of this edge. */
    public String other(String crs) {
        if (fromCrs.equals(crs)) {
            return toCrs;
        }
        if (toCrs.equals(crs)) {
            return fromCrs;
        }
        throw new IllegalArgumentException("edge " + id + " is not incident to " + crs);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("fromCrs", fromCrs);
        m.put("toCrs", toCrs);
        m.put("transform", transform.spec());
        m.put("domain", domain.toJson());
        m.put("accuracy", accuracy);
        m.put("invertible", invertible);
        if (inverseDomain != null) {
            m.put("inverseDomain", inverseDomain.toJson());
        }
        m.put("inverseAccuracy", inverseAccuracy);
        m.put("description", description);
        m.put("status", status.name());
        m.put("pendingReason", pendingReason);
        if (!history.isEmpty()) {
            List<Object> hs = new ArrayList<>();
            for (Confirmation c : history) {
                hs.add(c.toJson());
            }
            m.put("history", hs);
        }
        return m;
    }

    /**
     * Reconstruct an edge from persisted JSON. CRS registry is needed to know
     * whether domains live on wrap-around geographic axes.
     */
    public static Edge fromMap(Map<String, Object> m, Registry registry) {
        String id = Json.str(m, "id");
        String from = Json.str(m, "fromCrs");
        String to = Json.str(m, "toCrs");
        Transform t = Transform.fromSpec(Json.obj(m.get("transform")));
        Crs src = registry.crs(from);
        Crs dst = registry.crs(to);
        Domain dom = Domain.fromJson(m.get("domain"));
        boolean inv = Json.optBool(m, "invertible", false);
        double invAcc = Json.optNum(m, "inverseAccuracy", Json.num(m, "accuracy"));
        Domain invDom = m.get("inverseDomain") == null ? null : Domain.fromJson(m.get("inverseDomain"));
        if (inv && invDom == null) {
            invDom = deriveInverseDomain(t, dom, dst.geographic());
        }
        Status st = Status.valueOf(Json.optStr(m, "status", "ACTIVE"));
        Edge e = new Edge(id, from, to, t, dom, Json.num(m, "accuracy"), inv,
                invDom, invAcc, Json.optStr(m, "description", ""), st,
                Json.optStr(m, "pendingReason", ""));
        Object hist = m.get("history");
        if (hist instanceof List<?> list) {
            for (Object o : list) {
                e.history.add(Confirmation.fromMap(Json.obj(o)));
            }
        }
        return e;
    }

    /**
     * Build an edge from registration input, validating references and
     * deriving the inverse domain from transformed coverage corners/ring.
     */
    public static Edge create(Map<String, Object> m, Registry registry) {
        String id = Json.str(m, "id");
        String from = Json.str(m, "fromCrs");
        String to = Json.str(m, "toCrs");
        Crs src = registry.crs(from);
        Crs dst = registry.crs(to);
        Transform t = Transform.fromSpec(Json.obj(m.get("transform")));
        Object domainInput = m.get("domain");
        if (domainInput == null) {
            throw new IllegalArgumentException("edge requires a domain (effective coverage)");
        }
        Domain dom = Domain.create(domainInput, src.geographic());
        double accuracy = Json.num(m, "accuracy");
        boolean inv = Json.optBool(m, "invertible", false);
        double invAcc = Json.optNum(m, "inverseAccuracy", accuracy);
        Domain invDom;
        if (m.get("inverseDomain") != null) {
            invDom = Domain.create(m.get("inverseDomain"), dst.geographic());
        } else if (inv) {
            invDom = deriveInverseDomain(t, dom, dst.geographic());
        } else {
            invDom = null;
        }
        return new Edge(id, from, to, t, dom, accuracy, inv, invDom, invAcc,
                Json.optStr(m, "description", ""), Status.ACTIVE, "");
    }

    /**
     * Transform the coverage boundary forward to obtain coverage in target
     * coordinates. For bboxes the four corners are used (a bbox under an
     * affine/mercator map is still a box-aligned region for affine; the
     * corners give a safe enclosing polygon in the general case).
     */
    static Domain deriveInverseDomain(Transform t, Domain src, boolean targetGeographic) {
        if (src instanceof Domain.Bbox box) {
            List<double[]> corners = Transforms.bboxCorners(box.minX, box.minY, box.maxX, box.maxY)
                    .stream().map(t::apply).toList();
            return polygonOf(corners, targetGeographic);
        }
        Domain.PolygonDomain pd = (Domain.PolygonDomain) src;
        List<List<double[]>> rings = new ArrayList<>();
        for (List<double[]> ring : pd.rings) {
            List<double[]> out = new ArrayList<>(ring.size());
            for (double[] p : ring) {
                out.add(t.apply(p));
            }
            rings.add(out);
        }
        return new Domain.PolygonDomain(rings, targetGeographic);
    }

    private static Domain polygonOf(List<double[]> closed, boolean targetGeographic) {
        return new Domain.PolygonDomain(List.of(new ArrayList<>(closed)), targetGeographic);
    }
}
