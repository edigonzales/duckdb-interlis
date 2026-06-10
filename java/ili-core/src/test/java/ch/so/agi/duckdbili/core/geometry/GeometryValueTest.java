package ch.so.agi.duckdbili.core.geometry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeometryValueTest {

    @Test
    void wkbReturnsDefensiveCopy() {
        byte[] original = {0x01, 0x02, 0x03};
        GeometryMetadata meta = new GeometryMetadata(
                "Model", "Topic", "Class", "geom", "Model.Topic.Class.geom",
                GeometryKind.POINT, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);
        GeometryValue gv = new GeometryValue(meta, original, GeometryDimension.XY, false);

        byte[] got = gv.wkb();
        assertArrayEquals(original, got);
        got[0] = (byte) 0xFF;
        assertEquals(0x01, gv.wkb()[0]); // original unchanged
    }

    @Test
    void hexWkbRoundtrip() {
        byte[] wkb = {0x01, (byte) 0xFF, 0x42};
        GeometryMetadata meta = new GeometryMetadata(
                "Model", "Topic", "Class", "geom", "Model.Topic.Class.geom",
                GeometryKind.POINT, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);
        GeometryValue gv = new GeometryValue(meta, wkb, GeometryDimension.XY, false);
        assertEquals("01FF42", gv.hexWkb());
    }

    @Test
    void nullWkbMeansNullWktAndHexWkb() {
        GeometryMetadata meta = new GeometryMetadata(
                "Model", "Topic", "Class", "geom", "Model.Topic.Class.geom",
                GeometryKind.POINT, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);
        GeometryValue gv = new GeometryValue(meta, null, GeometryDimension.XY, false);
        assertNull(gv.wkb());
        assertNull(gv.hexWkb());
        assertNull(gv.wkt());
        assertTrue(gv.isNull());
    }

    @Test
    void wktFromValidWkb() throws Exception {
        // Build a valid WKB for POINT(1 2) using JTS
        com.vividsolutions.jts.geom.GeometryFactory gf =
                new com.vividsolutions.jts.geom.GeometryFactory();
        com.vividsolutions.jts.geom.Point pt =
                gf.createPoint(new com.vividsolutions.jts.geom.Coordinate(1, 2));
        byte[] wkb = new com.vividsolutions.jts.io.WKBWriter().write(pt);

        GeometryMetadata meta = new GeometryMetadata(
                "Model", "Topic", "Class", "geom", "Model.Topic.Class.geom",
                GeometryKind.POINT, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);
        GeometryValue gv = new GeometryValue(meta, wkb, GeometryDimension.XY, false);
        assertEquals("POINT (1 2)", gv.wkt());
    }

    @Test
    void emptyIsDistinctFromNull() {
        GeometryMetadata meta = new GeometryMetadata(
                "Model", "Topic", "Class", "geom", "Model.Topic.Class.geom",
                GeometryKind.POINT, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);
        GeometryValue gv = new GeometryValue(meta, null, GeometryDimension.XY, true);
        assertTrue(gv.isEmpty());
        assertTrue(gv.isNull());
    }

    @Test
    void metadataCannotBeNull() {
        assertThrows(NullPointerException.class,
                () -> new GeometryValue(null, new byte[]{0x01}, GeometryDimension.XY, false));
    }
}
