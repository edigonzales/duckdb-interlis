package ch.so.agi.duckdbili.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CoreVersionTest {

    @Test
    void versionIsNotEmpty() {
        assertNotNull(CoreVersion.VERSION);
        assertFalse(CoreVersion.VERSION.isBlank());
    }
}
