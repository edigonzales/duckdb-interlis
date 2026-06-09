package ch.so.agi.duckdbili.core.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ValidationResult {
    private final boolean valid;
    private final int errorCount;
    private final int warningCount;
    private final int infoCount;
    private final List<ValidationMessage> messages;

    public ValidationResult(List<ValidationMessage> messages) {
        this(messages, countMessages(messages));
    }

    public ValidationResult(List<ValidationMessage> messages, int errorCount, int warningCount, int infoCount) {
        this.messages = List.copyOf(messages);
        this.errorCount = errorCount;
        this.warningCount = warningCount;
        this.infoCount = infoCount;
        this.valid = errorCount == 0;
    }

    private ValidationResult(List<ValidationMessage> messages, Counts counts) {
        this(messages, counts.errors(), counts.warnings(), counts.infos());
    }

    private record Counts(int errors, int warnings, int infos) {}

    private static Counts countMessages(List<ValidationMessage> messages) {
        int errors = 0;
        int warnings = 0;
        int infos = 0;

        for (ValidationMessage message : messages) {
            if (message.getSeverity() == null) {
                continue;
            }

            switch (message.getSeverity().toUpperCase(Locale.ROOT)) {
                case "ERROR" -> errors++;
                case "WARNING" -> warnings++;
                case "INFO" -> infos++;
                default -> {
                }
            }
        }

        return new Counts(errors, warnings, infos);
    }

    public boolean isValid() { return valid; }
    public int getErrorCount() { return errorCount; }
    public int getWarningCount() { return warningCount; }
    public int getInfoCount() { return infoCount; }
    public List<ValidationMessage> getMessages() { return messages; }
}
