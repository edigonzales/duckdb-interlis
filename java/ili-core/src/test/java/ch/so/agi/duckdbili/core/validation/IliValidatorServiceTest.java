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
}
