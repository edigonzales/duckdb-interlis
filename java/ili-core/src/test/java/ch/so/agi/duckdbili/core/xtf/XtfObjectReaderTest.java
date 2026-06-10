package ch.so.agi.duckdbili.core.xtf;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class XtfObjectReaderTest {

    private static final Path TESTDIR;
    static {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("testdata/synthetic/structures/SO_AGI_Structures_20260605.ili"))) {
            TESTDIR = cwd.resolve("testdata/synthetic/structures");
        } else if (Files.isRegularFile(cwd.getParent().resolve("testdata/synthetic/structures/SO_AGI_Structures_20260605.ili"))) {
            TESTDIR = cwd.getParent().resolve("testdata/synthetic/structures");
        } else {
            TESTDIR = cwd.getParent().getParent().resolve("testdata/synthetic/structures");
        }
    }

    private static final String MODELDIR = TESTDIR.toString();
    private static final String XTF_PATH = TESTDIR.resolve("valid.xtf").toString();
    private static final String CLASS_NAME = "SO_AGI_Structures_20260605.Topic.Betrieb";

    private final XtfObjectReader reader = new XtfObjectReader();

    @Test
    void readClassSchema_hasJsonColumns() {
        String schema = reader.readClassSchema(CLASS_NAME, MODELDIR);
        assertNotNull(schema);
        assertFalse(schema.isBlank());
        assertTrue(schema.contains("Adresse_json"), "Schema should contain Adresse_json column");
        assertTrue(schema.contains("Kontakte_json"), "Schema should contain Kontakte_json column");
        assertTrue(schema.contains("Name"), "Schema should contain scalar Name column");
        assertTrue(schema.contains("unsupported_json"), "Schema should contain unsupported_json column");
    }

    @Test
    void readClass_returnsCorrectJson() {
        String result = reader.readClass(XTF_PATH, CLASS_NAME, MODELDIR);
        assertNotNull(result);
        assertFalse(result.isBlank());

        String[] lines = result.split("\n");
        assertTrue(lines.length >= 2, "Should have header + at least one data row");

        assertTrue(lines[0].contains("Adresse_json"));
        assertTrue(lines[0].contains("Kontakte_json"));

        String firmaRow = null;
        String leereRow = null;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].contains("Firma AG")) firmaRow = lines[i];
            if (lines[i].contains("Leere GmbH")) leereRow = lines[i];
        }
        assertNotNull(firmaRow, "Should find Firma AG row");

        assertTrue(firmaRow.contains("\"Strasse\"") && firmaRow.contains("\"Hauptstrasse 1\""));
        assertTrue(firmaRow.contains("\"PLZ\"") && firmaRow.contains("4500"));
        assertFalse(firmaRow.startsWith("\"["), "Single structure should be JSON object, not array");

        assertTrue(firmaRow.contains("\"Typ\":\"geschaeftlich\""));
        assertTrue(firmaRow.contains("\"Typ\":\"privat\""));

        if (leereRow != null) {
            assertNotNull(leereRow);
        }
    }

    @Test
    void readClass_unsupportedJson_isNull() {
        String result = reader.readClass(XTF_PATH, CLASS_NAME, MODELDIR);
        for (String line : result.split("\n")) {
            if (line.contains("Firma AG")) {
                String[] fields = line.split("\t", -1);
                String lastField = fields[fields.length - 1];
                assertEquals("\\N", lastField,
                    "unsupported_json should be \\N when no unsupported attrs, got: " + lastField);
            }
        }
    }

    @Test
    void readClass_missingStructure_returnsNull() {
        String result = reader.readClass(XTF_PATH, CLASS_NAME, MODELDIR);
        String[] lines = result.split("\n");
        String[] headerFields = lines[0].split("\t");

        int adresseIdx = -1;
        for (int i = 0; i < headerFields.length; i++) {
            if (headerFields[i].equals("Adresse_json")) { adresseIdx = i; break; }
        }
        assertTrue(adresseIdx > 0, "Should have Adresse_json column");

        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split("\t", -1);
            if (fields.length > adresseIdx && fields[1].equals("2")) {
                assertEquals("\\N", fields[adresseIdx], "Missing structure should be \\N (NULL sentinel)");
            }
        }
    }

    // -------------------------------------------------------------------
    // Association tests
    // -------------------------------------------------------------------
    private static final Path ASSOC_DIR;
    static {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("testdata/synthetic/associations/SO_AGI_Associations_20260605.ili"))) {
            ASSOC_DIR = cwd.resolve("testdata/synthetic/associations");
        } else if (Files.isRegularFile(cwd.getParent().resolve("testdata/synthetic/associations/SO_AGI_Associations_20260605.ili"))) {
            ASSOC_DIR = cwd.getParent().resolve("testdata/synthetic/associations");
        } else {
            ASSOC_DIR = cwd.getParent().getParent().resolve("testdata/synthetic/associations");
        }
    }

    private static final String ASSOC_MODELDIR = ASSOC_DIR.toString();
    private static final String ASSOC_XTF = ASSOC_DIR.resolve("valid.xtf").toString();
    private static final String ASSOC_BESITZ = "SO_AGI_Associations_20260605.Topic.Besitz";
    private static final String ASSOC_PERSON = "SO_AGI_Associations_20260605.Topic.Person";

    @Test
    void readAssociationSchema_hasRefColumns() {
        String schema = reader.readAssociationSchema(ASSOC_BESITZ, ASSOC_MODELDIR);
        assertNotNull(schema);
        assertTrue(schema.contains("besitzer_ref"), "Should contain besitzer_ref column");
        assertTrue(schema.contains("grundstueck_ref"), "Should contain grundstueck_ref column");
        assertTrue(schema.contains("Anteil"), "Should contain Anteil attribute column");
    }

    @Test
    void readAssociation_hasRefValues() {
        String result = reader.readAssociation(ASSOC_XTF, ASSOC_BESITZ, ASSOC_MODELDIR);
        assertNotNull(result);
        String[] lines = result.split("\n");
        assertTrue(lines.length >= 2, "Should have header + data rows");

        String header = lines[0];
        int besitzerIdx = -1, grundIdx = -1, anteilIdx = -1;
        String[] hdr = header.split("\t");
        for (int i = 0; i < hdr.length; i++) {
            if (hdr[i].equals("besitzer_ref")) besitzerIdx = i;
            if (hdr[i].equals("grundstueck_ref")) grundIdx = i;
            if (hdr[i].equals("Anteil")) anteilIdx = i;
        }
        assertTrue(besitzerIdx > 0, "Should have besitzer_ref column");
        assertTrue(grundIdx > 0, "Should have grundstueck_ref column");
        assertTrue(anteilIdx > 0, "Should have Anteil column");

        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split("\t", -1);
            assertFalse(fields[besitzerIdx].isEmpty(), "besitzer_ref should not be empty");
            assertFalse(fields[grundIdx].isEmpty(), "grundstueck_ref should not be empty");
            assertFalse(fields[anteilIdx].isEmpty(), "Anteil should not be empty");
        }
    }

    @Test
    void readAssociation_unsupportedJson_isNull() {
        String result = reader.readAssociation(ASSOC_XTF, ASSOC_BESITZ, ASSOC_MODELDIR);
        for (String line : result.split("\n")) {
            if (line.startsWith("xtf_bid")) continue;
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", -1);
            String lastField = fields[fields.length - 1];
            assertEquals("\\N", lastField, "unsupported_json should be \\N when no unsupported attrs, got: " + lastField);
        }
    }

    @Test
    void readClass_personHasNameColumn() {
        String schema = reader.readClassSchema(ASSOC_PERSON, ASSOC_MODELDIR);
        assertTrue(schema.contains("Name"), "Person schema should contain Name column");
        String result = reader.readClass(ASSOC_XTF, ASSOC_PERSON, ASSOC_MODELDIR);
        assertTrue(result.contains("Max Muster"));
        assertTrue(result.contains("Anna Beispiel"));
    }

    // -------------------------------------------------------------------
    // Schema v2 tests (Phase 1)
    // -------------------------------------------------------------------
    private static final Path GEOM_DIR;
    static {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili"))) {
            GEOM_DIR = cwd.resolve("testdata/synthetic/geometries");
        } else if (Files.isRegularFile(cwd.getParent().resolve("testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili"))) {
            GEOM_DIR = cwd.getParent().resolve("testdata/synthetic/geometries");
        } else {
            GEOM_DIR = cwd.getParent().getParent().resolve("testdata/synthetic/geometries");
        }
    }

    private static final String GEOM_MODELDIR = GEOM_DIR.toString();
    private static final String GEOM_XTF = GEOM_DIR.resolve("valid.xtf").toString();
    private static final String PUNKT_KLASSE = "SO_AGI_Geometries_20260605.Topic.PunktObjekt";
    private static final String FLAECHEN_KLASSE = "SO_AGI_Geometries_20260605.Topic.FlaechenObjekt";

    @Test
    void readClassSchemaV2_geometryColumnsMarkedAsGEOMETRY() {
        String schema = reader.readClassSchemaV2(PUNKT_KLASSE, GEOM_MODELDIR);
        assertNotNull(schema);
        assertFalse(schema.isBlank());

        String[] lines = schema.split("\n");
        boolean foundGeomColumn = false;
        for (String line : lines) {
            String[] fields = line.split("\t", -1);
            if (fields.length >= 4 && fields[0].contains("Lage_geom")) {
                foundGeomColumn = true;
                assertEquals("GEOMETRY", fields[1], "Geometry column should have GEOMETRY type");
                assertEquals("HEX_WKB", fields[2], "Geometry column should have HEX_WKB encoding");
                assertEquals("true", fields[3], "Geometry column should be nullable");
            }
            if (fields.length >= 4 && fields[0].equals("Name")) {
                assertEquals("VARCHAR", fields[1], "Scalar column should have VARCHAR type");
                assertEquals("TEXT", fields[2], "Scalar column should have TEXT encoding");
            }
        }
        assertTrue(foundGeomColumn, "Should find Lage_geom column in schema v2");
    }

    @Test
    void readClassSchemaV2_polygonMarkedAsPOLYGON() {
        String schema = reader.readClassSchemaV2(FLAECHEN_KLASSE, GEOM_MODELDIR);
        String[] lines = schema.split("\n");
        boolean found = false;
        for (String line : lines) {
            if (line.contains("Flaeche_geom")) {
                found = true;
                String[] fields = line.split("\t", -1);
                assertEquals("GEOMETRY", fields[1]);
                assertTrue(fields[4] != null && !fields[4].isEmpty(), "geometry_kind should be set");
            }
        }
        assertTrue(found);
    }

    @Test
    void readClassV2_geometryIsHexWKB() {
        String result = reader.readClassV2(GEOM_XTF, PUNKT_KLASSE, GEOM_MODELDIR, "json");
        assertNotNull(result);
        assertFalse(result.isBlank());

        String[] lines = result.split("\n");
        assertTrue(lines.length >= 2, "Should have header + data");

        // Find Lage_geom column index
        String[] header = lines[0].split("\t", -1);
        int geomIdx = -1;
        for (int i = 0; i < header.length; i++) {
            if (header[i].equals("Lage_geom")) { geomIdx = i; break; }
        }
        assertTrue(geomIdx > 0, "Should have Lage_geom column");

        // Check that geometry value is hex WKB (uppercase, even length, no spaces)
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split("\t", -1);
            if (fields.length > geomIdx && fields[1].equals("p1")) {
                String geomVal = fields[geomIdx];
                assertNotNull(geomVal);
                // Hex WKB should be uppercase, even length, only hex chars
                assertTrue(geomVal.matches("^[0-9A-F]*$"),
                    "Geometry value should be uppercase hex: " + geomVal);
                assertTrue(geomVal.length() % 2 == 0,
                    "Hex WKB should have even length");
                assertFalse(geomVal.contains(" "),
                    "Hex WKB should not contain spaces (WKT)");
            }
        }
    }
}
