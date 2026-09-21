package ctr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record GeoDocument(String kind, Geometry geometry, List<GeoDocument> features, Map<String, Object> properties, Map<String, Object> extra) {
    public static GeoDocument parse(Object value) {
        Map<String, Object> map = Json.object(value, "GeoJSON document");
        String type = Json.string(map, "type");
        return switch (type) {
            case "Feature" -> parseFeature(map);
            case "FeatureCollection" -> parseCollection(map);
            default -> parseSingleGeometry(type, map);
        };
    }

    private static GeoDocument parseSingleGeometry(String type, Map<String, Object> map) {
        if (!"Point".equals(type) && !"LineString".equals(type) && !"Polygon".equals(type)) {
            throw new ApiException(400, "Unsupported GeoJSON type: " + type);
        }
        Geometry geometry = GeoJson.parseGeometry(map);
        return new GeoDocument("geometry", geometry, List.of(), Map.of(), new LinkedHashMap<>(map));
    }

    private static GeoDocument parseFeature(Map<String, Object> map) {
        Object rawGeometry = map.get("geometry");
        if (rawGeometry == null) {
            throw new ApiException(400, "Feature geometry is required");
        }
        Geometry geometry = GeoJson.parseGeometry(rawGeometry);
        Object rawProperties = map.get("properties");
        Map<String, Object> properties = rawProperties == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(Json.object(rawProperties, "properties"));
        return new GeoDocument("feature", geometry, List.of(), properties, new LinkedHashMap<>(map));
    }

    private static GeoDocument parseCollection(Map<String, Object> map) {
        List<Object> rawFeatures = Json.arrayField(map, "features");
        List<GeoDocument> features = new ArrayList<>();
        for (Object rawFeature : rawFeatures) {
            features.add(parse(rawFeature));
        }
        return new GeoDocument("collection", null, List.copyOf(features), Map.of(), new LinkedHashMap<>(map));
    }

    public List<Geometry> allGeometries() {
        List<Geometry> geometries = new ArrayList<>();
        collect(geometries);
        return geometries;
    }

    private void collect(List<Geometry> geometries) {
        if (geometry != null) {
            geometries.add(geometry);
        }
        for (GeoDocument feature : features) {
            feature.collect(geometries);
        }
    }

    public Object toJson() {
        return switch (kind) {
            case "geometry" -> geometry.toJson();
            case "feature" -> featureJson();
            case "collection" -> collectionJson();
            default -> throw new IllegalStateException(kind);
        };
    }

    private Map<String, Object> featureJson() {
        Map<String, Object> map = new LinkedHashMap<>(extra);
        map.put("type", "Feature");
        map.put("geometry", geometry.toJson());
        map.put("properties", properties);
        return map;
    }

    private Map<String, Object> collectionJson() {
        Map<String, Object> map = new LinkedHashMap<>(extra);
        map.put("type", "FeatureCollection");
        List<Object> outputFeatures = new ArrayList<>();
        for (GeoDocument feature : features) {
            outputFeatures.add(feature.toJson());
        }
        map.put("features", outputFeatures);
        return map;
    }
}
