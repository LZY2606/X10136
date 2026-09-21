package crstation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A registered coordinate reference system. {@code geographic} axes are
 * (longitude, latitude) in degrees; other CRSs are treated as planar
 * (x, y) coordinate spaces.
 */
public record Crs(String code, String name, boolean geographic, String axes,
                  String description) {

    public Crs {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("CRS code is required");
        }
        code = code.trim();
        if (name == null || name.isBlank()) {
            name = code;
        }
        if (axes == null || axes.isBlank()) {
            axes = geographic ? "lon,lat (degrees)" : "x,y (metres-equivalent)";
        }
        if (description == null) {
            description = "";
        }
    }

    public static Crs fromMap(Map<String, Object> m) {
        return new Crs(
                Json.str(m, "code"),
                Json.optStr(m, "name", null),
                Json.optBool(m, "geographic", false),
                Json.optStr(m, "axes", null),
                Json.optStr(m, "description", ""));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("geographic", geographic);
        m.put("axes", axes);
        m.put("description", description);
        return m;
    }
}
