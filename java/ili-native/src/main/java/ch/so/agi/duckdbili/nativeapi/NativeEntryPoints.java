package ch.so.agi.duckdbili.nativeapi;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.word.WordFactory;

import ch.so.agi.duckdbili.core.CoreVersion;

public class NativeEntryPoints {

    public static void main(String[] args) {
        System.out.println("ILI Native Library - use as shared library only");
    }

    @CEntryPoint(name = "ili_native_version")
    public static int nativeVersion(
            IsolateThread thread,
            CCharPointerPointer outPayload) {

        String json = "{"
                + "\"native_version\": \"" + NativeVersion.VERSION + "\","
                + "\"core_version\": \"" + CoreVersion.VERSION + "\","
                + "\"graalvm_version\": \"" + NativeVersion.GRAALVM_VERSION + "\","
                + "\"platform\": \"" + NativeVersion.PLATFORM + "\","
                + "\"native_lib\": \"" + NativeVersion.NATIVE_LIB + "\""
                + "}";
        outPayload.write(allocCString(json));
        return 0;
    }

    @CEntryPoint(name = "ili_native_echo")
    public static int nativeEcho(
            IsolateThread thread,
            CCharPointer requestJson,
            CCharPointerPointer outPayload) {

        String request = CTypeConversion.toJavaString(requestJson);
        String json = "{\"echo\": \"" + escapeJson(request) + "\"}";
        outPayload.write(allocCString(json));
        return 0;
    }

    @CEntryPoint(name = "ili_free_string")
    public static void freeString(
            IsolateThread thread,
            CCharPointer str) {

        if (str.isNonNull()) {
            UnmanagedMemory.free(str);
        }
    }

    private static CCharPointer allocCString(String s) {
        if (s == null) {
            return WordFactory.nullPointer();
        }
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CCharPointer ptr = UnmanagedMemory.malloc(bytes.length + 1);
        for (int i = 0; i < bytes.length; i++) {
            ptr.write(i, bytes[i]);
        }
        ptr.write(bytes.length, (byte) 0);
        return ptr;
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
