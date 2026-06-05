package ch.so.agi.duckdbili.core.importer;

import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ilirepository.IliManager;

import java.io.File;
import java.nio.file.*;
import java.util.*;

public class IliImportService {

    private static final String DEFAULT_MODELDIR = System.getenv("ILI_DEFAULT_MODELDIR") != null
            ? System.getenv("ILI_DEFAULT_MODELDIR")
            : "https://models.interlis.ch";

    public String generateImportSql(String xtfPath, String modelDir, String schema, String mapping) {
        TransferDescription td = compileModel(modelDir, null);
        if (td == null) return "ERROR: Failed to compile model";

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE SCHEMA IF NOT EXISTS ").append(quoteIdent(schema)).append(";\n");

        for (Iterator<Model> mit = td.iterator(); mit.hasNext(); ) {
            Model model = mit.next();
            if (isBaseModel(model.getName())) continue;

            for (Iterator<Element> eit = model.iterator(); eit.hasNext(); ) {
                Element el = eit.next();
                if (!(el instanceof Topic topic)) continue;

                for (Iterator<Element> tit = topic.iterator(); tit.hasNext(); ) {
                    Element tel = tit.next();
                    if (tel instanceof AssociationDef assocDef) {
                        String assocFqn = model.getName() + "." + topic.getName() + "." + assocDef.getName();
                        String ddl = generateAssociationDdl(schema, td, model.getName(), topic.getName(), assocDef);
                        if (ddl != null) {
                            sql.append(ddl).append('\n');
                            sql.append(generateAssociationInsert(schema, xtfPath, modelDir, assocFqn)).append('\n');
                        }
                    } else if (tel instanceof AbstractClassDef classDef && !(tel instanceof AssociationDef)) {
                        String classFqn = model.getName() + "." + topic.getName() + "." + classDef.getName();
                        String ddl = generateClassDdl(schema, td, model.getName(), topic.getName(), classDef);
                        if (ddl != null) {
                            sql.append(ddl).append('\n');
                            sql.append(generateClassInsert(schema, xtfPath, modelDir, classFqn)).append('\n');
                        }
                    }
                }
            }
        }
        return sql.toString();
    }

