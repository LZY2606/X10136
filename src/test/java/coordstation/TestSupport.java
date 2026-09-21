package coordstation;

import coordstation.json.Json;
import coordstation.registry.Registry;

import java.util.Map;

final class TestSupport {
    private TestSupport() {}

    static Map<String, Object> j(String s) {
        return Json.parseObject(s);
    }

    static final String WORLD =
            "{\"minLon\":-180,\"minLat\":-90,\"maxLon\":180,\"maxLat\":90}";

    static String box(double minLon, double minLat, double maxLon, double maxLat) {
        return "{\"minLon\":" + minLon + ",\"minLat\":" + minLat
                + ",\"maxLon\":" + maxLon + ",\"maxLat\":" + maxLat + "}";
    }

    static String edge(String id, String src, String dst, String region,
                       double accuracy, String affine, boolean invertible) {
        return "{\"id\":\"" + id + "\",\"src\":\"" + src + "\",\"dst\":\"" + dst + "\""
                + ",\"region\":" + region
                + ",\"accuracy\":" + accuracy
                + ",\"affine\":" + affine
                + ",\"invertible\":" + invertible + "}";
    }

    static Registry withCrs(String... ids) {
        Registry r = new Registry();
        for (String id : ids) {
            r.registerCrs(j("{\"id\":\"" + id + "\",\"name\":\"" + id + "\"}"));
        }
        return r;
    }

    static String point(double lon, double lat) {
        return "{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}";
    }
}
