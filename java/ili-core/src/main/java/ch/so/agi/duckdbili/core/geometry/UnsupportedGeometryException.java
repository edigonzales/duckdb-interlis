package ch.so.agi.duckdbili.core.geometry;

/**
 * Thrown when a geometry construct is explicitly unsupported (custom line forms, AREA without
 * direct mapping, clipped polylines, etc.)
 */
public final class UnsupportedGeometryException extends GeometryConversionException {

    public UnsupportedGeometryException(String message, String attributeFqn, String objectTid, GeometryKind geometryKind) {
        super(message, attributeFqn, objectTid, geometryKind);
    }

    public UnsupportedGeometryException(String message, Throwable cause, String attributeFqn, String objectTid, GeometryKind geometryKind) {
        super(message, cause, attributeFqn, objectTid, geometryKind);
    }
}
