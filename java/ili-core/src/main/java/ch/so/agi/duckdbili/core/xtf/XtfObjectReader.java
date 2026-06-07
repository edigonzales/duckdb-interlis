package ch.so.agi.duckdbili.core.xtf;

import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ilirepository.IliManager;
import ch.interlis.iom.IomObject;
import ch.interlis.iox.*;
import ch.interlis.iox_j.jts.Iox2jts;
import ch.interlis.iox_j.jts.Iox2jtsext;
import ch.interlis.iom_j.xtf.Xtf24Reader;
import ch.interlis.iox_j.IoxIliReader;
import ch.interlis.iox_j.utility.ReaderFactory;
import ch.so.agi.duckdbili.core.logging.IliLogger;

import java.io.File;
import java.nio.file.*;
import java.util.*;

public class XtfObjectReader {

    private static final String DEFAULT_MODELDIR = System.getenv("ILI_DEFAULT_MODELDIR") != null
            ? System.getenv("ILI_DEFAULT_MODELDIR")
            : "https://models.interlis.ch";

    /**
     * Read all objects from an XTF file. TSV columns: xtf_bid, xtf_topic, xtf_class,
     * xtf_tid, operation, attributes_json, refs_json, geom_json, raw_event_json
     */
    public String readObjects(String xtfPath, String modelDir, String modelNames) {
        return readXtf(xtfPath, modelDir, modelNames, null);
    }

    /**
     * Read objects of a specific class. First line = TSV header (column names),
     * subsequent lines = data rows.
     */
    public String readClass(String xtfPath, String className, String modelDir) {
        return readXtf(xtfPath, modelDir, extractModelName(className), className, "json");
    }

    public String readClass(String xtfPath, String className, String modelDir, String nested) {
        return readXtf(xtfPath, modelDir, extractModelName(className), className, nested != null ? nested : "json");
    }

    /**
     * Returns just the column names (header line) for a class.
     */
    public String readClassSchema(String className, String modelDir) {
        return readClassSchema(className, modelDir, "json");
    }

