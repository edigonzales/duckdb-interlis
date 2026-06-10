package ch.so.agi.duckdbili.core.geometry;

/**
 * Static metadata for a geometry attribute, derived from the INTERLIS model.
 *
 * @param modelName             name of the INTERLIS model
 * @param topicName             name of the topic
 * @param className             short class name
 * @param attributeName         short attribute name
 * @param attributeFqn          fully qualified attribute name
 * @param geometryKind          resolved OGC-like kind
 * @param dimension             declared coordinate dimension
 * @param coordinateDomainName  short name of the coordinate domain (may be null)
 * @param coordinateDomainFqn   fully qualified coordinate domain (may be null)
 * @param crsAuthName           CRS authority (may be null)
 * @param crsCode               CRS code (may be null)
 * @param srid                  numeric SRID (may be null)
 * @param mandatory             whether the attribute is mandatory
 * @param cardinalityMin        minimum cardinality
 * @param cardinalityMax        maximum cardinality
 * @param supportsArcs          whether the line form may contain ARCs
 * @param isAreaType            whether the kind is AREA or MULTIAREA
 * @param isMultiType           whether the kind is a multi-type
 */
public record GeometryMetadata(
        String modelName,
        String topicName,
        String className,
        String attributeName,
        String attributeFqn,
        GeometryKind geometryKind,
        GeometryDimension dimension,
        String coordinateDomainName,
        String coordinateDomainFqn,
        String crsAuthName,
        String crsCode,
        Integer srid,
        boolean mandatory,
        long cardinalityMin,
        long cardinalityMax,
        boolean supportsArcs,
        boolean isAreaType,
        boolean isMultiType) {
}
