package ctstation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A directed transform edge between two registered CRS nodes.
 *
 * <p>The forward direction carries the edge's declared coverage and
 * per-axis accuracy (absolute error bound in target-CRS units). A reverse
 * direction exists only when the edge is safely invertible.
 *
 * <p>New edges that disagree with an existing loop are saved with
 * {@code state = "pending"} and are excluded from default path selection
 * until a confirm action records a reason.
 */
public final class Edge {

    public static final String ACTIVE = "active";
    public static final String PENDING = "pending";
    public static final String DISABLED = "disabled";

    public final String id;
    public final String sourceCrs;
    public final String targetCrs;
    public final Affine forward;
    public final Coverage coverage;
    public final double accuracyX;
    public final double accuracyY;
    /** null  => derive safe-invertibility from the matrix; otherwise explicit. */
    public final Boolean invertible;
    public final String description;

    public String state;
    public String stateReason;
    /** Evidence captured at registration when the edge was flagged. */
    public Map<String, Object> inconsistency;

    public Edge(String id, String sourceCrs, String targetCrs, Affine forward,
                Coverage coverage, double accuracyX, double accuracyY,
                Boolean invertible, String description) {
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new AppException("bad-edge", "edge id must match [A-Za-z0-9_.:-]{1,64}");
        }
        if (!(accuracyX >= 0.0d) || !(accuracyY >= 0.0d)) {
            throw new AppException("bad-edge", "edge accuracy must be >= 0");
        }
        this.id = id;
        this.sourceCrs = sourceCrs;
        this.targetCrs = targetCrs;
        this.forward = forward;
        this.coverage = coverage;
        this.accuracyX = accuracyX;
        this.accuracyY = accuracyY;
        this.invertible = invertible;
        this.description = description == null ? "" : description;
        this.state = ACTIVE;
    }

    /** Effective invertibility: explicit flag wins, otherwise matrix test. */
    public boolean invertible() {
        if (invertible != null) return invertible;
        return forward.safelyInvertible();
    }

    /** Accuracy vector for the given direction (always in the *target* CRS units). */
    public double[] accuracyFor(String toCrs) {
        if (toCrs.equals(targetCrs)) return new double[] {accuracyX, accuracyY};
        if (toCrs.equals(sourceCrs)) return new double[] {accuracyX, accuracyY};
        throw new AppException("bad-edge", "edge " + id + " does not touch " + toCrs);
    }

    /** Resolve the transform and domain for leaving {@code fromCrs}. */
    public Arc arc(String fromCrs) {
        if (fromCrs.equals(sourceCrs)) {
            return new Arc(this, true, forward, coverage, accuracyX, accuracyY);
        }
        if (fromCrs.equals(targetCrs)) {
            if (!invertible()) {
                throw new AppException("not-invertible",
                        "edge " + id + " cannot be safely inverted");
            }
            return new Arc(this, false, forward.inverse(), coverage, accuracyX, accuracyY);
        }
        throw new AppException("bad-edge", "edge " + id + " does not touch " + fromCrs);
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("sourceCrs", sourceCrs);
        m.put("targetCrs", targetCrs);
        m.put("transform", forward.toMap());
        m.put("coverage", coverage.toMap());
        m.put("accuracyX", accuracyX);
        m.put("accuracyY", accuracyY);
        m.put("invertible", invertible); // null = derived
        m.put("effectivelyInvertible", invertible());
        m.put("description", description);
        m.put("state", state);
        m.put("stateReason", stateReason);
        if (inconsistency != null) m.put("inconsistency", inconsistency);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Edge fromMap(Map<String, Object> m) {
        Edge e = new Edge(
                Json.str(m, "id"),
                Json.str(m, "sourceCrs"),
                Json.str(m, "targetCrs"),
                Affine.fromMap(Json.obj(m, "transform")),
                Coverage.fromMap(m.get("coverage")),
                Json.dbl(m, "accuracyX"),
                Json.dbl(m, "accuracyY"),
                (Boolean) m.get("invertible"),
                Json.optStr(m, "description", ""));
        e.state = Json.optStr(m, "state", ACTIVE);
        e.stateReason = (String) m.get("stateReason");
        Object inc = m.get("inconsistency");
        if (inc instanceof Map) e.inconsistency = (Map<String, Object>) inc;
        return e;
    }

    /** One traversable half-edge in a candidate chain. */
    public static final class Arc {
        public final Edge edge;
        public final boolean forwardDirection;
        public final Affine transform;
        public final Coverage domain;
        public final double accuracyX;
        public final double accuracyY;

        Arc(Edge edge, boolean forwardDirection, Affine transform, Coverage domain,
            double accuracyX, double accuracyY) {
            this.edge = edge;
            this.forwardDirection = forwardDirection;
            this.transform = transform;
            this.domain = domain;
            this.accuracyX = accuracyX;
            this.accuracyY = accuracyY;
        }

        public String fromCrs() { return forwardDirection ? edge.sourceCrs : edge.targetCrs; }
        public String toCrs() { return forwardDirection ? edge.targetCrs : edge.sourceCrs; }

        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("edgeId", edge.id);
            m.put("fromCrs", fromCrs());
            m.put("toCrs", toCrs());
            m.put("direction", forwardDirection ? "forward" : "inverse");
            m.put("accuracyX", accuracyX);
            m.put("accuracyY", accuracyY);
            List<Object> v = new ArrayList<>();
            for (double x : transform.toArray()) v.add(x);
            m.put("appliedMatrix", v);
            return m;
        }
    }
}
