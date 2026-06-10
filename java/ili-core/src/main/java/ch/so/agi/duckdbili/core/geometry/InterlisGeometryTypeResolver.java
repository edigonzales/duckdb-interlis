package ch.so.agi.duckdbili.core.geometry;

import ch.interlis.ili2c.metamodel.*;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Resolves INTERLIS model geometry types to structured metadata.
 */
public final class InterlisGeometryTypeResolver {

    public boolean isGeometryAttribute(AttributeDef attribute) {
        GeometryKind kind = resolveKind(attribute);
        return kind != GeometryKind.UNKNOWN;
    }

    public GeometryKind resolveKind(AttributeDef attribute) {
        Type base = resolveBaseType(attribute);
        // Order matters due to inheritance hierarchy:
        // e.g. MultiCoordType extends AbstractCoordType,
        // MultiSurfaceOrAreaType extends AbstractSurfaceOrAreaType extends LineType.
        if (base instanceof MultiAreaType) return GeometryKind.MULTIAREA;
        if (base instanceof AreaType) return GeometryKind.AREA;
        if (base instanceof MultiSurfaceType) return GeometryKind.MULTIPOLYGON;
        if (base instanceof SurfaceType) return GeometryKind.POLYGON;
        if (base instanceof MultiPolylineType) return GeometryKind.MULTILINESTRING;
        if (base instanceof MultiCoordType) return GeometryKind.MULTIPOINT;
        if (base instanceof AbstractCoordType) return GeometryKind.POINT;
        if (base instanceof LineType) return GeometryKind.LINESTRING;
        return GeometryKind.UNKNOWN;
    }

    public GeometryDimension resolveDeclaredDimension(AttributeDef attribute) {
        Type base = resolveBaseType(attribute);
        AbstractCoordType coordType = extractCoordType(base);
        if (coordType == null) return GeometryDimension.UNKNOWN;
        NumericalType[] dims = coordType.getDimensions();
        if (dims == null) return GeometryDimension.UNKNOWN;
        return switch (dims.length) {
            case 2 -> GeometryDimension.XY;
            case 3 -> GeometryDimension.XYZ;
            default -> GeometryDimension.UNKNOWN;
        };
    }

    public GeometryMetadata resolveMetadata(Model model, Topic topic, AbstractClassDef classDef, AttributeDef attribute) {
        GeometryKind kind = resolveKind(attribute);
        GeometryDimension dim = resolveDeclaredDimension(attribute);

        Type base = resolveBaseType(attribute);
        String coordinateDomainName = null;
        String coordinateDomainFqn = null;
        AbstractCoordType coordType = extractCoordType(base);
        if (coordType != null) {
            Domain domain = resolveCoordinateDomain(attribute);
            if (domain != null) {
                coordinateDomainName = domain.getName();
                coordinateDomainFqn = domain.getScopedName();
            }
        }

        Cardinality card = attribute.getCardinality();
        boolean isArea = kind == GeometryKind.AREA || kind == GeometryKind.MULTIAREA;
        boolean isMulti = kind == GeometryKind.MULTIPOINT || kind == GeometryKind.MULTILINESTRING
                       || kind == GeometryKind.MULTIPOLYGON || kind == GeometryKind.MULTIAREA;

        boolean supportsArcs = false;
        if (base instanceof LineType lt) {
            supportsArcs = lt.getLineForms().length > 0;
        } else if (base instanceof AbstractSurfaceOrAreaType sat) {
            supportsArcs = sat.getLineForms().length > 0;
        }

        return new GeometryMetadata(
                model.getName(),
                topic.getName(),
                classDef.getName(),
                attribute.getName(),
                attribute.getScopedName(),
                kind,
                dim,
                coordinateDomainName,
                coordinateDomainFqn,
                null, // crsAuthName – filled by resolver later
                null, // crsCode
                null, // srid
                attribute.getCardinality() != null && attribute.getCardinality().getMinimum() >= 1,
                card != null ? card.getMinimum() : 0,
                card != null ? card.getMaximum() : 1,
                supportsArcs,
                isArea,
                isMulti
        );
    }

    public Type resolveBaseType(AttributeDef attribute) {
        Type type = attribute.getDomainResolvingAll();
        if (type == null) {
            type = attribute.getDomain();
        }

        Set<Type> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (type instanceof TypeAlias alias) {
            if (!visited.add(type)) {
                throw new IllegalStateException(
                        "Cyclic INTERLIS type alias for " + attribute.getScopedName());
            }
            Domain domain = alias.getAliasing();
            if (domain == null) break;
            type = domain.getType();
        }
        return type;
    }

    private AbstractCoordType extractCoordType(Type base) {
        if (base instanceof AbstractCoordType act) return act;
        if (base instanceof LineType lt) {
            Type cpType = lt.getControlPointDomain().getType();
            return cpType instanceof AbstractCoordType act ? act : null;
        }
        if (base instanceof AbstractSurfaceOrAreaType sat) {
            Type cpType = sat.getControlPointDomain().getType();
            return cpType instanceof AbstractCoordType act ? act : null;
        }
        return null;
    }

    private Domain resolveCoordinateDomain(AttributeDef attribute) {
        Type type = attribute.getDomain();
        Set<Type> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (type instanceof TypeAlias alias) {
            if (!visited.add(type)) break;
            Domain domain = alias.getAliasing();
            if (domain == null) break;
            Type domainType = domain.getType();
            if (domainType instanceof AbstractCoordType) {
                return domain;
            }
            type = domainType;
        }
        return null;
    }
}
