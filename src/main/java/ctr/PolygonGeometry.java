package ctr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PolygonGeometry(List<List<Coordinate> > ringsCoordinates, boolean crossesAntimeridian) implements Geometry {
    public PolygonGeometry {
        ringsCoordinates = ringsCoordinates.stream().map(List::copyOf).toList();
        if (ringsCoordinates.isEmpty()) {
            throw new ApiException(400, "Polygon requires an exterior ring");
        }
        for (List<Coordinate> ring : ringsCoordinates) {
            if (ring.size() < 4) {
                throw new ApiException(400, "Polygon rings must contain at least four coordinates");
            }
            Coordinate first = ring.get(0);
            Coordinate last = ring.get(ring.size() - 1);
            if (!exactSame(first, last)) {
                throw new ApiException(400, "Polygon ring is not closed: first and last coordinates must be identical");
            }
        }
    }

    private static boolean exactSame(Coordinate a, Coordinate b) {
        if (Double.doubleToRawLongBits(a.x()) != Double.doubleToRawLongBits(b.x())
                || Double.doubleToRawLongBits(a.y()) != Double.doubleToRawLongBits(b.y())) {
            return false;
        }
        if (a.z() == null && b.z() == null) {
            return true;
        }
        if (a.z() == null || b.z() == null) {
            return false;
        }
        return Double.doubleToRawLongBits(a.z()) == Double.doubleToRawLongBits(b.z());
    }

    @Override
    public String type() {
        return "Polygon";
    }

    @Override
    public List<List<Coordinate>> rings() {
        List<List<Coordinate>> groups = new java.util.ArrayList<>();
        for (List<Coordinate> ring : ringsCoordinates) groups.add(ring);
        return groups;
    }

    @Override
    public Bounds bounds() {
        List<Coordinate> all = new ArrayList<>();
        for (List<Coordinate> ring : ringsCoordinates) {
            all.addAll(ring);
        }
        return Bounds.ofCoordinates(all);
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "Polygon");
        List<Object> rings = new ArrayList<>();
        for (List<Coordinate> ring : ringsCoordinates) {
            List<Object> converted = new ArrayList<>();
            for (Coordinate coordinate : ring) {
                converted.add(coordinate.toJson());
            }
            rings.add(converted);
        }
        map.put("coordinates", rings);
        return map;
    }
}
