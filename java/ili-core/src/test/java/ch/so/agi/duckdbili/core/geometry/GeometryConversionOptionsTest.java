package ch.so.agi.duckdbili.core.geometry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeometryConversionOptionsTest {

    @Test
    void defaultsAreAsSpecified() {
        GeometryConversionOptions opts = GeometryConversionOptions.defaults();
        assertEquals(ArcHandlingMode.LINEARIZE, opts.arcHandlingMode());
        assertEquals(0.0, opts.strokeTolerance());
        assertTrue(opts.preserveZ());
        assertTrue(opts.rejectMultipleAttributeValues());
        assertFalse(opts.validateWkbRoundtrip());
    }

    @Test
    void negativeToleranceIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new GeometryConversionOptions(ArcHandlingMode.LINEARIZE, -0.1, true, true, false));
        assertTrue(ex.getMessage().contains("strokeTolerance"));
    }

    @Test
    void zeroToleranceIsAllowed() {
        assertDoesNotThrow(
                () -> new GeometryConversionOptions(ArcHandlingMode.LINEARIZE, 0.0, true, true, false));
    }
}
