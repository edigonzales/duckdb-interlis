package ch.so.agi.duckdbili.core.geometry;

import ch.interlis.ili2c.metamodel.*;

import java.util.*;

/**
 * Lists geometry attributes from an INTERLIS TransferDescription.
 * Never guesses CRS from coordinate values.
 */
public final class GeometryAttributeMetadataService {

    private final InterlisGeometryTypeResolver typeResolver;
    private final GeometryCrsResolver crsResolver;

    public GeometryAttributeMetadataService(
            InterlisGeometryTypeResolver typeResolver,
            GeometryCrsResolver crsResolver) {
        if (typeResolver == null) throw new NullPointerException("typeResolver is null");
        if (crsResolver == null) throw new NullPointerException("crsResolver is null");
        this.typeResolver = typeResolver;
        this.crsResolver = crsResolver;
    }

    public List<GeometryMetadata> listGeometryAttributes(
            TransferDescription td,
            String modelFilter,
            String classFilter) {

        List<GeometryMetadata> result = new ArrayList<>();
        for (Iterator<Model> mit = td.iterator(); mit.hasNext(); ) {
            Model model = mit.next();
            if (modelFilter != null && !modelFilter.isBlank()
                    && !modelFilter.equals(model.getName())) {
                continue;
            }
            for (Iterator<Element> eit = model.iterator(); eit.hasNext(); ) {
                Element el = eit.next();
                if (el instanceof Topic topic) {
                    for (Iterator<Element> tit = topic.iterator(); tit.hasNext(); ) {
                        Element tel = tit.next();
                        if (tel instanceof AbstractClassDef classDef
                                && !(tel instanceof AssociationDef)) {
                            if (classFilter != null && !classFilter.isBlank()
                                    && !classFilter.equals(classDef.getName())) {
                                continue;
                            }
                            collectFromClass(model, topic, classDef, result);
                        }
                    }
                }
            }
        }

        result.sort(Comparator
                .comparing(GeometryMetadata::modelName)
                .thenComparing(GeometryMetadata::topicName)
                .thenComparing(GeometryMetadata::className)
                .thenComparing(GeometryMetadata::attributeName));

        return Collections.unmodifiableList(result);
    }

    private void collectFromClass(
            Model model,
            Topic topic,
            AbstractClassDef classDef,
            List<GeometryMetadata> result) {

        Iterator<?> ait = classDef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof AttributeDef ad
                    && typeResolver.isGeometryAttribute(ad)) {
                GeometryMetadata base = typeResolver.resolveMetadata(model, topic, classDef, ad);
                GeometryMetadataContext ctx = new GeometryMetadataContext(
                        base.modelName(), base.topicName(), base.className(),
                        base.attributeName(), base.coordinateDomainFqn());
                Optional<CrsIdentifier> crs = crsResolver.resolve(ctx);
                GeometryMetadata withCrs = new GeometryMetadata(
                        base.modelName(),
                        base.topicName(),
                        base.className(),
                        base.attributeName(),
                        base.attributeFqn(),
                        base.geometryKind(),
                        base.dimension(),
                        base.coordinateDomainName(),
                        base.coordinateDomainFqn(),
                        crs.map(CrsIdentifier::authority).orElse(null),
                        crs.map(CrsIdentifier::code).orElse(null),
                        crs.map(CrsIdentifier::srid).orElse(null),
                        base.mandatory(),
                        base.cardinalityMin(),
                        base.cardinalityMax(),
                        base.supportsArcs(),
                        base.isAreaType(),
                        base.isMultiType()
                );
                result.add(withCrs);
            }
        }
    }
}
