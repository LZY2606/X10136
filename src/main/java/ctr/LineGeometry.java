package ctr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record LineGeometry(List<Coordinate> coordinates) implements Geometry {
    public LineGeometry {
        coordinates = List.copyOf(coordinates);
        if (coordinates.size() < 2) {
            throw new ApiException(400, "LineString requires at least two coordinates");
        }
    }

    @Override
    public String type() {
        return "LineString";
    }

    @Override
    public List<List<Coordinate>> rings() {
        return List.of(coordinates);
    }

    @Override
    public Bounds bounds() {
        return Bounds.ofCoordinates(coordinates);
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "LineString");
        List<Object> list = new java.util.ArrayList<>();
        for (Coordinate coordinate : coordinates) {
            list.add(coordinate.toJson());
        }
        map.put("coordinates", list);
        return map;
    }
}
