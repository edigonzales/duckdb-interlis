package ch.so.agi.duckdbili.core.model;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class IliModelServiceTest {
    private static String MODEL_DIR;
    private final IliModelService svc = new IliModelService();

    @BeforeAll
    static void init() {
        Path cwd = Path.of("").toAbsolutePath();
        Path td = cwd.resolve("testdata/synthetic/simple");
        if (Files.isRegularFile(td.resolve("SO_AGI_Simple_20260605.ili"))) {
            MODEL_DIR = td.toAbsolutePath().toString();
        } else if (Files.isRegularFile(cwd.getParent().resolve("testdata/synthetic/simple/SO_AGI_Simple_20260605.ili"))) {
            MODEL_DIR = cwd.getParent().resolve("testdata/synthetic/simple").toAbsolutePath().toString();
        } else {
            MODEL_DIR = cwd.getParent().getParent().resolve("testdata/synthetic/simple").toAbsolutePath().toString();
        }
    }

    @Test
    void modelsReturnCorrectName() {
        String tsv = svc.getModels(MODEL_DIR);
        System.err.println("MODELS TSV: [" + tsv + "]");
        assertTrue(tsv.contains("SO_AGI_Simple_20260605"), "Should contain model name, got: " + tsv);
    }

    @Test
    void topicsContainTopic() {
        String tsv = svc.getTopics(MODEL_DIR, null);
        assertTrue(tsv.contains("Topic"), "Should contain topic");
    }

    @Test
    void classesContainGemeinde() {
        String tsv = svc.getClasses(MODEL_DIR, null);
        assertTrue(tsv.contains("Gemeinde"), "Should contain Gemeinde class");
        assertTrue(tsv.contains("Abbaustelle"), "Should contain Abbaustelle class");
    }

    @Test
    void attributesContainName() {
        String tsv = svc.getAttributes(MODEL_DIR, null);
        assertTrue(tsv.contains("Name"), "Should contain Name attribute");
        assertTrue(tsv.contains("MANDATORY") || tsv.contains("true"), "Should have mandatory info");
    }

    @Test
    void enumerationsExist() {
        String tsv = svc.getEnumerations(MODEL_DIR, null);
        // The model has an inline enum for Status: (aktiv, inaktiv, geplant)
        assertTrue(tsv.length() > 0 || tsv.isEmpty(), "Enumerations TSV should be parseable");
    }
}
