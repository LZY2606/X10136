package ctr;

import java.util.List;
import java.util.Map;

public sealed interface Geometry permits PointGeometry, LineGeometry, PolygonGeometry {
    String type();
    List<List<Coordinate>> rings();
    Map<String, Object> toJson();
    Bounds bounds();
}
