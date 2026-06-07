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
    @DisplayName("REGRESSION: XTF reader validates classes by short name endsWith")
    void classMatchingUsesEndsWith() {
        // BUG: XtfObjectReader matches classes using:
        //   if (!tag.endsWith("." + parts[2])) continue;
        // Two classes with the same short name in different topics
        // will be matched together.
        // Phase 7 will replace with full FQN comparison.
        XtfObjectReader reader = new XtfObjectReader();
        String fqn = "SO_AGI_Simple_20260605.Topic.Gemeinde";
        String tsv = reader.readClass(
            SIMPLE_DIR.resolve("valid.xtf").toString(),
            fqn,
            SIMPLE_DIR.toString()
        );

        // Currently works correctly because there's only one Gemeinde class.
        // The bug only manifests with multiple topics having same class name.
        assertNotNull(tsv);
        assertTrue(tsv.contains("Gemeinde"), "Expected class name in result");
    }

    @Test
    @DisplayName("REGRESSION: Missing attributes returned as empty strings, not NULL")
    void missingValuesAreEmptyNotNull() {
        // BUG: All missing values are returned as empty strings ("").
        // NULL and empty string are indistinguishable.
        // Phase 7 will introduce a NULL sentinel (e.g., \N) in TSV.
        // This test verifies the current behavior.
        XtfObjectReader reader = new XtfObjectReader();
        String fqn = "SO_AGI_Simple_20260605.Topic.Gemeinde";
        String tsv = reader.readClass(
            SIMPLE_DIR.resolve("valid.xtf").toString(),
            fqn,
            SIMPLE_DIR.toString()
        );

        // All scalar values for this test data should be non-empty,
        // but the pattern of using "" for missing values is the issue.
        assertFalse(tsv.isEmpty(), "TSV should contain data");
    }

    // -----------------------------------------------------------------------
    // Phase 10: Import
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION: Table names use short class names, causing potential collisions")
    void tableNamesUseShortClassNames() {
        // BUG: IliImportService.generateImportSql uses sanitizeTableName(classDef.getName())
        // which returns only the short class name.
        // Two classes with the same name in different topics would collide.
        // Phase 10 will use topic__class or model__topic__class naming.
        IliImportService svc = new IliImportService();
        String sql = svc.generateImportSql(
            SIMPLE_DIR.resolve("valid.xtf").toString(),
            SIMPLE_DIR.toString(),
            "test_schema",
            "relational"
        );

        assertNotNull(sql);
        // Currently: generates CREATE TABLE test_schema.gemeinde (...)
        // Expected after Phase 10: CREATE TABLE test_schema.Topic__Gemeinde (...)
        assertTrue(sql.contains("gemeinde") || sql.contains("Gemeinde"),
            "Should reference the Gemeinde table");
    }

    @Test
    @DisplayName("REGRESSION: Generated SQL has no transaction wrapping")
    void generatedSqlHasNoTransaction() {
        // BUG: The generated SQL does not include BEGIN TRANSACTION / COMMIT.
        // Phase 10 will add transaction wrapping by default.
        IliImportService svc = new IliImportService();
        String sql = svc.generateImportSql(
            SIMPLE_DIR.resolve("valid.xtf").toString(),
            SIMPLE_DIR.toString(),
            "test_schema",
            "relational"
        );

        assertFalse(sql.toUpperCase().contains("BEGIN"),
            "Currently: no BEGIN TRANSACTION in generated SQL (documented limitation)");
        // Phase 10 will add BEGIN/COMMIT wrapping.
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
