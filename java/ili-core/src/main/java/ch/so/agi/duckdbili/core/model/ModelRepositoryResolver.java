package ch.so.agi.duckdbili.core.model;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class ModelRepositoryResolver {

    private ModelRepositoryResolver() {
    }

    public static List<String> resolve(String modelDir, String defaultModelDir) {
        String effective = modelDir != null && !modelDir.isBlank()
                ? modelDir
                : defaultModelDir;

        LinkedHashSet<String> repositories = new LinkedHashSet<>();

        for (String part : effective.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                repositories.add(trimmed);
            }
        }

        if (repositories.isEmpty()) {
            repositories.add(defaultModelDir);
        }

        return List.copyOf(repositories);
    }

    public static String resolveToString(String modelDir, String defaultModelDir) {
        return String.join(";", resolve(modelDir, defaultModelDir));
    }

    public static List<Path> localDirectories(String modelDir, String defaultModelDir) {
        List<Path> directories = new ArrayList<>();

        for (String repository : resolve(modelDir, defaultModelDir)) {
            if (repository.startsWith("http://") || repository.startsWith("https://")) {
                continue;
            }

            try {
                Path path = Path.of(repository);
                if (Files.isDirectory(path)) {
                    directories.add(path);
                }
            } catch (InvalidPathException ignored) {
                // Not interpretable as a local directory.
            }
        }

        return List.copyOf(directories);
    }
}
