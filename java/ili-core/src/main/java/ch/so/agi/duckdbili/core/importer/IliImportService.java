package ch.so.agi.duckdbili.core.importer;

import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ilirepository.IliManager;
import ch.so.agi.duckdbili.core.logging.IliLogger;

import java.io.File;
import java.nio.file.*;
import java.util.*;

public class IliImportService {

    private static final String DEFAULT_MODELDIR = System.getenv("ILI_DEFAULT_MODELDIR") != null
            ? System.getenv("ILI_DEFAULT_MODELDIR")
            : "https://models.interlis.ch";

    public String generateImportSql(String xtfPath, String modelDir, String schema, String mapping) {
        TransferDescription td = compileModel(resolveModelDir(modelDir, xtfPath), null);

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
                        List<ColInfo> cols = buildAssociationColumns(assocDef);
                        String ddl = generateDdl(schema, sanitizeTableName(assocDef.getName()), cols);
                        sql.append(ddl).append('\n');
                        sql.append(generateAssociationInsert(schema, xtfPath, modelDir, assocFqn, cols)).append('\n');
                    } else if (tel instanceof AbstractClassDef classDef && !(tel instanceof AssociationDef)) {
                        String classFqn = model.getName() + "." + topic.getName() + "." + classDef.getName();
                        List<ColInfo> cols = buildClassColumns(classDef);
                        String ddl = generateDdl(schema, sanitizeTableName(classDef.getName()), cols);
                        sql.append(ddl).append('\n');
                        sql.append(generateClassInsert(schema, xtfPath, modelDir, classFqn, cols)).append('\n');
                    }
                }
            }
        }
        return sql.toString();
    }

    // -----------------------------------------------------------------------
    // Column info
    // -----------------------------------------------------------------------

    private static final class ColInfo {
        final String name;
        final String duckdbType;

        ColInfo(String name, String duckdbType) {
            this.name = safeColName(name);
            this.duckdbType = duckdbType;
        }
    }

    private static final List<String> TECH_COLS = List.of("xtf_bid", "xtf_tid", "xtf_class");

    private List<ColInfo> buildClassColumns(AbstractClassDef cdef) {
        List<ColInfo> cols = new ArrayList<>();
        for (String tc : TECH_COLS) cols.add(new ColInfo(tc, "VARCHAR"));

        Iterator<?> ait = cdef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof AttributeDef ad) {
                String type = mapScalarType(ad);
                if (isGeometryDomain(ad) || isMultiGeometryDomain(ad)) {
                    cols.add(new ColInfo(ad.getName() + "_wkb", type));
                } else if (isStructureDomain(ad) || isCompositionDomain(ad)) {
                    cols.add(new ColInfo(ad.getName() + "_json", type));
                } else {
                    cols.add(new ColInfo(ad.getName(), type));
                }
            } else if (vte.obj instanceof RoleDef rd) {
                cols.add(new ColInfo(rd.getName() + "_ref", "VARCHAR"));
            }
        }
        cols.add(new ColInfo("unsupported_json", "VARCHAR"));
        return cols;
    }

    private List<ColInfo> buildAssociationColumns(AssociationDef adef) {
        List<ColInfo> cols = new ArrayList<>();
        for (String tc : TECH_COLS) cols.add(new ColInfo(tc, "VARCHAR"));

        Iterator<?> ait = adef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof RoleDef rd) {
                cols.add(new ColInfo(rd.getName() + "_ref", "VARCHAR"));
            } else if (vte.obj instanceof AttributeDef ad) {
                cols.add(new ColInfo(ad.getName(), mapScalarType(ad)));
            }
        }
        cols.add(new ColInfo("unsupported_json", "VARCHAR"));
        return cols;
    }

    // -----------------------------------------------------------------------
    // DDL generation
    // -----------------------------------------------------------------------

    private static String generateDdl(String schema, String tableName, List<ColInfo> cols) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(quoteIdent(schema)).append(".")
          .append(quoteIdent(tableName)).append(" (");
        List<String> parts = new ArrayList<>();
        for (ColInfo ci : cols) {
            parts.add(ci.name + " " + ci.duckdbType);
        }
        sb.append(String.join(", ", parts));
        sb.append(");");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // INSERT generation with typed CASTs
    // -----------------------------------------------------------------------

    private String generateClassInsert(String schema, String xtfPath, String modelDir,
                                        String classFqn, List<ColInfo> cols) {
        String tableName = sanitizeTableName(extractClassName(classFqn));
        String colList = buildTargetColList(cols);
        String selList = buildSelectList(cols);
        return "INSERT INTO " + quoteIdent(schema) + "." + quoteIdent(tableName)
             + " (" + colList + ")"
             + " SELECT " + selList
             + " FROM read_xtf_class("
             + sqlString(xtfPath)
             + ", class := " + sqlString(classFqn)
             + ", modeldir := " + sqlString(modelDir)
             + ");";
    }

    private String generateAssociationInsert(String schema, String xtfPath, String modelDir,
                                              String assocFqn, List<ColInfo> cols) {
        String tableName = sanitizeTableName(extractClassName(assocFqn));
        String colList = buildTargetColList(cols);
        String selList = buildSelectList(cols);
        return "INSERT INTO " + quoteIdent(schema) + "." + quoteIdent(tableName)
             + " (" + colList + ")"
             + " SELECT " + selList
             + " FROM read_xtf_association("
             + sqlString(xtfPath)
             + ", association := " + sqlString(assocFqn)
             + ", modeldir := " + sqlString(modelDir)
             + ");";
    }

    private static String buildTargetColList(List<ColInfo> cols) {
        List<String> names = new ArrayList<>();
        for (ColInfo ci : cols) names.add(ci.name);
        return String.join(", ", names);
    }

    private static String buildSelectList(List<ColInfo> cols) {
        List<String> parts = new ArrayList<>();
        for (ColInfo ci : cols) {
            if ("VARCHAR".equals(ci.duckdbType)) {
                // read_xtf_class/read_xtf_association returns VARCHAR for everything
                parts.add(ci.name);
            } else {
                parts.add("CAST(" + ci.name + " AS " + ci.duckdbType + ")");
            }
        }
        return String.join(", ", parts);
    }

    // -----------------------------------------------------------------------
    // Type mapping from INTERLIS domain to DuckDB
    // -----------------------------------------------------------------------

    private static String mapScalarType(AttributeDef ad) {
        Type domain = resolveToBaseType(ad);
        if (domain == null) return "VARCHAR";

        if (domain instanceof NumericType nt) {
            PrecisionDecimal min = nt.getMinimum();
            PrecisionDecimal max = nt.getMaximum();
            boolean hasDecimals = (min != null && min.getExponent() < 0)
                               || (max != null && max.getExponent() < 0);
            return hasDecimals ? "DOUBLE" : "BIGINT";
        }
        if (domain instanceof TextType) return "VARCHAR";
        if (domain instanceof EnumerationType) return "VARCHAR";
        if (domain instanceof AbstractCoordType
                || domain instanceof LineType
                || domain instanceof AbstractSurfaceOrAreaType
                || domain instanceof MultiCoordType
                || domain instanceof MultiSurfaceType
                || domain instanceof MultiPolylineType
                || domain instanceof MultiAreaType) return "VARCHAR"; // geometry → WKB as VARCHAR
        if (domain instanceof ObjectType || domain instanceof CompositionType) return "VARCHAR"; // structures → JSON
        if (domain instanceof ReferenceType) return "VARCHAR";
        // BOOLEAN, DATE, TIMESTAMP etc. are mapped by name
        String typeName = domain.getScopedName(null);
        if (typeName == null) typeName = domain.getName();
        if (typeName != null) {
            if (typeName.contains("BOOLEAN")) return "BOOLEAN";
            if (typeName.contains("DATE") && !typeName.contains("DATETIME")) return "DATE";
            if (typeName.contains("DATETIME")) return "TIMESTAMP";
        }
        return "VARCHAR";
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
            if (cfg == null) {
                throw new RuntimeException("INTERLIS model compilation failed: no valid configuration for modelDir=" + modelDir);
            }

            Ili2cSettings settings = new Ili2cSettings();
            Main.setDefaultIli2cPathMap(settings);
            settings.setIlidirs(modelDir);

            IliLogger.suppress();
            try {
                TransferDescription td = Main.runCompiler(cfg, settings, null);
                if (td == null) {
                    throw new RuntimeException("INTERLIS model compilation returned null for modelDir=" + modelDir);
                }
                return td;
            } finally {
                IliLogger.restore();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("INTERLIS model compilation failed for modelDir=" + modelDir, e);
        }
    }

    // -----------------------------------------------------------------------
    // Domain detection
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

    private static String resolveModelDir(String modelDir, String xtfPath) {
        if (modelDir != null && !modelDir.isBlank()) return modelDir;
        String xtfDir = "";
        try { xtfDir = new File(xtfPath).getAbsoluteFile().getParent(); } catch (Exception ignored) {}
        if (xtfDir != null && !xtfDir.isBlank()) return xtfDir + ";" + DEFAULT_MODELDIR;
        return DEFAULT_MODELDIR;
    }
}
