package ch.so.agi.duckdbili.core.geometry;

import java.util.Optional;

/**
 * No-op CRS resolver that always returns empty.
 * Used when no explicit CRS mapping is configured.
 */
public final class NoopGeometryCrsResolver implements GeometryCrsResolver {

    @Override
    public Optional<CrsIdentifier> resolve(GeometryMetadataContext context) {
        return Optional.empty();
    }
}
