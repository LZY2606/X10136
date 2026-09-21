package station.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import station.json.Json;

/**
 * GeoJSON 风格几何：Point / LineString / Polygon。
 * 计算前校验：非有限数字、纬度越界、多边形不闭合一律拒绝。
 */
public final class Geometry {
    public enum Type { Point, LineString, Polygon }

    public final Type type;
    // Point: 单环单点；LineString: 单环；Polygon: 多环
    public final List<List<double[]>> rings;

    private Geometry(Type type, List<List<double[]>> rings) {
        this.type = type;
        this.rings = rings;
    }

    public static final class ValidationException extends RuntimeException {
        public ValidationException(String msg) { super(msg); }
    }

    public static Geometry fromGeoJson(Object obj) {
        Map<String, Object> m = Json.asObject(obj, "几何对象");
        String type = Json.asString(m.get("type"), "几何 type");
        Object coords = m.get("coordinates");
        switch (type) {
            case "Point": {
                double[] p = point(coords, "Point.coordinates");
                List<List<double[]>> rings = new ArrayList<>();
                List<double[]> ring = new ArrayList<>();
                ring.add(p);
                rings.add(ring);
                return new Geometry(Type.Point, rings);
            }
            case "LineString": {
                List<double[]> line = new ArrayList<>();
                for (Object o : Json.asArray(coords, "LineString.coordinates")) {
                    line.add(point(o, "LineString 坐标"));
                }
                if (line.size() < 2) throw new ValidationException("LineString 至少需要 2 个点");
                List<List<double[]>> rings = new ArrayList<>();
                rings.add(line);
                return new Geometry(Type.LineString, rings);
            }
            case "Polygon": {
                List<List<double[]>> rings = new ArrayList<>();
                int ringIndex = 0;
                for (Object ringObj : Json.asArray(coords, "Polygon.coordinates")) {
                    List<double[]> ring = new ArrayList<>();
                    for (Object o : Json.asArray(ringObj, "Polygon 环")) {
                        ring.add(point(o, "Polygon 坐标"));
                    }
                    if (ring.size() < 4) {
                        throw new ValidationException("多边形第 " + ringIndex + " 环至少需要 4 个坐标点");
                    }
                    double[] first = ring.get(0);
                    double[] last = ring.get(ring.size() - 1);
                    if (first[0] != last[0] || first[1] != last[1]) {
                        throw new ValidationException("多边形第 " + ringIndex + " 环不闭合：首尾坐标必须完全一致");
                    }
                    rings.add(ring);
                    ringIndex++;
                }
                if (rings.isEmpty()) throw new ValidationException("多边形至少需要一个环");
                return new Geometry(Type.Polygon, rings);
            }
            default:
                throw new ValidationException("不支持的几何类型: " + type);
        }
    }

    private static double[] point(Object o, String what) {
        List<Object> arr = Json.asArray(o, what);
        if (arr.size() < 2) throw new ValidationException(what + " 需要 [lon, lat]");
        double lon = Json.asDouble(arr.get(0), what + "[0]");
        double lat = Json.asDouble(arr.get(1), what + "[1]");
        validate(lon, lat, what);
        return new double[]{lon, lat};
    }

    private static void validate(double lon, double lat, String what) {
        if (!Double.isFinite(lon) || !Double.isFinite(lat)) {
            throw new ValidationException(what + " 含非有限数字 (lon=" + lon + ", lat=" + lat + ")");
        }
        if (lat < -90.0 || lat > 90.0) {
            throw new ValidationException(what + " 纬度越界: " + lat + "（合法范围 [-90, 90]）");
        }
        if (lon < -360.0 || lon > 360.0) {
            throw new ValidationException(what + " 经度越界: " + lon + "（合法范围 [-360, 360]）");
        }
    }

    /** 对象的最小经度覆盖弧（正确处理跨反经线）。 */
    public LonArc lonArc() {
        List<Double> lons = new ArrayList<>();
        for (List<double[]> ring : rings) for (double[] p : ring) lons.add(p[0]);
        return LonArc.minimalCovering(lons);
    }

    public double latMin() {
        double v = Double.POSITIVE_INFINITY;
        for (List<double[]> ring : rings) for (double[] p : ring) v = Math.min(v, p[1]);
        return v;
    }

    public double latMax() {
        double v = Double.NEGATIVE_INFINITY;
        for (List<double[]> ring : rings) for (double[] p : ring) v = Math.max(v, p[1]);
        return v;
    }

    public Object toGeoJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type.name());
        switch (type) {
            case Point:
                m.put("coordinates", coord(rings.get(0).get(0)));
                break;
            case LineString: {
                List<Object> line = new ArrayList<>();
                for (double[] p : rings.get(0)) line.add(coord(p));
                m.put("coordinates", line);
                break;
            }
            case Polygon: {
                List<Object> ringsOut = new ArrayList<>();
                for (List<double[]> ring : rings) {
                    List<Object> r = new ArrayList<>();
                    for (double[] p : ring) r.add(coord(p));
                    ringsOut.add(r);
                }
                m.put("coordinates", ringsOut);
                break;
            }
        }
        return m;
    }

    private static List<Object> coord(double[] p) {
        List<Object> c = new ArrayList<>();
        c.add(p[0]);
        c.add(p[1]);
        return c;
    }
}
