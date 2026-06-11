package ch.so.agi.duckdbili.core.model;

import ch.interlis.ili2c.metamodel.AbstractCoordType;
import ch.interlis.ili2c.metamodel.AbstractSurfaceOrAreaType;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.Domain;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.LineType;
import ch.interlis.ili2c.metamodel.MultiAreaType;
import ch.interlis.ili2c.metamodel.MultiCoordType;
import ch.interlis.ili2c.metamodel.MultiPolylineType;
import ch.interlis.ili2c.metamodel.MultiSurfaceType;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectType;
import ch.interlis.ili2c.metamodel.PrecisionDecimal;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.TypeAlias;

import java.util.Locale;

/**
 * Maps INTERLIS attribute domains to the logical DuckDB types exposed by the
 * typed XTF readers and the import SQL generator.
 */
public final class InterlisLogicalTypeMapper {

    public enum LogicalType {
        VARCHAR,
        BIGINT,
        DOUBLE,
        BOOLEAN,
        DATE,
        TIME,
        TIMESTAMP,
        GEOMETRY;

        public String sqlTypeName() {
            return name();
        }
    }

    public LogicalType mapAttribute(AttributeDef attributeDef) {
        Type declared = attributeDef != null
                ? (attributeDef.getDomainResolvingAll() != null ? attributeDef.getDomainResolvingAll() : attributeDef.getDomain())
                : null;
        Type raw = attributeDef != null ? attributeDef.getDomain() : null;
        Type base = resolveToBaseType(declared);

        String declaredTypeName = normalizedDeclaredTypeName(raw, base);
        if (declaredTypeName.contains("BOOLEAN")) return LogicalType.BOOLEAN;
        if (declaredTypeName.contains("DATETIME")) return LogicalType.TIMESTAMP;
        if (declaredTypeName.contains("TIME") && !declaredTypeName.contains("DATETIME")) return LogicalType.TIME;
        if (declaredTypeName.contains("DATE") && !declaredTypeName.contains("DATETIME")) return LogicalType.DATE;

        return mapResolvedType(base);
    }

    public LogicalType mapType(Type domain) {
        return mapResolvedType(resolveToBaseType(domain));
    }

    private LogicalType mapResolvedType(Type domain) {
        if (domain == null) return LogicalType.VARCHAR;
        if (domain instanceof NumericType numericType) {
            PrecisionDecimal min = numericType.getMinimum();
            PrecisionDecimal max = numericType.getMaximum();
            boolean hasDecimals = (min != null && min.getAccuracy() > 0)
                    || (max != null && max.getAccuracy() > 0);
            return hasDecimals ? LogicalType.DOUBLE : LogicalType.BIGINT;
        }
        if (domain instanceof TextType) return LogicalType.VARCHAR;
        if (domain instanceof EnumerationType) return LogicalType.VARCHAR;

        if (domain instanceof AbstractCoordType
                || domain instanceof LineType
                || domain instanceof AbstractSurfaceOrAreaType
                || domain instanceof MultiCoordType
                || domain instanceof MultiSurfaceType
                || domain instanceof MultiPolylineType
                || domain instanceof MultiAreaType) {
            return LogicalType.GEOMETRY;
        }
        if (domain instanceof CompositionType) return LogicalType.VARCHAR;
        if (domain instanceof ObjectType) return LogicalType.VARCHAR;
        if (domain instanceof ReferenceType) return LogicalType.VARCHAR;

        return LogicalType.VARCHAR;
    }

    public Type resolveToBaseType(AttributeDef attributeDef) {
        if (attributeDef == null) return null;
        return resolveToBaseType(attributeDef.getDomainResolvingAll() != null
                ? attributeDef.getDomainResolvingAll()
                : attributeDef.getDomain());
    }

    public Type resolveToBaseType(Type domain) {
        Type current = domain;
        for (int i = 0; i < 10 && current != null; i++) {
            if (!(current instanceof TypeAlias typeAlias)) {
                return current;
            }
            Domain aliasing = typeAlias.getAliasing();
            current = aliasing != null ? aliasing.getType() : null;
        }
        return current;
    }

    private String normalizedDeclaredTypeName(Type rawDomain, Type baseDomain) {
        String typeName = null;
        if (rawDomain instanceof TypeAlias typeAlias) {
            Domain aliasing = typeAlias.getAliasing();
            if (aliasing != null) {
                typeName = aliasing.getScopedName(null);
                if (typeName == null) typeName = aliasing.getName();
            }
        }
        if (typeName == null && rawDomain != null) {
            typeName = rawDomain.getScopedName(null);
            if (typeName == null) typeName = rawDomain.getName();
        }
        if (typeName == null && baseDomain != null) {
            typeName = baseDomain.getScopedName(null);
            if (typeName == null) typeName = baseDomain.getName();
        }
        return typeName != null ? typeName.toUpperCase(Locale.ROOT) : "";
    }
}
