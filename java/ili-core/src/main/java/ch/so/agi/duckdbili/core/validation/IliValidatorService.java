package ch.so.agi.duckdbili.core.validation;

import ch.ehi.basics.settings.Settings;
import ch.so.agi.duckdbili.core.logging.IliLogger;
import org.interlis2.validator.Validator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class IliValidatorService {

    private static final String DEFAULT_MODELDIR = System.getenv("ILI_DEFAULT_MODELDIR") != null
            ? System.getenv("ILI_DEFAULT_MODELDIR")
            : "https://models.interlis.ch";

    public ValidationResult validate(Path xtfFile, String modelDir) {
        return validate(xtfFile, modelDir, -1, ValidationProfile.FULL);
    }

    public ValidationResult validate(Path xtfFile, String modelDir, int maxMessages) {
        return validate(xtfFile, modelDir, maxMessages, ValidationProfile.FULL);
    }

    public ValidationResult validate(Path xtfFile, String modelDir, int maxMessages, ValidationProfile profile) {
        long startNanos = System.nanoTime();

        if (!Files.isRegularFile(xtfFile)) {
            throw ValidationExecutionException.forFileNotFound(xtfFile);
        }

        long xtfFileSize = -1;
        try { xtfFileSize = Files.size(xtfFile); } catch (IOException ignored) {}

        if (IliLogger.isDebugEnabled()) {
            System.err.println("[ili-debug] Validating: " + xtfFile + " (size=" + xtfFileSize + " bytes, profile=" + profile + ")");
        }

        String effectiveModelDir = resolveModelDir(modelDir, xtfFile);

        Path tempDir = null;
        Path csvLog = null;
        try {
            tempDir = Files.createTempDirectory("ilival-");
            csvLog = tempDir.resolve("log.csv");
        } catch (IOException e) {
            throw ValidationExecutionException.forTempDirFailure(e);
        }

        try {
            Settings settings = new Settings();
            settings.setValue(Validator.SETTING_ILIDIRS, effectiveModelDir);
            settings.setValue(Validator.SETTING_CSVLOG, csvLog.toAbsolutePath().toString());
            settings.setValue(Validator.SETTING_FORCE_TYPE_VALIDATION, Validator.TRUE);
            settings.setValue(Validator.SETTING_ALL_OBJECTS_ACCESSIBLE,
                    profile.isAllObjectsAccessible() ? Validator.TRUE : Validator.FALSE);

            if (profile.isMultiplicityValidationEnabled()) {
                settings.setValue(Validator.SETTING_MULTIPLICITY_VALIDATION, Validator.TRUE);
            } else {
                settings.setValue(Validator.SETTING_MULTIPLICITY_VALIDATION, Validator.FALSE);
            }

            if (!profile.isConstraintValidationEnabled()) {
                settings.setValue(Validator.SETTING_DISABLE_CONSTRAINT_VALIDATION, Validator.TRUE);
            }
            if (!profile.isAreaValidationEnabled()) {
                settings.setValue(Validator.SETTING_DISABLE_AREA_VALIDATION, Validator.TRUE);
            }

            Validator validator = new Validator();
            IliLogger.suppress();
            try {
                validator.validate(new String[]{xtfFile.toAbsolutePath().toString()}, settings);
            } finally {
                IliLogger.restore();
            }

            ValidationResult result = parseCsv(csvLog, xtfFile, maxMessages);

            if (IliLogger.isDebugEnabled()) {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                System.err.println("[ili-debug] Validation completed: " + durationMs + " ms, "
                    + result.getMessages().size() + " messages (file=" + xtfFileSize + " bytes)");
            }

            return result;

        } catch (ValidationExecutionException | ValidationOutputException e) {
            throw e;
        } catch (Exception e) {
            throw ValidationExecutionException.forValidatorException(e, xtfFile);
        } finally {
            deleteRecursive(tempDir);
        }
    }

    private static void deleteRecursive(Path path) {
        if (path == null) return;
        try {
            try (var stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> {
                          try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                      });
            }
        } catch (IOException ignored) {}
    }

    private ValidationResult parseCsv(Path csvLog, Path xtfFile, int maxMessages) {
        List<ValidationMessage> messages = new ArrayList<>();

        if (!Files.isRegularFile(csvLog)) {
            throw ValidationOutputException.forMissingCsvLog(csvLog);
        }

        try (BufferedReader reader = Files.newBufferedReader(csvLog, StandardCharsets.UTF_8)) {
            String line;
            boolean first = true;
            int rowIdx = 0;

            while ((line = reader.readLine()) != null) {
                rowIdx++;
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '\uFEFF') line = line.substring(1);
                if (first) { first = false; continue; }

                if (maxMessages > 0 && messages.size() >= maxMessages) break;

                List<String> fields = parseCsvLine(line);
                if (fields.size() < 2) continue;

                String message = fields.get(0);
                String type = fields.get(1);
                String iliQName = fields.size() > 2 ? fields.get(2) : "";
                String tid = fields.size() > 3 ? fields.get(3) : "";
                String dataSource = fields.size() > 7 ? fields.get(7) : "";
                String lineStr = fields.size() > 8 ? fields.get(8) : "";

                String severity = type.equalsIgnoreCase("Error") ? "ERROR"
                        : type.equalsIgnoreCase("Warning") ? "WARNING" : "INFO";

                Integer csvLineNo = null;
                if (!lineStr.isBlank()) {
                    try { csvLineNo = Integer.parseInt(lineStr.trim()); }
                    catch (NumberFormatException ignored) {}
                }

                String model = null, topic = null, className = null, attrName = null;
                if (!iliQName.isBlank()) {
                    String[] parts = iliQName.split("\\.");
                    if (parts.length >= 1) model = parts[0];
                    if (parts.length >= 2) topic = parts[1];
                    if (parts.length >= 3) className = parts[2];
                    if (parts.length >= 4) attrName = parts[3];
                }

                messages.add(new ValidationMessage.Builder()
                        .severity(severity)
                        .code("")
                        .message(message)
                        .fileName(dataSource.isBlank() ? xtfFile.toString() : dataSource)
                        .line(csvLineNo)
                        .xtfTid(tid.isBlank() ? null : tid)
                        .model(model)
                        .topic(topic)
                        .className(className)
                        .attributeName(attrName)
                        .raw(line)
                        .build());
            }
        } catch (IOException e) {
            throw ValidationOutputException.forReadError(csvLog, e);
        }

        return new ValidationResult(messages);
    }

    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int len = line.length();

        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    if (field.length() == 0) {
                        inQuotes = true;
                    } else {
                        field.append(c);
                    }
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static String resolveModelDir(String modelDir, Path xtfFile) {
        if (modelDir != null && !modelDir.isBlank()) return modelDir;
        String xtfDir = "";
        try { xtfDir = xtfFile.getParent().toString(); } catch (Exception ignored) {}
        if (!xtfDir.isBlank()) return xtfDir + ";" + DEFAULT_MODELDIR;
        return DEFAULT_MODELDIR;
    }
}
