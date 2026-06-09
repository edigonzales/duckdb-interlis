package ch.so.agi.duckdbili.core.model;

import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ilirepository.IliManager;
import ch.so.agi.duckdbili.core.logging.IliLogger;
import ch.so.agi.duckdbili.core.transport.TsvCodec;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static java.util.Collections.emptySet;

public class IliModelService {

    private static final String DEFAULT_MODELDIR = System.getenv("ILI_DEFAULT_MODELDIR") != null
            ? System.getenv("ILI_DEFAULT_MODELDIR")
            : "https://models.interlis.ch";

    private TransferDescription compileIli(String modelDir) {
        String effectiveDir = ModelRepositoryResolver.resolveToString(modelDir, DEFAULT_MODELDIR);
        String fingerprint = ModelCache.computeFingerprint(effectiveDir);
        ModelCache.CacheKey key = new ModelCache.CacheKey(effectiveDir, emptySet(), fingerprint);
        return ModelCache.getInstance().getOrCompile(key, () -> doCompileIli(effectiveDir));
    }

    private TransferDescription doCompileIli(String effectiveDir) {
        try {
            IliManager manager = new IliManager();
            List<String> repositories = ModelRepositoryResolver.resolve(effectiveDir, DEFAULT_MODELDIR);
            manager.setRepositories(repositories.toArray(String[]::new));

            ArrayList<String> entries = new ArrayList<>();
            for (Path directory : ModelRepositoryResolver.localDirectories(effectiveDir, DEFAULT_MODELDIR)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.ili")) {
                    for (Path f : stream)
                        entries.add(f.toAbsolutePath().toString());
                }
            }

            Configuration cfg = manager.getConfigWithFiles(entries, null, 0.0);
            if (cfg == null) {
                throw new RuntimeException("INTERLIS model compilation failed for: " + effectiveDir
                        + " — no ILI files found or invalid model directory");
            }

            Ili2cSettings settings = new Ili2cSettings();
            Main.setDefaultIli2cPathMap(settings);
            settings.setIlidirs(effectiveDir);

            IliLogger.suppress();
            try {
                TransferDescription td = Main.runCompiler(cfg, settings, null);
                if (td == null) {
                    throw new RuntimeException("INTERLIS model compilation returned null for: " + effectiveDir);
                }
                return td;
            } finally {
                IliLogger.restore();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("INTERLIS model compilation failed for: " + effectiveDir, e);
        }
    }

    private static boolean isBaseModel(String name) {
        return "INTERLIS".equals(name);
    }

    public String getModels(String modelDir) {
        StringBuilder sb = new StringBuilder();
        TransferDescription td = compileIli(modelDir);
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (isBaseModel(m.getName())) continue;
            sb.append(TsvCodec.encodeNullable(m.getName())).append('\t').append(TsvCodec.encodeNullable(m.getModelVersion())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.getIssuer())).append('\t').append(TsvCodec.encodeNullable(m.getLanguage())).append('\t');
            sb.append(TsvCodec.encodeNullable(m.getIliVersion())).append('\n');
        }
        return sb.toString();
    }

    public String getTopics(String modelDir, String modelName) {
        StringBuilder sb = new StringBuilder();
        TransferDescription td = compileIli(modelDir);
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (modelName != null && !modelName.equals(m.getName())) continue;
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                if (eit.next() instanceof Topic t) {
                    sb.append(TsvCodec.encodeNullable(m.getName())).append('\t').append(TsvCodec.encodeNullable(t.getName())).append('\t');
                    sb.append(t.isViewTopic() ? "VIEW" : "TABLE").append('\n');
                }
            }
        }
        return sb.toString();
    }

    public String getClasses(String modelDir, String modelName) {
        StringBuilder sb = new StringBuilder();
        TransferDescription td = compileIli(modelDir);
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (isBaseModel(m.getName())) continue;
            if (modelName != null && !modelName.equals(m.getName())) continue;
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                Element el = eit.next();
                if (el instanceof Topic t) {
                    for (Iterator<Element> tit = t.iterator(); tit.hasNext(); ) {
                        Element tel = tit.next();
                        if (tel instanceof AbstractClassDef c && !(tel instanceof AssociationDef)) {
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

    public String getAttributes(String modelDir, String className) {
        StringBuilder sb = new StringBuilder();
        TransferDescription td = compileIli(modelDir);
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (isBaseModel(m.getName())) continue;
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                Element el = eit.next();
                if (el instanceof Topic t) {
                    for (Iterator<Element> tit = t.iterator(); tit.hasNext(); ) {
                        Element tel = tit.next();
                        if (tel instanceof AbstractClassDef c && !(tel instanceof AssociationDef)) {
                            String fn = m.getName() + "." + t.getName() + "." + c.getName();
                            if (className != null && !className.equals(fn)) continue;
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

    public String getEnumerations(String modelDir, String modelName) {
        StringBuilder sb = new StringBuilder();
        TransferDescription td = compileIli(modelDir);
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (isBaseModel(m.getName())) continue;
            if (modelName != null && !modelName.equals(m.getName())) continue;
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

