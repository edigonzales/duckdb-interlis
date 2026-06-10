package ch.so.agi.duckdbili.core.geometry;

/**
 * Enumerates the supported INTERLIS geometry kinds and their OGC equivalents.
 */
public enum GeometryKind {
    /**
     * INTERLIS COORD
     */
    POINT,
    /**
     * INTERLIS MULTICOORD
     */
    MULTIPOINT,
    /**
     * INTERLIS POLYLINE
     */
    LINESTRING,
    /**
     * INTERLIS MULTIPOLYLINE
     */
    MULTILINESTRING,
    /**
     * INTERLIS SURFACE
     */
    POLYGON,
    /**
     * INTERLIS MULTISURFACE
     */
    MULTIPOLYGON,
    /**
     * INTERLIS AREA (may be mapped to POLYGON but kept distinct for metadata)
     */
    AREA,
    /**
     * INTERLIS MULTIAREA
     */
    MULTIAREA,
    /**
     * Not a recognised geometry kind; must not appear as a successfully converted value.
     */
    UNKNOWN
}
