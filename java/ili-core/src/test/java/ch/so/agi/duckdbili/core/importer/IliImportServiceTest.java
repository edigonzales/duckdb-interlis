package ch.so.agi.duckdbili.core.importer;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class IliImportServiceTest {

    private static final Path TESTDIR;
    static {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("testdata/synthetic/simple/SO_AGI_Simple_20260605.ili"))) {
            TESTDIR = cwd.resolve("testdata/synthetic/simple");
        } else if (Files.isRegularFile(cwd.getParent().resolve("testdata/synthetic/simple/SO_AGI_Simple_20260605.ili"))) {
            TESTDIR = cwd.getParent().resolve("testdata/synthetic/simple");
        } else {
            TESTDIR = cwd.getParent().getParent().resolve("testdata/synthetic/simple");
        }
    }

    private static final String MODELDIR = TESTDIR.toString();
    private static final String XTF_PATH = TESTDIR.resolve("valid.xtf").toString();

    private final IliImportService service = new IliImportService();

    @Test
    void generateImportSql_createsSchema() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational");
        assertNotNull(sql);
        assertTrue(sql.contains("CREATE SCHEMA IF NOT EXISTS \"test\";"));
    }

    @Test
    void generateImportSql_createsTableForGemeinde() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"gemeinde\""));
        assertTrue(sql.contains("xtf_bid VARCHAR"));
        assertTrue(sql.contains("xtf_tid VARCHAR"));
    }

    @Test
    void generateImportSql_createsTableForAbbaustelle() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"abbaustelle\""));
    }

    @Test
    void generateImportSql_includesInsertStatements() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational");
        assertTrue(sql.contains("INSERT INTO"));
        assertTrue(sql.contains("read_xtf_class"));
    }

    @Test
    void generateImportSql_noErrorOnMissingMappingParam() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", null);
        assertNotNull(sql);
        assertFalse(sql.isBlank());
    }

    @Test
    void generateImportSql_associationsModel() {
        Path assocDir;
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("testdata/synthetic/associations/SO_AGI_Associations_20260605.ili"))) {
            assocDir = cwd.resolve("testdata/synthetic/associations");
        } else if (Files.isRegularFile(cwd.getParent().resolve("testdata/synthetic/associations/SO_AGI_Associations_20260605.ili"))) {
            assocDir = cwd.getParent().resolve("testdata/synthetic/associations");
        } else {
            assocDir = cwd.getParent().getParent().resolve("testdata/synthetic/associations");
        }

        String sql = service.generateImportSql(
            assocDir.resolve("valid.xtf").toString(),
            assocDir.toString(),
            "test", "relational");

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"person\""), "Should have person table");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"grundstueck\""), "Should have grundstueck table");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"besitz\""), "Should have besitz association table");
        assertTrue(sql.contains("besitzer_ref"), "Should have besitzer_ref column");
        assertTrue(sql.contains("grundstueck_ref"), "Should have grundstueck_ref column");
        assertTrue(sql.contains("anteil"), "Should have Anteil attribute (lowercase)");
        assertTrue(sql.contains("read_xtf_association"), "Should use read_xtf_association for association data");
    }

    @Test
    void generateImportSql_structuresModel() {
        Path structDir;
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("testdata/synthetic/structures/SO_AGI_Structures_20260605.ili"))) {
            structDir = cwd.resolve("testdata/synthetic/structures");
        } else if (Files.isRegularFile(cwd.getParent().resolve("testdata/synthetic/structures/SO_AGI_Structures_20260605.ili"))) {
            structDir = cwd.getParent().resolve("testdata/synthetic/structures");
        } else {
            structDir = cwd.getParent().getParent().resolve("testdata/synthetic/structures");
        }

        String sql = service.generateImportSql(
            structDir.resolve("valid.xtf").toString(),
            structDir.toString(),
            "test", "relational");

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"betrieb\""), "Should have betrieb table");
        assertTrue(sql.contains("adresse_json"), "Should have adresse_json column for structure");
        assertTrue(sql.contains("kontakte_json"), "Should have kontakte_json column for bag");
        assertTrue(sql.contains("name"), "Should have name scalar column");
    }
}
