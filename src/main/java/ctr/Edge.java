package ctr;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Edge {
    public final String id;
    public final String fromCrs;
    public final String toCrs;
    public final AffineTransform2D forward;
    public final boolean inverseSafe;
    public final double forwardAccuracy;
    public final Double inverseAccuracy;
    public final Region region;
    public final Region inverseRegion;
    public final String source;
    public final String notes;
    public final String introducedInVersion;

    public Edge(String id, String fromCrs, String toCrs, AffineTransform2D forward,
                boolean inverseSafe, double forwardAccuracy, Double inverseAccuracy,
                Region region, Region inverseRegion, String source, String notes,
                String introducedInVersion) {
        this.id = Validate.id(id, "edge id");
        this.fromCrs = Validate.id(fromCrs, "fromCrs");
        this.toCrs = Validate.id(toCrs, "toCrs");
        if (fromCrs.equals(toCrs)) {
            throw new ApiException(400, "An edge cannot connect a CRS to itself");
        }
        this.forward = forward;
        this.inverseSafe = inverseSafe;
        if (inverseSafe) {
            try {
                forward.inverse();
            } catch (ApiException e) {
                throw new ApiException(400, "inverseSafe is true but the forward transform is numerically singular");
            }
        }
        Validate.nonNegative(forwardAccuracy, "forwardAccuracy");
        this.forwardAccuracy = forwardAccuracy;
        if (inverseSafe) {
            if (inverseAccuracy == null) {
                throw new ApiException(400, "inverseAccuracy is required when inverseSafe is true");
            }
            Validate.nonNegative(inverseAccuracy, "inverseAccuracy");
            if (inverseRegion == null) {
                throw new ApiException(400, "inverseRegion is required when inverseSafe is true");
            }
        }
        this.inverseAccuracy = inverseAccuracy;
        if (region == null) {
            throw new ApiException(400, "Valid forward region is required");
        }
        this.region = region;
        this.inverseRegion = inverseRegion;
        this.source = source == null ? "" : source;
        this.notes = notes == null ? "" : notes;
        this.introducedInVersion = introducedInVersion;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("fromCrs", fromCrs);
        map.put("toCrs", toCrs);
        map.put("forward", forward.toJson());
        map.put("inverseSafe", inverseSafe);
        map.put("forwardAccuracy", forwardAccuracy);
        map.put("inverseAccuracy", inverseAccuracy);
        map.put("region", region.toJson());
        map.put("inverseRegion", inverseRegion == null ? null : inverseRegion.toJson());
        map.put("source", source);
        map.put("notes", notes);
        map.put("introducedInVersion", introducedInVersion);
        return map;
    }

    public static Edge fromJson(Map<String, Object> map) {
        boolean inverseSafe = Json.optionalBoolean(map, "inverseSafe", false);
        Double inverseAccuracy = Json.optionalNumber(map, "inverseAccuracy");
        Object rawInverseRegion = map.get("inverseRegion");
        return new Edge(
                Json.string(map, "id"),
                Json.string(map, "fromCrs"),
                Json.string(map, "toCrs"),
                AffineTransform2D.fromJson(Json.objectField(map, "forward")),
                inverseSafe,
                Json.requiredNumber(map, "forwardAccuracy"),
                inverseAccuracy,
                Region.fromJson(Json.required(map, "region")),
                rawInverseRegion == null ? null : Region.fromJson(rawInverseRegion),
                Json.optionalString(map, "source", ""),
                Json.optionalString(map, "notes", ""),
                Json.optionalString(map, "introducedInVersion", "0"));
    }
}
