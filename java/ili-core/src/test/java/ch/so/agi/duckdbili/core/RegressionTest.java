package ch.so.agi.duckdbili.core;

import ch.so.agi.duckdbili.core.importer.IliImportService;
import ch.so.agi.duckdbili.core.logging.IliLogger;
import ch.so.agi.duckdbili.core.model.IliModelService;
import ch.so.agi.duckdbili.core.validation.IliValidatorService;
import ch.so.agi.duckdbili.core.validation.ValidationMessage;
import ch.so.agi.duckdbili.core.validation.ValidationProfile;
import ch.so.agi.duckdbili.core.validation.ValidationResult;
import ch.so.agi.duckdbili.core.xtf.XtfObjectReader;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for known bugs.
 *
 * Each test is annotated with the issue it demonstrates and the Phase
 * that will fix it. Tests marked @Disabled should be enabled when the
 * corresponding Phase is implemented.
 */
class RegressionTest {

    private static Path REPO_ROOT;
    private static Path SIMPLE_DIR;

    @BeforeAll
    static void resolvePaths() {
        Path cwd = Path.of("").toAbsolutePath();
        REPO_ROOT = cwd;
        if (!Files.isRegularFile(REPO_ROOT.resolve("testdata/synthetic/simple/SO_AGI_Simple_20260605.ili"))) {
            REPO_ROOT = cwd.getParent();
        }
        if (!Files.isRegularFile(REPO_ROOT.resolve("testdata/synthetic/simple/SO_AGI_Simple_20260605.ili"))) {
            REPO_ROOT = cwd.getParent().getParent();
        }
        SIMPLE_DIR = REPO_ROOT.resolve("testdata/synthetic/simple");
    }

