package coordstation.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Crs {
    public final String id;
    public final String name;
    public final String description;

    public Crs(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description == null ? "" : description;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        return m;
    }

    public static Crs fromJson(Map<String, Object> m) {
        return new Crs(
                coordstation.json.Json.str(m, "id"),
                coordstation.json.Json.strOr(m, "name", coordstation.json.Json.str(m, "id")),
                coordstation.json.Json.strOr(m, "description", ""));
    }
}
