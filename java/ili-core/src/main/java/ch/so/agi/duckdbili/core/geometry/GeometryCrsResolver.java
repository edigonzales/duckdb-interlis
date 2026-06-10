package ch.so.agi.duckdbili.core.geometry;

import java.util.Optional;

/**
 * Resolves a CRS identifier for an INTERLIS geometry attribute from
 * explicit configuration only. Never guesses based on coordinate values.
 */
public interface GeometryCrsResolver {

    /**
     * Look up the CRS for the given geometry attribute.
     *
     * @param context Attribute context including model, class and coordinate domain.
     * @return The CRS if an explicit mapping exists, otherwise empty.
     */
    Optional<CrsIdentifier> resolve(GeometryMetadataContext context);
}
