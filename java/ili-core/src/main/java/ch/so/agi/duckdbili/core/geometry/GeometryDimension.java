package ch.so.agi.duckdbili.core.geometry;

/**
 * Declared coordinate dimension derived from the INTERLIS model domain.
 */
public enum GeometryDimension {
    XY(2),
    XYZ(3),
    UNKNOWN(0);

    private final int coordinateDimension;

    GeometryDimension(int coordinateDimension) {
        this.coordinateDimension = coordinateDimension;
    }

    public int coordinateDimension() {
        return coordinateDimension;
    }
}
