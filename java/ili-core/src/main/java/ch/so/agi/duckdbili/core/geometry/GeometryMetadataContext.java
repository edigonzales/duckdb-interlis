package ch.so.agi.duckdbili.core.geometry;

/**
 * Context data for CRS resolution. Carries enough information to look up
 * an explicit CRS mapping for a geometry attribute without relying on
 * coordinate-value heuristics.
 */
public record GeometryMetadataContext(
        String modelName,
        String topicName,
        String className,
        String attributeName,
        String coordinateDomainFqn) {
}
