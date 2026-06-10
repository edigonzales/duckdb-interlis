package ch.so.agi.duckdbili.core.geometry;

/**
 * Conversion options controlling geometry encoder behaviour.
 *
 * @param arcHandlingMode           how ARC segments are handled
 * @param strokeTolerance           linearisation tolerance; 0.0 means use library default
 * @param preserveZ                 whether Z coordinates must be preserved (throws if impossible)
 * @param rejectMultipleAttributeValues fail if an attribute contains more than one geometry value
 * @param validateWkbRoundtrip       verify WKB roundtrip (intended for tests / debugging)
 */
public record GeometryConversionOptions(
        ArcHandlingMode arcHandlingMode,
        double strokeTolerance,
        boolean preserveZ,
        boolean rejectMultipleAttributeValues,
        boolean validateWkbRoundtrip) {

    public GeometryConversionOptions {
        if (strokeTolerance < 0.0) {
            throw new IllegalArgumentException(
                    "strokeTolerance must be >= 0.0, got " + strokeTolerance);
        }
    }

    public static GeometryConversionOptions defaults() {
        return new GeometryConversionOptions(
                ArcHandlingMode.LINEARIZE,
                0.0,
                true,
                true,
                false
        );
    }
}
