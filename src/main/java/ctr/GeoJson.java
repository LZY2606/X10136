package ctr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GeoJson {
    private GeoJson() {
    }

    public static Geometry parseGeometry(Object value) {
        Map<String, Object> map = Json.object(value, "GeoJSON geometry");
        String type = Json.string(map, "type");
        return switch (type) {
            case "Point" -> parsePoint(map);
            case "LineString" -> parseLine(map);
            case "Polygon" -> parsePolygon(map);
            default -> throw new ApiException(400, "Unsupported GeoJSON geometry type: " + type
                    + " (Point, LineString and Polygon are supported)");
        };
    }

    static PointGeometry parsePoint(Map<String, Object> map) {
        return new PointGeometry(Coordinate.fromJson(Json.required(map, "coordinates")));
    }

    static LineGeometry parseLine(Map<String, Object> map) {
        List<Object> raw = Json.arrayField(map, "coordinates");
        List<Coordinate> coordinates = new ArrayList<>();
        for (Object item : raw) {
            coordinates.add(Coordinate.fromJson(item));
        }
        return new LineGeometry(coordinates);
    }

    static PolygonGeometry parsePolygon(Map<String, Object> map) {
        List<Object> rawRings = Json.arrayField(map, "coordinates");
        List<List<Coordinate>> rings = new ArrayList<>();
        boolean crosses = false;
        for (Object rawRing : rawRings) {
            List<Object> rawCoordinates = Json.array(rawRing, "polygon ring");
            List<Coordinate> ring = new ArrayList<>();
            for (Object rawCoordinate : rawCoordinates) {
                ring.add(Coordinate.fromJson(rawCoordinate));
            }
            for (int i = 1; i < ring.size(); i++) {
                if (Bounds.isDatelineSegment(ring.get(i - 1).x(), ring.get(i).x())) {
                    crosses = true;
                }
            }
            if (!crosses && ring.size() > 1 && Bounds.isDatelineSegment(ring.get(ring.size() - 1).x(), ring.get(0).x())) {
                crosses = true;
            }
            rings.add(ring);
        }
        return new PolygonGeometry(rings, crosses);
    }
}
