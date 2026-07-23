package ch.so.agi.duckdbili.core.model;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class ModelRepositoryResolver {

    public enum SourceKind {
        REMOTE_URL,
        LOCAL_DIRECTORY,
        LOCAL_ILI_FILE,
        INVALID
    }

    private ModelRepositoryResolver() {
    }

    public static List<String> resolve(String modelSources, String defaultModelDir) {
        String effective = modelSources != null && !modelSources.isBlank()
                ? modelSources
                : defaultModelDir;

        LinkedHashSet<String> repositories = new LinkedHashSet<>();

        for (String part : splitSources(effective)) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                repositories.add(normalizeSource(trimmed));
            }
        }

        if (repositories.isEmpty()) {
            repositories.add(defaultModelDir);
        }

        return List.copyOf(repositories);
    }

    public static String resolveToString(String modelSources, String defaultModelDir) {
        return String.join(";", resolve(modelSources, defaultModelDir));
    }

    public static List<Path> localDirectories(String modelSources, String defaultModelDir) {
        List<Path> directories = new ArrayList<>();

        for (String repository : resolve(modelSources, defaultModelDir)) {
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
    public static List<Path> localFiles(String modelSources, String defaultModelDir) {
        List<Path> files = new ArrayList<>();

        for (String source : resolve(modelSources, defaultModelDir)) {
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
    public static List<String> repositorySources(String modelSources, String defaultModelDir) {
        LinkedHashSet<String> repositories = new LinkedHashSet<>();

        for (String source : resolve(modelSources, defaultModelDir)) {
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

    /**
     * Checks local entries before a compiler or validator is invoked. Remote
     * repositories are intentionally not probed here; the underlying
     * repository client owns their availability and authentication errors.
     */
    public static void validateSources(String modelSources, String defaultModelDir) {
        for (String source : resolve(modelSources, defaultModelDir)) {
            SourceKind kind = classify(source);
            if (kind == SourceKind.INVALID) {
                throw new IllegalArgumentException("INTERLIS model source not found or unsupported: " + source);
            }
        }
    }

    public static SourceKind classify(String source) {
        if (source == null || source.isBlank()) return SourceKind.INVALID;
        if (isRemote(source.trim())) return SourceKind.REMOTE_URL;
        try {
            Path path = Path.of(source.trim());
            if (Files.isDirectory(path)) return SourceKind.LOCAL_DIRECTORY;
            if (Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ili")) {
                return SourceKind.LOCAL_ILI_FILE;
            }
            return SourceKind.INVALID;
        } catch (InvalidPathException e) {
            return SourceKind.INVALID;
        }
    }

    public static boolean isRemote(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }

    private static String normalizeSource(String source) {
        if (isRemote(source)) return source;
        try {
            return Path.of(source).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException ignored) {
            return source;
        }
    }

    /**
     * Splits a user-facing list parameter. This is also used for the plural
     * XTF model filter, so both comma and semicolon have exactly the same
     * semantics everywhere in the SQL API.
     */
    public static List<String> splitList(String value) {
        List<String> result = new ArrayList<>();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String part : splitSources(value)) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) unique.add(trimmed);
        }
        result.addAll(unique);
        return List.copyOf(result);
    }

    private static List<String> splitSources(String value) {
        if (value == null || value.isBlank()) return List.of();
        // Semicolon was the original separator. Comma is accepted as a more
        // natural SQL spelling for a list of model sources.
        return List.of(value.split("[;,]", -1));
    }
}
