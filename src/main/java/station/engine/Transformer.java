package station.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import station.model.Geometry;

/** 沿已选链路对几何对象逐顶点施加仿射变换。 */
public final class Transformer {

    public static Geometry apply(Geometry input, PathFinder.Path path) {
        List<List<double[]>> rings = new ArrayList<>();
        for (List<double[]> ring : input.rings) {
            List<double[]> out = new ArrayList<>();
            for (double[] p : ring) {
                double x = p[0];
                double y = p[1];
                for (PathFinder.Arc arc : path.arcs) {
                    double[] r = arc.affine.apply(x, y);
                    x = r[0];
                    y = r[1];
                }
                out.add(new double[]{x, y});
            }
            rings.add(out);
        }
        return rebuild(input, rings);
    }

    private static Geometry rebuild(Geometry template, List<List<double[]>> rings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", template.type.name());
        switch (template.type) {
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
                List<Object> rs = new ArrayList<>();
                for (List<double[]> ring : rings) {
                    List<Object> r = new ArrayList<>();
                    for (double[] p : ring) r.add(coord(p));
                    rs.add(r);
                }
                m.put("coordinates", rs);
                break;
            }
        }
        return Geometry.fromGeoJson(m);
    }

    private static List<Object> coord(double[] p) {
        List<Object> c = new ArrayList<>();
        c.add(p[0]);
        c.add(p[1]);
        return c;
    }
}
