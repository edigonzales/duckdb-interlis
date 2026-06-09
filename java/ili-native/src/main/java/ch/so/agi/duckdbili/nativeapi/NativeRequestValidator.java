package ch.so.agi.duckdbili.nativeapi;

import ch.so.agi.duckdbili.core.NativeError;
import ch.so.agi.duckdbili.core.NativeStatus;

public final class NativeRequestValidator {

    public static final long EXPECTED_STRUCT_SIZE = 112L;

    private NativeRequestValidator() {}

    public static NativeError requireRequest(IliRequest request, long minimumStructSize, String operation) {
        if (request.isNull()) {
            return NativeError.internalError(operation,
                    "Native request pointer is NULL",
                    "NullPointerException");
        }
        int structSize = request.struct_size();
        if (structSize < minimumStructSize) {
            return NativeError.invalidArgument(operation,
                    "Request struct too small: got " + structSize
                        + " bytes, need at least " + minimumStructSize,
                    "struct_size");
        }
        return null;
    }

    public static NativeError requireField(String value, String fieldName, String operation) {
        if (value == null || value.isBlank()) {
            return NativeError.invalidArgument(operation,
                    "Missing required field: " + fieldName,
                    fieldName);
        }
        return null;
    }
}
