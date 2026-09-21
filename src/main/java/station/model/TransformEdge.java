package station.model;

import java.util.LinkedHashMap;
import java.util.Map;
import station.engine.Affine;

/** 一条有向变换边：源 CRS -> 目标 CRS，带有效范围、精度说明与可逆标记。 */
public final class TransformEdge {
    public enum Status { ACTIVE, PENDING }

    public final String id;
    public final String fromCrs;
    public final String toCrs;
    public final Region region;
    public final double accuracy;
    public final boolean invertible;
    public final Affine affine;
    public Status status;
    public String pendingReason;
    public String confirmReason;
    public Long confirmedAtVersion;

    public TransformEdge(String id, String fromCrs, String toCrs, Region region,
                         double accuracy, boolean invertible, Affine affine) {
        this.id = id;
        this.fromCrs = fromCrs;
        this.toCrs = toCrs;
        this.region = region;
        this.accuracy = accuracy;
        this.invertible = invertible;
        this.affine = affine;
        this.status = Status.ACTIVE;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("from", fromCrs);
        m.put("to", toCrs);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("lonMin", region.lonArc.start);
        r.put("lonMax", region.lonArc.end);
        r.put("latMin", region.latMin);
        r.put("latMax", region.latMax);
        m.put("region", r);
        m.put("accuracy", accuracy);
        m.put("invertible", invertible);
        m.put("affine", affine.toList());
        m.put("status", status.name());
        if (pendingReason != null) m.put("pendingReason", pendingReason);
        if (confirmReason != null) m.put("confirmReason", confirmReason);
        if (confirmedAtVersion != null) m.put("confirmedAtVersion", confirmedAtVersion);
        return m;
    }

    public static TransformEdge fromJson(Map<String, Object> m) {
        String id = station.json.Json.asString(m.get("id"), "边 id");
        String from = station.json.Json.asString(m.get("from"), "边 from");
        String to = station.json.Json.asString(m.get("to"), "边 to");
        Map<String, Object> r = station.json.Json.asObject(m.get("region"), "边 region");
        double lonMin = station.json.Json.asDouble(r.get("lonMin"), "region.lonMin");
        double lonMax = station.json.Json.asDouble(r.get("lonMax"), "region.lonMax");
        double latMin = station.json.Json.asDouble(r.get("latMin"), "region.latMin");
        double latMax = station.json.Json.asDouble(r.get("latMax"), "region.latMax");
        for (double v : new double[]{lonMin, lonMax, latMin, latMax}) {
            if (!Double.isFinite(v)) throw new IllegalArgumentException("有效范围含非有限数字");
        }
        if (latMin < -90 || latMax > 90 || latMin > latMax) {
            throw new IllegalArgumentException("有效范围纬度非法: [" + latMin + ", " + latMax + "]");
        }
        double accuracy = station.json.Json.asDouble(m.get("accuracy"), "边 accuracy");
        if (!Double.isFinite(accuracy) || accuracy < 0) {
            throw new IllegalArgumentException("精度说明必须是非负有限数值");
        }
        boolean invertible = station.json.Json.asBoolean(m.get("invertible"), "边 invertible");
        Affine affine = Affine.fromList(station.json.Json.asArray(m.get("affine"), "边 affine"));
        TransformEdge e = new TransformEdge(id, from, to,
                new Region(lonMin, lonMax, latMin, latMax), accuracy, invertible, affine);
        Object status = m.get("status");
        if (status != null) e.status = Status.valueOf(station.json.Json.asString(status, "边 status"));
        Object pr = m.get("pendingReason");
        if (pr instanceof String) e.pendingReason = (String) pr;
        Object cr = m.get("confirmReason");
        if (cr instanceof String) e.confirmReason = (String) cr;
        Object cv = m.get("confirmedAtVersion");
        if (cv instanceof Long) e.confirmedAtVersion = (Long) cv;
        return e;
    }
}
