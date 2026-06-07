package ch.so.agi.duckdbili.core;

public final class NativeError {

    private final int status;
    private final String operation;
    private final String message;
    private final String detail;
    private final String path;
    private final String model;
    private final String exceptionClass;

    private NativeError(int status, String operation, String message, String detail,
                        String path, String model, String exceptionClass) {
        this.status = status;
        this.operation = operation;
        this.message = message;
        this.detail = detail;
        this.path = path;
        this.model = model;
        this.exceptionClass = exceptionClass;
    }

    public int status() { return status; }
    public String operation() { return operation; }
    public String message() { return message; }
    public String detail() { return detail; }
    public String path() { return path; }
    public String model() { return model; }
    public String exceptionClass() { return exceptionClass; }

    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"status\":\"").append(esc(NativeStatus.nameFor(status))).append("\"");
        appendField(sb, "operation", operation);
        appendField(sb, "message", message);
        if (detail != null) appendField(sb, "detail", detail);
        if (path != null) appendField(sb, "path", path);
        if (model != null) appendField(sb, "model", model);
        if (exceptionClass != null) appendField(sb, "exception", exceptionClass);
        sb.append('}');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String name, String value) {
        sb.append(",\"").append(name).append("\":\"").append(esc(value)).append('\"');
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    public static NativeError invalidArgument(String operation, String message, String detail) {
        return new NativeError(NativeStatus.INVALID_ARGUMENT, operation, message, detail, null, null, null);
    }

    public static NativeError ioError(String operation, String message, String detail, String path) {
        return new NativeError(NativeStatus.IO_ERROR, operation, message, detail, path, null, null);
    }

    public static NativeError modelError(String operation, String message, String detail, String path) {
        return new NativeError(NativeStatus.MODEL_ERROR, operation, message, detail, path, null, null);
    }

    public static NativeError modelError(String operation, String message, String detail, String path, String model) {
        return new NativeError(NativeStatus.MODEL_ERROR, operation, message, detail, path, model, null);
    }

    public static NativeError parseError(String operation, String message, String detail) {
        return new NativeError(NativeStatus.PARSE_ERROR, operation, message, detail, null, null, null);
    }

    public static NativeError validationError(String operation, String message, String detail) {
        return new NativeError(NativeStatus.VALIDATION_ERROR, operation, message, detail, null, null, null);
    }

    public static NativeError unsupported(String operation, String message, String detail) {
        return new NativeError(NativeStatus.UNSUPPORTED, operation, message, detail, null, null, null);
    }

    public static NativeError internalError(String operation, Exception e) {
        return new NativeError(NativeStatus.INTERNAL_ERROR, operation,
                "Internal error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                e.toString(), null, null, e.getClass().getName());
    }

    public static NativeError internalError(String operation, String message, String exceptionClass) {
        return new NativeError(NativeStatus.INTERNAL_ERROR, operation, message, message, null, null, exceptionClass);
    }
}
