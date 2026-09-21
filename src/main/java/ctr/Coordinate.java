package ctr;

import java.util.List;
import java.util.Map;

public record Coordinate(double x, double y, Double z) {
    public Coordinate {
        if (!Double.isFinite(x) || !Double.isFinite(y) || (z != null && !Double.isFinite(z))) {
            throw new ApiException(400, "Coordinates must contain finite numbers");
        }
    }

    public static Coordinate geo(double lon, double lat) {
        return new Coordinate(lon, lat, null);
    }

    public List<Object> toJson() {
        List<Object> list = new java.util.ArrayList<>();
        list.add(x);
        list.add(y);
        if (z != null) {
            list.add(z);
        }
        return list;
    }

    public static Coordinate fromJson(Object value) {
        List<Object> list = Json.array(value, "coordinate");
        if (list.size() < 2 || list.size() > 3) {
            throw new ApiException(400, "Each coordinate must contain x,y or x,y,z");
        }
        double x = Json.number(list.get(0), "coordinate x");
        double y = Json.number(list.get(1), "coordinate y");
        Double z = list.size() == 3 ? Json.number(list.get(2), "coordinate z") : null;
        return new Coordinate(x, y, z);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("x", x);
        map.put("y", y);
        if (z != null) {
            map.put("z", z);
        }
        return map;
    }
}
