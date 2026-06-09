package ch.so.agi.duckdbili.core.validation;

import ch.so.agi.duckdbili.core.NativeStatus;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IliValidatorServiceTest {

    private static Path REPO_ROOT;
    private static Path TESTDATA;

    private final IliValidatorService service = new IliValidatorService();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void resolvePaths() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("testdata/synthetic/simple/valid.xtf"))) {
            REPO_ROOT = cwd;
        } else if (Files.isRegularFile(cwd.getParent().resolve("testdata/synthetic/simple/valid.xtf"))) {
            REPO_ROOT = cwd.getParent();
        } else if (Files.isRegularFile(cwd.getParent().getParent().resolve("testdata/synthetic/simple/valid.xtf"))) {
            REPO_ROOT = cwd.getParent().getParent();
        } else {
            REPO_ROOT = Path.of(System.getProperty("user.dir"));
        }
        TESTDATA = REPO_ROOT.resolve("testdata/synthetic/simple");
    }

    private Path writeCsv(String... lines) throws IOException {
        Path csv = tempDir.resolve("test.csv");
        Files.write(csv, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return csv;
    }

    private ValidationResult parseCsv(String... lines) {
        try {
            Path csv = writeCsv(lines);
            return service.parseCsv(csv, tempDir.resolve("test.xtf"), -1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
    // CSV parser tests (using Apache Commons CSV via parseCsv)
    // -----------------------------------------------------------------------

    @Test
    void csvParserSimpleLine() {
        ValidationResult result = parseCsv(
                "Message,Type,iliName,Tid,,,,DataSource,Line",
                "hello,Info,,,"
        );
        assertEquals(1, result.getInfoCount());
        assertEquals(0, result.getErrorCount());
        assertEquals("hello", result.getMessages().get(0).getMessage());
    }

    @Test
    void csvParserQuotedFieldComma() {
        ValidationResult result = parseCsv(
                "Message,Type",
                "\"hello, world\",Warning"
        );
        assertEquals(1, result.getWarningCount());
        assertEquals("hello, world", result.getMessages().get(0).getMessage());
    }

    @Test
    void csvParserEscapedQuotes() {
        ValidationResult result = parseCsv(
                "Message,Type",
                "\"a\"\"b\"\"\",Info"
        );
        assertEquals(1, result.getInfoCount());
        assertEquals("a\"b\"", result.getMessages().get(0).getMessage());
    }

    @Test
    void csvParserEmptyFields() {
        ValidationResult result = parseCsv(
                "Message,Type,Col3",
                "msg,Error,"
        );
        assertEquals(1, result.getErrorCount());
        assertEquals("msg", result.getMessages().get(0).getMessage());
    }

    @Test
    void csvParserQuotedEmptyField() {
        ValidationResult result = parseCsv(
                "Message,Type,Col3",
                "msg,Info,\"\""
        );
        assertEquals(1, result.getInfoCount());
    }

    @Test
    void csvParserUnicode() {
        ValidationResult result = parseCsv(
                "Message,Type",
                "\"Höhe ü. M.\",Info"
        );
        assertEquals(1, result.getInfoCount());
        assertEquals("Höhe ü. M.", result.getMessages().get(0).getMessage());
    }

    @Test
    void csvParserTrailingEmptyFields() {
        ValidationResult result = parseCsv(
                "Message,Type,Col3,Col4",
                "msg,Info,,"
        );
        assertEquals(1, result.getInfoCount());
        assertEquals("msg", result.getMessages().get(0).getMessage());
    }

    @Test
    void csvParserMultiLineField() {
        ValidationResult result = parseCsv(
                "Message,Type",
                "\"line1\nline2\",Error"
        );
        assertEquals(1, result.getErrorCount());
        assertEquals("line1\nline2", result.getMessages().get(0).getMessage());
    }

    @Test
    void csvParserUnclosedQuoteThrowsException() throws IOException {
        Path csv = writeCsv("Message,Type", "\"unclosed,Error");
        var xtfFile = tempDir.resolve("test.xtf");
        assertThrows(ValidationOutputException.class,
                () -> service.parseCsv(csv, xtfFile, -1));
    }

    @Test
    void csvParserTooFewColumnsThrowsException() throws IOException {
        Path csv = writeCsv("Message,Type", "only_one_column");
        var xtfFile = tempDir.resolve("test.xtf");
        assertThrows(ValidationOutputException.class,
                () -> service.parseCsv(csv, xtfFile, -1));
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

    @Test
    void maxMessagesDoesNotChangeValidity() {
        Path xtfFile = TESTDATA.resolve("invalid.xtf");
        String modelDir = TESTDATA.toAbsolutePath().toString();

        ValidationResult unlimited = service.validate(xtfFile, modelDir, -1, ValidationProfile.FULL);
        ValidationResult limited = service.validate(xtfFile, modelDir, 1, ValidationProfile.FULL);

        assertEquals(unlimited.isValid(), limited.isValid(),
                "validity must not change when max_messages limits output");
        assertFalse(limited.isValid(),
                "invalid file must remain invalid even when max_messages=1");
    }

    @Test
    void maxMessagesDoesNotChangeCounts() {
        Path xtfFile = TESTDATA.resolve("invalid.xtf");
        String modelDir = TESTDATA.toAbsolutePath().toString();

        ValidationResult unlimited = service.validate(xtfFile, modelDir, -1, ValidationProfile.FULL);
        ValidationResult limited = service.validate(xtfFile, modelDir, 1, ValidationProfile.FULL);

        assertEquals(unlimited.getErrorCount(), limited.getErrorCount(),
                "error count must be identical regardless of max_messages");
        assertEquals(unlimited.getWarningCount(), limited.getWarningCount(),
                "warning count must be identical regardless of max_messages");
        assertEquals(unlimited.getInfoCount(), limited.getInfoCount(),
                "info count must be identical regardless of max_messages");

        assertTrue(limited.getMessages().size() <= 1,
                "max_messages=1 should limit messages but not counts, got " + limited.getMessages().size());
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
