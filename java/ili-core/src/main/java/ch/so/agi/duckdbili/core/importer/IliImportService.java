package ch.so.agi.duckdbili.core.importer;

import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.Main;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ilirepository.IliManager;
import ch.so.agi.duckdbili.core.logging.IliLogger;
import ch.so.agi.duckdbili.core.model.ModelCache;
import ch.so.agi.duckdbili.core.model.ModelRepositoryResolver;
import ch.so.agi.duckdbili.core.sql.SqlIdentifiers;

import java.io.File;
import java.nio.file.*;
import java.util.*;

public class IliImportService {

    private static final String DEFAULT_MODELDIR = System.getenv("ILI_DEFAULT_MODELDIR") != null
            ? System.getenv("ILI_DEFAULT_MODELDIR")
            : "https://models.interlis.ch";

    public String generateImportSql(String xtfPath, String modelDir, String schema, String mapping, String mode) {
        long startNanos = System.nanoTime();

        long xtfFileSize = -1;
        try { xtfFileSize = Files.size(Path.of(xtfPath)); } catch (Exception ignored) {}

        if (IliLogger.isDebugEnabled()) {
            System.err.println("[ili-debug] Generating import SQL: " + xtfPath
                + " (size=" + xtfFileSize + " bytes, schema=" + schema + ", mapping=" + mapping + ", mode=" + mode + ")");
        }
        String effectiveMode = (mode != null && !mode.isBlank()) ? mode : "create";
        if (!effectiveMode.equals("create") && !effectiveMode.equals("replace") && !effectiveMode.equals("append")) {
            throw new RuntimeException("Unsupported import mode: '" + mode + "'. Valid modes: create, replace, append.");
        }

        String effectiveMapping = mapping == null || mapping.isBlank() ? "relational" : mapping;
        if (!"relational".equals(effectiveMapping)) {
            throw new IllegalArgumentException(
                    "Unsupported mapping mode: '" + effectiveMapping
                            + "'. Currently only 'relational' mapping is supported.");
        }

        String effectiveModelDir = resolveModelDir(modelDir, xtfPath);
        TransferDescription td = compileModel(effectiveModelDir, null);

        Set<String> tableNames = new HashSet<>();
        StringBuilder sql = new StringBuilder();
        sql.append("BEGIN TRANSACTION;\n");
        sql.append("CREATE SCHEMA IF NOT EXISTS ").append(SqlIdentifiers.quoteIdent(schema)).append(";\n");

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
                        String tableName = buildTableName(topic, assocDef.getName());
                        ensureNoTableCollision(tableName, assocFqn, tableNames);
                        List<ColInfo> cols = buildAssociationColumns(assocDef);
                        if (effectiveMode.equals("replace")) {
                            sql.append("DROP TABLE IF EXISTS ").append(SqlIdentifiers.quoteIdent(schema)).append(".")
                              .append(SqlIdentifiers.quoteIdent(tableName)).append(";\n");
                        }
                        if (!effectiveMode.equals("append")) {
                            sql.append(generateDdl(schema, tableName, cols)).append('\n');
                        }
                        sql.append(generateAssociationInsert(schema, xtfPath, effectiveModelDir, assocFqn, tableName, cols)).append('\n');
                    } else if (tel instanceof AbstractClassDef classDef && !(tel instanceof AssociationDef)) {
                        String classFqn = model.getName() + "." + topic.getName() + "." + classDef.getName();
                        String tableName = buildTableName(topic, classDef.getName());
                        ensureNoTableCollision(tableName, classFqn, tableNames);
                        List<ColInfo> cols = buildClassColumns(classDef);
                        if (effectiveMode.equals("replace")) {
                            sql.append("DROP TABLE IF EXISTS ").append(SqlIdentifiers.quoteIdent(schema)).append(".")
                              .append(SqlIdentifiers.quoteIdent(tableName)).append(";\n");
                        }
                        if (!effectiveMode.equals("append")) {
                            sql.append(generateDdl(schema, tableName, cols)).append('\n');
                        }
                        sql.append(generateClassInsert(schema, xtfPath, effectiveModelDir, classFqn, tableName, cols)).append('\n');
                    }
                }
            }
        }
        sql.append("COMMIT;\n");
        String result = sql.toString();

        if (IliLogger.isDebugEnabled()) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            System.err.println("[ili-debug] Import SQL generation completed: " + durationMs + " ms, "
                + result.length() + " chars (file=" + xtfFileSize + " bytes)");
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Column info
    // -----------------------------------------------------------------------

    private static final class ColInfo {
        final String sourceName;
        final String name;
        final String duckdbType;

        ColInfo(String sourceName, String generatedName, String duckdbType) {
            this.sourceName = sourceName;
            this.name = SqlIdentifiers.normalizeColumnName(generatedName);
            this.duckdbType = duckdbType;
        }
    }

    private static final List<String> TECH_COLS = List.of("xtf_bid", "xtf_tid", "xtf_class");

    private List<ColInfo> buildClassColumns(AbstractClassDef cdef) {
        List<ColInfo> cols = new ArrayList<>();
        for (String tc : TECH_COLS) cols.add(new ColInfo("<technical>", tc, "VARCHAR"));

        Iterator<?> ait = cdef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof AttributeDef ad) {
                String type = mapScalarType(ad);
                if (isGeometryDomain(ad) || isMultiGeometryDomain(ad)) {
                    cols.add(new ColInfo(ad.getScopedName(null), ad.getName() + "_wkb", type));
                } else if (isStructureDomain(ad) || isCompositionDomain(ad)) {
                    cols.add(new ColInfo(ad.getScopedName(null), ad.getName() + "_json", type));
                } else {
                    cols.add(new ColInfo(ad.getScopedName(null), ad.getName(), type));
                }
            } else if (vte.obj instanceof RoleDef rd) {
                cols.add(new ColInfo(rd.getScopedName(null), rd.getName() + "_ref", "VARCHAR"));
            }
        }
        cols.add(new ColInfo("<technical>", "unsupported_json", "VARCHAR"));
        ensureNoColumnCollisions(cdef.getScopedName(null), cols);
        return cols;
    }

    private List<ColInfo> buildAssociationColumns(AssociationDef adef) {
        List<ColInfo> cols = new ArrayList<>();
        for (String tc : TECH_COLS) cols.add(new ColInfo("<technical>", tc, "VARCHAR"));

        Iterator<?> ait = adef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof RoleDef rd) {
                cols.add(new ColInfo(rd.getScopedName(null), rd.getName() + "_ref", "VARCHAR"));
            } else if (vte.obj instanceof AttributeDef ad) {
                cols.add(new ColInfo(ad.getScopedName(null), ad.getName(), mapScalarType(ad)));
            }
        }
        cols.add(new ColInfo("<technical>", "unsupported_json", "VARCHAR"));
        ensureNoColumnCollisions(adef.getScopedName(null), cols);
        return cols;
    }

    // -----------------------------------------------------------------------
    // DDL generation
    // -----------------------------------------------------------------------

    private static String generateDdl(String schema, String tableName, List<ColInfo> cols) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(SqlIdentifiers.quoteIdent(schema)).append(".")
          .append(SqlIdentifiers.quoteIdent(tableName)).append(" (");
        List<String> parts = new ArrayList<>();
        for (ColInfo ci : cols) {
            parts.add(SqlIdentifiers.quoteIdent(ci.name) + " " + ci.duckdbType);
        }
        sb.append(String.join(", ", parts));
        sb.append(");");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // INSERT generation with typed CASTs
    // -----------------------------------------------------------------------

    private String generateClassInsert(String schema, String xtfPath, String modelDir,
                                        String classFqn, String tableName, List<ColInfo> cols) {
        String colList = buildTargetColList(cols);
        String selList = buildSelectList(cols);
        return "INSERT INTO " + SqlIdentifiers.quoteIdent(schema) + "." + SqlIdentifiers.quoteIdent(tableName)
             + " (" + colList + ")"
             + " SELECT " + selList
             + " FROM read_xtf_class("
             + SqlIdentifiers.sqlString(xtfPath)
             + ", class := " + SqlIdentifiers.sqlString(classFqn)
             + ", modeldir := " + SqlIdentifiers.sqlString(modelDir)
             + ");";
    }

    private String generateAssociationInsert(String schema, String xtfPath, String modelDir,
                                              String assocFqn, String tableName, List<ColInfo> cols) {
        String colList = buildTargetColList(cols);
        String selList = buildSelectList(cols);
        return "INSERT INTO " + SqlIdentifiers.quoteIdent(schema) + "." + SqlIdentifiers.quoteIdent(tableName)
             + " (" + colList + ")"
             + " SELECT " + selList
             + " FROM read_xtf_association("
             + SqlIdentifiers.sqlString(xtfPath)
             + ", association := " + SqlIdentifiers.sqlString(assocFqn)
             + ", modeldir := " + SqlIdentifiers.sqlString(modelDir)
             + ");";
    }

    private static String buildTargetColList(List<ColInfo> cols) {
        List<String> names = new ArrayList<>();
        for (ColInfo ci : cols)             names.add(SqlIdentifiers.quoteIdent(ci.name));
        return String.join(", ", names);
    }

    private static String buildSelectList(List<ColInfo> cols) {
        List<String> parts = new ArrayList<>();
        for (ColInfo ci : cols) {
            if ("VARCHAR".equals(ci.duckdbType)) {
                // read_xtf_class/read_xtf_association returns VARCHAR for everything
                parts.add(SqlIdentifiers.quoteIdent(ci.name));
            } else {
                parts.add("CAST(" + SqlIdentifiers.quoteIdent(ci.name) + " AS " + ci.duckdbType + ")");
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

        if (domain.isBoolean()) return "BOOLEAN";

        String typeName = domain.getScopedName(null);
        if (typeName == null) typeName = domain.getName();
        if (typeName != null) {
            if (typeName.contains("DATETIME")) return "TIMESTAMP";
            if (typeName.contains("TIME") && !typeName.contains("DATETIME")) return "TIME";
            if (typeName.contains("DATE") && !typeName.contains("DATETIME")) return "DATE";
        }

        if (domain instanceof AbstractCoordType
                || domain instanceof LineType
                || domain instanceof AbstractSurfaceOrAreaType
                || domain instanceof MultiCoordType
                || domain instanceof MultiSurfaceType
                || domain instanceof MultiPolylineType
                || domain instanceof MultiAreaType) return "VARCHAR";
        if (domain instanceof ObjectType || domain instanceof CompositionType) return "VARCHAR";
        if (domain instanceof ReferenceType) return "VARCHAR";

        return "VARCHAR";
    }

    private static void ensureNoColumnCollisions(String ownerFqn, List<ColInfo> columns) {
        Map<String, ColInfo> seen = new LinkedHashMap<>();

        for (ColInfo column : columns) {
            String key = SqlIdentifiers.collisionKey(column.name);
            ColInfo existing = seen.putIfAbsent(key, column);

            if (existing != null) {
                throw new IllegalArgumentException(
                        "Column name collision in " + ownerFqn
                                + ": source '" + existing.sourceName
                                + "' and source '" + column.sourceName
                                + "' both map to DuckDB column '"
                                + column.name + "'");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String buildTableName(Topic topic, String className) {
        return SqlIdentifiers.sanitizeTableName(topic.getName() + "__" + className);
    }

    private static void ensureNoTableCollision(String tableName, String fqn, Set<String> seen) {
        if (!seen.add(tableName)) {
            throw new RuntimeException("Table name collision: '" + tableName
                    + "' produced by FQN '" + fqn
                    + "'. Two different INTERLIS classes/associations produce the same table name. "
                    + "Use distinct topic or class names.");
        }
    }

    private static boolean isBaseModel(String name) {
        return "INTERLIS".equals(name);
    }

    // -----------------------------------------------------------------------
    // Model compilation (mirrors XtfObjectReader.compileModel)
    // -----------------------------------------------------------------------

    private TransferDescription compileModel(String modelDir, String modelNames) {
        String md = ModelRepositoryResolver.resolveToString(modelDir, DEFAULT_MODELDIR);
        Set<String> names = parseModelNames(modelNames);
        String fingerprint = ModelCache.computeFingerprint(md);
        ModelCache.CacheKey key = new ModelCache.CacheKey(md, names, fingerprint);
        return ModelCache.getInstance().getOrCompile(key, () -> doCompileModel(md, modelNames));
    }

    private static Set<String> parseModelNames(String modelNames) {
        if (modelNames == null || modelNames.isBlank()) return Set.of();
        Set<String> names = new TreeSet<>();
        for (String entry : modelNames.split(";")) {
            String trimmed = entry.trim();
            if (!trimmed.isBlank()) names.add(trimmed);
        }
        return names;
    }

    private TransferDescription doCompileModel(String normalizedModelDir, String modelNames) {
        try {
            IliManager manager = new IliManager();

            List<String> repoList = ModelRepositoryResolver.resolve(normalizedModelDir, DEFAULT_MODELDIR);
            manager.setRepositories(repoList.toArray(new String[0]));

            ArrayList<String> entries = new ArrayList<>();
            if (modelNames != null && !modelNames.isBlank()) {
                for (String entry : modelNames.split(";")) {
                    String trimmed = entry.trim();
                    if (!trimmed.isBlank()) entries.add(trimmed);
                }
            } else {
                for (Path directory : ModelRepositoryResolver.localDirectories(normalizedModelDir, DEFAULT_MODELDIR)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(directory, "*.ili")) {
                        for (Path f : ds) entries.add(f.toAbsolutePath().toString());
                    }
                }
            }

            Configuration cfg = manager.getConfigWithFiles(entries, null, 0.0);
            if (cfg == null) {
                throw new RuntimeException("INTERLIS model compilation failed: no valid configuration for modelDir=" + normalizedModelDir);
            }

            Ili2cSettings settings = new Ili2cSettings();
            Main.setDefaultIli2cPathMap(settings);
            settings.setIlidirs(normalizedModelDir);

            IliLogger.suppress();
            try {
                TransferDescription td = Main.runCompiler(cfg, settings, null);
                if (td == null) {
                    throw new RuntimeException("INTERLIS model compilation returned null for modelDir=" + normalizedModelDir);
                }
                return td;
            } finally {
                IliLogger.restore();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("INTERLIS model compilation failed for modelDir=" + normalizedModelDir, e);
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
        return ModelRepositoryResolver.resolveToString(
                xtfDir != null && !xtfDir.isBlank() ? xtfDir + ";" + DEFAULT_MODELDIR : DEFAULT_MODELDIR,
                DEFAULT_MODELDIR);
    }
}
