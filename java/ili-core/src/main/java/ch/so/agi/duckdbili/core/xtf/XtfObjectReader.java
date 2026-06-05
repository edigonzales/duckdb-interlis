package ch.so.agi.duckdbili.core.xtf;

import ch.ehi.basics.settings.Settings;
import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.config.FileEntry;
import ch.interlis.ili2c.config.FileEntryKind;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.iom.IomObject;
import ch.interlis.iox.*;
import ch.interlis.iom_j.xtf.Xtf24Reader;
import ch.interlis.iox_j.IoxIliReader;
import ch.interlis.iox_j.utility.ReaderFactory;
import ch.interlis.ilirepository.IliManager;
import org.interlis2.validator.Validator;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class XtfObjectReader {

    public String readObjects(String xtfPath, String modelDir) {
        StringBuilder sb = new StringBuilder();

        TransferDescription td = compileModelLocal(modelDir);
        if (td == null) td = compileModelViaValidator(modelDir);

        IoxReader reader = null;
        try {
            File xtfFile = new File(xtfPath);
            reader = new ReaderFactory().createReader(xtfFile, null);
            if (reader == null) reader = new Xtf24Reader(xtfFile);
            if (td != null && reader instanceof IoxIliReader iliReader) iliReader.setModel(td);

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
                    String cn = tag.contains(".") ? tag.substring(tag.lastIndexOf('.')+1) : tag;
                    String tid = obj.getobjectoid();
                    if (tid == null || tid.isBlank()) tid = "";
                    int op = obj.getobjectoperation();
                    String operation = op == 1 ? "DELETE" : op == 2 ? "UPDATE" : op == 3 ? "INSERT" : "";

                    sb.append(e(currentBid)).append('\t').append(e(currentTopic)).append('\t');
                    sb.append(e(cn)).append('\t').append(e(tid)).append('\t');
                    sb.append(e(operation)).append('\t').append(e(buildAttrs(obj))).append('\t');
                    sb.append(e(buildRefs(obj))).append('\t').append(e(buildGeom(obj))).append('\t');
                    sb.append(e(buildRaw(obj))).append('\n');
                }
            }
        } catch (Exception ex) {
            System.err.println("XTF read: " + ex.getMessage());
        } finally {
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
        }
        return sb.toString();
    }

    private TransferDescription compileModelLocal(String modelDir) {
        try {
            Path dir = Path.of(modelDir.split(";")[0]);
            if (!Files.isDirectory(dir)) return null;
            Ili2cSettings s = new Ili2cSettings();
            Main.setDefaultIli2cPathMap(s);
            s.setIlidirs(modelDir);
            Configuration c = new Configuration();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.ili")) {
                for (Path f : ds) c.addFileEntry(new FileEntry(f.toAbsolutePath().toString(), FileEntryKind.ILIMODELFILE));
            }
            c.setAutoCompleteModelList(true);
            return Main.runCompiler(c, s, null);
        } catch (Exception e) { return null; }
    }

    private TransferDescription compileModelViaValidator(String modelDir) {
        try {
            Settings s = new Settings();
            s.setValue(Validator.SETTING_ILIDIRS, modelDir);
            IliManager mgr = Validator.createRepositoryManager(modelDir, null, s);
            if (mgr == null) return null;
            return Validator.compileIli(mgr, modelDir, null, null, s);
        } catch (Exception e) { return null; }
    }

    private String buildAttrs(IomObject obj) {
        StringBuilder j = new StringBuilder("{");
        int n = obj.getattrcount(); boolean f = true;
        for (int i = 0; i < n; i++) {
            String nm = obj.getattrname(i), v = obj.getattrvalue(nm);
            if (v == null) continue;
            if (!f) j.append(","); f = false;
            j.append("\"").append(escJson(nm)).append("\":\"").append(escJson(v)).append("\"");
        }
        j.append("}"); return j.toString();
    }

    private String buildRefs(IomObject obj) {
        StringBuilder j = new StringBuilder("{");
        String oid = obj.getobjectrefoid(), bid = obj.getobjectrefbid(); boolean f = true;
        if (oid != null && !oid.isBlank()) {
            j.append("\"_ref_tid\":\"").append(escJson(oid)).append("\""); f = false;
        }
        if (bid != null && !bid.isBlank()) {
            if (!f) j.append(",");
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
