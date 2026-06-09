package ch.so.agi.duckdbili.core.validation;

import ch.so.agi.duckdbili.core.NativeStatus;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IliValidatorServiceTest {

    private static Path REPO_ROOT;
    private static Path TESTDATA;

    private final IliValidatorService service = new IliValidatorService();

    @BeforeAll
    static void resolvePaths() {
        // JUnit runs from the subproject directory; navigate up to repo root
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("testdata/synthetic/simple/valid.xtf"))) {
            REPO_ROOT = cwd;
        } else if (Files.isRegularFile(cwd.getParent().resolve("testdata/synthetic/simple/valid.xtf"))) {
            REPO_ROOT = cwd.getParent();
        } else if (Files.isRegularFile(cwd.getParent().getParent().resolve("testdata/synthetic/simple/valid.xtf"))) {
            REPO_ROOT = cwd.getParent().getParent();
        } else {
            // fallback: try absolute
            REPO_ROOT = Path.of(System.getProperty("user.dir"));
        }
        TESTDATA = REPO_ROOT.resolve("testdata/synthetic/simple");
    }

    @Test
    void validFileProducesNoErrors() {
        Path xtfFile = TESTDATA.resolve("valid.xtf");
        String modelDir = TESTDATA.toAbsolutePath().toString();

        ValidationResult result = service.validate(xtfFile, modelDir);

        assertTrue(result.isValid(), "Expected valid XTF to produce no errors, got " + result.getErrorCount() + " errors");
        assertEquals(0, result.getErrorCount(), "Expected 0 errors");
    }

    @Test
    void invalidFileProducesErrors() {
        Path xtfFile = TESTDATA.resolve("invalid.xtf");
        String modelDir = TESTDATA.toAbsolutePath().toString();

        ValidationResult result = service.validate(xtfFile, modelDir);

        assertFalse(result.isValid(), "Expected invalid XTF to produce errors, got " + result.getErrorCount() + " errors");
        assertTrue(result.getErrorCount() > 0, "Expected at least one error, got " + result.getErrorCount());
    }

    @Test
    void messagesHaveRequiredFields() {
        Path xtfFile = TESTDATA.resolve("invalid.xtf");
        String modelDir = TESTDATA.toAbsolutePath().toString();

        ValidationResult result = service.validate(xtfFile, modelDir);

        for (ValidationMessage msg : result.getMessages()) {
            assertNotNull(msg.getSeverity(), "severity must not be null");
            assertNotNull(msg.getMessage(), "message must not be null");
        }
    }

    @Test
    void fileNotFoundThrowsException() {
        Path xtfFile = TESTDATA.resolve("does_not_exist.xtf");
        String modelDir = TESTDATA.toAbsolutePath().toString();

        ValidationExecutionException ex = assertThrows(ValidationExecutionException.class,
                () -> service.validate(xtfFile, modelDir));
        assertEquals(NativeStatus.IO_ERROR, ex.nativeErrorCode());
        assertTrue(ex.getMessage().contains("not found"));
        assertEquals(xtfFile.toAbsolutePath().toString(), ex.path());
    }

    @Test
    void resultCountsAreCorrect() {
        Path xtfFile = TESTDATA.resolve("invalid.xtf");
        String modelDir = TESTDATA.toAbsolutePath().toString();

        ValidationResult result = service.validate(xtfFile, modelDir);

        int errors = 0, warnings = 0, infos = 0;
        for (var m : result.getMessages()) {
            switch (m.getSeverity()) {
                case "ERROR": errors++; break;
                case "WARNING": warnings++; break;
                case "INFO": infos++; break;
            }
        }
        assertEquals(errors, result.getErrorCount());
        assertEquals(warnings, result.getWarningCount());
        assertEquals(infos, result.getInfoCount());
    }

    // -----------------------------------------------------------------------
    // CSV parser tests
    // -----------------------------------------------------------------------

    @Test
    void csvParserSimpleLine() {
        var fields = IliValidatorService.parseCsvLine("a,b,c");
        assertEquals(3, fields.size());
        assertEquals("a", fields.get(0));
        assertEquals("b", fields.get(1));
        assertEquals("c", fields.get(2));
    }

    @Test
    void csvParserQuotedFieldComma() {
        var fields = IliValidatorService.parseCsvLine("\"a,b\",c,d");
        assertEquals(3, fields.size());
        assertEquals("a,b", fields.get(0));
        assertEquals("c", fields.get(1));
        assertEquals("d", fields.get(2));
    }

    @Test
    void csvParserEscapedQuotes() {
        var fields = IliValidatorService.parseCsvLine("\"a\"\"b\"\"\",c");
        assertEquals(2, fields.size());
        assertEquals("a\"b\"", fields.get(0));
        assertEquals("c", fields.get(1));
    }

    @Test
    void csvParserEmptyFields() {
        var fields = IliValidatorService.parseCsvLine("a,,c");
        assertEquals(3, fields.size());
        assertEquals("a", fields.get(0));
        assertEquals("", fields.get(1));
        assertEquals("c", fields.get(2));
    }

    @Test
    void csvParserQuotedEmptyField() {
        var fields = IliValidatorService.parseCsvLine("a,\"\",c");
        assertEquals(3, fields.size());
        assertEquals("a", fields.get(0));
        assertEquals("", fields.get(1));
        assertEquals("c", fields.get(2));
    }

    @Test
    void csvParserSingleQuoteInMiddle() {
        var fields = IliValidatorService.parseCsvLine("value 1\"5,c");
        assertEquals(2, fields.size());
        assertEquals("value 1\"5", fields.get(0));
        assertEquals("c", fields.get(1));
    }

    @Test
    void csvParserUnicode() {
        var fields = IliValidatorService.parseCsvLine("\"Höhe ü. M.\",äöü,中文,🎉");
        assertEquals(4, fields.size());
        assertEquals("Höhe ü. M.", fields.get(0));
        assertEquals("äöü", fields.get(1));
        assertEquals("中文", fields.get(2));
        assertEquals("🎉", fields.get(3));
    }

    @Test
    void csvParserTrailingEmptyFields() {
        var fields = IliValidatorService.parseCsvLine("a,b,c,");
        assertEquals(4, fields.size());
        assertEquals("a", fields.get(0));
        assertEquals("b", fields.get(1));
        assertEquals("c", fields.get(2));
        assertEquals("", fields.get(3));
    }

    // -----------------------------------------------------------------------
    // Validation profile tests
    // -----------------------------------------------------------------------

    @Test
    void defaultProfileIsFull() {
        assertEquals(ValidationProfile.FULL, ValidationProfile.fromString(null));
        assertEquals(ValidationProfile.FULL, ValidationProfile.fromString(""));
        assertEquals(ValidationProfile.FULL, ValidationProfile.fromString("  "));
    }

    @Test
    void profileFromString() {
        assertEquals(ValidationProfile.FULL, ValidationProfile.fromString("full"));
        assertEquals(ValidationProfile.STRUCTURAL, ValidationProfile.fromString("structural"));
        assertEquals(ValidationProfile.FAST, ValidationProfile.fromString("fast"));
        assertThrows(IllegalArgumentException.class, () -> ValidationProfile.fromString("unknown"));
    }

    @Test
    void profileCaseInsensitive() {
        assertEquals(ValidationProfile.FULL, ValidationProfile.fromString("FULL"));
        assertEquals(ValidationProfile.FAST, ValidationProfile.fromString("Fast"));
        assertEquals(ValidationProfile.STRUCTURAL, ValidationProfile.fromString("STRUCTURAL"));
    }

    @Test
    void unknownProfileThrowsException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ValidationProfile.fromString("garbage"));
        assertTrue(ex.getMessage().contains("garbage"));
    }

    @Test
    void fullProfileEnablesConstraints() {
        assertTrue(ValidationProfile.FULL.isConstraintValidationEnabled());
        assertTrue(ValidationProfile.FULL.isAreaValidationEnabled());
        assertTrue(ValidationProfile.FULL.isAllObjectsAccessible());
        assertTrue(ValidationProfile.FULL.isMultiplicityValidationEnabled());
    }

    @Test
    void fastProfileMinimalChecks() {
        assertFalse(ValidationProfile.FAST.isConstraintValidationEnabled());
        assertFalse(ValidationProfile.FAST.isAreaValidationEnabled());
        assertFalse(ValidationProfile.FAST.isAllObjectsAccessible());
        assertFalse(ValidationProfile.FAST.isMultiplicityValidationEnabled());
    }

    @Test
    void structuralProfileDisablesAreaOnly() {
        assertTrue(ValidationProfile.STRUCTURAL.isConstraintValidationEnabled());
        assertFalse(ValidationProfile.STRUCTURAL.isAreaValidationEnabled());
        assertTrue(ValidationProfile.STRUCTURAL.isAllObjectsAccessible());
        assertTrue(ValidationProfile.STRUCTURAL.isMultiplicityValidationEnabled());
    }

    @Test
    void validateWithFastProfileSucceeds() {
        Path xtfFile = TESTDATA.resolve("valid.xtf");
        String modelDir = TESTDATA.toAbsolutePath().toString();
        var service = new IliValidatorService();
        ValidationResult result = service.validate(xtfFile, modelDir, -1, ValidationProfile.FAST);
        assertNotNull(result);
        assertTrue(result.isValid());
    }

    @Test
    void maxMessagesLimitsOutput() {
        Path xtfFile = TESTDATA.resolve("invalid.xtf");
        String modelDir = TESTDATA.toAbsolutePath().toString();
        var service = new IliValidatorService();
        ValidationResult result = service.validate(xtfFile, modelDir, 1, ValidationProfile.FULL);
        // With maxMessages=1, at most 1 message should be returned
        // (ilivalidator may return 0 or 1 depending on early abort behavior)
        assertNotNull(result);
        assertTrue(result.getMessages().size() <= 1,
            "maxMessages=1 should limit to at most 1 message, got " + result.getMessages().size());
    }

    // -----------------------------------------------------------------------
    // ValidationExecutionException tests
    // -----------------------------------------------------------------------

    @Test
    void executionExceptionForFileNotFound() {
        Path file = Path.of("/nonexistent/file.xtf");
        ValidationExecutionException ex = ValidationExecutionException.forFileNotFound(file);

        assertEquals(NativeStatus.IO_ERROR, ex.nativeErrorCode());
        assertEquals(file.toAbsolutePath().toString(), ex.path());
        assertTrue(ex.getMessage().contains("File not found"));
        assertNull(ex.getCause());
    }

    @Test
    void executionExceptionForTempDirFailure() {
        IOException cause = new IOException("disk full");
        ValidationExecutionException ex = ValidationExecutionException.forTempDirFailure(cause);

        assertEquals(NativeStatus.IO_ERROR, ex.nativeErrorCode());
        assertSame(cause, ex.getCause());
        assertTrue(ex.getMessage().contains("disk full"));
        assertNull(ex.path());
    }

    @Test
    void executionExceptionForValidatorException() {
        RuntimeException cause = new RuntimeException("validator crash");
        Path xtf = Path.of("/data/test.xtf");
        ValidationExecutionException ex = ValidationExecutionException.forValidatorException(cause, xtf);

        assertEquals(NativeStatus.INTERNAL_ERROR, ex.nativeErrorCode());
        assertSame(cause, ex.getCause());
        assertEquals(xtf.toAbsolutePath().toString(), ex.path());
    }

    // -----------------------------------------------------------------------
    // ValidationOutputException tests
    // -----------------------------------------------------------------------

    @Test
    void outputExceptionForMissingCsvLog() {
        Path csvLog = Path.of("/tmp/missing.csv");
        ValidationOutputException ex = ValidationOutputException.forMissingCsvLog(csvLog);

        assertTrue(ex.getMessage().contains("CSV log not found"));
        assertEquals(csvLog.toAbsolutePath().toString(), ex.path());
    }

    @Test
    void outputExceptionForReadError() {
        Path csvLog = Path.of("/tmp/unreadable.csv");
        IOException cause = new IOException("permission denied");
        ValidationOutputException ex = ValidationOutputException.forReadError(csvLog, cause);

        assertTrue(ex.getMessage().contains("Failed to read CSV log"));
        assertSame(cause, ex.getCause());
        assertEquals(csvLog.toAbsolutePath().toString(), ex.path());
    }
}
