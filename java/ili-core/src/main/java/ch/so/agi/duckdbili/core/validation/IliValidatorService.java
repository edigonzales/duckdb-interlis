package ch.so.agi.duckdbili.core.validation;

import ch.ehi.basics.settings.Settings;
import org.interlis2.validator.Validator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class IliValidatorService {

    public ValidationResult validate(Path xtfFile, String modelDir) {
        return validate(xtfFile, modelDir, -1);
    }

    public ValidationResult validate(Path xtfFile, String modelDir, int maxMessages) {
        if (!Files.isRegularFile(xtfFile)) {
            return new ValidationResult(List.of(
                new ValidationMessage.Builder()
                    .severity("ERROR")
                    .message("File not found: " + xtfFile)
                    .fileName(xtfFile.toString())
                    .build()));
        }

        Path csvLog = null;
        try {
            csvLog = Files.createTempFile("ilival-", ".csv");
        } catch (IOException e) {
            return new ValidationResult(List.of(
                new ValidationMessage.Builder()
                    .severity("ERROR")
                    .message("Failed to create temp file: " + e.getMessage())
                    .build()));
        }

        try {
            Settings settings = new Settings();
            settings.setValue(Validator.SETTING_ILIDIRS, modelDir);
            settings.setValue(Validator.SETTING_CSVLOG, csvLog.toAbsolutePath().toString());
            settings.setValue(Validator.SETTING_FORCE_TYPE_VALIDATION, Validator.TRUE);
            settings.setValue(Validator.SETTING_MULTIPLICITY_VALIDATION, Validator.TRUE);
            settings.setValue(Validator.SETTING_ALL_OBJECTS_ACCESSIBLE, Validator.TRUE);
            settings.setValue(Validator.SETTING_DISABLE_AREA_VALIDATION, Validator.TRUE);
            settings.setValue(Validator.SETTING_DISABLE_CONSTRAINT_VALIDATION, Validator.TRUE);

            if (maxMessages > 0) {
                settings.setValue("org.interlis2.validator.maxMessages", String.valueOf(maxMessages));
            }

            Validator validator = new Validator();
            validator.validate(new String[]{xtfFile.toAbsolutePath().toString()}, settings);

            return parseCsv(csvLog, xtfFile);

        } catch (Exception e) {
            return new ValidationResult(List.of(
                new ValidationMessage.Builder()
                    .severity("ERROR")
                    .message("Validation error: " + e.getMessage())
                    .fileName(xtfFile.toString())
                    .raw(e.toString())
                    .build()));
        } finally {
            try { Files.deleteIfExists(csvLog); } catch (IOException ignored) {}
        }
    }

    private ValidationResult parseCsv(Path csvLog, Path xtfFile) {
        List<ValidationMessage> messages = new ArrayList<>();

        if (!Files.isRegularFile(csvLog)) {
            return new ValidationResult(messages);
        }

        try {
            List<String> lines = Files.readAllLines(csvLog, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return new ValidationResult(messages);

            boolean first = true;
            int rowIdx = 0;
            for (String line : lines) {
                rowIdx++;
                if (line.startsWith("\uFEFF")) line = line.substring(1);
                if (first) { first = false; continue; }

                String[] fields = line.split(",", -1);
                if (fields.length < 2) continue;

                String message = fields[0];
                String type = fields[1];
                String tid = fields.length > 3 ? fields[3] : "";
                String iliQName = fields.length > 6 ? fields[6] : "";
                String dataSource = fields.length > 7 ? fields[7] : "";
                String lineStr = fields.length > 8 ? fields[8] : "";

                // Keep all non-empty messages; correct severity based on Type field
                String severity = type.equalsIgnoreCase("Error") ? "ERROR"
                        : type.equalsIgnoreCase("Warning") ? "WARNING" : "INFO";

                Integer csvLine = null;
                if (!lineStr.isBlank()) {
                    try { csvLine = Integer.parseInt(lineStr.trim()); }
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
                        .message(message)
                        .fileName(dataSource.isBlank() ? xtfFile.toString() : dataSource)
                        .line(csvLine)
                        .xtfTid(tid.isBlank() ? null : tid)
                        .model(model)
                        .topic(topic)
                        .className(className)
                        .attributeName(attrName)
                        .raw(line)
                        .build());
            }
        } catch (IOException e) {
            // non-fatal
        }

        return new ValidationResult(messages);
    }
}
