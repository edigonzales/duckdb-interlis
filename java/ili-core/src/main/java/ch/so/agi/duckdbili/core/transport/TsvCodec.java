package ch.so.agi.duckdbili.core.transport;

public final class TsvCodec {
    public static final String NULL = "\\N";

    private TsvCodec() {}

    public static String encodeNullable(String value) {
        if (value == null) return NULL;
        return escape(value);
    }

    public static String encodeRequired(String value) {
        if (value == null) return "";
        return escape(value);
    }

    public static String encodeNullableInteger(Integer value) {
        if (value == null) return NULL;
        return value.toString();
    }

    public static String encodeNullableDouble(Double value) {
        if (value == null) return NULL;
        return value.toString();
    }

    public static String encodeNullableLong(Long value) {
        if (value == null) return NULL;
        return value.toString();
    }

    public static String encodeNullableBoolean(Boolean value) {
        if (value == null) return NULL;
        return value.toString();
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
