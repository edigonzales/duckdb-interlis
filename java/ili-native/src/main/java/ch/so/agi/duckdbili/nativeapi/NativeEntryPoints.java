package ch.so.agi.duckdbili.nativeapi;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.Pointer;
import org.graalvm.nativeimage.UnmanagedMemory;

import ch.so.agi.duckdbili.core.CoreVersion;

/**
 * C ABI entry points for the DuckDB ILI native library.
 * <p>
 * All functions follow the convention:
 * <pre>{@code
 *   int ili_<name>(graal_isolatethread_t* thread, ili_params* params, ili_result* out);
 * }</pre>
 * <p>
 * The {@code code} field indicates: 0 = success, non-zero = error.
 * Strings returned in payload/error_message are owned by the native library
 * and must be freed via {@code ili_free_result()}.
 */
public class NativeEntryPoints {

    @CEntryPoint(name = "ili_native_version")
    public static int nativeVersion(
            IsolateThread thread,
            NativeResult result) {

        String payload = "{"
                + "\"native_version\": \"" + NativeVersion.VERSION + "\","
                + "\"core_version\": \"" + CoreVersion.VERSION + "\","
                + "\"graalvm_version\": \"" + NativeVersion.GRAALVM_VERSION + "\","
                + "\"platform\": \"" + NativeVersion.PLATFORM + "\","
                + "\"native_lib\": \"" + NativeVersion.NATIVE_LIB + "\""
                + "}";

        CCharPointer cPayload = CTypeConversion.toCString(payload).get();
        result.setCode(0);
        result.setPayload(cPayload);
        result.setErrorMessage(org.graalvm.word.WordFactory.nullPointer());
        return 0;
    }

    @CEntryPoint(name = "ili_native_echo")
    public static int nativeEcho(
            IsolateThread thread,
            CCharPointer requestJson,
            NativeResult result) {

        String request = CTypeConversion.toJavaString(requestJson);
        String payload = "{\"echo\": \"" + escapeJson(request) + "\"}";

        CCharPointer cPayload = CTypeConversion.toCString(payload).get();
        result.setCode(0);
        result.setPayload(cPayload);
        result.setErrorMessage(org.graalvm.word.WordFactory.nullPointer());
        return 0;
    }

    @CEntryPoint(name = "ili_free_result")
    public static void freeResult(
            IsolateThread thread,
            NativeResult result) {

        if (result.getPayload().isNonNull()) {
            UnmanagedMemory.free(result.getPayload());
        }
        if (result.getErrorMessage().isNonNull()) {
            UnmanagedMemory.free(result.getErrorMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
