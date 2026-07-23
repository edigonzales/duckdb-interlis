package ch.so.agi.duckdbili.core.model;

import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ilirepository.IliManager;
import ch.so.agi.duckdbili.core.logging.IliLogger;
import ch.so.agi.duckdbili.core.transport.TsvCodec;

import ch.so.agi.duckdbili.core.geometry.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static java.util.Collections.emptySet;

public class IliModelService {

    private static final String DEFAULT_MODELDIR = System.getenv("ILI_DEFAULT_MODELDIR") != null
            ? System.getenv("ILI_DEFAULT_MODELDIR")
            : "https://models.interlis.ch";

    private TransferDescription compileIli(String modelSources, String modelName) {
        String effectiveSources = ModelRepositoryResolver.resolveToString(modelSources, DEFAULT_MODELDIR);
        String normalizedModel = normalizeFilter(modelName);
        String fingerprint = ModelCache.computeFingerprint(effectiveSources);
        Set<String> modelNames = normalizedModel == null
                ? emptySet()
                : Set.of(normalizedModel);
        ModelCache.CacheKey key = new ModelCache.CacheKey(effectiveSources, modelNames, fingerprint);
        return ModelCache.getInstance().getOrCompile(
                key,
                () -> doCompileIli(effectiveSources, normalizedModel));
    }

    private TransferDescription doCompileIli(String effectiveSources, String modelName) {
        try {
            IliManager manager = new IliManager();
            try {
                ModelRepositoryResolver.validateSources(effectiveSources, DEFAULT_MODELDIR);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("INTERLIS model compilation failed for: " + effectiveSources
                        + " — " + e.getMessage(), e);
            }

            List<String> repositories = ModelRepositoryResolver.repositorySources(effectiveSources, DEFAULT_MODELDIR);
            manager.setRepositories(repositories.toArray(String[]::new));

            ArrayList<String> entries = new ArrayList<>();
            for (Path file : ModelRepositoryResolver.localFiles(effectiveSources, DEFAULT_MODELDIR)) {
                entries.add(file.toAbsolutePath().toString());
            }
            for (Path directory : ModelRepositoryResolver.localDirectories(effectiveSources, DEFAULT_MODELDIR)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.ili")) {
                    for (Path f : stream)
                        entries.add(f.toAbsolutePath().toString());
                }
            }

            Configuration cfg;
            if (modelName != null && ModelRepositoryResolver.localFiles(effectiveSources, DEFAULT_MODELDIR).isEmpty()) {
                // IliManager resolves the selected model and its imports from
                // the configured repositories without compiling every local
                // model in those repositories.
                cfg = manager.getConfig(new ArrayList<>(List.of(modelName)), 0.0);
            } else {
                if (entries.isEmpty()) {
                    throw new RuntimeException("no local .ili files found; a model name is required for remote-only sources");
                }
                cfg = manager.getConfigWithFiles(entries, null, 0.0);
            }
            if (cfg == null) {
                throw new RuntimeException("INTERLIS model compilation failed for: " + effectiveSources
                        + " — no ILI files found or invalid model directory");
            }

            Ili2cSettings settings = new Ili2cSettings();
            Main.setDefaultIli2cPathMap(settings);
            settings.setIlidirs(effectiveSources);

            IliLogger.suppress();
            try {
                TransferDescription td = Main.runCompiler(cfg, settings, null);
                if (td == null) {
                    throw new RuntimeException("INTERLIS model compilation returned null for: " + effectiveSources);
                }
                return td;
            } finally {
                IliLogger.restore();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("INTERLIS model compilation failed for: " + effectiveSources, e);
        }
    }

    // Kept as a small compatibility seam for the cache tests and internal
    // callers that exercise compilation without a model filter.
    private TransferDescription doCompileIli(String modelSources) {
        String effectiveSources = ModelRepositoryResolver.resolveToString(modelSources, DEFAULT_MODELDIR);
        return doCompileIli(effectiveSources, null);
    }

