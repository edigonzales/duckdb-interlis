package ch.so.agi.duckdbili.core;

public final class NativeStatus {

    public static final int OK = 0;
    public static final int INVALID_ARGUMENT = 1;
    public static final int IO_ERROR = 2;
    public static final int MODEL_ERROR = 3;
    public static final int PARSE_ERROR = 4;
    public static final int VALIDATION_ERROR = 5;
    public static final int UNSUPPORTED = 6;
    public static final int INTERNAL_ERROR = 100;

    private NativeStatus() {}

    public static String nameFor(int code) {
        return switch (code) {
            case OK -> "OK";
            case INVALID_ARGUMENT -> "INVALID_ARGUMENT";
            case IO_ERROR -> "IO_ERROR";
            case MODEL_ERROR -> "MODEL_ERROR";
            case PARSE_ERROR -> "PARSE_ERROR";
            case VALIDATION_ERROR -> "VALIDATION_ERROR";
            case UNSUPPORTED -> "UNSUPPORTED";
            case INTERNAL_ERROR -> "INTERNAL_ERROR";
            default -> "UNKNOWN_" + code;
        };
    }
}
