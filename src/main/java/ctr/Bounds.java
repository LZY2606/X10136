package ctr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Bounds(double minX, double minY, double maxX, double maxY, boolean crossesAntimeridian) {
    public static Bounds ofCoordinates(List<Coordinate> coordinates) {
        if (coordinates.isEmpty()) {
            throw new ApiException(400, "Cannot calculate bounds of an empty coordinate list");
        }
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Coordinate coordinate : coordinates) {
            minY = Math.min(minY, coordinate.y());
            maxY = Math.max(maxY, coordinate.y());
        }
        boolean crosses = false;
        for (int i = 1; i < coordinates.size(); i++) {
            if (isDatelineSegment(coordinates.get(i - 1).x(), coordinates.get(i).x())) {
                crosses = true;
                break;
            }
        }
        if (crosses) {
            double east = -180.0;
            double west = 180.0;
            for (Coordinate coordinate : coordinates) {
                east = Math.max(east, coordinate.x());
                west = Math.min(west, coordinate.x());
            }
            return new Bounds(east, minY, west, maxY, true);
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        for (Coordinate coordinate : coordinates) {
            minX = Math.min(minX, coordinate.x());
            maxX = Math.max(maxX, coordinate.x());
        }
        return new Bounds(minX, minY, maxX, maxY, false);
    }

    public static boolean isDatelineSegment(double x1, double x2) {
        return Math.abs(x1 - x2) > 180.0 && x1 >= -180.0 && x1 <= 180.0 && x2 >= -180.0 && x2 <= 180.0;
    }

    public boolean contains(Coordinate coordinate) {
        if (coordinate.y() < minY || coordinate.y() > maxY) {
            return false;
        }
        if (crossesAntimeridian) {
            return coordinate.x() >= minX || coordinate.x() <= maxX;
        }
        return coordinate.x() >= minX && coordinate.x() <= maxX;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("minX", minX);
        map.put("minY", minY);
        map.put("maxX", maxX);
        map.put("maxY", maxY);
        map.put("crossesAntimeridian", crossesAntimeridian);
        return map;
    }
}
