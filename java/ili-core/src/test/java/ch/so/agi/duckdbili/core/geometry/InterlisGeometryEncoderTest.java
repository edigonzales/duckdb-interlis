package ch.so.agi.duckdbili.core.geometry;

import ch.interlis.iom.IomObject;
import ch.interlis.iox.*;
import ch.interlis.iox_j.utility.ReaderFactory;
import ch.interlis.iom_j.xtf.Xtf24Reader;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.iox_j.IoxIliReader;
import ch.interlis.ilirepository.IliManager;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKBReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InterlisGeometryEncoder using real XTF test fixtures.
 */
class InterlisGeometryEncoderTest {

    private static final Path TESTDIR;
    static {
        Path cwd = Path.of("").toAbsolutePath();
        Path dir = cwd.resolve("testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili");
        if (!Files.isRegularFile(dir)) {
            dir = cwd.getParent().resolve("testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili");
        }
        if (!Files.isRegularFile(dir)) {
            dir = cwd.getParent().getParent().resolve("testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili");
        }
        if (!Files.isRegularFile(dir)) {
            throw new RuntimeException("Cannot locate geometry test data directory");
        }
        TESTDIR = dir.getParent();
    }

    private final InterlisGeometryTypeResolver typeResolver = new InterlisGeometryTypeResolver();
    private final InterlisGeometryExtractor extractor = new InterlisGeometryExtractor();
    private final GeometryConversionOptions options = GeometryConversionOptions.defaults();
    private final InterlisGeometryEncoder encoder = new InterlisGeometryEncoder(typeResolver, extractor, options);

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<IomObject> readXtfObjects(String xtfFileName) throws Exception {
        IliManager manager = new IliManager();
        manager.setRepositories(new String[]{TESTDIR.toString(), "https://models.interlis.ch"});
        ArrayList<String> entries = new ArrayList<>();
        entries.add(TESTDIR.resolve("SO_AGI_Geometries_20260605.ili").toAbsolutePath().toString());
        Configuration cfg = manager.getConfigWithFiles(entries, null, 0.0);
        Ili2cSettings settings = new Ili2cSettings();
        Main.setDefaultIli2cPathMap(settings);
        settings.setIlidirs(TESTDIR.toString());
        TransferDescription td = Main.runCompiler(cfg, settings, null);

        IoxReader reader = new ReaderFactory().createReader(
                TESTDIR.resolve(xtfFileName).toFile(), null);
        if (reader == null) reader = new Xtf24Reader(TESTDIR.resolve(xtfFileName).toFile());
        if (reader instanceof IoxIliReader) ((IoxIliReader) reader).setModel(td);

        List<IomObject> objects = new ArrayList<>();
        boolean sawEndBasket = false;
        try {
            IoxEvent event;
            while ((event = reader.read()) != null) {
                if (event instanceof EndBasketEvent) {
                    sawEndBasket = true;
                } else if (event instanceof ObjectEvent oe) {
                    objects.add(oe.getIomObject());
                }
            }
        } catch (ch.interlis.iox_j.IoxSyntaxException e) {
            if (!sawEndBasket || (e.getMessage() != null && !e.getMessage().isBlank())) {
                throw e;
            }
            // Ignore trailing EOF exception after EndBasketEvent
        } finally {
            reader.close();
        }
        return objects;
    }

    private IomObject findObject(List<IomObject> objects, String className) {
        for (IomObject obj : objects) {
            String tag = obj.getobjecttag();
            if (tag != null && (tag.endsWith("." + className) || tag.equals(className))) {
                return obj;
            }
        }
        throw new AssertionError("Object not found: " + className);
    }

    // ------------------------------------------------------------------
    // Positive cases
    // ------------------------------------------------------------------

    @Test
    void encodePoint2d() throws Exception {
        List<IomObject> objs = readXtfObjects("valid.xtf");
        IomObject pt = findObject(objs, "PunktObjekt");
        assertNotNull(pt);

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Lage", "M.T.C.Lage",
                GeometryKind.POINT, GeometryDimension.XY,
                "Koord", "M.Koord", null, null, null,
                true, 1, 1, false, false, false);

