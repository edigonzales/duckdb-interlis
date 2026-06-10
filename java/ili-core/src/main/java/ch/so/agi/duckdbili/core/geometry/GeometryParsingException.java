package ch.so.agi.duckdbili.core.geometry;

public class GeometryParsingException extends GeometryConversionException {
    public GeometryParsingException(String message, String attributeFqn, String objectTid, GeometryKind detectedKind) {
        super(message, attributeFqn, objectTid, detectedKind);
    }
}
