package ch.so.agi.duckdbili.core.validation;

import ch.ehi.basics.logging.EhiLogger;
import ch.ehi.basics.logging.LogEvent;
import ch.ehi.basics.logging.LogListener;
import ch.ehi.basics.settings.Settings;
import org.interlis2.validator.Validator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        List<ValidationMessage> messages = new ArrayList<>();
        LogListener listener = event -> messages.add(convertEvent(event, xtfFile));

        EhiLogger logger = EhiLogger.getInstance();
        logger.addListener(listener);

        try {
            Settings settings = new Settings();
            settings.setValue(Validator.SETTING_ILIDIRS, modelDir);
            settings.setValue(Validator.SETTING_FORCE_TYPE_VALIDATION, Validator.TRUE);
            settings.setValue(Validator.SETTING_MULTIPLICITY_VALIDATION, Validator.TRUE);
            settings.setValue(Validator.SETTING_ALL_OBJECTS_ACCESSIBLE, Validator.TRUE);

            if (maxMessages > 0) {
                settings.setValue("org.interlis2.validator.maxMessages", String.valueOf(maxMessages));
            }

            Validator validator = new Validator();
            validator.validate(new String[]{xtfFile.toAbsolutePath().toString()}, settings);

            // Keep ERROR/WARNING messages, plus meaningful INFO messages
            List<ValidationMessage> filtered = new ArrayList<>();
            for (var m : messages) {
                if ("ERROR".equals(m.getSeverity()) || "WARNING".equals(m.getSeverity())) {
                    filtered.add(m);
                    continue;
                }
                // Include info messages with structured data or result summaries
                String msg = m.getMessage();
                if (msg != null && !msg.isBlank()) {
                    if (msg.contains("objects in CLASS") || msg.contains("validation done")
                            || msg.contains("validation failed") || msg.contains("object count")) {
                        filtered.add(m);
                    }
                }
            }
            return new ValidationResult(filtered);

        } catch (Exception e) {
            return new ValidationResult(List.of(
                new ValidationMessage.Builder()
                    .severity("ERROR")
                    .message("Validation error: " + e.getMessage())
                    .fileName(xtfFile.toString())
                    .raw(e.toString())
                    .build()));
        } finally {
            logger.removeListener(listener);
        }
    }

    // Pattern: "line 10: SO_AGI_Simple_20260605.Topic.Gemeinde: tid 1: message text"
    private static final Pattern VALIDATION_MSG = Pattern.compile(
            "line\\s+(\\d+)\\s*:\\s*(([\\w.]+)\\s*:\\s*)?(tid\\s+(\\S+)\\s*:\\s*)?(.*)");

    private static final Pattern ERROR_PREFIX = Pattern.compile("(?i)(Error|Warning|Info)\\s*:\\s*(.*)");

    private ValidationMessage convertEvent(LogEvent event, Path xtfFile) {
        String raw = event.getEventMsg();
        String severity = switch (event.getEventKind()) {
            case LogEvent.ERROR -> "ERROR";
            default -> "INFO";
        };

        String fileName = xtfFile.toString();
        Integer lineNum = null;
        String iliQName = null;
        String tid = null;
        String message = raw;

        // Strip "Error: ", "Warning: ", "Info: " prefix if present
        Matcher prefixMatcher = ERROR_PREFIX.matcher(raw);
        if (prefixMatcher.matches()) {
            severity = prefixMatcher.group(1).toUpperCase();
            raw = prefixMatcher.group(2).trim();
            message = raw;
        }

        Matcher m = VALIDATION_MSG.matcher(raw);
        if (m.matches()) {
            if (m.group(1) != null) lineNum = Integer.parseInt(m.group(1));
            if (m.group(3) != null) iliQName = m.group(3);
            if (m.group(5) != null) tid = m.group(5);
            if (m.group(6) != null) message = m.group(6).trim();
        }

        String model = null, topic = null, className = null, attrName = null;
        if (iliQName != null && !iliQName.isBlank()) {
            String[] parts = iliQName.split("\\.");
            if (parts.length >= 1) model = parts[0];
            if (parts.length >= 2) topic = parts[1];
            if (parts.length >= 3) className = parts[2];
            if (parts.length >= 4) attrName = parts[3];
        }

        return new ValidationMessage.Builder()
                .severity(severity)
                .message(message)
                .fileName(fileName)
                .line(lineNum)
                .xtfTid(blankToNull(tid))
                .model(blankToNull(model))
                .topic(blankToNull(topic))
                .className(blankToNull(className))
                .attributeName(blankToNull(attrName))
                .raw(raw)
                .build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
