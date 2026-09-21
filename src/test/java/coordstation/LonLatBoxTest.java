package coordstation;

import coordstation.model.LonLatBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LonLatBoxTest {

    @Test
    void crossingBoxContainsBothSidesOfAntimeridian() {
        LonLatBox box = new LonLatBox(170, -10, -170, 10);
        assertTrue(box.crossesAntimeridian());
        assertTrue(box.contains(179, 0));
        assertTrue(box.contains(-179, 0));
        assertTrue(box.contains(180, 0));
        assertFalse(box.contains(0, 0), "naive min/max would wrongly include lon 0");
        assertFalse(box.contains(170, 20), "latitude outside");
    }

    @Test
    void enclosingDetectsAntimeridianCrossing() {
        LonLatBox region = LonLatBox.enclosing(List.of(
                new double[]{175, 10}, new double[]{-175, 12}));
        assertTrue(region.crossesAntimeridian());
        assertEquals(175, region.minLon);
        assertEquals(-175, region.maxLon);
        // A naive min/max box would span [-175, 175] (nearly the whole world).
        LonLatBox edgeRegion = new LonLatBox(160, 0, -160, 30);
        assertTrue(edgeRegion.covers(region));
    }

    @Test
    void enclosingStaysPlainWhenSpanIsSmall() {
        LonLatBox region = LonLatBox.enclosing(List.of(
                new double[]{10, 20}, new double[]{30, 40}));
        assertFalse(region.crossesAntimeridian());
        assertEquals(10, region.minLon);
        assertEquals(30, region.maxLon);
    }

    @Test
    void crossingCoverageRequiresBothSidesCovered() {
        LonLatBox outer = new LonLatBox(170, -10, -170, 10);
        LonLatBox inner = new LonLatBox(175, -5, -175, 5);
        assertTrue(outer.covers(inner));
        LonLatBox notCovering = new LonLatBox(170, -10, 179, 10);
        assertFalse(notCovering.covers(inner));
    }

    @Test
    void intersectionAcrossAntimeridian() {
        LonLatBox a = new LonLatBox(170, 0, -170, 10);
        LonLatBox b = new LonLatBox(175, 2, -175, 8);
        LonLatBox i = a.intersection(b);
        assertNotNull(i);
        assertTrue(i.crossesAntimeridian());
        assertTrue(i.contains(178, 5));
        assertTrue(i.contains(-178, 5));
        assertFalse(i.contains(172, 5));
    }
}
