package ch.so.agi.duckdbili.core.geometry;

/**
 * Pure utility for encoding / decoding OGC WKB to and from uppercase hexadecimal strings.
 */
public final class HexWkbCodec {

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private HexWkbCodec() {
        // utility class
    }

    /**
     * Encodes a byte array to an uppercase hexadecimal string.
     *
     * @param wkb bytes may be null
     * @return hex string or null if input is null
     */
    public static String encode(byte[] wkb) {
        if (wkb == null) {
            return null;
        }
        char[] out = new char[wkb.length * 2];
        for (int i = 0; i < wkb.length; i++) {
            int v = wkb[i] & 0xFF;
            out[i * 2] = HEX_DIGITS[v >>> 4];
            out[i * 2 + 1] = HEX_DIGITS[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * Decodes a hexadecimal string to a byte array.
     *
     * @param hex hex string, may be null
     * @return byte array or null if input is null
     * @throws IllegalArgumentException if the string is not valid hex
     */
    public static byte[] decode(String hex) {
        if (hex == null) {
            return null;
        }
        if (hex.isEmpty()) {
            return new byte[0];
        }
        if ((hex.length() & 1) == 1) {
            throw new IllegalArgumentException("Hex string has odd length: " + hex.length());
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = charToNibble(hex.charAt(i * 2));
            int lo = charToNibble(hex.charAt(i * 2 + 1));
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * Validates whether the given string is a well-formed hex-wkb string.
     *
     * @param hex the string to validate
     * @return true if non-null, non-empty, even-length and contains only hex characters
     */
    public static boolean isValidHexWkb(String hex) {
        if (hex == null || hex.isEmpty()) {
            return false;
        }
        if ((hex.length() & 1) == 1) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!isHexDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static int charToNibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        throw new IllegalArgumentException("Invalid hex character: '" + c + "'");
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }
}
