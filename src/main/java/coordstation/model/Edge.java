package coordstation.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Edge {
    public enum Status { ACTIVE, PENDING }

    public final String id;
    public final String src;
    public final String dst;
    public final LonLatBox region;
    public final double accuracy;
    public final Affine forward;
    public final boolean invertible;
    public Status status;
    public String note;
    public String confirmReason;

    public Edge(String id, String src, String dst, LonLatBox region, double accuracy,
                Affine forward, boolean invertible, Status status, String note) {
        this.id = id;
        this.src = src;
        this.dst = dst;
        this.region = region;
        this.accuracy = accuracy;
        this.forward = forward;
        this.invertible = invertible;
        this.status = status;
        this.note = note == null ? "" : note;
        this.confirmReason = "";
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("src", src);
        m.put("dst", dst);
        m.put("region", region.toJson());
        m.put("accuracy", accuracy);
        m.put("affine", forward.toJson());
        m.put("invertible", invertible);
        m.put("status", status.name());
        m.put("note", note);
        m.put("confirmReason", confirmReason);
        return m;
    }

    public static Edge fromJson(Map<String, Object> m) {
        Edge e = new Edge(
                coordstation.json.Json.str(m, "id"),
                coordstation.json.Json.str(m, "src"),
                coordstation.json.Json.str(m, "dst"),
                LonLatBox.fromJson(coordstation.json.Json.obj(m, "region")),
                coordstation.json.Json.num(m, "accuracy"),
                Affine.fromJson(coordstation.json.Json.arr(m, "affine")),
                coordstation.json.Json.bool(m, "invertible", false),
                Status.valueOf(coordstation.json.Json.strOr(m, "status", "ACTIVE")),
                coordstation.json.Json.strOr(m, "note", ""));
        e.confirmReason = coordstation.json.Json.strOr(m, "confirmReason", "");
        return e;
    }
}