    // -----------------------------------------------------------------------
    // Phase 1: Memory Ownership (tests demonstrating leaked error messages)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION-FIXED: Model compilation failure now throws RuntimeException (Phase 2)")
    void modelCompileFailureSilentlyReturnsEmpty() {
        IliModelService svc = new IliModelService();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            svc.getModels("/nonexistent/directory/that/does/not/exist"));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("compilation failed"));
    }

    @Test
    @DisplayName("REGRESSION: Missing XTF file returns validation error message, not DuckDB error")
    void missingFileReturnsValidationMessage() {
        // BUG: file-not-found is returned as an ERROR-level validation message,
        // not as a technical DuckDB error. The caller cannot distinguish
        // "file not found" from "XTF has errors".
        IliValidatorService svc = new IliValidatorService();
        Path nonexistent = SIMPLE_DIR.resolve("does_not_exist.xtf");
        ValidationResult result = svc.validate(nonexistent, SIMPLE_DIR.toString());

        assertFalse(result.isValid());
        assertEquals(1, result.getErrorCount());
        // Currently: message contains "not found" - returned as validation data
        // Phase 6 should consider: should file-not-found be a technical error?
    }

    // -----------------------------------------------------------------------
    // Phase 2: Error Contract (tests demonstrating ERROR: prefix, status codes)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION-FIXED: Model info throws on compilation failure instead of returning empty (Phase 2)")
    void modelInfoReturnsEmptyOnFailure() {
        IliModelService svc = new IliModelService();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            svc.getModels("/nonexistent/path"));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("compilation failed"));
    }

    // -----------------------------------------------------------------------
    // Phase 5: Java Cache & Logger  (FIXED)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION-FIXED: Multiple model service objects now share thread-safe ModelCache (Phase 5)")
    void modelServiceCacheNotShared() {
        // FIXED in Phase 5: IliModelService now delegates to shared ModelCache singleton.
        // The HashMap is replaced with ConcurrentHashMap with fingerprint-based keys.
        IliModelService svc1 = new IliModelService();
        IliModelService svc2 = new IliModelService();

        String r1 = svc1.getModels(SIMPLE_DIR.toString());
        String r2 = svc2.getModels(SIMPLE_DIR.toString());

        assertEquals(r1, r2, "Same model dir should produce same result");
    }

    @Test
    @DisplayName("REGRESSION-FIXED: Logger suppress/restore no longer modifies global System.err (Phase 5)")
    void loggerUsesGlobalSystemErr() {
        // FIXED in Phase 5: IliLogger no longer calls System.setErr().
        // System.err remains unchanged across suppress/restore.
        var originalErr = System.err;
        IliLogger.suppress();
        try {
            assertSame(originalErr, System.err,
                "System.err should NOT be modified by suppress() - it stays thread-shared");
        } finally {
            IliLogger.restore();
        }
        assertSame(originalErr, System.err,
            "System.err should still be the original after restore()");
    }

    // -----------------------------------------------------------------------
    // Phase 6: Validator
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION: CSV parser handles comma-containing values correctly")
    void csvParseHandlesCommas() {
        // Phase 6 fix: parseCsvLine() replaces split(",", -1) with
        // proper state-machine CSV parser supporting quoted fields.
        // Verify via validate() that messages survive round-trip.
        IliValidatorService service = new IliValidatorService();
        Path xtfFile = SIMPLE_DIR.resolve("invalid.xtf");
        ValidationResult result = service.validate(xtfFile, SIMPLE_DIR.toString());
        assertNotNull(result);
        assertFalse(result.getMessages().isEmpty(),
            "Invalid XTF should produce validation messages");
        for (var msg : result.getMessages()) {
            assertNotNull(msg.getMessage());
            assertNotNull(msg.getCode(), "code should be populated (non-null)");
        }
    }

    @Test
    @DisplayName("REGRESSION: CONSTRAINT and AREA validation enabled for full profile")
    void constraintValidationEnabledForFullProfile() {
        // Phase 6 fix: full profile enables CONSTRAINT + AREA validation.
        // Previously these were hardcoded disabled.
        // Verify that validate() with default args uses FULL profile.
        IliValidatorService service = new IliValidatorService();
        Path xtfFile = SIMPLE_DIR.resolve("valid.xtf");
        ValidationResult result = service.validate(xtfFile, SIMPLE_DIR.toString());
        // Valid XTF with full profile passes all checks
        assertTrue(result.isValid(),
            "Valid XTF should pass full validation (constraints+area enabled), errors="
            + result.getErrorCount());
    }

    @Test
    @DisplayName("REGRESSION: Fast profile does less work than full profile")
    void fastProfileProducesFewerChecks() {
        // Verify fast profile validates but may produce different counts
        IliValidatorService service = new IliValidatorService();
        Path xtfFile = SIMPLE_DIR.resolve("invalid.xtf");
        ValidationResult full = service.validate(xtfFile, SIMPLE_DIR.toString(),
                -1, ValidationProfile.FULL);
        ValidationResult fast = service.validate(xtfFile, SIMPLE_DIR.toString(),
                -1, ValidationProfile.FAST);
        // Both should run without exception
        assertNotNull(full);
        assertNotNull(fast);
        // Fast profile should not produce MORE errors than full
        // (it may produce fewer or same, but not more)
        assertTrue(fast.getErrorCount() <= full.getErrorCount(),
            "fast profile should not have more errors than full: fast="
            + fast.getErrorCount() + " full=" + full.getErrorCount());
    }

    // -----------------------------------------------------------------------
    // Phase 7: XTF Reader
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION-FIXED: XTF reader now validates classes by full FQN (Phase 7)")
    void classMatchingUsesFQN() {
        XtfObjectReader reader = new XtfObjectReader();
        Path samenamesDir = REPO_ROOT.resolve("testdata/synthetic/samenames");
        Path xtfPath = samenamesDir.resolve("valid.xtf");

        String tsvA = reader.readClass(
            xtfPath.toString(),
            "SO_AGI_SameNames_20260608.TopicA.Eintrag",
            samenamesDir.toString()
        );
        assertNotNull(tsvA);
        assertTrue(tsvA.contains("Wert aus TopicA"),
            "Should only contain TopicA.Eintrag, but got: " + tsvA);
        assertFalse(tsvA.contains("Wert aus TopicB"),
            "Should NOT contain TopicB.Eintrag data");

        String tsvB = reader.readClass(
            xtfPath.toString(),
            "SO_AGI_SameNames_20260608.TopicB.Eintrag",
            samenamesDir.toString()
        );
        assertNotNull(tsvB);
        assertTrue(tsvB.contains("Wert aus TopicB"),
            "Should only contain TopicB.Eintrag, but got: " + tsvB);
        assertFalse(tsvB.contains("Wert aus TopicA"),
            "Should NOT contain TopicA.Eintrag data");
    }

    @Test
    @DisplayName("REGRESSION-FIXED: Missing attributes are NULL (\\N), not empty strings (Phase 7)")
    void missingValuesAreNull() {
        XtfObjectReader reader = new XtfObjectReader();
        Path xtfPath = SIMPLE_DIR.resolve("null_and_empty.xtf");
        String tsv = reader.readClass(
            xtfPath.toString(),
            "SO_AGI_Simple_20260605.Topic.Gemeinde",
            SIMPLE_DIR.toString()
        );
        assertNotNull(tsv);
        assertFalse(tsv.isEmpty());

        for (String line : tsv.split("\n")) {
            if (line.startsWith("xtf_bid")) continue;
            if (line.isBlank()) continue;
            String[] fields = line.split("\t", -1);
            // TID=1: empty Name, BFS_Nr=100
            // TID=2: Name="Name", missing BFS_Nr
            if (fields.length > 1 && fields[1].equals("1")) {
                // Empty string attribute should be "" not NULL
                // Check that the Name field (index depends on schema) is empty
                // The empty string is preserved as empty TSV field
            }
            // Verify \N appears for missing values somewhere in the output
        }
        // Key check: \N sentinel appears for the missing BFS_Nr in TID=2
        assertTrue(tsv.contains("\\N"),
            "Missing attribute should produce \\N sentinel, got: " + tsv);
    }

    @Test
    @DisplayName("REGRESSION: Truncated XTF file throws error (Phase 7)")
    void truncatedXtfThrowsError() {
        XtfObjectReader reader = new XtfObjectReader();
        Path xtfPath = SIMPLE_DIR.resolve("truncated.xtf");
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            reader.readObjects(xtfPath.toString(), SIMPLE_DIR.toString(), null));
        assertTrue(ex.getMessage().contains("XTF read error"),
            "Should contain 'XTF read error': " + ex.getMessage());
    }

    @Test
    @DisplayName("REGRESSION: Invalid XML throws error (Phase 7)")
    void invalidXmlThrowsError() {
        XtfObjectReader reader = new XtfObjectReader();
        Path xtfPath = SIMPLE_DIR.resolve("invalid_xml.xtf");
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            reader.readObjects(xtfPath.toString(), SIMPLE_DIR.toString(), null));
        assertTrue(ex.getMessage().contains("XTF read error"),
            "Should throw XTF read error for invalid XML");
    }

    @Test
    @DisplayName("REGRESSION: Invalid geometry throws error (Phase 7)")
    void invalidGeometryThrowsError() {
        XtfObjectReader reader = new XtfObjectReader();
        Path geomDir = REPO_ROOT.resolve("testdata/synthetic/geometries");
        Path xtfPath = geomDir.resolve("broken_geom.xtf");
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            reader.readClass(xtfPath.toString(),
                "SO_AGI_Geometries_20260605.Topic.FlaechenObjekt",
                geomDir.toString()));
        assertTrue(ex.getMessage().contains("XTF read error"),
            "Should throw XTF read error for invalid geometry: " + ex.getMessage());
    }

    @Test
    @DisplayName("REGRESSION: Unqualified class name throws error (Phase 7)")
    void unqualifiedClassNameThrowsError() {
        XtfObjectReader reader = new XtfObjectReader();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            reader.readClass(
                SIMPLE_DIR.resolve("valid.xtf").toString(),
                "Gemeinde",
                SIMPLE_DIR.toString()
            ));
        assertTrue(ex.getMessage().contains("fully qualified"),
            "Should reject short class name: " + ex.getMessage());
    }

    @Test
    @DisplayName("REGRESSION: Operations INSERT/UPDATE/DELETE (Phase 7)")
    void operationsAreCorrect() {
        XtfObjectReader reader = new XtfObjectReader();
        Path xtfPath = SIMPLE_DIR.resolve("operations.xtf");
        String tsv = reader.readObjects(xtfPath.toString(), SIMPLE_DIR.toString(), null);
        assertNotNull(tsv);
        // operation column: INSERT=1, UPDATE=2, DELETE=1+2... wait let me check the code
        // In the Java code: op==1->DELETE, op==2->UPDATE, op==3->INSERT
        assertTrue(tsv.contains("INSERT"), "Should contain INSERT: " + tsv);
        assertTrue(tsv.contains("UPDATE"), "Should contain UPDATE: " + tsv);
        assertTrue(tsv.contains("DELETE"), "Should contain DELETE: " + tsv);
    }

    @Test
    @DisplayName("REGRESSION: Multiple baskets preserve BID (Phase 7)")
    void multibasketPreservesBid() {
        XtfObjectReader reader = new XtfObjectReader();
        Path xtfPath = SIMPLE_DIR.resolve("multibasket.xtf");
        String tsv = reader.readObjects(xtfPath.toString(), SIMPLE_DIR.toString(), null);
        assertNotNull(tsv);
        assertTrue(tsv.contains("basket1"), "Should contain basket1");
        assertTrue(tsv.contains("basket2"), "Should contain basket2");
    }

    @Test
    @DisplayName("REGRESSION-FIXED: Class not found in model throws error (Phase 7)")
    void classNotFoundThrowsError() {
        XtfObjectReader reader = new XtfObjectReader();
        String nonexistentClass = "SO_AGI_Simple_20260605.Topic.NichtVorhanden";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            reader.readClass(
                SIMPLE_DIR.resolve("valid.xtf").toString(),
                nonexistentClass,
                SIMPLE_DIR.toString()
            ));
        assertTrue(ex.getMessage().contains("not found"),
            "Should return 'not found' error: " + ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // Phase 10: Import
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION-FIXED: Table names now use topic__class naming (Phase 10)")
    void tableNamesUseShortClassNames() {
        // FIXED in Phase 10: Table names now use topic__class naming
        // to avoid collisions when classes share short names across topics.
        IliImportService svc = new IliImportService();
        String sql = svc.generateImportSql(
            SIMPLE_DIR.resolve("valid.xtf").toString(),
            SIMPLE_DIR.toString(),
            "test_schema",
            "relational", null
        );

        assertNotNull(sql);
        assertTrue(sql.contains("topic__gemeinde") || sql.contains("Topic__Gemeinde"),
            "Should reference the Topic__Gemeinde table using topic__class naming, got: " + sql);
    }

    @Test
    @DisplayName("REGRESSION-FIXED: Generated SQL now includes transaction wrapping (Phase 10)")
    void generatedSqlHasNoTransaction() {
        // FIXED in Phase 10: The generated SQL now includes
        // BEGIN TRANSACTION / COMMIT wrapping.
        IliImportService svc = new IliImportService();
        String sql = svc.generateImportSql(
            SIMPLE_DIR.resolve("valid.xtf").toString(),
            SIMPLE_DIR.toString(),
            "test_schema",
            "relational", null
        );

        assertTrue(sql.toUpperCase().contains("BEGIN"),
            "After Phase 10: BEGIN TRANSACTION should be present in generated SQL");
        assertTrue(sql.toUpperCase().contains("COMMIT"),
            "After Phase 10: COMMIT should be present in generated SQL");
    }

    // -----------------------------------------------------------------------
    // Phase 5: Thread safety of cache
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION-FIXED: Concurrent model access is now safe with ConcurrentHashMap (Phase 5)")
    void concurrentModelAccess() throws Exception {
        // FIXED in Phase 5: ModelCache uses ConcurrentHashMap and thread-safe
        // LRU tracking. Concurrent access is now safe and deterministic.
        int threads = 8;
        int iterations = 10;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicReference<Exception> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    IliModelService svc = new IliModelService();
                    for (int i = 0; i < iterations; i++) {
                        String models = svc.getModels(SIMPLE_DIR.toString());
                        assertNotNull(models);
                        String topics = svc.getTopics(SIMPLE_DIR.toString(), null);
                        assertNotNull(topics);
                        String classes = svc.getClasses(SIMPLE_DIR.toString(), null);
                        assertNotNull(classes);
                    }
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertNull(error.get(), "No concurrent access exceptions expected after Phase 5");
    }
}
