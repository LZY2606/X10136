package ctstation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A registered coordinate reference system.
 *
 * <p>{@code kind = "geographic"} means longitude/latitude in degrees:
 * latitude is range checked to [-90, 90]. {@code kind = "projected"} means
 * generic easting/northing values with no latitude constraint.
 */
public final class Crs {
    public final String id;
    public final String name;
    public final String kind; // "geographic" | "projected"
    public final String units;

    public Crs(String id, String name, String kind, String units) {
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new AppException("bad-crs", "CRS id must match [A-Za-z0-9_.:-]{1,64}");
        }
        if (!"geographic".equals(kind) && !"projected".equals(kind)) {
            throw new AppException("bad-crs", "CRS kind must be 'geographic' or 'projected'");
        }
        this.id = id;
        this.name = name == null ? id : name;
        this.kind = kind;
        this.units = units == null ? ("geographic".equals(kind) ? "degrees" : "metres") : units;
    }

    public boolean geographic() { return "geographic".equals(kind); }

    public void validatePoint(Point2 p) {
        if (geographic() && (p.y < -90.0d || p.y > 90.0d)) {
            throw new AppException("latitude-out-of-range",
                    "latitude " + Json.num(p.y) + " out of [-90, 90] in CRS " + id);
        }
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("kind", kind);
        m.put("units", units);
        return m;
    }

    public static Crs fromMap(Map<String, Object> m) {
        return new Crs(Json.str(m, "id"),
                Json.optStr(m, "name", null),
                Json.optStr(m, "kind", "projected"),
                Json.optStr(m, "units", null));
    }
}
