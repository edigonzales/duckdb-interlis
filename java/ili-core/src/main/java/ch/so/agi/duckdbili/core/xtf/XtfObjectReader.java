package ch.so.agi.duckdbili.core.xtf;

import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ilirepository.IliManager;
import ch.interlis.iom.IomObject;
import ch.interlis.iox.*;
import ch.interlis.iom_j.xtf.Xtf24Reader;
import ch.interlis.iox_j.IoxIliReader;
import ch.interlis.iox_j.utility.ReaderFactory;

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
        return readXtf(xtfPath, modelDir, extractModelName(className), className);
    }

    /**
     * Returns just the column names (header line) for a class.
     */
    public String readClassSchema(String className, String modelDir) {
        TransferDescription td = compileModel(modelDir, extractModelName(className));
        if (td == null) return "";

        String[] parts = className.split("\\.");
        if (parts.length < 3) return "";

        AbstractClassDef cdef = findClass(td, parts[0], parts[1], parts[2]);
        if (cdef == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("xtf_bid\txtf_tid\txtf_class");
        Iterator<?> ait = cdef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof AttributeDef ad)
                sb.append('\t').append(e(ad.getName()));
        }
        sb.append("\tunsupported_json");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private String readXtf(String xtfPath, String modelDir, String modelNames, String className) {
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
        if (classDef != null) {
            Iterator<?> ait = classDef.getAttributesAndRoles2();
            while (ait.hasNext()) {
                ViewableTransferElement vte = (ViewableTransferElement) ait.next();
                if (vte.obj instanceof AttributeDef ad) attrNames.add(ad.getName());
            }
        }

        StringBuilder sb = new StringBuilder();

        // Header for class-specific mode
        if (className != null) {
            sb.append("xtf_bid\txtf_tid\txtf_class");
            for (String an : attrNames) sb.append('\t').append(e(an));
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
                        for (String an : attrNames)
                            sb.append('\t').append(e(obj.getattrvalue(an)));
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
            System.err.println("XTF read: " + ex.getMessage());
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

            return Main.runCompiler(cfg, settings, null);
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
}
