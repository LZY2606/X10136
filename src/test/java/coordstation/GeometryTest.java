package coordstation;

import coordstation.json.Json;
import coordstation.model.Geometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryTest {

    private static Geometry parse(String geojson) {
        return Geometry.fromGeoJson(Json.parseObject(geojson));
    }

    @Test
    void latitudeOutOfRangeRejected() {
        Geometry.ValidationException e = assertThrows(Geometry.ValidationException.class,
                () -> parse("{\"type\":\"Point\",\"coordinates\":[10, 91]}"));
        assertTrue(e.getMessage().contains("latitude"));
    }

    @Test
    void longitudeOutOfRangeRejected() {
        assertThrows(Geometry.ValidationException.class,
                () -> parse("{\"type\":\"Point\",\"coordinates\":[-181, 10]}"));
    }

    @Test
    void nonFiniteCoordinateRejected() {
        // 1e999 overflows to Infinity when parsed as a double.
        Geometry.ValidationException e = assertThrows(Geometry.ValidationException.class,
                () -> parse("{\"type\":\"Point\",\"coordinates\":[1e999, 10]}"));
        assertTrue(e.getMessage().contains("finite"));
    }

    @Test
    void unclosedPolygonRejected() {
        String g = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1]]]}";
        Geometry.ValidationException e = assertThrows(Geometry.ValidationException.class,
                () -> parse(g));
        assertTrue(e.getMessage().contains("closed"));
    }

    @Test
    void closedPolygonAccepted() {
        Geometry g = parse("{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}");
        assertEquals(Geometry.Type.POLYGON, g.type);
    }

    @Test
    void shortLineStringRejected() {
        assertThrows(Geometry.ValidationException.class,
                () -> parse("{\"type\":\"LineString\",\"coordinates\":[[0,0]]}"));
    }
}