    private String generateClassDdl(String schema, TransferDescription td,
                                     String modelName, String topicName, AbstractClassDef cdef) {
        StringBuilder sb = new StringBuilder();
        String tableName = sanitizeTableName(cdef.getName());
        sb.append("CREATE TABLE IF NOT EXISTS ").append(quoteIdent(schema)).append(".")
          .append(quoteIdent(tableName)).append(" (");

        List<String> cols = new ArrayList<>();
        cols.add("xtf_bid VARCHAR");
        cols.add("xtf_tid VARCHAR");
        cols.add("xtf_class VARCHAR");

        Iterator<?> ait = cdef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof AttributeDef ad) {
                if (isGeometryDomain(ad) || isMultiGeometryDomain(ad))
                    cols.add(safeColName(ad.getName() + "_wkb") + " VARCHAR");
                else if (isStructureDomain(ad) || isCompositionDomain(ad))
                    cols.add(safeColName(ad.getName() + "_json") + " VARCHAR");
                else
                    cols.add(safeColName(ad.getName()) + " VARCHAR");
            } else if (vte.obj instanceof RoleDef rd) {
                cols.add(safeColName(rd.getName() + "_ref") + " VARCHAR");
            }
        }
        cols.add("unsupported_json VARCHAR");

        sb.append(String.join(", ", cols));
        sb.append(");");
        return sb.toString();
    }

    private String generateAssociationDdl(String schema, TransferDescription td,
                                           String modelName, String topicName, AssociationDef adef) {
        StringBuilder sb = new StringBuilder();
        String tableName = sanitizeTableName(adef.getName());
        sb.append("CREATE TABLE IF NOT EXISTS ").append(quoteIdent(schema)).append(".")
          .append(quoteIdent(tableName)).append(" (");

        List<String> cols = new ArrayList<>();
        cols.add("xtf_bid VARCHAR");
        cols.add("xtf_tid VARCHAR");
        cols.add("xtf_class VARCHAR");

        Iterator<?> ait = adef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof RoleDef rd) {
                cols.add(safeColName(rd.getName() + "_ref") + " VARCHAR");
            } else if (vte.obj instanceof AttributeDef ad) {
                cols.add(safeColName(ad.getName()) + " VARCHAR");
            }
        }
        cols.add("unsupported_json VARCHAR");

        sb.append(String.join(", ", cols));
        sb.append(");");
        return sb.toString();
    }

    private String generateClassInsert(String schema, String xtfPath, String modelDir, String classFqn) {
        String tableName = sanitizeTableName(extractClassName(classFqn));
        return "INSERT INTO " + quoteIdent(schema) + "." + quoteIdent(tableName)
             + " SELECT * FROM read_xtf_class("
             + sqlString(xtfPath)
             + ", class := " + sqlString(classFqn)
             + ", modeldir := " + sqlString(modelDir)
             + ");";
    }

    private String generateAssociationInsert(String schema, String xtfPath, String modelDir, String assocFqn) {
        String tableName = sanitizeTableName(extractClassName(assocFqn));
        return "INSERT INTO " + quoteIdent(schema) + "." + quoteIdent(tableName)
             + " SELECT * FROM read_xtf_association("
             + sqlString(xtfPath)
             + ", association := " + sqlString(assocFqn)
             + ", modeldir := " + sqlString(modelDir)
             + ");";
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String sqlString(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String quoteIdent(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String safeColName(String s) {
        // Return unquoted, lowercase; matches what read_xtf_class returns as column names
        if (s == null) return "";
        return s.toLowerCase();
    }

    private static String sanitizeTableName(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    private static String extractClassName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static boolean isBaseModel(String name) {
        return "INTERLIS".equals(name);
    }

    // -----------------------------------------------------------------------
    // Model compilation (mirrors XtfObjectReader.compileModel)
    // -----------------------------------------------------------------------

    private TransferDescription compileModel(String modelDir, String modelNames) {
        try {
            IliManager manager = new IliManager();

            List<String> repoList = new ArrayList<>();
            if (modelDir != null) {
                for (String part : modelDir.split(";")) {
                    String trimmed = part.trim();
                    if (!trimmed.isBlank()) repoList.add(trimmed);
                }
            }
            if (repoList.isEmpty()) repoList.add(DEFAULT_MODELDIR);
            manager.setRepositories(repoList.toArray(new String[0]));

            ArrayList<String> entries = new ArrayList<>();
            if (modelNames != null && !modelNames.isBlank()) {
                for (String entry : modelNames.split(";")) {
                    String trimmed = entry.trim();
                    if (!trimmed.isBlank()) entries.add(trimmed);
                }
            } else {
                for (String part : modelDir.split(";")) {
                    Path p = null;
                    try { p = Path.of(part.trim()); } catch (Exception ignored) {}
                    if (p != null && Files.isDirectory(p)) {
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.ili")) {
                            for (Path f : ds) entries.add(f.toAbsolutePath().toString());
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

    // -----------------------------------------------------------------------
    // Domain detection (mirrors XtfObjectReader)
    // -----------------------------------------------------------------------

    private static boolean isStructureDomain(AttributeDef ad) {
        Type domain = ad.getDomain();
        if (domain instanceof ObjectType ot) return !ot.isObjects();
        if (domain instanceof CompositionType) {
            Cardinality card = ad.getCardinality();
            return card != null && card.getMaximum() <= 1;
        }
        return false;
    }

    private static boolean isCompositionDomain(AttributeDef ad) {
        Type domain = ad.getDomain();
        if (domain instanceof CompositionType) {
            Cardinality card = ad.getCardinality();
            return card == null || card.getMaximum() > 1;
        }
        return false;
    }

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
        for (int i = 0; i < 5 && domain != null; i++) {
            if (domain instanceof TypeAlias ta) {
                Domain aliasing = ta.getAliasing();
                if (aliasing != null) domain = aliasing.getType();
            }
        }
        return domain;
    }
}
