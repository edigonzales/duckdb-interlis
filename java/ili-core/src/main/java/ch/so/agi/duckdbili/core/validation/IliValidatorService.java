package ch.so.agi.duckdbili.core.validation;

import ch.ehi.basics.settings.Settings;
import ch.so.agi.duckdbili.core.logging.IliLogger;
import org.interlis2.validator.Validator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
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

    private static final int REQUIRED_CSV_COLUMNS = 2;

    ValidationResult parseCsv(Path csvLog, Path xtfFile, int maxMessages) {
        List<ValidationMessage> messages = new ArrayList<>();

        int totalErrors = 0;
        int totalWarnings = 0;
        int totalInfos = 0;

        if (!Files.isRegularFile(csvLog)) {
            throw ValidationOutputException.forMissingCsvLog(csvLog);
        }

        try (BufferedReader reader = Files.newBufferedReader(csvLog, StandardCharsets.UTF_8)) {
            StringBuilder record = new StringBuilder();
            boolean first = true;
            long recordNumber = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                // Strip UTF-8 BOM if present
                if (record.length() == 0 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }

                record.append(line);
                // Check if we have a complete CSV record (quotes are balanced)
                if (!isRecordComplete(record)) {
                    record.append('\n');
                    continue;
                }

                recordNumber++;
                String recordText = record.toString();
                record.setLength(0);

                if (recordText.isEmpty()) continue;

                // Skip header row
                if (first) { first = false; continue; }

                List<String> fields = parseCsvFields(recordText);
                if (fields.size() < REQUIRED_CSV_COLUMNS) {
                    throw ValidationOutputException.forMalformedCsv(csvLog, recordNumber,
                            "Expected at least " + REQUIRED_CSV_COLUMNS + " columns, got " + fields.size());
                }

                boolean retainMessage = maxMessages <= 0 || messages.size() < maxMessages;

                String message = fields.get(0);
                String type = fields.get(1);
                String iliQName = fields.size() > 2 ? fields.get(2) : "";
                String tid = fields.size() > 3 ? fields.get(3) : "";
                String dataSource = fields.size() > 7 ? fields.get(7) : "";
                String lineStr = fields.size() > 8 ? fields.get(8) : "";

                String severity = type.equalsIgnoreCase("Error") ? "ERROR"
                        : type.equalsIgnoreCase("Warning") ? "WARNING" : "INFO";

                switch (severity) {
                    case "ERROR" -> totalErrors++;
                    case "WARNING" -> totalWarnings++;
                    case "INFO" -> totalInfos++;
                    default -> throw ValidationOutputException.forMalformedCsv(csvLog, recordNumber,
                            "Unknown severity/type: " + type);
                }

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

                if (retainMessage) {
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
                            .raw(recordText)
                            .build());
                }
            }

            // Handle any trailing incomplete record as an error
            if (record.length() > 0) {
                throw ValidationOutputException.forMalformedCsv(csvLog, recordNumber + 1,
                        "Unterminated quoted field at end of CSV log");
            }
        } catch (IOException e) {
            throw ValidationOutputException.forReadError(csvLog, e);
        }

        return new ValidationResult(messages, totalErrors, totalWarnings, totalInfos);
    }

    // Check whether a CSV record has balanced double-quotes.
    // A record is complete when the number of non-escaped double-quotes is even.
    private static boolean isRecordComplete(StringBuilder record) {
        int len = record.length();
        boolean inQuote = false;
        for (int i = 0; i < len; i++) {
            char c = record.charAt(i);
            if (c == '"') {
                if (inQuote && i + 1 < len && record.charAt(i + 1) == '"') {
                    i++; // escaped quote
                } else {
                    inQuote = !inQuote;
                }
            }
        }
        return !inQuote;
    }

    // Parse a single CSV record into fields.
    // Handles quoted fields (with escaped quotes "") and empty fields.
    static List<String> parseCsvFields(String record) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int len = record.length();

        for (int i = 0; i < len; i++) {
            char c = record.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && record.charAt(i + 1) == '"') {
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
