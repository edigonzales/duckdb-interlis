package ch.so.agi.duckdbili.core.geometry;

/**
 * Identifies a coordinate reference system by authority and code.
 * Used when an explicit CRS mapping is configured; never auto-detected.
 *
 * @param authority The CRS authority, e.g. "EPSG".
 * @param code     The CRS code within that authority, e.g. "2056".
 * @param srid     The numeric SRID if known, otherwise null.
 */
public record CrsIdentifier(String authority, String code, Integer srid) {
}
