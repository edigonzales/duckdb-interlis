package ch.so.agi.duckdbili.core.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class TsvCodecTest {

    @Test
    @DisplayName("encodeNullable: null maps to NULL sentinel")
    void testEncodeNullableNull() {
        assertEquals("\\N", TsvCodec.encodeNullable(null));
    }

    @Test
    @DisplayName("encodeNullable: empty string maps to empty string")
    void testEncodeNullableEmpty() {
        assertEquals("", TsvCodec.encodeNullable(""));
    }

    @Test
    @DisplayName("encodeNullable: plain text passes through")
    void testEncodeNullablePlain() {
        assertEquals("hello", TsvCodec.encodeNullable("hello"));
    }

    @Test
    @DisplayName("encodeNullable: backslash is escaped")
    void testEncodeNullableBackslash() {
        assertEquals("\\\\", TsvCodec.encodeNullable("\\"));
    }

    @Test
    @DisplayName("encodeNullable: tab is escaped")
    void testEncodeNullableTab() {
        assertEquals("\\t", TsvCodec.encodeNullable("\t"));
    }

    @Test
    @DisplayName("encodeNullable: newline is escaped")
    void testEncodeNullableNewline() {
        assertEquals("\\n", TsvCodec.encodeNullable("\n"));
    }

    @Test
    @DisplayName("encodeNullable: carriage return is escaped")
    void testEncodeNullableCR() {
        assertEquals("\\r", TsvCodec.encodeNullable("\r"));
    }

    @Test
    @DisplayName("encodeNullable: literal backslash-N distinguishable from NULL")
    void testEncodeNullableLiteralBackslashN() {
        assertEquals("\\\\N", TsvCodec.encodeNullable("\\N"));
    }

    @Test
    @DisplayName("encodeNullable: mixed special characters")
    void testEncodeNullableMixed() {
        assertEquals("a\\\\b\\tc\\nd\\re", TsvCodec.encodeNullable("a\\b\tc\nd\re"));
    }

    @Test
    @DisplayName("encodeNullable: Unicode preserved")
    void testEncodeNullableUnicode() {
        assertEquals("Höhe äöü 中文", TsvCodec.encodeNullable("Höhe äöü 中文"));
    }

    @Test
    @DisplayName("encodeRequired: null maps to empty string")
    void testEncodeRequiredNull() {
        assertEquals("", TsvCodec.encodeRequired(null));
    }

    @Test
    @DisplayName("encodeRequired: empty string stays empty")
    void testEncodeRequiredEmpty() {
        assertEquals("", TsvCodec.encodeRequired(""));
    }

    @Test
    @DisplayName("encodeRequired: escapes correctly")
    void testEncodeRequiredEscapes() {
        assertEquals("\\\\", TsvCodec.encodeRequired("\\"));
    }

    @Test
    @DisplayName("encodeNullableInteger: null maps to NULL sentinel")
    void testEncodeNullableIntegerNull() {
        assertEquals("\\N", TsvCodec.encodeNullableInteger(null));
    }

    @Test
    @DisplayName("encodeNullableInteger: zero encodes")
    void testEncodeNullableIntegerZero() {
        assertEquals("0", TsvCodec.encodeNullableInteger(0));
    }

    @Test
    @DisplayName("encodeNullableInteger: positive value")
    void testEncodeNullableIntegerPositive() {
        assertEquals("42", TsvCodec.encodeNullableInteger(42));
    }

    @Test
    @DisplayName("encodeNullableInteger: negative value")
    void testEncodeNullableIntegerNegative() {
        assertEquals("-17", TsvCodec.encodeNullableInteger(-17));
    }

    @Test
    @DisplayName("encodeNullableDouble: null maps to NULL sentinel")
    void testEncodeNullableDoubleNull() {
        assertEquals("\\N", TsvCodec.encodeNullableDouble(null));
    }

    @Test
    @DisplayName("encodeNullableDouble: value encodes")
    void testEncodeNullableDoubleValue() {
        assertEquals("3.14", TsvCodec.encodeNullableDouble(3.14));
    }

    @Test
    @DisplayName("encodeNullableLong: null maps to NULL sentinel")
    void testEncodeNullableLongNull() {
        assertEquals("\\N", TsvCodec.encodeNullableLong(null));
    }

    @Test
    @DisplayName("encodeNullableLong: value encodes")
    void testEncodeNullableLongValue() {
        assertEquals("9223372036854775807", TsvCodec.encodeNullableLong(9223372036854775807L));
    }

    @Test
    @DisplayName("encodeNullableBoolean: null maps to NULL sentinel")
    void testEncodeNullableBooleanNull() {
        assertEquals("\\N", TsvCodec.encodeNullableBoolean(null));
    }

    @Test
    @DisplayName("encodeNullableBoolean: true encodes")
    void testEncodeNullableBooleanTrue() {
        assertEquals("true", TsvCodec.encodeNullableBoolean(true));
    }

    @Test
    @DisplayName("encodeNullableBoolean: false encodes")
    void testEncodeNullableBooleanFalse() {
        assertEquals("false", TsvCodec.encodeNullableBoolean(false));
    }

    @Test
    @DisplayName("NULL constant is correct")
    void testNullConstant() {
        assertEquals("\\N", TsvCodec.NULL);
    }

    @Test
    @DisplayName("Roundtrip: null → \\N recognizable as is_null")
    void testRoundtripNull() {
        String encoded = TsvCodec.encodeNullable(null);
        assertEquals("\\N", encoded);
        assertEquals(2, encoded.length());
        assertEquals('\\', encoded.charAt(0));
        assertEquals('N', encoded.charAt(1));
    }
}
