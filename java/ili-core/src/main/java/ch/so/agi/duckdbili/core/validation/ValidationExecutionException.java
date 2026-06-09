package ch.so.agi.duckdbili.core.validation;

import ch.so.agi.duckdbili.core.NativeStatus;

import java.io.IOException;
import java.nio.file.Path;

public class ValidationExecutionException extends RuntimeException {

    private final int nativeErrorCode;
    private final String path;
    private final String model;

    private ValidationExecutionException(String message, Throwable cause,
                                          int nativeErrorCode, String path, String model) {
        super(message, cause);
        this.nativeErrorCode = nativeErrorCode;
        this.path = path;
        this.model = model;
    }

    public int nativeErrorCode() { return nativeErrorCode; }
    public String path() { return path; }
    public String model() { return model; }

    public static ValidationExecutionException forFileNotFound(Path xtfFile) {
        String pathStr = xtfFile.toAbsolutePath().toString();
        return new ValidationExecutionException(
                "File not found: " + pathStr,
                null, NativeStatus.IO_ERROR, pathStr, null);
    }

    public static ValidationExecutionException forTempDirFailure(IOException cause) {
        return new ValidationExecutionException(
                "Failed to create temp directory: " + (cause.getMessage() != null ? cause.getMessage() : cause),
                cause, NativeStatus.IO_ERROR, null, null);
    }

    public static ValidationExecutionException forValidatorException(Throwable cause, Path xtfFile) {
        String pathStr = xtfFile != null ? xtfFile.toAbsolutePath().toString() : null;
        return new ValidationExecutionException(
                "Validation process error: " + (cause != null && cause.getMessage() != null ? cause.getMessage() : cause),
                cause, NativeStatus.INTERNAL_ERROR, pathStr, null);
    }
}
