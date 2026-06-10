package ch.so.agi.duckdbili.core.geometry;

/**
 * Generic geometry conversion error carrying context for debugging.
 */
public class GeometryConversionException extends RuntimeException {

    private final String attributeFqn;
    private final String objectTid;
    private final GeometryKind geometryKind;

    public GeometryConversionException(String message, String attributeFqn, String objectTid, GeometryKind geometryKind) {
        super(message);
        this.attributeFqn = attributeFqn;
        this.objectTid = objectTid;
        this.geometryKind = geometryKind;
    }

    public GeometryConversionException(String message, Throwable cause, String attributeFqn, String objectTid, GeometryKind geometryKind) {
        super(message, cause);
        this.attributeFqn = attributeFqn;
        this.objectTid = objectTid;
        this.geometryKind = geometryKind;
    }

    public String attributeFqn() {
        return attributeFqn;
    }

    public String objectTid() {
        return objectTid;
    }

    public GeometryKind geometryKind() {
        return geometryKind;
    }

    @Override
    public String getMessage() {
        String msg = super.getMessage();
        return String.format(
                "Geometry conversion failed: attribute=%s tid=%s declaredKind=%s reason=%s",
                attributeFqn != null ? attributeFqn : "n/a",
                objectTid != null ? objectTid : "n/a",
                geometryKind != null ? geometryKind : "n/a",
                msg != null ? msg : "unknown"
        );
    }
}
