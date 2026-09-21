package ctr;

import ctr.Geometry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PointGeometry(Coordinate coordinate) implements Geometry {
    @Override
    public String type() {
        return "Point";
    }

    @Override
    public List<List<Coordinate>> rings() {
        return List.of(java.util.List.of(coordinate));
    }

    @Override
    public Bounds bounds() {
        return Bounds.ofCoordinates(List.of(coordinate));
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "Point");
        map.put("coordinates", coordinate.toJson());
        return map;
    }
}
