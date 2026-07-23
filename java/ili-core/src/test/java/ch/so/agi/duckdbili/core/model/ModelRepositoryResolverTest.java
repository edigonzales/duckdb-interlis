package ch.so.agi.duckdbili.core.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRepositoryResolverTest {

    @Test
    void acceptsCommaAndSemicolonSeparatedSources() throws Exception {
        Path dir = Files.createTempDirectory("ili-model-sources");
        Path file = Files.createFile(dir.resolve("model.ili"));

        var resolved = ModelRepositoryResolver.resolve(
                " " + dir + "," + file + ";" + dir + " ",
                "https://default.example/models");

        assertEquals(2, resolved.size());
        assertEquals(dir.toString(), resolved.get(0));
        assertEquals(file.toString(), resolved.get(1));
        assertEquals(1, ModelRepositoryResolver.localDirectories(
                String.join(";", resolved), "").size());
        assertEquals(file.toAbsolutePath().normalize(),
                ModelRepositoryResolver.localFiles(String.join(",", resolved), "").get(0));
    }

    @Test
    void directFileParentIsUsableAsRepository() throws Exception {
        Path dir = Files.createTempDirectory("ili-model-repository");
        Path file = Files.createFile(dir.resolve("model.ili"));

        var repositories = ModelRepositoryResolver.repositorySources(file.toString(), "");

        assertEquals(1, repositories.size());
        assertEquals(dir.toAbsolutePath().normalize().toString(), repositories.get(0));
    }

    @Test
    void blankSourcesUseDefault() {
        assertTrue(ModelRepositoryResolver.resolve("", "https://default.example/models")
                .contains("https://default.example/models"));
    }
}
