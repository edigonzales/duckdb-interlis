package ch.so.agi.duckdbili.core.geometry;

import ch.interlis.iom.IomConstants;
import ch.interlis.iom.IomObject;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.iox_j.jts.Iox2jts;
import ch.interlis.iox_j.jts.Iox2jtsext;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKBReader;
import com.vividsolutions.jts.io.WKBWriter;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Converts INTERLIS IomObject geometry values to OGC WKB byte arrays.
 *
 * This class performs tag validation and dispatches to the appropriate iox-ili converter
 * based on the resolved {@link GeometryKind}. The output is always a {@code byte[]};
 * hex encoding happens only later via {@link GeometryValue#hexWkb()}.
 */
public final class InterlisGeometryEncoder {

    private final InterlisGeometryTypeResolver typeResolver;
    private final InterlisGeometryExtractor extractor;
    private final GeometryConversionOptions options;

    private static final Map<GeometryKind, Set<String>> VALID_TAGS = Map.of(
            GeometryKind.POINT, Set.of("COORD"),
            GeometryKind.MULTIPOINT, Set.of("MULTICOORD"),
            GeometryKind.LINESTRING, Set.of("POLYLINE"),
            GeometryKind.MULTILINESTRING, Set.of("MULTIPOLYLINE"),
            GeometryKind.POLYGON, Set.of("SURFACE", "MULTISURFACE"),
            GeometryKind.MULTIPOLYGON, Set.of("MULTISURFACE"),
            GeometryKind.AREA, Set.of("AREA", "MULTIAREA", "SURFACE", "MULTISURFACE"),
            GeometryKind.MULTIAREA, Set.of("MULTIAREA", "MULTISURFACE")
    );

    public InterlisGeometryEncoder(
            InterlisGeometryTypeResolver typeResolver,
            InterlisGeometryExtractor extractor,
            GeometryConversionOptions options) {
        this.typeResolver = Objects.requireNonNull(typeResolver);
        this.extractor = Objects.requireNonNull(extractor);
        this.options = Objects.requireNonNull(options);
    }

    /**
     * Encodes a geometry attribute of an IomObject into a {@link GeometryValue}.
     */
    public Optional<GeometryValue> encodeAttribute(
            IomObject parent,
            AttributeDef attribute,
            GeometryMetadata metadata) {

        Optional<IomObject> geomObj = extractor.extractSingle(parent, attribute, options);
        if (geomObj.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(encodeGeometry(geomObj.get(), metadata));
    }

    /**
     * Encodes a single IomObject geometry into a {@link GeometryValue}.
     */
    public GeometryValue encodeGeometry(IomObject geometryObject, GeometryMetadata metadata) {
        byte[] wkb = encodeToWkb(geometryObject, metadata.geometryKind());
        GeometryDimension actualDim = determineActualDimension(wkb);
        
        if (options.preserveZ() && metadata.dimension() == GeometryDimension.XYZ && actualDim != GeometryDimension.XYZ) {
            throw new UnsupportedGeometryException(
                    "3D coordinate declared but WKB output is 2D. Z ordinate was lost during conversion " +
                    "(dimension=" + metadata.dimension() + " actual=" + actualDim + "). " +
                    "Set preserveZ=false to allow 2D output or use a library that supports 3D WKB.",
                    metadata.attributeFqn(), null, metadata.geometryKind());
        }
        
        return new GeometryValue(metadata, wkb, actualDim, false);
    }

    /**
     * Converts an IomObject to a raw WKB byte array.
     *
     * @param geometry the IomObject representing the geometry
     * @param kind the expected geometry kind
     * @return non-null byte array containing OGC WKB
     */
    public byte[] encodeToWkb(IomObject geometry, GeometryKind kind) {
        validateGeometryTag(geometry.getobjecttag(), kind);
        validateGeometryCompleteness(geometry, kind);
        validateLineForms(geometry, kind);

        try {
            String hexWkb = switch (kind) {
                case POINT -> Iox2jtsext.coord2hexwkb(geometry);
                case LINESTRING -> Iox2jtsext.polyline2hexwkb(geometry, options.strokeTolerance());
                case POLYGON, MULTIPOLYGON, AREA, MULTIAREA ->
                    Iox2jts.multisurface2hexwkb(geometry, options.strokeTolerance());
                case MULTIPOINT -> Iox2jts.multicoord2hexwkb(geometry);
                case MULTILINESTRING -> Iox2jts.multipolyline2hexwkb(geometry, options.strokeTolerance());
                case UNKNOWN -> throw new UnsupportedGeometryException(
                        "Unknown geometry kind: " + kind, null, null, kind);
            };
            return hexToBytes(hexWkb);
        } catch (ch.interlis.iox.IoxException | ch.interlis.iox_j.jts.Iox2jtsException e) {
            String attrFqn = geometry.getobjecttag() != null ? geometry.getobjecttag() : null;
            throw new GeometryConversionException(
                    "IOX conversion failed: " + e.getMessage(), e,
                    attrFqn, geometry.getobjectoid(), kind);
        }
    }

    // ------------------------------------------------------------------
    // Completeness / edge case validation
    // ------------------------------------------------------------------

    private void validateGeometryCompleteness(IomObject geometry, GeometryKind kind) {
        int consistency = geometry.getobjectconsistency();
        if (consistency == IomConstants.IOM_INCOMPLETE) {
            throw new UnsupportedGeometryException(
                    "Clipped/incomplete geometry is not supported (IOM_INCOMPLETE). "
                    + "The XTF file may be truncated or contain only geometry fragments.",
                    null, geometry.getobjectoid(), kind);
        }
    }

    private void validateLineForms(IomObject geometry, GeometryKind kind) {
        // Only relevant for line-based geometries
        if (kind != GeometryKind.LINESTRING && kind != GeometryKind.MULTILINESTRING) return;

        // Check for custom line form sub-elements.
        // Whitelist: COORD, ARC, SEGMENT, POLYLINE, and short tags (<=3 chars like C1, C2)
        int attrCount = geometry.getattrcount();
        for (int i = 0; i < attrCount; i++) {
            String aname = geometry.getattrname(i);
            if (aname == null) continue;
            int cnt = geometry.getattrvaluecount(aname);
            for (int j = 0; j < cnt; j++) {
                IomObject sub = geometry.getattrobj(aname, j);
                if (sub == null) continue;
                String tag = sub.getobjecttag();
                if (tag == null) continue;

                String shortTag = tag;
                int sep = Math.max(tag.lastIndexOf('.'), tag.lastIndexOf(':'));
                if (sep >= 0) shortTag = tag.substring(sep + 1);

                if (shortTag.length() <= 3) continue;  // coordinate axes, ioX-internal
                if ("COORD".equalsIgnoreCase(shortTag)) continue;
                if ("ARC".equalsIgnoreCase(shortTag)) continue;
                if ("SEGMENT".equalsIgnoreCase(shortTag)) continue;
                if ("POLYLINE".equalsIgnoreCase(shortTag)) continue;

                // Unknown geometry segment — only flag namespaced tags
                if (sep >= 0) {
                    throw new UnsupportedGeometryException(
                            "Custom line form is not supported: segment tag '" + tag + "'",
                            null, geometry.getobjectoid(), kind);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Tag validation
    // ------------------------------------------------------------------

    private void validateGeometryTag(String tag, GeometryKind kind) {
        if (tag == null || tag.isBlank()) {
            throw new GeometryConversionException(
                    "Geometry tag is null or blank", null, null, kind);
        }
        String shortTag = tag;
        int sep = Math.max(tag.lastIndexOf('.'), tag.lastIndexOf(':'));
        if (sep >= 0) {
            shortTag = tag.substring(sep + 1);
        }
        Set<String> valid = VALID_TAGS.get(kind);
        if (valid == null || !valid.contains(shortTag.toUpperCase())) {
            throw new GeometryConversionException(
                    "Unexpected geometry tag '" + shortTag + "' for kind " + kind +
                    " (expected one of: " + valid + ")",
                    null, null, kind);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static byte[] hexToBytes(String hexWkb) {
        if (hexWkb == null || hexWkb.isBlank()) {
            return new byte[0];
        }
        return HexWkbCodec.decode(hexWkb);
    }

    private GeometryDimension determineActualDimension(byte[] wkb) {
        if (wkb == null || wkb.length == 0) {
            return GeometryDimension.UNKNOWN;
        }
        try {
            WKBReader reader = new WKBReader();
            Geometry geom = reader.read(wkb);
            if (geom == null || geom.isEmpty()) {
                return GeometryDimension.UNKNOWN;
            }
            double z = geom.getCoordinate() != null ? geom.getCoordinate().z : Double.NaN;
            if (!Double.isNaN(z)) {
                return GeometryDimension.XYZ;
            }
            return GeometryDimension.XY;
        } catch (Exception e) {
            return GeometryDimension.UNKNOWN;
        }
    }
}
