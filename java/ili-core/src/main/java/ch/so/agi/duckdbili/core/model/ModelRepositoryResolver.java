package ch.so.agi.duckdbili.core.model;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class ModelRepositoryResolver {

    private ModelRepositoryResolver() {
    }

    public static List<String> resolve(String modelDir, String defaultModelDir) {
        String effective = modelDir != null && !modelDir.isBlank()
                ? modelDir
                : defaultModelDir;

        LinkedHashSet<String> repositories = new LinkedHashSet<>();

        for (String part : splitSources(effective)) {
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

    /**
     * Returns explicit local INTERLIS files from the source list.
     *
     * A source list may contain repositories as well as individual .ili files.
     * Files are deliberately kept separate from local directories because the
     * compiler must not expand a directory when the caller selected one file.
     */
    public static List<Path> localFiles(String modelDir, String defaultModelDir) {
        List<Path> files = new ArrayList<>();

        for (String source : resolve(modelDir, defaultModelDir)) {
            if (isRemote(source)) continue;
            try {
                Path path = Path.of(source);
                if (Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ili")) {
                    files.add(path.toAbsolutePath().normalize());
                }
            } catch (InvalidPathException ignored) {
                // Validation is performed by the model service so that the
                // resulting error contains the offending source.
            }
        }

        return List.copyOf(files);
    }

    /**
     * Returns repository locations suitable for IliManager. For a direct
     * .ili source its parent directory is also a repository, allowing imports
     * next to the selected file to be resolved.
     */
    public static List<String> repositorySources(String modelDir, String defaultModelDir) {
        LinkedHashSet<String> repositories = new LinkedHashSet<>();

        for (String source : resolve(modelDir, defaultModelDir)) {
            if (isRemote(source)) {
                repositories.add(source);
                continue;
            }

            try {
                Path path = Path.of(source);
                if (Files.isDirectory(path)) {
                    repositories.add(path.toAbsolutePath().normalize().toString());
                } else if (Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ili")) {
                    Path parent = path.toAbsolutePath().normalize().getParent();
                    if (parent != null) repositories.add(parent.toString());
                }
            } catch (InvalidPathException ignored) {
                // Let the model service report the invalid source.
            }
        }

        return List.copyOf(repositories);
    }

    public static boolean isRemote(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }

    private static List<String> splitSources(String value) {
        if (value == null || value.isBlank()) return List.of();
        // Semicolon was the original separator. Comma is accepted as a more
        // natural SQL spelling for a list of model sources.
        return List.of(value.split("[;,]", -1));
    }
}
