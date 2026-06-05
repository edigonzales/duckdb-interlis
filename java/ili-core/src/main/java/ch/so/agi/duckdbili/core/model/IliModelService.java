package ch.so.agi.duckdbili.core.model;

import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.config.FileEntry;
import ch.interlis.ili2c.config.FileEntryKind;
import ch.interlis.ili2c.metamodel.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class IliModelService {

    private final Map<String, TransferDescription> cache = new HashMap<>();

    private TransferDescription compileIli(String modelDir) {
        String key = modelDir;
        if (cache.containsKey(key)) return cache.get(key);
        try {
            Ili2cSettings settings = new Ili2cSettings();
            Main.setDefaultIli2cPathMap(settings);
            settings.setIlidirs(modelDir);
            Configuration cfg = new Configuration();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(modelDir), "*.ili")) {
                for (Path f : stream)
                    cfg.addFileEntry(new FileEntry(f.toAbsolutePath().toString(), FileEntryKind.ILIMODELFILE));
            } catch (IOException e) { return null; }
            cfg.setAutoCompleteModelList(true);
            TransferDescription td = Main.runCompiler(cfg, settings, null);
            if (td != null) cache.put(key, td);
            return td;
        } catch (Exception e) { return null; }
    }

    private static boolean isBaseModel(String name) {
        return "INTERLIS".equals(name);
    }

    public String getModels(String modelDir) {
        StringBuilder sb = new StringBuilder();
        TransferDescription td = compileIli(modelDir);
        if (td == null) return "";
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (isBaseModel(m.getName())) continue;
            sb.append(e(m.getName())).append('\t').append(e(m.getModelVersion())).append('\t');
            sb.append(e(m.getIssuer())).append('\t').append(e(m.getLanguage())).append('\t');
            sb.append(e(m.getIliVersion())).append('\n');
        }
        return sb.toString();
    }

    public String getTopics(String modelDir, String modelName) {
        StringBuilder sb = new StringBuilder();
        TransferDescription td = compileIli(modelDir);
        if (td == null) return "";
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (modelName != null && !modelName.equals(m.getName())) continue;
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                if (eit.next() instanceof Topic t) {
                    sb.append(e(m.getName())).append('\t').append(e(t.getName())).append('\t');
                    sb.append(t.isViewTopic() ? "VIEW" : "TABLE").append('\n');
                }
            }
        }
        return sb.toString();
    }

    public String getClasses(String modelDir, String modelName) {
        StringBuilder sb = new StringBuilder();
        TransferDescription td = compileIli(modelDir);
        if (td == null) return "";
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
                            sb.append(e(m.getName())).append('\t').append(e(t.getName())).append('\t');
                            sb.append(e(c.getName())).append('\t').append(kind).append('\t');
                            sb.append(c.isAbstract() ? "true" : "false").append('\t');
                            sb.append(c.getExtending() != null ? "true" : "false").append('\t');
                            sb.append(e(base)).append('\n');
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
        if (td == null) return "";
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
                                sb.append(e(m.getName())).append('\t').append(e(t.getName())).append('\t');
                                sb.append(e(c.getName())).append('\t').append(e(an)).append('\t');
                                sb.append(e(tn)).append('\t').append(kd).append('\t');
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
        if (td == null) return "";
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
            sb.append(e(mn)).append('\t').append(e(tn)).append('\t');
            sb.append(e(ename)).append('\t').append(e(ee.getName())).append('\t');
            sb.append(ee.getSourceLine()).append('\n');
        }
    }

    private static String formatEnumType(EnumerationType et) {
        ch.interlis.ili2c.metamodel.Enumeration en = et.getConsolidatedEnumeration();
        if (en == null) en = et.getEnumeration();
        if (en == null) return e(et.getName());
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

    private static String e(Object s) {
        if (s == null) return "";
        return s.toString().replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }
}
