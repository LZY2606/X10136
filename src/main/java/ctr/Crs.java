package ctr;

import java.util.LinkedHashMap;
import java.util.Map;

public record Crs(String id, String name, String axes, String unit, String description) {
    public Crs {
        Validate.id(id, "CRS id");
        if (name == null || name.isBlank()) {
            throw new ApiException(400, "CRS name is required");
        }
        if (!"geographic".equals(axes) && !"projected".equals(axes)) {
            throw new ApiException(400, "CRS axes must be geographic or projected");
        }
        if (unit == null || unit.isBlank()) {
            throw new ApiException(400, "CRS unit is required");
        }
        if (description == null) {
            description = "";
        }
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("axes", axes);
        map.put("unit", unit);
        map.put("description", description);
        return map;
    }

    public static Crs fromJson(Map<String, Object> map) {
        return new Crs(
                Json.string(map, "id"),
                Json.string(map, "name"),
                Json.string(map, "axes"),
                Json.string(map, "unit"),
                Json.optionalString(map, "description", ""));
    }
}