    public String readClassSchema(String className, String modelDir, String nested) {
        TransferDescription td = compileModel(modelDir, extractModelName(className));
        if (td == null) return "";

        String[] parts = className.split("\\.");
        if (parts.length < 3) return "";

        AbstractClassDef cdef = findClass(td, parts[0], parts[1], parts[2]);
        if (cdef == null) return "";

        String colSuffix = "duckdb".equals(nested) ? "" : "_json";

        StringBuilder sb = new StringBuilder();
        sb.append("xtf_bid\txtf_tid\txtf_class");
        Iterator<?> ait = cdef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof AttributeDef ad) {
                if (isGeometryDomain(ad) || isMultiGeometryDomain(ad))
                    sb.append('\t').append(e(ad.getName() + "_wkb"));
                else if (isStructureDomain(ad) || isCompositionDomain(ad))
                    sb.append('\t').append(e(ad.getName() + colSuffix));
                else
                    sb.append('\t').append(e(ad.getName()));
            } else if (vte.obj instanceof RoleDef rd) {
                sb.append('\t').append(e(rd.getName() + "_ref"));
            }
        }
        sb.append("\tunsupported_json");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private String readXtf(String xtfPath, String modelDir, String modelNames, String className) {
        return readXtf(xtfPath, modelDir, modelNames, className, "json");
    }

    private String readXtf(String xtfPath, String modelDir, String modelNames, String className, String nested) {
        // Build effective modeldir: user-specified > XTF directory > default
        String xtfDir = "";
        try { xtfDir = new File(xtfPath).getAbsoluteFile().getParent(); } catch (Exception ignored) {}
        String md = (modelDir != null && !modelDir.isBlank()) ? modelDir
                : (!xtfDir.isBlank() ? xtfDir + ";" + DEFAULT_MODELDIR : DEFAULT_MODELDIR);

        TransferDescription td = compileModel(md, modelNames);
        if (td == null) return "";

        AbstractClassDef classDef = null;
        if (className != null) {
            String[] parts = className.split("\\.");
            if (parts.length < 3) return "";
            classDef = findClass(td, parts[0], parts[1], parts[2]);
            if (classDef == null) return "";
        }

        List<String> attrNames = new ArrayList<>();
        List<String> scalarAttrs = new ArrayList<>();
        List<String> structureAttrs = new ArrayList<>();
        List<String> bagAttrs = new ArrayList<>();
        List<String> geomAttrs = new ArrayList<>();
        List<String> roleRefs = new ArrayList<>();
        if (classDef != null) {
            Iterator<?> ait = classDef.getAttributesAndRoles2();
            while (ait.hasNext()) {
                ViewableTransferElement vte = (ViewableTransferElement) ait.next();
                if (vte.obj instanceof AttributeDef ad) {
                    attrNames.add(ad.getName());
                    if (isGeometryDomain(ad) || isMultiGeometryDomain(ad))
                        geomAttrs.add(ad.getName());
                    else if (isStructureDomain(ad))
                        structureAttrs.add(ad.getName());
                    else if (isCompositionDomain(ad))
                        bagAttrs.add(ad.getName());
                    else
                        scalarAttrs.add(ad.getName());
                } else if (vte.obj instanceof RoleDef rd) {
                    String refName = rd.getName() + "_ref";
                    attrNames.add(rd.getName());
                    roleRefs.add(rd.getName());
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        String colSuffix = "duckdb".equals(nested) ? "" : "_json";

        // Header for class-specific mode
        if (className != null) {
            sb.append("xtf_bid\txtf_tid\txtf_class");
            for (String an : scalarAttrs) sb.append('\t').append(e(an));
            for (String an : geomAttrs) sb.append('\t').append(e(an + "_wkb"));
            for (String an : structureAttrs) sb.append('\t').append(e(an + colSuffix));
            for (String an : bagAttrs) sb.append('\t').append(e(an + colSuffix));
            for (String rn : roleRefs) sb.append('\t').append(e(rn + "_ref"));
            sb.append("\tunsupported_json\n");
        }

        IoxReader reader = null;
        try {
            reader = new ReaderFactory().createReader(new File(xtfPath), null);
            if (reader == null) reader = new Xtf24Reader(new File(xtfPath));
            if (reader instanceof IoxIliReader iliReader) iliReader.setModel(td);

            String currentBid = "", currentTopic = "";
            IoxEvent event;
            while ((event = reader.read()) != null) {
                if (event instanceof StartBasketEvent sbe) {
                    currentBid = sbe.getBid() != null ? sbe.getBid() : "";
                    String[] topics = sbe.getTopicv();
                    currentTopic = topics != null && topics.length > 0 ? topics[0] : "";
                } else if (event instanceof ObjectEvent oe) {
                    IomObject obj = oe.getIomObject();
                    if (obj == null) continue;
                    String tag = obj.getobjecttag();
                    if (tag == null) continue;

                    if (className != null) {
                        String[] parts = className.split("\\.");
                        if (!tag.endsWith("." + parts[2])) continue;
                    }

                    String cn = tag.contains(".") ? tag.substring(tag.lastIndexOf('.') + 1) : tag;
                    String tid = obj.getobjectoid();
                    if (tid == null) tid = "";

                    if (className != null) {
                        // Class-specific output
                        sb.append(e(currentBid)).append('\t').append(e(tid)).append('\t').append(e(cn));
                        for (String an : scalarAttrs)
                            sb.append('\t').append(e(obj.getattrvalue(an)));
                        for (String an : geomAttrs)
                            sb.append('\t').append(e(buildGeometryWkb(obj, an)));
                        for (String an : structureAttrs)
                            sb.append('\t').append(e(buildSingleStructureJson(obj, an)));
                        for (String an : bagAttrs)
                            sb.append('\t').append(e(buildBagStructureJson(obj, an)));
                        for (String rn : roleRefs)
                            sb.append('\t').append(e(getRoleRef(obj, rn)));
                        sb.append('\t').append(e(buildUnsupported(obj, attrNames))).append('\n');
                    } else {
                        // Generic object stream output
                        int op = obj.getobjectoperation();
                        String operation = op == 1 ? "DELETE" : op == 2 ? "UPDATE" : op == 3 ? "INSERT" : "";
                        sb.append(e(currentBid)).append('\t').append(e(currentTopic)).append('\t');
                        sb.append(e(cn)).append('\t').append(e(tid)).append('\t');
                        sb.append(e(operation)).append('\t').append(e(buildAttrs(obj))).append('\t');
                        sb.append(e(buildRefs(obj))).append('\t').append(e(buildGeom(obj))).append('\t');
                        sb.append(e(buildRaw(obj))).append('\n');
                    }
                }
            }
        } catch (Exception ex) {
            if (IliLogger.isDebugEnabled()) {
                System.err.println("XTF read: " + ex.getMessage());
            }
        } finally {
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
        }
        return sb.toString();
    }

    private TransferDescription compileModel(String modelDir, String modelNames) {
        try {
            IliManager manager = new IliManager();

            // Configure repositories from modelDir
            List<String> repoList = new ArrayList<>();
            if (modelDir != null) {
                for (String part : modelDir.split(";")) {
                    String trimmed = part.trim();
                    if (!trimmed.isBlank()) {
                        repoList.add(trimmed);
                    }
                }
            }
            if (repoList.isEmpty()) {
                repoList.add(DEFAULT_MODELDIR);
            }
            manager.setRepositories(repoList.toArray(new String[0]));

            // Build entry list: model names, file paths, or scanned .ili files
            ArrayList<String> entries = new ArrayList<>();
            if (modelNames != null && !modelNames.isBlank()) {
                for (String entry : modelNames.split(";")) {
                    String trimmed = entry.trim();
                    if (!trimmed.isBlank()) {
                        entries.add(trimmed);
                    }
                }
            } else {
                for (String part : modelDir.split(";")) {
                    Path p = null;
                    try { p = Path.of(part.trim()); } catch (Exception ignored) {}
                    if (p != null && Files.isDirectory(p)) {
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.ili")) {
                            for (Path f : ds) {
                                entries.add(f.toAbsolutePath().toString());
                            }
                        }
                    }
                }
            }

            Configuration cfg = manager.getConfigWithFiles(entries, null, 0.0);
            if (cfg == null) return null;

            Ili2cSettings settings = new Ili2cSettings();
            Main.setDefaultIli2cPathMap(settings);
            settings.setIlidirs(modelDir);

            IliLogger.suppress();
            try {
                return Main.runCompiler(cfg, settings, null);
            } finally {
                IliLogger.restore();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractModelName(String className) {
        if (className == null) return null;
        int dot = className.indexOf('.');
        return dot > 0 ? className.substring(0, dot) : null;
    }

    private AbstractClassDef findClass(TransferDescription td, String mn, String tn, String cn) {
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (!mn.equals(m.getName())) continue;
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                Element el = eit.next();
                if (el instanceof Topic t && tn.equals(t.getName())) {
                    for (Iterator<Element> tit = t.iterator(); tit.hasNext(); ) {
                        Element tel = tit.next();
                        if (tel instanceof AbstractClassDef c && !(tel instanceof AssociationDef) && cn.equals(c.getName()))
                            return c;
                    }
                }
            }
        }
        return null;
    }

    private String buildUnsupported(IomObject obj, List<String> known) {
        Set<String> knownSet = new HashSet<>(known);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        int n = obj.getattrcount();
        for (int i = 0; i < n; i++) {
            String an = obj.getattrname(i);
            if (knownSet.contains(an)) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escJson(an)).append("\":\"");
            sb.append(escJson(obj.getattrvalue(an))).append("\"");
        }
        sb.append("}");
        return first ? "" : sb.toString();
    }

    private String buildAttrs(IomObject obj) {
        StringBuilder j = new StringBuilder("{");
        boolean first = true;
        int n = obj.getattrcount();
        for (int i = 0; i < n; i++) {
            String nm = obj.getattrname(i), v = obj.getattrvalue(nm);
            if (v == null) continue;
            if (!first) j.append(",");
            first = false;
            j.append("\"").append(escJson(nm)).append("\":\"").append(escJson(v)).append("\"");
        }
        j.append("}"); return j.toString();
    }

    private String buildRefs(IomObject obj) {
        String oid = obj.getobjectrefoid(), bid = obj.getobjectrefbid();
        if ((oid == null || oid.isBlank()) && (bid == null || bid.isBlank())) return "{}";
        StringBuilder j = new StringBuilder("{");
        if (oid != null && !oid.isBlank()) j.append("\"_ref_tid\":\"").append(escJson(oid)).append("\"");
        if (bid != null && !bid.isBlank()) {
            if (oid != null && !oid.isBlank()) j.append(",");
            j.append("\"_ref_bid\":\"").append(escJson(bid)).append("\"");
        }
        j.append("}"); return j.toString();
    }

    private String buildGeom(IomObject obj) {
        int n = obj.getxmlelecount();
        for (int i = 0; i < n; i++) {
            String tag = obj.getxmleleattrname(i);
            if (tag != null && (tag.contains("COORD") || tag.contains("Surface") || tag.contains("Polyline")))
                return "{\"_has_geometry\":true}";
        }
        return "{}";
    }

    private String buildRaw(IomObject obj) {
        return "{\"tag\":\"" + escJson(obj.getobjecttag()) + "\",\"tid\":\"" +
            escJson(obj.getobjectoid()) + "\",\"attr_count\":" + obj.getattrcount() + "}";
    }

    private static String e(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\t","\\t").replace("\n","\\n").replace("\r","\\r");
    }

    private static String escJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Structure / BAG OF detection
    // -----------------------------------------------------------------------

    private static boolean isStructureDomain(AttributeDef ad) {
        Type domain = ad.getDomain();
        if (domain instanceof ObjectType ot) {
            // ObjectType wraps a Viewable reference (used for single STRUCTURE attributes)
            // If isObjects() is true, it's a REFERENCE TO class, not an embedded structure
            return !ot.isObjects();
        }
        if (domain instanceof CompositionType) {
            // CompositionType is used for both single and bag structures
            // Single structure has cardinality max == 1
            Cardinality card = ad.getCardinality();
            return card != null && card.getMaximum() <= 1;
        }
        return false;
    }

    private static boolean isCompositionDomain(AttributeDef ad) {
        Type domain = ad.getDomain();
        if (domain instanceof CompositionType) {
            // Bag of structure has cardinality max > 1 or UNBOUND
            Cardinality card = ad.getCardinality();
            return card == null || card.getMaximum() > 1;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Structure value extraction
    // -----------------------------------------------------------------------

    private String buildSingleStructureJson(IomObject obj, String attrName) {
        int count = obj.getattrvaluecount(attrName);
        if (count == 0) return "";
        IomObject child = obj.getattrobj(attrName, 0);
        if (child == null) return "";
        return buildStructObjJson(child);
    }

    private String buildBagStructureJson(IomObject obj, String attrName) {
        int count = obj.getattrvaluecount(attrName);
        if (count == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            IomObject child = obj.getattrobj(attrName, i);
            if (child != null)
                sb.append(buildStructObjJson(child));
            else
                sb.append("null");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String buildStructObjJson(IomObject structObj) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        int n = structObj.getattrcount();
        for (int i = 0; i < n; i++) {
            String name = structObj.getattrname(i);
            String value = structObj.getattrvalue(name);
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escJson(name)).append("\":");
            if (value != null && !value.isEmpty())
                sb.append("\"").append(escJson(value)).append("\"");
            else
                sb.append("null");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Returns TSV with all STRUCTURE definitions used by a class.
     * Columns: structure_name, attr_name, attr_type, card_min, card_max
     */
    public String readStructures(String className, String modelDir) {
        TransferDescription td = compileModel(modelDir, extractModelName(className));
        if (td == null) return "";

        String[] parts = className.split("\\.");
        if (parts.length < 3) return "";

        AbstractClassDef cdef = findClass(td, parts[0], parts[1], parts[2]);
        if (cdef == null) return "";

        Set<String> seen = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        sb.append("structure_name\tattr_name\tattr_type\tcard_min\tcard_max\n");

        Iterator<?> ait = cdef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (!(vte.obj instanceof AttributeDef ad)) continue;
            Type domain = ad.getDomain();
            if (!(domain instanceof CompositionType ct) && !(domain instanceof ObjectType)) continue;

            Table structTable = null;
            if (domain instanceof CompositionType ct2) structTable = ct2.getComponentType();
            else if (domain instanceof ObjectType ot) {
                Viewable<?> ref = ot.getRef();
                if (ref instanceof Table t) structTable = t;
            }
            if (structTable == null || seen.contains(structTable.getName())) continue;
            seen.add(structTable.getName());

            Iterator<?> sait = structTable.getAttributesAndRoles2();
            while (sait.hasNext()) {
                ViewableTransferElement svte = (ViewableTransferElement) sait.next();
                if (!(svte.obj instanceof AttributeDef sad)) continue;
                Type sat = sad.getDomain();
                Cardinality card = sad.getCardinality();
                String typeName = "";
                if (sat instanceof EnumerationType et) {
                    StringBuilder etb = new StringBuilder("(");
                    boolean first = true;
                    ch.interlis.ili2c.metamodel.Enumeration consolidated = et.getConsolidatedEnumeration();
                    for (Iterator<?> eit = consolidated.getElements(); eit.hasNext(); ) {
                        if (!first) etb.append(", ");
                        first = false;
                        etb.append(e(((ch.interlis.ili2c.metamodel.Enumeration.Element) eit.next()).getName()));
                    }
                    etb.append(")");
                    typeName = etb.toString();
                }
                sb.append(e(structTable.getName())).append('\t');
                sb.append(e(sad.getName())).append('\t');
                sb.append(e(typeName)).append('\t');
                sb.append(card != null ? String.valueOf(card.getMinimum()) : "0").append('\t');
                sb.append(card != null ? String.valueOf(card.getMaximum()) : "1").append('\n');
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Geometry detection
    // -----------------------------------------------------------------------

    private static boolean isGeometryDomain(AttributeDef ad) {
        Type domain = resolveToBaseType(ad);
        return domain instanceof AbstractCoordType
            || domain instanceof LineType
            || domain instanceof AbstractSurfaceOrAreaType;
    }

    private static boolean isMultiGeometryDomain(AttributeDef ad) {
        Type domain = resolveToBaseType(ad);
        return domain instanceof MultiCoordType
            || domain instanceof MultiSurfaceType
            || domain instanceof MultiPolylineType
            || domain instanceof MultiAreaType;
    }

    private static Type resolveToBaseType(AttributeDef ad) {
        Type domain = ad.getDomainResolvingAll();
        if (domain == null) domain = ad.getDomain();
        // Unwrap TypeAlias -> Domain -> Type
        for (int i = 0; i < 5 && domain != null; i++) {
            if (domain instanceof TypeAlias ta) {
                Domain aliasing = ta.getAliasing();
                if (aliasing != null) domain = aliasing.getType();
            }
        }
        return domain;
    }

    // -----------------------------------------------------------------------
    // WKB extraction via Iox2jtsext hexwkb methods
    // -----------------------------------------------------------------------

    private String buildGeometryWkb(IomObject obj, String attrName) {
        int cnt = obj.getattrvaluecount(attrName);
        if (cnt == 0) return "";
        IomObject geom = obj.getattrobj(attrName, 0);
        if (geom == null) return "";
        return geomToHexWkb(geom);
    }

    private String geomToHexWkb(IomObject geom) {
        if (geom == null) return "";
        String tag = geom.getobjecttag();
        String shortType = tag;
        int dot = tag.lastIndexOf('.');
        if (dot >= 0) shortType = tag.substring(dot + 1);

        try {
            switch (shortType) {
                case "COORD":       return Iox2jtsext.coord2hexwkb(geom);
                case "POLYLINE":    return Iox2jtsext.polyline2hexwkb(geom, 0);
                case "SURFACE":     return Iox2jtsext.surface2hexwkb(geom, 0);
                case "MULTICOORD":   return Iox2jts.multicoord2hexwkb(geom);
                case "MULTIPOLYLINE": return Iox2jts.multipolyline2hexwkb(geom, 0);
                case "MULTISURFACE":  return Iox2jts.multisurface2hexwkb(geom, 0);
                default:            return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    // -----------------------------------------------------------------------
    // Role / Reference extraction
    // -----------------------------------------------------------------------

    private static String getRoleRef(IomObject obj, String roleName) {
        int cnt = obj.getattrvaluecount(roleName);
        if (cnt == 0) return "";
        IomObject child = obj.getattrobj(roleName, 0);
        if (child == null) return "";
        String ref = child.getobjectrefoid();
        return ref != null ? ref : "";
    }

    // -----------------------------------------------------------------------
    // Association reading
    // -----------------------------------------------------------------------

    public String readAssociationSchema(String associationName, String modelDir) {
        TransferDescription td = compileModel(modelDir, extractModelName(associationName));
        if (td == null) return "";

        String[] parts = associationName.split("\\.");
        if (parts.length < 3) return "";

        AssociationDef adef = findAssociation(td, parts[0], parts[1], parts[2]);
        if (adef == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("xtf_bid\txtf_tid\txtf_class");
        Iterator<?> ait = adef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof RoleDef rd) {
                sb.append('\t').append(e(rd.getName() + "_ref"));
            } else if (vte.obj instanceof AttributeDef ad) {
                sb.append('\t').append(e(ad.getName()));
            }
        }
        sb.append("\tunsupported_json");
        return sb.toString();
    }

    public String readAssociation(String xtfPath, String associationName, String modelDir) {
        TransferDescription td = compileModel(modelDir, extractModelName(associationName));
        if (td == null) return "";

        String[] parts = associationName.split("\\.");
        if (parts.length < 3) return "";

        AssociationDef assocDef = findAssociation(td, parts[0], parts[1], parts[2]);
        if (assocDef == null) return "";

        List<String> roleNames = new ArrayList<>();
        List<String> attrNames = new ArrayList<>();
        List<String> allNames = new ArrayList<>();
        Iterator<?> ait = assocDef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof RoleDef rd) {
                roleNames.add(rd.getName());
                allNames.add(rd.getName());
            } else if (vte.obj instanceof AttributeDef ad) {
                attrNames.add(ad.getName());
                allNames.add(ad.getName());
            }
        }

        String xtfDir = "";
        try { xtfDir = new File(xtfPath).getAbsoluteFile().getParent(); } catch (Exception ignored) {}
        String md = (modelDir != null && !modelDir.isBlank()) ? modelDir
                : (!xtfDir.isBlank() ? xtfDir + ";" + DEFAULT_MODELDIR : DEFAULT_MODELDIR);
        td = compileModel(md, extractModelName(associationName));
        if (td == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("xtf_bid\txtf_tid\txtf_class");
        for (String rn : roleNames) sb.append('\t').append(e(rn + "_ref"));
        for (String an : attrNames) sb.append('\t').append(e(an));
        sb.append("\tunsupported_json\n");

        IoxReader reader = null;
        try {
            reader = new ReaderFactory().createReader(new File(xtfPath), null);
            if (reader == null) reader = new Xtf24Reader(new File(xtfPath));
            if (reader instanceof IoxIliReader iliReader) iliReader.setModel(td);

            String currentBid = "";
            IoxEvent event;
            while ((event = reader.read()) != null) {
                if (event instanceof StartBasketEvent sbe) {
                    currentBid = sbe.getBid() != null ? sbe.getBid() : "";
                } else if (event instanceof ObjectEvent oe) {
                    IomObject obj = oe.getIomObject();
                    if (obj == null) continue;
                    String tag = obj.getobjecttag();
                    if (tag == null) continue;
                    if (!tag.endsWith("." + parts[2])) continue;

                    String cn = tag.contains(".") ? tag.substring(tag.lastIndexOf('.') + 1) : tag;
                    String tid = obj.getobjectoid();
                    if (tid == null) tid = "";

                    sb.append(e(currentBid)).append('\t').append(e(tid)).append('\t').append(e(cn));
                    for (String rn : roleNames)
                        sb.append('\t').append(e(getRoleRef(obj, rn)));
                    for (String an : attrNames)
                        sb.append('\t').append(e(obj.getattrvalue(an)));
                    sb.append('\t').append(e(buildUnsupported(obj, allNames))).append('\n');
                }
            }
        } catch (Exception ex) {
            if (IliLogger.isDebugEnabled()) {
                System.err.println("XTF association read: " + ex.getMessage());
            }
        } finally {
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
        }
        return sb.toString();
    }

    private AssociationDef findAssociation(TransferDescription td, String mn, String tn, String an) {
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (!mn.equals(m.getName())) continue;
            for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
                Element el = eit.next();
                if (el instanceof Topic t && tn.equals(t.getName())) {
                    for (Iterator<Element> tit = t.iterator(); tit.hasNext(); ) {
                        Element tel = tit.next();
                        if (tel instanceof AssociationDef a && an.equals(a.getName()))
                            return a;
                    }
                }
            }
        }
        return null;
    }
}
