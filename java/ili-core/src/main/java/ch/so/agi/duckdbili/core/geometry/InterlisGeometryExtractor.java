package ch.so.agi.duckdbili.core.geometry;

import ch.interlis.iom.IomObject;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.iox_j.IoxSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extracts geometry sub-objects from an IomObject parent, enforcing options such as
 * rejection of multiple attribute values.
 */
public final class InterlisGeometryExtractor {

    public Optional<IomObject> extractSingle(IomObject parent, AttributeDef attribute, GeometryConversionOptions options) {
        String attrName = attribute.getName();
        int count = parent.getattrvaluecount(attrName);
        if (count == 0) {
            return Optional.empty();
        }
        if (count > 1) {
            if (options.rejectMultipleAttributeValues()) {
                throw new GeometryConversionException(
                        "Attribute has " + count + " geometry values (expected 1)",
                        attribute.getScopedName(),
                        parent.getobjectoid(),
                        null
                );
            }
            // Documented warning: use first element only
            System.err.println("[ili-warn] Multiple geometry values for " + attribute.getScopedName()
                    + " tid=" + parent.getobjectoid() + ", using first of " + count);
        }
        IomObject geom = parent.getattrobj(attrName, 0);
        if (geom == null) {
            // Should not happen when count > 0, but guard anyway
            return Optional.empty();
        }
        return Optional.of(geom);
    }

    public List<IomObject> extractAll(IomObject parent, AttributeDef attribute) {
        String attrName = attribute.getName();
        int count = parent.getattrvaluecount(attrName);
        List<IomObject> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            IomObject geom = parent.getattrobj(attrName, i);
            if (geom != null) {
                result.add(geom);
            }
        }
        return result;
    }
}
