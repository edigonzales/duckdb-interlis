package ch.so.agi.duckdbili.core.validation;

import java.nio.file.Path;

public class ValidationOutputException extends RuntimeException {

    private final String path;

    public ValidationOutputException(String message, Throwable cause) {
        super(message, cause);
        this.path = null;
    }

    public ValidationOutputException(String message, Throwable cause, String path) {
        super(message, cause);
        this.path = path;
    }

    public String path() { return path; }

    public static ValidationOutputException forMissingCsvLog(Path csvLog) {
        return new ValidationOutputException(
                "CSV log not found: " + csvLog.toAbsolutePath(),
                null, csvLog.toAbsolutePath().toString());
    }

    public static ValidationOutputException forReadError(Path csvLog, Throwable cause) {
        return new ValidationOutputException(
                "Failed to read CSV log: " + csvLog.toAbsolutePath(),
                cause, csvLog.toAbsolutePath().toString());
    }

    public static ValidationOutputException forMalformedCsv(Path csvLog, long recordNumber, String detail) {
        return new ValidationOutputException(
                "Malformed validator CSV log at record " + recordNumber + ": " + detail,
                null,
                csvLog.toAbsolutePath().toString());
    }
}
