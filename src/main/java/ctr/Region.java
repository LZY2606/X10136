package ctr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Region(List<Box> boxes) {
    public static Region box(double minX, double minY, double maxX, double maxY, boolean crosses) {
        return new Region(List.of(new Box(minX, minY, maxX, maxY, crosses)));
    }

    public Region {
        boxes = List.copyOf(boxes);
        if (boxes.isEmpty()) {
            throw new ApiException(400, "Valid region must contain at least one box");
        }
    }

    public boolean contains(Coordinate point) {
        for (Box box : boxes) {
            if (box.contains(point)) {
                return true;
            }
        }
        return false;
    }

    public List<Coordinate> sample() {
        List<Coordinate> points = new ArrayList<>();
        for (Box box : boxes) {
            points.addAll(box.sample());
        }
        return points;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        List<Object> boxList = new ArrayList<>();
        for (Box box : boxes) {
            boxList.add(box.toJson());
        }
        map.put("boxes", boxList);
        return map;
    }

    public static Region fromJson(Object value) {
        Map<String, Object> map = Json.object(value, "region");
        List<Object> rawBoxes = Json.arrayField(map, "boxes");
        List<Box> parsed = new ArrayList<>();
        for (Object raw : rawBoxes) {
            parsed.add(Box.fromJson(raw));
        }
        return new Region(parsed);
    }

    public record Box(double minX, double minY, double maxX, double maxY, boolean crossesAntimeridian) {
        public Box {
            Validate.finite(minX, "minX");
            Validate.finite(minY, "minY");
            Validate.finite(maxX, "maxX");
            Validate.finite(maxY, "maxY");
            if (crossesAntimeridian) {
                if (minX <= maxX || minX < -180.0 || maxX > 180.0 || minY < -90.0 || maxY > 90.0) {
                    throw new ApiException(400, "Antimeridian-crossing box needs minX > maxX, longitude endpoints in [-180,180] and latitudes in [-90,90]");
                }
            } else if (minX > maxX || minY > maxY) {
                throw new ApiException(400, "Region box minima must not exceed maxima unless it crosses the antimeridian");
            }
        }

        public boolean contains(Coordinate point) {
            if (point.y() < minY || point.y() > maxY) {
                return false;
            }
            double x = point.x();
            if (crossesAntimeridian) {
                return x >= minX || x <= maxX;
            }
            return x >= minX && x <= maxX;
        }

        public List<Coordinate> sample() {
            List<Coordinate> points = new ArrayList<>();
            double midY = (minY + maxY) / 2.0;
            if (crossesAntimeridian) {
                double span = (180.0 - minX) + (maxX + 180.0);
                double midUnwrapped = minX + span / 2.0;
                double midX = midUnwrapped > 180.0 ? midUnwrapped - 360.0 : midUnwrapped;
                addSamples(points, minX, minY, maxX, maxY);
                addSamples(points, minX, midY, midX, midY);
                points.add(Coordinate.geo(180.0, minY));
                points.add(Coordinate.geo(-180.0, maxY));
            } else {
                addSamples(points, minX, minY, maxX, maxY);
            }
            return points;
        }

        private static void addSamples(List<Coordinate> points, double x1, double y1, double x2, double y2) {
            points.add(Coordinate.geo(x1, y1));
            points.add(Coordinate.geo(x2, y1));
            points.add(Coordinate.geo((x1 + x2) / 2.0, (y1 + y2) / 2.0));
            points.add(Coordinate.geo(x1, y2));
            points.add(Coordinate.geo(x2, y2));
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

        public static Box fromJson(Object value) {
            Map<String, Object> map = Json.object(value, "region box");
            return new Box(
                    Json.requiredNumber(map, "minX"),
                    Json.requiredNumber(map, "minY"),
                    Json.requiredNumber(map, "maxX"),
                    Json.requiredNumber(map, "maxY"),
                    Json.optionalBoolean(map, "crossesAntimeridian", false));
        }
    }
}
