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
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", null);
        assertNotNull(sql);
        assertTrue(sql.contains("CREATE SCHEMA IF NOT EXISTS \"test\";"));
    }

    @Test
    void generateImportSql_createsTableForGemeinde() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", null);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"topic__gemeinde\""));
        assertTrue(sql.contains("xtf_bid VARCHAR"));
        assertTrue(sql.contains("xtf_tid VARCHAR"));
    }

    @Test
    void generateImportSql_createsTableForAbbaustelle() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", null);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"topic__abbaustelle\""));
    }

    @Test
    void generateImportSql_includesInsertStatements() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", null);
        assertTrue(sql.contains("INSERT INTO"));
        assertTrue(sql.contains("read_xtf_class"));
    }

    @Test
    void generateImportSql_noErrorOnMissingMappingParam() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", null, null);
        assertNotNull(sql);
        assertFalse(sql.isBlank());
    }

    @Test
    void generateImportSql_usesTypedColumns() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", null);
        // BFS_Nr is a NUMERIC 0..9999 → should be BIGINT
        assertTrue(sql.contains("bfs_nr BIGINT"), "bfs_nr should be BIGINT, got: " + sql);
        // Name is TEXT → should stay VARCHAR
        assertTrue(sql.contains("name VARCHAR"), "name should be VARCHAR");
        // Status is enumeration → should stay VARCHAR
        assertTrue(sql.contains("status VARCHAR"), "status should be VARCHAR");
    }

    @Test
    void generateImportSql_usesCastInSelect() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", null);
        // BFS_Nr needs CAST to BIGINT
        assertTrue(sql.contains("CAST(bfs_nr AS BIGINT)"), "Should CAST bfs_nr, got: " + sql);
        // Name does NOT need CAST
        assertFalse(sql.contains("CAST(name AS"), "Should not CAST name (it's VARCHAR)");
    }

    @Test
    void generateImportSql_usesExplicitColumnList() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", null);
        // INSERT should have explicit column list in parentheses
        String line = sql.lines().filter(l -> l.contains("INSERT INTO \"test\".\"topic__gemeinde\"")).findFirst().orElse("");
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
            "test", "relational", null);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"topic__person\""), "Should have person table");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"topic__grundstueck\""), "Should have grundstueck table");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"topic__besitz\""), "Should have besitz association table");
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
            "test", "relational", null);

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
            "test", "relational", null);

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"topic__betrieb\""), "Should have betrieb table");
        assertTrue(sql.contains("adresse_json"), "Should have adresse_json column for structure");
        assertTrue(sql.contains("kontakte_json"), "Should have kontakte_json column for bag");
        assertTrue(sql.contains("name"), "Should have name scalar column");
    }

    @Test
    void generateImportSql_includesTransaction() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", null);
        assertTrue(sql.startsWith("BEGIN TRANSACTION;"), "SQL should start with BEGIN TRANSACTION");
        assertTrue(sql.trim().endsWith("COMMIT;"), "SQL should end with COMMIT");
    }

    @Test
    void generateImportSql_sameNamesModel() {
        Path samenamesDir;
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("testdata/synthetic/samenames/SO_AGI_SameNames_20260608.ili"))) {
            samenamesDir = cwd.resolve("testdata/synthetic/samenames");
        } else if (Files.isRegularFile(cwd.getParent().resolve("testdata/synthetic/samenames/SO_AGI_SameNames_20260608.ili"))) {
            samenamesDir = cwd.getParent().resolve("testdata/synthetic/samenames");
        } else {
            samenamesDir = cwd.getParent().getParent().resolve("testdata/synthetic/samenames");
        }

        String sql = service.generateImportSql(
            samenamesDir.resolve("valid.xtf").toString(),
            samenamesDir.toString(),
            "test", "relational", null);

        // Both topics have class "Eintrag" — must produce different table names
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"topica__eintrag\""),
            "TopicA.Eintrag should become topica__eintrag, got: " + sql);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS \"test\".\"topicb__eintrag\""),
            "TopicB.Eintrag should become topicb__eintrag, got: " + sql);
    }

    @Test
    void generateImportSql_modeCreate() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", "create");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS"), "mode=create should include CREATE TABLE IF NOT EXISTS");
        assertTrue(sql.contains("INSERT INTO"), "mode=create should include INSERT");
    }

    @Test
    void generateImportSql_modeReplace() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", "replace");
        assertTrue(sql.contains("DROP TABLE IF EXISTS"), "mode=replace should include DROP TABLE IF EXISTS");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS"), "mode=replace should include CREATE TABLE");
        assertTrue(sql.contains("INSERT INTO"), "mode=replace should include INSERT");
    }

    @Test
    void generateImportSql_modeAppend() {
        String sql = service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", "append");
        assertFalse(sql.contains("CREATE TABLE"), "mode=append should NOT include CREATE TABLE");
        assertTrue(sql.contains("INSERT INTO"), "mode=append should include INSERT");
        assertTrue(sql.contains("CREATE SCHEMA"), "mode=append should include CREATE SCHEMA");
    }

    @Test
    void generateImportSql_modeInvalid() {
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            service.generateImportSql(XTF_PATH, MODELDIR, "test", "relational", "bogus"));
        assertTrue(ex.getMessage().contains("Unsupported import mode"),
            "Should reject invalid mode: " + ex.getMessage());
    }
}