    private static String normalizeFilter(String value) {
        if (value == null || value.isBlank()) return null;
        return value;
    }

    private static boolean isBaseModel(String name) {
        return "INTERLIS".equals(name);
    }

    public String getModels(String modelSources, String modelName) {
        StringBuilder sb = new StringBuilder();
        String selectedModel = normalizeFilter(modelName);
        TransferDescription td = compileIli(modelSources, selectedModel);
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (isBaseModel(m.getName())) continue;
            if (selectedModel != null && !selectedModel.equals(m.getName())) continue;
            sb.append(TsvCodec.encodeNullable(m.getName())).append('\t').append(TsvCodec.encodeNullable(m.getModelVersion())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.getIssuer())).append('\t').append(TsvCodec.encodeNullable(m.getLanguage())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.getIliVersion())).append('\n');
        }
        return sb.toString();
    }

    public String getTopics(String modelSources, String modelName) {
        StringBuilder sb = new StringBuilder();
        String selectedModel = normalizeFilter(modelName);
        TransferDescription td = compileIli(modelSources, selectedModel);
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (selectedModel != null && !selectedModel.equals(m.getName())) continue;
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                if (eit.next() instanceof Topic t) {
                    sb.append(TsvCodec.encodeNullable(m.getName())).append('\t').append(TsvCodec.encodeNullable(t.getName())).append('\t');
                    sb.append(t.isViewTopic() ? "VIEW" : "TABLE").append('\n');
                }
            }
        }
        return sb.toString();
    }

    public String getClasses(String modelSources, String modelName, String classFilter) {
        StringBuilder sb = new StringBuilder();
        String selectedModel = normalizeFilter(modelName);
        TransferDescription td = compileIli(modelSources, selectedModel);
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (isBaseModel(m.getName())) continue;
            if (selectedModel != null && !selectedModel.equals(m.getName())) continue;
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                Element el = eit.next();
                if (el instanceof Topic t) {
                    for (Iterator<Element> tit = t.iterator(); tit.hasNext(); ) {
                        Element tel = tit.next();
                        if (tel instanceof AbstractClassDef c && !(tel instanceof AssociationDef)) {
                            if (!matchesClass(classFilter, m, t, c)) continue;
                            String kind = tel instanceof Table ? "TABLE" : "CLASS";
                            String base = c.getExtending() != null ? c.getExtending().getScopedName(null) : "";
                            sb.append(TsvCodec.encodeNullable(m.getName())).append('\t').append(TsvCodec.encodeNullable(t.getName())).append('\t');
                            sb.append(TsvCodec.encodeNullable(c.getName())).append('\t').append(kind).append('\t');
                            sb.append(c.isAbstract() ? "true" : "false").append('\t');
                            sb.append(c.getExtending() != null ? "true" : "false").append('\t');
                            sb.append(TsvCodec.encodeNullable(base)).append('\n');
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    public String getAttributes(String modelSources, String modelName, String classFilter) {
        StringBuilder sb = new StringBuilder();
        String selectedModel = normalizeFilter(modelName);
        TransferDescription td = compileIli(modelSources, selectedModel);
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (isBaseModel(m.getName())) continue;
            if (selectedModel != null && !selectedModel.equals(m.getName())) continue;
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                Element el = eit.next();
                if (el instanceof Topic t) {
                    for (Iterator<Element> tit = t.iterator(); tit.hasNext(); ) {
                        Element tel = tit.next();
                        if (tel instanceof AbstractClassDef c && !(tel instanceof AssociationDef)) {
                            if (!matchesClass(classFilter, m, t, c)) continue;
                            Iterator<?> ait = c.getAttributesAndRoles2();
                            while (ait.hasNext()) {
                                ViewableTransferElement vte = (ViewableTransferElement) ait.next();
                                Object a = vte.obj;
                                String an = a instanceof AttributeDef ad ? ad.getName()
                                        : a instanceof RoleDef rd ? rd.getName() : "";
                                if (an.isBlank()) continue;
                                String tn = ""; String kd; boolean man = false; int cm = 0, cx = 1;
                                if (a instanceof AttributeDef ad) {
                                    kd = "ATTRIBUTE";
                                    Type dm = ad.getDomainResolvingAliases();
                                    if (dm != null) {
                                        if (dm instanceof EnumerationType et) {
                                            tn = formatEnumType(et);
                                        } else {
                                            tn = dm.getScopedName(null);
                                            if (tn == null || tn.isBlank()) tn = dm.getName();
                                            // For types without name, use the base type name
                                            if (tn == null || tn.isBlank()) {
                                                Type base = dm;
                                                while (base != null && (tn == null || tn.isBlank())) {
                                                    Element ext = base.getExtending();
                                                    if (ext instanceof Type bt) {
                                                        base = bt;
                                                        tn = bt.getScopedName(null);
                                                        if (tn == null || tn.isBlank()) tn = bt.getName();
                                                    } else break;
                                                }
                                            }
                                        }
                                        man = dm.isMandatory();
                                        Cardinality cd = dm.getCardinality();
                                        if (cd != null) { cm = (int)cd.getMinimum(); cx = (int)cd.getMaximum(); }
                                    }
                                } else if (a instanceof RoleDef rd) {
                                    kd = "ROLE";
                                    ReferenceType rf = rd.getReference();
                                    if (rf != null) {
                                        tn = rf.getScopedName(null);
                                        if (tn == null || tn.isBlank()) tn = rf.getName();
                                    }
                                    Cardinality cd = rd.getCardinality();
                                    if (cd != null) { cm = (int)cd.getMinimum(); cx = (int)cd.getMaximum(); }
                                } else continue;
                                sb.append(TsvCodec.encodeNullable(m.getName())).append('\t').append(TsvCodec.encodeNullable(t.getName())).append('\t');
                                sb.append(TsvCodec.encodeNullable(c.getName())).append('\t').append(TsvCodec.encodeNullable(an)).append('\t');
                                sb.append(TsvCodec.encodeNullable(tn)).append('\t').append(kd).append('\t');
                                sb.append(man ? "true" : "false").append('\t');
                                sb.append(cm).append('\t').append(cx).append('\n');
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    public String getEnumerations(String modelSources, String modelName) {
        StringBuilder sb = new StringBuilder();
        String selectedModel = normalizeFilter(modelName);
        TransferDescription td = compileIli(modelSources, selectedModel);
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (isBaseModel(m.getName())) continue;
            if (selectedModel != null && !selectedModel.equals(m.getName())) continue;
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                Element el = eit.next();
                if (el instanceof Topic t) collectEnums(t, m.getName(), t.getName(), sb);
                else if (el instanceof EnumerationType et) appendEnum(et, m.getName(), "", sb);
            }
            // Also scan for inline enums in class attributes
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                Element el = eit.next();
                if (el instanceof Topic t) {
                    for (Iterator<Element> tit = t.iterator(); tit.hasNext(); ) {
                        Element tel = tit.next();
                        if (tel instanceof AbstractClassDef c && !(tel instanceof AssociationDef)) {
                            Iterator<?> ait = c.getAttributesAndRoles2();
                            while (ait.hasNext()) {
                                ViewableTransferElement vte = (ViewableTransferElement) ait.next();
                                if (vte.obj instanceof AttributeDef ad) {
                                    Type dm = ad.getDomainResolvingAliases();
                                    if (dm instanceof EnumerationType et) {
                                        // Use attribute name if enum has no name (inline enum)
                                        String ename = et.getScopedName(null);
                                        if (ename == null || ename.isBlank()) ename = ad.getName();
                                        appendEnumNamed(et, m.getName(), t.getName(), ename, sb);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private void collectEnums(Topic t, String mn, String tn, StringBuilder sb) {
        for (Iterator<Element> it = t.iterator(); it.hasNext(); ) {
            if (it.next() instanceof EnumerationType et) appendEnum(et, mn, tn, sb);
        }
    }

    private void appendEnum(EnumerationType et, String mn, String tn, StringBuilder sb) {
        String ename = et.getScopedName(null);
        if (ename == null || ename.isBlank()) ename = et.getName();
        appendEnumNamed(et, mn, tn, ename, sb);
    }

    private void appendEnumNamed(EnumerationType et, String mn, String tn, String ename, StringBuilder sb) {
        ch.interlis.ili2c.metamodel.Enumeration en = et.getConsolidatedEnumeration();
        if (en == null) en = et.getEnumeration();
        if (en == null) return;
        for (Iterator<ch.interlis.ili2c.metamodel.Enumeration.Element> eit = en.getElements(); eit.hasNext(); ) {
            ch.interlis.ili2c.metamodel.Enumeration.Element ee = eit.next();
            sb.append(TsvCodec.encodeNullable(mn)).append('\t').append(TsvCodec.encodeNullable(tn)).append('\t');
            sb.append(TsvCodec.encodeNullable(ename)).append('\t').append(TsvCodec.encodeNullable(ee.getName())).append('\t');
            sb.append(ee.getSourceLine()).append('\n');
        }
    }

    public String getGeometryAttributes(String modelSources, String modelFilter, String classFilter) {
        String selectedModel = normalizeFilter(modelFilter);
        TransferDescription td = compileIli(modelSources, selectedModel);
        InterlisGeometryTypeResolver typeResolver = new InterlisGeometryTypeResolver();
        GeometryCrsResolver crsResolver = new MapGeometryCrsResolver();
        GeometryAttributeMetadataService service = new GeometryAttributeMetadataService(typeResolver, crsResolver);

        List<GeometryMetadata> attrs = service.listGeometryAttributes(td, selectedModel, classFilter);

        StringBuilder sb = new StringBuilder();
        // No header line – columns are defined by the C extension bind callback

        for (GeometryMetadata m : attrs) {
            sb.append(TsvCodec.encodeNullable(m.modelName())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.topicName())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.className())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.modelName() + "." + m.topicName() + "." + m.className())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.attributeName())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.attributeFqn())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.geometryKind().name())).append('\t');
            sb.append(m.dimension().coordinateDimension()).append('\t');
            sb.append(TsvCodec.encodeNullable(m.coordinateDomainName())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.coordinateDomainFqn())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.crsAuthName())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.crsCode())).append('\t');
            sb.append(m.srid() != null ? m.srid().toString() : "\\N").append('\t');
            sb.append(m.mandatory() ? "true" : "false").append('\t');
            sb.append(m.cardinalityMin()).append('\t');
            sb.append(m.cardinalityMax()).append('\t');
            sb.append(m.supportsArcs() ? "true" : "false").append('\t');
            sb.append(m.isAreaType() ? "true" : "false").append('\t');
            sb.append(m.isMultiType() ? "true" : "false").append('\t');
            sb.append("WKT").append('\t');
            sb.append(TsvCodec.encodeNullable("ST_GeomFromText")).append('\n');
        }
        return sb.toString();
    }

    private static boolean matchesClass(String classFilter, Model model, Topic topic, AbstractClassDef classDef) {
        if (classFilter == null || classFilter.isBlank()) return true;
        String classFqn = model.getName() + "." + topic.getName() + "." + classDef.getName();
        String topicClass = topic.getName() + "." + classDef.getName();
        return classFilter.equals(classDef.getName())
                || classFilter.equals(topicClass)
                || classFilter.equals(classFqn);
    }

    private static String formatEnumType(EnumerationType et) {
        ch.interlis.ili2c.metamodel.Enumeration en = et.getConsolidatedEnumeration();
        if (en == null) en = et.getEnumeration();
        if (en == null) return TsvCodec.encodeNullable(et.getName());
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (Iterator<ch.interlis.ili2c.metamodel.Enumeration.Element> it = en.getElements(); it.hasNext(); ) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(it.next().getName());
        }
        sb.append(")");
        return sb.toString();
    }
}
