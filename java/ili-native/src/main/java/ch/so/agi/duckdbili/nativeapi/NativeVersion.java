package ch.so.agi.duckdbili.nativeapi;

/**
 * Version info for the native library.
 */
public final class NativeVersion {

    private NativeVersion() {}

    public static final String VERSION = "0.1.0-dev";
    public static final String GRAALVM_VERSION = "GraalVM 25.0.3";
    public static final String PLATFORM = "macos-aarch64";
    public static final String NATIVE_LIB = "libduckdb_ili_native.dylib";
}
