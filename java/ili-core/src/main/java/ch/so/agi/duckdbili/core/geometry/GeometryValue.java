package ch.so.agi.duckdbili.core.geometry;

import java.util.Objects;

/**
 * Immutable value object representing a converted geometry.
 * Internally stores WKB as {@code byte[]}; the hex string is produced on demand.
 */
public final class GeometryValue {

    private final GeometryMetadata metadata;
    private final byte[] wkb;
    private final GeometryDimension actualDimension;
    private final boolean empty;

    public GeometryValue(GeometryMetadata metadata, byte[] wkb, GeometryDimension actualDimension, boolean empty) {
        this.metadata = Objects.requireNonNull(metadata);
        this.wkb = wkb != null ? wkb.clone() : null;
        this.actualDimension = Objects.requireNonNull(actualDimension);
        this.empty = empty;
    }

    public GeometryMetadata metadata() {
        return metadata;
    }

    /**
     * Returns a defensive copy of the WKB byte array.
     */
    public byte[] wkb() {
        return wkb != null ? wkb.clone() : null;
    }

    /**
     * Returns the uppercase hex-encoded WKB, or {@code null} if WKB is null.
     */
    public String hexWkb() {
        return wkb == null ? null : HexWkbCodec.encode(wkb);
    }

    public GeometryDimension actualDimension() {
        return actualDimension;
    }

    public boolean isEmpty() {
        return empty;
    }

    public boolean isNull() {
        return wkb == null;
    }
}
