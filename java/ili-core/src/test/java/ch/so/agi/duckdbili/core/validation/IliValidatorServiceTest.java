package ch.so.agi.duckdbili.core.validation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
    void fileNotFoundProducesError() {
        Path xtfFile = TESTDATA.resolve("does_not_exist.xtf");
        String modelDir = TESTDATA.toAbsolutePath().toString();

        ValidationResult result = service.validate(xtfFile, modelDir);

        assertFalse(result.isValid());
        assertEquals(1, result.getErrorCount());
        assertTrue(result.getMessages().get(0).getMessage().contains("not found"));
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
        assertEquals(ValidationProfile.FULL, ValidationProfile.fromString("unknown"));
    }

    @Test
    void profileCaseInsensitive() {
        assertEquals(ValidationProfile.FULL, ValidationProfile.fromString("FULL"));
        assertEquals(ValidationProfile.FAST, ValidationProfile.fromString("Fast"));
        assertEquals(ValidationProfile.STRUCTURAL, ValidationProfile.fromString("STRUCTURAL"));
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
}
