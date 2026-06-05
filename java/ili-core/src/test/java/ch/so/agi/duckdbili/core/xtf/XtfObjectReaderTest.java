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
    void readClass_unsupportedJson_isEmpty() {
        String result = reader.readClass(XTF_PATH, CLASS_NAME, MODELDIR);
        for (String line : result.split("\n")) {
            if (line.contains("Firma AG")) {
                String[] fields = line.split("\t", -1);
                String lastField = fields[fields.length - 1];
                assertTrue(lastField.isEmpty() || lastField.equals("{}"),
                    "unsupported_json should be empty, got: " + lastField);
            }
        }
    }

    @Test
    void readClass_missingStructure_returnsEmptyString() {
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
                assertEquals("", fields[adresseIdx], "Missing structure should be empty string");
            }
        }
    }
}
