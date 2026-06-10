package ch.so.agi.duckdbili.core.geometry;

public class GeometryAttributeMissingException extends GeometryConversionException {
    public GeometryAttributeMissingException(String message, String attributeFqn, String objectTid, GeometryKind detectedKind) {
        super(message, attributeFqn, objectTid, detectedKind);
    }
}
