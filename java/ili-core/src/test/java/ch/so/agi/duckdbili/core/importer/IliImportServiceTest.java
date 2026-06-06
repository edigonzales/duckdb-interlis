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
    void generateImportSql_usesTypedColumns() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational");
        // BFS_Nr is a NUMERIC 0..9999 → should be BIGINT
        assertTrue(sql.contains("bfs_nr BIGINT"), "bfs_nr should be BIGINT, got: " + sql);
        // Name is TEXT → should stay VARCHAR
        assertTrue(sql.contains("name VARCHAR"), "name should be VARCHAR");
        // Status is enumeration → should stay VARCHAR
        assertTrue(sql.contains("status VARCHAR"), "status should be VARCHAR");
    }

    @Test
    void generateImportSql_usesCastInSelect() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational");
        // BFS_Nr needs CAST to BIGINT
        assertTrue(sql.contains("CAST(bfs_nr AS BIGINT)"), "Should CAST bfs_nr, got: " + sql);
        // Name does NOT need CAST
        assertFalse(sql.contains("CAST(name AS"), "Should not CAST name (it's VARCHAR)");
    }

    @Test
    void generateImportSql_usesExplicitColumnList() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational");
        // INSERT should have explicit column list in parentheses
        String line = sql.lines().filter(l -> l.contains("INSERT INTO \"test\".\"gemeinde\"")).findFirst().orElse("");
        assertTrue(line.contains("(") && line.contains(")"), "INSERT should have column list: " + line);
        assertTrue(line.contains("xtf_bid"), "col list should contain xtf_bid: " + line);
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
    }

    @Test
    void generateImportSql_associationsTypedColumns() {
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

        // Flaeche NUMERIC → BIGINT
        assertTrue(sql.contains("flaeche BIGINT"), "flaeche should be BIGINT, got: " + sql);
        // Anteil NUMERIC 0..100 → BIGINT
        assertTrue(sql.contains("anteil BIGINT"), "anteil should be BIGINT, got: " + sql);
        // CAST for Flaeche
        assertTrue(sql.contains("CAST(flaeche AS BIGINT)"), "Should CAST flaeche");
        assertTrue(sql.contains("CAST(anteil AS BIGINT)"), "Should CAST anteil");
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
