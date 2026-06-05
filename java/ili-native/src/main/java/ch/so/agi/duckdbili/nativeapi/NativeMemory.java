package ch.so.agi.duckdbili.nativeapi;

import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.Pointer;

/**
 * Utility for native memory management.
 */
public final class NativeMemory {

    private NativeMemory() {}

    public static CCharPointer cString(String s) {
        if (s == null) {
            return org.graalvm.word.WordFactory.nullPointer();
        }
        return CTypeConversion.toCString(s).get();
    }

    public static String javaString(CCharPointer ptr) {
        if (ptr.isNull()) {
            return null;
        }
        return CTypeConversion.toJavaString(ptr);
    }

    public static void free(Pointer ptr) {
        if (ptr.isNonNull()) {
            UnmanagedMemory.free(ptr);
        }
    }
}
