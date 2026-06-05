package ch.so.agi.duckdbili.nativeapi;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NativeVersionTest {

    @Test
    void versionIsNotEmpty() {
        assertNotNull(NativeVersion.VERSION);
        assertFalse(NativeVersion.VERSION.isBlank());
    }

    @Test
    void platformIsMacArm64() {
        assertTrue(NativeVersion.PLATFORM.contains("aarch64") || NativeVersion.PLATFORM.contains("arm64"));
    }
}