        Optional<GeometryValue> opt = encoder.encodeAttribute(pt, attrDefOf(pt, "Lage"), meta);
        assertTrue(opt.isPresent());

        WKBReader reader = new WKBReader();
        Geometry geom = reader.read(opt.get().wkb());
        assertEquals("Point", geom.getGeometryType());
        assertEquals(2605000.0, geom.getCoordinate().x, 0.000001);
        assertEquals(1203000.0, geom.getCoordinate().y, 0.000001);
    }

    @Test
    void encodeLineString() throws Exception {
        List<IomObject> objs = readXtfObjects("valid.xtf");
        IomObject line = findObject(objs, "LinienObjekt");

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Verlauf", "M.T.C.Verlauf",
                GeometryKind.LINESTRING, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        Optional<GeometryValue> opt = encoder.encodeAttribute(line, attrDefOf(line, "Verlauf"), meta);
        assertTrue(opt.isPresent());

        WKBReader reader = new WKBReader();
        Geometry geom = reader.read(opt.get().wkb());
        assertEquals("LineString", geom.getGeometryType());
        assertEquals(3, geom.getNumPoints());
    }

    @Test
    void encodePolygon() throws Exception {
        List<IomObject> objs = readXtfObjects("valid.xtf");
        IomObject poly = findObject(objs, "FlaechenObjekt");

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Flaeche", "M.T.C.Flaeche",
                GeometryKind.POLYGON, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        Optional<GeometryValue> opt = encoder.encodeAttribute(poly, attrDefOf(poly, "Flaeche"), meta);
        assertTrue(opt.isPresent());

        WKBReader reader = new WKBReader();
        Geometry geom = reader.read(opt.get().wkb());
        assertEquals("MultiPolygon", geom.getGeometryType());
        assertEquals(1, geom.getNumGeometries());
    }

    @Test
    void encodePolygonWithHole() throws Exception {
        List<IomObject> objs = readXtfObjects("valid-holes.xtf");
        IomObject poly = findObject(objs, "FlaechenObjektLoch");

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Flaeche", "M.T.C.Flaeche",
                GeometryKind.POLYGON, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        Optional<GeometryValue> opt = encoder.encodeAttribute(poly, attrDefOf(poly, "Flaeche"), meta);
        assertTrue(opt.isPresent());

        WKBReader reader = new WKBReader();
        Geometry geom = reader.read(opt.get().wkb());
        com.vividsolutions.jts.geom.Polygon firstPoly =
                (com.vividsolutions.jts.geom.Polygon) ((com.vividsolutions.jts.geom.MultiPolygon) geom).getGeometryN(0);
        assertEquals(1, firstPoly.getNumInteriorRing(), "Should have one interior ring");
    }

    @Test
    void encodeMultiPolygon() throws Exception {
        List<IomObject> objs = readXtfObjects("valid.xtf");
        IomObject mp = findObject(objs, "MultiFlaechenObjekt");

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Flaechen", "M.T.C.Flaechen",
                GeometryKind.MULTIPOLYGON, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        Optional<GeometryValue> opt = encoder.encodeAttribute(mp, attrDefOf(mp, "Flaechen"), meta);
        assertTrue(opt.isPresent());

        WKBReader reader = new WKBReader();
        Geometry geom = reader.read(opt.get().wkb());
        assertEquals("MultiPolygon", geom.getGeometryType());
        assertEquals(2, geom.getNumGeometries());
    }

    @Test
    void encodeArea() throws Exception {
        List<IomObject> objs = readXtfObjects("valid.xtf");
        IomObject area = findObject(objs, "AreaObjekt");

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Flaeche", "M.T.C.Flaeche",
                GeometryKind.AREA, GeometryDimension.XY,
                "Koord", "M.Koord", null, null, null,
                true, 1, 1, false, true, false);

        Optional<GeometryValue> opt = encoder.encodeAttribute(area, attrDefOf(area, "Flaeche"), meta);
        assertTrue(opt.isPresent(), "AREA should be convertible via multisurface2hexwkb");

        WKBReader reader = new WKBReader();
        Geometry geom = reader.read(opt.get().wkb());
        assertEquals("MultiPolygon", geom.getGeometryType());
        assertEquals(1, geom.getNumGeometries());
        assertEquals(GeometryKind.AREA, opt.get().metadata().geometryKind());
        assertTrue(opt.get().metadata().isAreaType());
    }

    @Test
    void encodeMultiArea() throws Exception {
        List<IomObject> objs = readXtfObjects("valid.xtf");
        IomObject ma = findObject(objs, "MultiAreaObjekt");

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Flaechen", "M.T.C.Flaechen",
                GeometryKind.MULTIAREA, GeometryDimension.XY,
                "Koord", "M.Koord", null, null, null,
                true, 1, 1, false, true, true);

        Optional<GeometryValue> opt = encoder.encodeAttribute(ma, attrDefOf(ma, "Flaechen"), meta);
        assertTrue(opt.isPresent(), "MULTIAREA should be convertible via multisurface2hexwkb");

        WKBReader reader = new WKBReader();
        Geometry geom = reader.read(opt.get().wkb());
        assertEquals("MultiPolygon", geom.getGeometryType());
        assertEquals(2, geom.getNumGeometries());
        assertEquals(GeometryKind.MULTIAREA, opt.get().metadata().geometryKind());
        assertTrue(opt.get().metadata().isMultiType());
    }

    // ARC test disabled: ioX requires a specific XML structure for ARC segments
    // that is non-trivial to produce manually. ARC handling is documented as a
    // limitation; the encoder dispatches to ioX with strokeTolerance=0.
    /*
    @Test
    void encodeArc() throws Exception { ... }
    */

    @Test
    void encode3d_throwsBecauseZIsLost() throws Exception {
        List<IomObject> objs = readXtfObjects("valid-3d.xtf");
        IomObject pt = findObject(objs, "Punkt3dObjekt");

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Lage3d", "M.T.C.Lage3d",
                GeometryKind.POINT, GeometryDimension.XYZ,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        UnsupportedGeometryException ex = assertThrows(UnsupportedGeometryException.class,
                () -> encoder.encodeAttribute(pt, attrDefOf(pt, "Lage3d"), meta));
        assertTrue(ex.getMessage().contains("3D coordinate declared but WKB output is 2D"));
    }

    @Test
    void encode3d_whenPreserveZIsFalse_returns2D() throws Exception {
        List<IomObject> objs = readXtfObjects("valid-3d.xtf");
        IomObject pt = findObject(objs, "Punkt3dObjekt");

        GeometryConversionOptions opts2d = new GeometryConversionOptions(
                ArcHandlingMode.LINEARIZE, 0.0, false, true, false);
        InterlisGeometryEncoder encoder2d = new InterlisGeometryEncoder(typeResolver, extractor, opts2d);

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Lage3d", "M.T.C.Lage3d",
                GeometryKind.POINT, GeometryDimension.XYZ,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        Optional<GeometryValue> opt = encoder2d.encodeAttribute(pt, attrDefOf(pt, "Lage3d"), meta);
        assertTrue(opt.isPresent());

        WKBReader reader = new WKBReader();
        Geometry geom = reader.read(opt.get().wkb());
        assertEquals("Point", geom.getGeometryType());
        assertEquals(2605000.0, geom.getCoordinate().x, 0.000001);
        assertEquals(1203000.0, geom.getCoordinate().y, 0.000001);
        assertTrue(Double.isNaN(geom.getCoordinate().z), "Z should be NaN when preserveZ=false");
    }

    // ------------------------------------------------------------------
    // Error / edge cases
    // ------------------------------------------------------------------

    @Test
    void clippedPolylineThrows() {
        IomObject polyline = new ch.interlis.iom_j.Iom_jObject("geom:polyline", null);
        polyline.setobjectconsistency(ch.interlis.iom.IomConstants.IOM_INCOMPLETE);

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Verlauf", "M.T.C.Verlauf",
                GeometryKind.LINESTRING, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        UnsupportedGeometryException ex = assertThrows(UnsupportedGeometryException.class,
                () -> encoder.encodeGeometry(polyline, meta));
        assertTrue(ex.getMessage().contains("IOM_INCOMPLETE"),
                "Should mention IOM_INCOMPLETE: " + ex.getMessage());
    }

    @Test
    void customLineFormThrows() {
        IomObject polyline = new ch.interlis.iom_j.Iom_jObject("geom:polyline", null);
        polyline.setobjectconsistency(ch.interlis.iom.IomConstants.IOM_COMPLETE);
        // Add a non-standard sub-element
        IomObject unknownSeg = new ch.interlis.iom_j.Iom_jObject("geom:CLOTHOID", null);
        polyline.addattrobj("CLOTHOID", unknownSeg);

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Verlauf", "M.T.C.Verlauf",
                GeometryKind.LINESTRING, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        UnsupportedGeometryException ex = assertThrows(UnsupportedGeometryException.class,
                () -> encoder.encodeGeometry(polyline, meta));
        assertTrue(ex.getMessage().contains("Custom line form"),
                "Should mention custom line form: " + ex.getMessage());
    }

    @Test
    void normalLinestringWithCoordPasses() {
        IomObject polyline = new ch.interlis.iom_j.Iom_jObject("geom:polyline", null);
        polyline.setobjectconsistency(ch.interlis.iom.IomConstants.IOM_COMPLETE);
        IomObject coord = new ch.interlis.iom_j.Iom_jObject("geom:coord", null);
        coord.setattrvalue("C1", "2600000.000");
        coord.setattrvalue("C2", "1200000.000");
        polyline.addattrobj("coord", coord);

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Verlauf", "M.T.C.Verlauf",
                GeometryKind.LINESTRING, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        // Should NOT throw — normal COORD segments are valid
        assertDoesNotThrow(() -> {
            encoder.encodeGeometry(polyline, meta);
        });
    }

    @Test
    void missingGeometryReturnsEmpty() throws Exception {
        IomObject parent = new ch.interlis.iom_j.Iom_jObject("Test.Topic.Klasse", "t1");
        ch.interlis.ili2c.metamodel.AttributeDef fakeAttr = new ch.interlis.ili2c.metamodel.AttributeDef() {
            @Override public String getName() { return "Lage"; }
            @Override public String getScopedName() { return "Test.Topic.Klasse.Lage"; }
        };
        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "Lage", "M.T.C.Lage",
                GeometryKind.POINT, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        Optional<GeometryValue> result = encoder.encodeAttribute(parent, fakeAttr, meta);
        assertTrue(result.isEmpty());
    }

    @Test
    void unsupportedTagThrows() {
        IomObject coord = new ch.interlis.iom_j.Iom_jObject("geom:foobar", null);
        coord.setattrvalue("c1", "1");
        coord.setattrvalue("c2", "2");

        GeometryMetadata meta = new GeometryMetadata(
                "M", "T", "C", "geom", "M.T.C.geom",
                GeometryKind.POINT, GeometryDimension.XY,
                null, null, null, null, null,
                true, 1, 1, false, false, false);

        GeometryConversionException ex = assertThrows(GeometryConversionException.class,
                () -> encoder.encodeGeometry(coord, meta));
        assertTrue(ex.getMessage().contains("Unexpected geometry tag"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ch.interlis.ili2c.metamodel.AttributeDef attrDefOf(IomObject obj, String attrName) {
        // We don't have the model metadata in this isolated test, so we create a minimal fake
        return new ch.interlis.ili2c.metamodel.AttributeDef() {
            @Override public String getName() { return attrName; }
            @Override public String getScopedName() { return "M.T.C." + attrName; }
        };
    }
}
