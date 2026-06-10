package ch.so.agi.duckdbili.core.geometry;

import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ilirepository.IliManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

class InterlisGeometryTypeResolverTest {

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
    private static TransferDescription TD;
    private static final InterlisGeometryTypeResolver RESOLVER = new InterlisGeometryTypeResolver();

    @BeforeAll
    static void compileModel() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream capture = new java.io.PrintStream(baos);
        java.io.PrintStream original = System.err;
        System.setErr(capture);
        try {
            IliManager manager = new IliManager();
            manager.setRepositories(new String[]{TESTDIR.toString(), "https://models.interlis.ch"});
            ArrayList<String> entries = new ArrayList<>();
            entries.add(TESTDIR.resolve("SO_AGI_Geometries_20260605.ili").toAbsolutePath().toString());
            Configuration cfg = manager.getConfigWithFiles(entries, null, 0.0);
            Ili2cSettings settings = new Ili2cSettings();
            Main.setDefaultIli2cPathMap(settings);
            settings.setIlidirs(TESTDIR.toString());
            TD = Main.runCompiler(cfg, settings, null);
        } finally {
            System.setErr(original);
        }
        if (TD == null) {
            throw new AssertionError("Model compilation returned null. stderr:\n" + baos.toString("UTF-8"));
        }
    }

    // ------------------------------------------------------------------
    // Kind resolution
    // ------------------------------------------------------------------

    @Test
    void resolveKind_point() {
        assertEquals(GeometryKind.POINT, RESOLVER.resolveKind(findAttr("PunktObjekt", "Lage")));
    }

    @Test
    void resolveKind_multipoint() {
        assertEquals(GeometryKind.MULTIPOINT, RESOLVER.resolveKind(findAttr("MultiPunktObjekt", "Lagen")));
    }

    @Test
    void resolveKind_linestring() {
        assertEquals(GeometryKind.LINESTRING, RESOLVER.resolveKind(findAttr("LinienObjekt", "Verlauf")));
    }

    @Test
    void resolveKind_multilinestring() {
        assertEquals(GeometryKind.MULTILINESTRING, RESOLVER.resolveKind(findAttr("MultiLinienObjekt", "Verlaeufe")));
    }

    @Test
    void resolveKind_polygon() {
        assertEquals(GeometryKind.POLYGON, RESOLVER.resolveKind(findAttr("FlaechenObjekt", "Flaeche")));
    }

    @Test
    void resolveKind_multipolygon() {
        assertEquals(GeometryKind.MULTIPOLYGON, RESOLVER.resolveKind(findAttr("MultiFlaechenObjekt", "Flaechen")));
    }

    @Test
    void resolveKind_area() {
        assertEquals(GeometryKind.AREA, RESOLVER.resolveKind(findAttr("AreaObjekt", "Flaeche")));
    }

    @Test
    void resolveKind_multiarea() {
        assertEquals(GeometryKind.MULTIAREA, RESOLVER.resolveKind(findAttr("MultiAreaObjekt", "Flaechen")));
    }

    @Test
    void resolveKind_nonGeometry() {
        assertEquals(GeometryKind.UNKNOWN, RESOLVER.resolveKind(findAttr("PunktObjekt", "Name")));
    }

    @Test
    void isGeometryAttribute_true() {
        assertTrue(RESOLVER.isGeometryAttribute(findAttr("PunktObjekt", "Lage")));
    }

    @Test
    void isGeometryAttribute_false() {
        assertFalse(RESOLVER.isGeometryAttribute(findAttr("PunktObjekt", "Name")));
    }

    // ------------------------------------------------------------------
    // Dimension resolution
    // ------------------------------------------------------------------

    @Test
    void resolveDeclaredDimension_xy() {
        assertEquals(GeometryDimension.XY, RESOLVER.resolveDeclaredDimension(findAttr("PunktObjekt", "Lage")));
    }

    @Test
    void resolveDeclaredDimension_xyz() {
        assertEquals(GeometryDimension.XYZ, RESOLVER.resolveDeclaredDimension(findAttr("Punkt3dObjekt", "Lage3d")));
    }

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    @Test
    void resolveMetadata_fields() {
        AttributeDef attr = findAttr("PunktObjekt", "Lage");
        Model model = findModel();
        Topic topic = findTopic(model);
        AbstractClassDef clazz = findClassDef("PunktObjekt");
        GeometryMetadata md = RESOLVER.resolveMetadata(model, topic, clazz, attr);

        assertEquals("SO_AGI_Geometries_20260605", md.modelName());
        assertEquals("Topic", md.topicName());
        assertEquals("PunktObjekt", md.className());
        assertEquals("Lage", md.attributeName());
        assertEquals("SO_AGI_Geometries_20260605.Topic.PunktObjekt.Lage", md.attributeFqn());
        assertEquals(GeometryKind.POINT, md.geometryKind());
        assertEquals(GeometryDimension.XY, md.dimension());
        assertEquals("Koord", md.coordinateDomainName());
        assertEquals("SO_AGI_Geometries_20260605.Koord", md.coordinateDomainFqn());
        assertFalse(md.isAreaType());
        assertFalse(md.isMultiType());
    }

    @Test
    void resolveMetadata_areaFlags() {
        AttributeDef attr = findAttr("AreaObjekt", "Flaeche");
        Model model = findModel();
        Topic topic = findTopic(model);
        AbstractClassDef clazz = findClassDef("AreaObjekt");
        GeometryMetadata md = RESOLVER.resolveMetadata(model, topic, clazz, attr);

        assertEquals(GeometryKind.AREA, md.geometryKind());
        assertTrue(md.isAreaType());
        assertFalse(md.isMultiType());
    }

    @Test
    void resolveMetadata_multiFlags() {
        AttributeDef attr = findAttr("MultiFlaechenObjekt", "Flaechen");
        Model model = findModel();
        Topic topic = findTopic(model);
        AbstractClassDef clazz = findClassDef("MultiFlaechenObjekt");
        GeometryMetadata md = RESOLVER.resolveMetadata(model, topic, clazz, attr);

        assertEquals(GeometryKind.MULTIPOLYGON, md.geometryKind());
        assertTrue(md.isMultiType());
        assertFalse(md.isAreaType());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Model findModel() {
        for (Iterator<Model> it = TD.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if ("SO_AGI_Geometries_20260605".equals(m.getName())) return m;
        }
        throw new AssertionError("Model not found");
    }

    private static Topic findTopic(Model model) {
        for (Iterator<Element> it = model.iterator(); it.hasNext(); ) {
            Element el = it.next();
            if (el instanceof Topic t && "Topic".equals(t.getName())) return t;
        }
        throw new AssertionError("Topic not found");
    }

    private static AttributeDef findAttr(String className, String attrName) {
        AbstractClassDef clazz = findClassDef(className);
        Iterator<?> it = clazz.getAttributesAndRoles2();
        while (it.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) it.next();
            if (vte.obj instanceof AttributeDef ad && attrName.equals(ad.getName())) {
                return ad;
            }
        }
        throw new AssertionError("Attribute not found: " + className + "." + attrName);
    }

    private static AbstractClassDef findClassDef(String className) {
        Model model = findModel();
        Topic topic = findTopic(model);
        Iterator<Element> it = topic.iterator();
        while (it.hasNext()) {
            Element el = it.next();
            if (el instanceof AbstractClassDef cd && !(el instanceof AssociationDef) && className.equals(cd.getName())) {
                return cd;
            }
        }
        throw new AssertionError("Class not found: " + className);
    }
}
