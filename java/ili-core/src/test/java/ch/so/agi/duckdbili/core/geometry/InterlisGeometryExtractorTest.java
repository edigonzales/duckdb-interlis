package ch.so.agi.duckdbili.core.geometry;

import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.Iom_jObject;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Ili2cSemanticException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InterlisGeometryExtractorTest {

    private final InterlisGeometryExtractor extractor = new InterlisGeometryExtractor();

    // Dummy attribute – only the name matters for the extractor
    private AttributeDef dummyAttr(String name) throws Ili2cSemanticException {
        // We need a light-weight AttributeDef. Since ili2c doesn't expose
        // a public constructor we create a minimal mock via subclassing.
        // For the extractor only getName() and getScopedName() are used.
        // We'll create a fake attribute via an anonymous subclass if possible,
        // but AttributeDef is abstract? Let's check... actually AttributeDef
        // is concrete in ili2c-core. It requires a container.
        // Alternative: just create a real one programmatically.
        // For brevity we use a helper that creates via reflection / subclass.
        return new FakeAttributeDef(name);
    }

    @Test
    void noValueReturnsEmpty() throws Exception {
        IomObject parent = new Iom_jObject("Test.Topic.Klasse", "t1");
        Optional<IomObject> result = extractor.extractSingle(parent, dummyAttr("Lage"), GeometryConversionOptions.defaults());
        assertTrue(result.isEmpty());
    }

    @Test
    void singleValueReturnsPresent() throws Exception {
        IomObject parent = new Iom_jObject("Test.Topic.Klasse", "t1");
        IomObject coord = new Iom_jObject("geom:coord", null);
        coord.setattrvalue("c1", "2605000.0");
        coord.setattrvalue("c2", "1203000.0");
        parent.addattrobj("Lage", coord);

        Optional<IomObject> result = extractor.extractSingle(parent, dummyAttr("Lage"), GeometryConversionOptions.defaults());
        assertTrue(result.isPresent());
    }

    @Test
    void multipleValuesRejectedWhenConfigured() throws Exception {
        IomObject parent = new Iom_jObject("Test.Topic.Klasse", "t1");
        IomObject c1 = new Iom_jObject("geom:coord", null);
        IomObject c2 = new Iom_jObject("geom:coord", null);
        parent.addattrobj("Lage", c1);
        parent.addattrobj("Lage", c2);

        GeometryConversionOptions opts = new GeometryConversionOptions(
                ArcHandlingMode.LINEARIZE, 0.0, true, true, false);

        GeometryConversionException ex = assertThrows(GeometryConversionException.class,
                () -> extractor.extractSingle(parent, dummyAttr("Lage"), opts));
        assertTrue(ex.getMessage().contains("2 geometry values"));
    }

    @Test
    void multipleValuesFirstTakenWhenNotRejected() throws Exception {
        IomObject parent = new Iom_jObject("Test.Topic.Klasse", "t1");
        IomObject c1 = new Iom_jObject("geom:coord", null);
        IomObject c2 = new Iom_jObject("geom:coord", null);
        c1.setattrvalue("c1", "1");
        c2.setattrvalue("c1", "2");
        parent.addattrobj("Lage", c1);
        parent.addattrobj("Lage", c2);

        GeometryConversionOptions opts = new GeometryConversionOptions(
                ArcHandlingMode.LINEARIZE, 0.0, true, false, false);

        Optional<IomObject> result = extractor.extractSingle(parent, dummyAttr("Lage"), opts);
        assertTrue(result.isPresent());
        assertEquals("1", result.get().getattrvalue("c1"));
    }

    @Test
    void extractAllReturnsBoth() throws Exception {
        IomObject parent = new Iom_jObject("Test.Topic.Klasse", "t1");
        parent.addattrobj("Lage", new Iom_jObject("geom:coord", null));
        parent.addattrobj("Lage", new Iom_jObject("geom:coord", null));

        var list = extractor.extractAll(parent, dummyAttr("Lage"));
        assertEquals(2, list.size());
    }

    // Minimal fake to satisfy the extractor's usage of AttributeDef
    private static final class FakeAttributeDef extends AttributeDef {
        private final String name;

        FakeAttributeDef(String name) {
            super();
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getScopedName() {
            return "Test.Topic.Klasse." + name;
        }
    }
}
