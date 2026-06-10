package ch.so.agi.duckdbili.core.geometry;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKBReader;
import com.vividsolutions.jts.io.WKTWriter;

import java.util.Objects;

/**
 * Immutable value object representing a converted geometry.
 * Internally stores WKB as {@code byte[]}; WKT and hex strings are produced on demand.
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

    /**
     * Returns Well-Known Text (WKT), or {@code null} if WKB is null.
     * Converts WKB → JTS Geometry → WKT.
     */
    public String wkt() {
        if (wkb == null) return null;
        try {
            WKBReader reader = new WKBReader();
            Geometry geom = reader.read(wkb);
            WKTWriter writer = new WKTWriter();
            return writer.write(geom);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to convert WKB to WKT for " + metadata.attributeFqn() + ": " + e.getMessage(), e);
        }
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
