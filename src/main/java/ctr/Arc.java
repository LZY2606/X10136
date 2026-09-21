package ctr;

import java.util.LinkedHashMap;
import java.util.Map;

public record Arc(Edge edge, boolean reversed) {
    public String edgeId() {
        return edge.id;
    }
    public String signature() {
        return edge.id + (reversed ? "|reverse" : "|forward");
    }

    public String fromCrs() {
        return reversed ? edge.toCrs : edge.fromCrs;
    }

    public String toCrs() {
        return reversed ? edge.fromCrs : edge.toCrs;
    }

    public Region region() {
        return reversed ? edge.inverseRegion : edge.region;
    }

    public double accuracy() {
        return reversed ? edge.inverseAccuracy : edge.forwardAccuracy;
    }

    public AffineTransform2D transform() {
        AffineTransform2D transform = edge.forward;
        return reversed ? transform.inverse() : transform;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("edgeId", edge.id);
        map.put("direction", reversed ? "reverse" : "forward");
        map.put("fromCrs", fromCrs());
        map.put("toCrs", toCrs());
        map.put("accuracy", accuracy());
        return map;
    }
}
