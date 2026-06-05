package ch.so.agi.duckdbili.core.validation;

import java.util.ArrayList;
import java.util.List;

public class ValidationResult {
    private final boolean valid;
    private final int errorCount;
    private final int warningCount;
    private final int infoCount;
    private final List<ValidationMessage> messages;

    public ValidationResult(List<ValidationMessage> messages) {
        this.messages = new ArrayList<>(messages);
        int errors = 0, warnings = 0, infos = 0;
        for (var m : messages) {
            if (m.getSeverity() == null) continue;
            switch (m.getSeverity().toUpperCase()) {
                case "ERROR": errors++; break;
                case "WARNING": warnings++; break;
                case "INFO": infos++; break;
            }
        }
        this.errorCount = errors;
        this.warningCount = warnings;
        this.infoCount = infos;
        this.valid = errors == 0;
    }

    public boolean isValid() { return valid; }
    public int getErrorCount() { return errorCount; }
    public int getWarningCount() { return warningCount; }
    public int getInfoCount() { return infoCount; }
    public List<ValidationMessage> getMessages() { return messages; }
}
