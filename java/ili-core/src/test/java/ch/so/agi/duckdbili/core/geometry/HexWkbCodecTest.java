package ch.so.agi.duckdbili.core.geometry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HexWkbCodecTest {

    @Test
    void encodeProducesUppercase() {
        byte[] data = {0x01, 0x02, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF};
        assertEquals("0102ABCDEF", HexWkbCodec.encode(data));
    }

    @Test
    void encodeNullReturnsNull() {
        assertNull(HexWkbCodec.encode(null));
    }

    @Test
    void decodeAcceptsUppercase() {
        assertArrayEquals(new byte[]{0x01, 0x02, (byte) 0xAB}, HexWkbCodec.decode("0102AB"));
    }

    @Test
    void decodeAcceptsLowercase() {
        assertArrayEquals(new byte[]{0x01, 0x02, (byte) 0xAB}, HexWkbCodec.decode("0102ab"));
    }

    @Test
    void decodeEmptyStringReturnsEmptyArray() {
        assertArrayEquals(new byte[]{}, HexWkbCodec.decode(""));
    }

    @Test
    void decodeNullReturnsNull() {
        assertNull(HexWkbCodec.decode(null));
    }

    @Test
    void decodeOddLengthThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HexWkbCodec.decode("01A"));
        assertTrue(ex.getMessage().contains("odd"));
    }

    @Test
    void decodeInvalidCharacterThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HexWkbCodec.decode("01G2"));
        assertTrue(ex.getMessage().contains("Invalid hex"));
    }

    @Test
    void roundtripPreservesBytes() {
        byte[] original = {0x01, (byte) 0xFF, 0x00, 0x42, (byte) 0xAB, (byte) 0xCD};
        String hex = HexWkbCodec.encode(original);
        byte[] decoded = HexWkbCodec.decode(hex);
        assertArrayEquals(original, decoded);
    }

    @Test
    void isValidHexWkb_detectsValid() {
        assertTrue(HexWkbCodec.isValidHexWkb("0102AB"));
        assertTrue(HexWkbCodec.isValidHexWkb("abcdef"));
    }

    @Test
    void isValidHexWkb_rejectsInvalid() {
        assertFalse(HexWkbCodec.isValidHexWkb(null));
        assertFalse(HexWkbCodec.isValidHexWkb(""));
        assertFalse(HexWkbCodec.isValidHexWkb("01A"));
        assertFalse(HexWkbCodec.isValidHexWkb("01G2"));
    }
}
