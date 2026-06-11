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
import ch.interlis.iox_j.IoxSyntaxException;
import ch.interlis.iox_j.utility.ReaderFactory;
import ch.so.agi.duckdbili.core.geometry.*;
import ch.so.agi.duckdbili.core.logging.IliLogger;
import ch.so.agi.duckdbili.core.model.InterlisLogicalTypeMapper;
import ch.so.agi.duckdbili.core.model.ModelCache;
import ch.so.agi.duckdbili.core.model.ModelRepositoryResolver;
import ch.so.agi.duckdbili.core.transport.TsvCodec;

import java.io.File;
import java.util.*;
import java.nio.file.*;

public class XtfObjectReader {

    private static final String DEFAULT_MODELDIR = System.getenv("ILI_DEFAULT_MODELDIR") != null
            ? System.getenv("ILI_DEFAULT_MODELDIR")
            : "https://models.interlis.ch";

    private final InterlisGeometryTypeResolver geometryTypeResolver;
    private final InterlisGeometryEncoder geometryEncoder;
    private final GeometryConversionOptions geometryOptions;
    private final GeometryCrsResolver crsResolver;
    private final InterlisLogicalTypeMapper logicalTypeMapper;

    private enum ReaderColumnKind {
        XTF_BID,
        XTF_TID,
        XTF_CLASS,
        SCALAR,
        GEOMETRY,
        STRUCTURE_JSON,
        BAG_JSON,
        ROLE_REF,
        UNSUPPORTED_JSON
    }

    private record ReaderColumnSpec(
            String columnName,
            InterlisLogicalTypeMapper.LogicalType logicalType,
            String wireEncoding,
            boolean nullable,
            ReaderColumnKind kind,
            String sourceName,
            AttributeDef attributeDef,
            GeometryMetadata geometryMetadata,
            String geometryKind,
            String crsAuth,
            String crsCode) {
    }

    private record StructureRow(
            String rootClassFqn,
            String structureFqn,
            String structureName,
            String attributeFqn,
            String attributeName,
            String interlisType,
            String logicalType,
            String kind,
            Boolean isMandatory,
            Integer cardMin,
            Long cardMax,
            String enumValuesJson) {
    }

    public XtfObjectReader() {
        this.geometryTypeResolver = new InterlisGeometryTypeResolver();
        this.geometryOptions = GeometryConversionOptions.defaults();
        this.geometryEncoder = new InterlisGeometryEncoder(
                geometryTypeResolver,
                new InterlisGeometryExtractor(),
                geometryOptions);
        this.crsResolver = new MapGeometryCrsResolver();
        this.logicalTypeMapper = new InterlisLogicalTypeMapper();
    }

    public XtfObjectReader(
            InterlisGeometryTypeResolver geometryTypeResolver,
            InterlisGeometryEncoder geometryEncoder,
            GeometryConversionOptions geometryOptions) {
        this(geometryTypeResolver, geometryEncoder, geometryOptions, new MapGeometryCrsResolver());
    }

    public XtfObjectReader(
            InterlisGeometryTypeResolver geometryTypeResolver,
            InterlisGeometryEncoder geometryEncoder,
            GeometryConversionOptions geometryOptions,
            GeometryCrsResolver crsResolver) {
        if (geometryTypeResolver == null) throw new NullPointerException("geometryTypeResolver is null");
        if (geometryEncoder == null) throw new NullPointerException("geometryEncoder is null");
        if (geometryOptions == null) throw new NullPointerException("geometryOptions is null");
        if (crsResolver == null) throw new NullPointerException("crsResolver is null");
        this.geometryTypeResolver = geometryTypeResolver;
        this.geometryEncoder = geometryEncoder;
        this.geometryOptions = geometryOptions;
        this.crsResolver = crsResolver;
        this.logicalTypeMapper = new InterlisLogicalTypeMapper();
    }

    /**
     * Read all objects from an XTF file. TSV columns: xtf_bid, xtf_topic, xtf_class,
     * xtf_class_fqn, xtf_tid, operation, xtf_model, attributes_json, refs_json,
     * geom_json, raw_event_json
     */
    public String readObjects(String xtfPath, String modelDir, String modelNames) {
        return readXtf(xtfPath, modelDir, modelNames, null);
    }

    /**
     * Read objects of a specific class. First line = TSV header (column names),
     * subsequent lines = data rows.
     */
    public String readClass(String xtfPath, String className, String modelDir) {
        return readNamedViewable(
                xtfPath,
                modelDir,
                className,
                "json",
                false,
                buildClassColumnSpecs(className, modelDir, "json"));
    }

    public String readClass(String xtfPath, String className, String modelDir, String nested) {
        String effectiveNested = nested != null ? nested : "json";
        return readNamedViewable(
                xtfPath,
                modelDir,
                className,
                effectiveNested,
                false,
                buildClassColumnSpecs(className, modelDir, effectiveNested));
    }

    /**
     * Returns just the column names (header line) for a class.
     */
    public String readClassSchema(String className, String modelDir) {
        return readClassSchema(className, modelDir, "json");
    }

    public String readClassSchema(String className, String modelDir, String nested) {
        return renderHeader(buildClassColumnSpecs(className, modelDir, nested));
    }

    /**
     * Returns a typed schema descriptor (v2) for a class.
     * One line per column, TSV-encoded with fields:
     * name, logical_type, wire_encoding, nullable, geometry_kind, crs_auth_name, crs_code
     * <p>
     * Geometry columns report GEOMETRY/HEX_WKB; scalar columns report VARCHAR/TEXT.
     * This is the basis for typed bind in C (DUCKDB_TYPE_GEOMETRY vs DUCKDB_TYPE_VARCHAR).
     */
    public String readClassSchemaV2(String className, String modelDir) {
        return renderSchemaV2(buildClassColumnSpecs(className, modelDir, "json"));
    }

    /**
     * Reads class-specific data with typed v2 transport.
     * Geometry columns use hex-WKB encoding; scalar columns use plain text.
     * Header line is the same as v1 (column names only).
     * Data lines use hex-WKB for geometry columns instead of WKT.
     */
    public String readClassV2(String xtfPath, String className, String modelDir, String nested) {
        String effectiveNested = nested != null ? nested : "json";
        return readNamedViewable(
                xtfPath,
                modelDir,
                className,
                effectiveNested,
                true,
                buildClassColumnSpecs(className, modelDir, effectiveNested));
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void appendSchemaRow(StringBuilder sb, String name, String logicalType,
                                  String wireEncoding, String nullable,
                                  String geometryKind, String crsAuth, String crsCode) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(TsvCodec.encodeNullable(name)).append('\t');
        sb.append(TsvCodec.encodeNullable(logicalType)).append('\t');
        sb.append(TsvCodec.encodeNullable(wireEncoding)).append('\t');
        sb.append(TsvCodec.encodeNullable(nullable)).append('\t');
        sb.append(TsvCodec.encodeNullable(geometryKind.isEmpty() ? null : geometryKind)).append('\t');
        sb.append(TsvCodec.encodeNullable(crsAuth.isEmpty() ? null : crsAuth)).append('\t');
        sb.append(TsvCodec.encodeNullable(crsCode.isEmpty() ? null : crsCode));
    }

    private String renderHeader(List<ReaderColumnSpec> columns) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ReaderColumnSpec column : columns) {
            if (!first) sb.append('\t');
            first = false;
            sb.append(TsvCodec.encodeNullable(column.columnName()));
        }
        return sb.toString();
    }

    private String renderSchemaV2(List<ReaderColumnSpec> columns) {
        StringBuilder sb = new StringBuilder();
        for (ReaderColumnSpec column : columns) {
            appendSchemaRow(
                    sb,
                    column.columnName(),
                    column.logicalType().sqlTypeName(),
                    column.wireEncoding(),
                    column.nullable() ? "true" : "false",
                    column.geometryKind() != null ? column.geometryKind() : "",
                    column.crsAuth() != null ? column.crsAuth() : "",
                    column.crsCode() != null ? column.crsCode() : "");
        }
        return sb.toString();
    }

    private List<ReaderColumnSpec> buildClassColumnSpecs(String className, String modelDir, String nested) {
        TransferDescription td = compileModel(modelDir, extractModelName(className));
        String[] parts = splitViewableName(className, "Class");
        AbstractClassDef cdef = findClass(td, parts[0], parts[1], parts[2]);
        if (cdef == null) throw new IllegalArgumentException("Class not found in model: " + className);
        Model model = findModel(td, parts[0]);
        Topic topic = model != null ? findTopic(model, parts[1]) : null;
        return buildViewableColumnSpecs(model, topic, cdef, parts[2], nested);
    }

    private List<ReaderColumnSpec> buildAssociationColumnSpecs(String associationName, String modelDir) {
        TransferDescription td = compileModel(modelDir, extractModelName(associationName));
        String[] parts = splitViewableName(associationName, "Association");
        AssociationDef adef = findAssociation(td, parts[0], parts[1], parts[2]);
        if (adef == null) throw new IllegalArgumentException("Association not found in model: " + associationName);
        Model model = findModel(td, parts[0]);
        Topic topic = model != null ? findTopic(model, parts[1]) : null;
        return buildViewableColumnSpecs(model, topic, adef, parts[2], "json");
    }

    private List<ReaderColumnSpec> buildViewableColumnSpecs(
            Model model,
            Topic topic,
            AbstractClassDef classDef,
            String shortClassName,
            String nested) {

        String jsonSuffix = "duckdb".equals(nested) ? "" : "_json";
        List<ReaderColumnSpec> columns = new ArrayList<>();
        columns.add(new ReaderColumnSpec("xtf_bid", InterlisLogicalTypeMapper.LogicalType.VARCHAR, "TEXT", false,
                ReaderColumnKind.XTF_BID, null, null, null, null, null, null));
        columns.add(new ReaderColumnSpec("xtf_tid", InterlisLogicalTypeMapper.LogicalType.VARCHAR, "TEXT", false,
                ReaderColumnKind.XTF_TID, null, null, null, null, null, null));
        columns.add(new ReaderColumnSpec("xtf_class", InterlisLogicalTypeMapper.LogicalType.VARCHAR, "TEXT", false,
                ReaderColumnKind.XTF_CLASS, shortClassName, null, null, null, null, null));

        Iterator<?> ait = classDef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof AttributeDef ad) {
                if (geometryTypeResolver.isGeometryAttribute(ad)) {
                    columns.add(buildGeometryColumnSpec(model, topic, classDef, ad));
                } else if (isStructureDomain(ad)) {
                    columns.add(new ReaderColumnSpec(ad.getName() + jsonSuffix,
                            InterlisLogicalTypeMapper.LogicalType.VARCHAR, "TEXT", true,
                            ReaderColumnKind.STRUCTURE_JSON, ad.getName(), ad, null, null, null, null));
                } else if (isCompositionDomain(ad)) {
                    columns.add(new ReaderColumnSpec(ad.getName() + jsonSuffix,
                            InterlisLogicalTypeMapper.LogicalType.VARCHAR, "TEXT", true,
                            ReaderColumnKind.BAG_JSON, ad.getName(), ad, null, null, null, null));
                } else {
                    InterlisLogicalTypeMapper.LogicalType logicalType = logicalTypeMapper.mapAttribute(ad);
                    columns.add(new ReaderColumnSpec(ad.getName(), logicalType, "TEXT", true,
                            ReaderColumnKind.SCALAR, ad.getName(), ad, null, null, null, null));
                }
            } else if (vte.obj instanceof RoleDef rd) {
                columns.add(new ReaderColumnSpec(rd.getName() + "_ref",
                        InterlisLogicalTypeMapper.LogicalType.VARCHAR, "TEXT", true,
                        ReaderColumnKind.ROLE_REF, rd.getName(), null, null, null, null, null));
            }
        }
        columns.add(new ReaderColumnSpec("unsupported_json", InterlisLogicalTypeMapper.LogicalType.VARCHAR, "TEXT", true,
                ReaderColumnKind.UNSUPPORTED_JSON, null, null, null, null, null, null));
        return columns;
    }

    private ReaderColumnSpec buildGeometryColumnSpec(
            Model model,
            Topic topic,
            AbstractClassDef classDef,
            AttributeDef attributeDef) {
        GeometryMetadata meta = geometryTypeResolver.resolveMetadata(model, topic, classDef, attributeDef);
        String crsAuth = null;
        String crsCode = null;
        if (meta != null && meta.coordinateDomainFqn() != null) {
            GeometryMetadataContext ctx = new GeometryMetadataContext(
                    meta.modelName(),
                    meta.topicName(),
                    meta.className(),
                    meta.attributeName(),
                    meta.coordinateDomainFqn());
            var crs = crsResolver.resolve(ctx);
            if (crs.isPresent()) {
                crsAuth = crs.get().authority();
                crsCode = crs.get().code();
            }
        }
        return new ReaderColumnSpec(
                attributeDef.getName() + "_geom",
                InterlisLogicalTypeMapper.LogicalType.GEOMETRY,
                "HEX_WKB",
                true,
                ReaderColumnKind.GEOMETRY,
                attributeDef.getName(),
                attributeDef,
                meta,
                meta != null && meta.geometryKind() != null ? meta.geometryKind().name() : "UNKNOWN",
                crsAuth,
                crsCode);
    }

    private String[] splitViewableName(String name, String label) {
        String[] parts = name.split("\\.");
        if (parts.length < 3) {
            throw new IllegalArgumentException(label + " name must be fully qualified (Model.Topic." + label + "), got: " + name);
        }
        return parts;
    }

    private String readNamedViewable(
            String xtfPath,
            String modelDir,
            String viewableName,
            String nested,
            boolean useHexWkb,
            List<ReaderColumnSpec> columns) {
        long startNanos = System.nanoTime();

        String xtfDir = "";
        try { xtfDir = new File(xtfPath).getAbsoluteFile().getParent(); } catch (Exception ignored) {}
        String md = (modelDir != null && !modelDir.isBlank()) ? modelDir
                : (!xtfDir.isBlank() ? xtfDir + ";" + DEFAULT_MODELDIR : DEFAULT_MODELDIR);

        long xtfFileSize = -1;
        try { xtfFileSize = Files.size(Path.of(xtfPath)); } catch (Exception ignored) {}

        if (IliLogger.isDebugEnabled()) {
            System.err.println("[ili-debug] Reading named XTF viewable: " + xtfPath
                    + " (size=" + xtfFileSize + " bytes, viewable=" + viewableName + ", nested=" + nested + ")");
        }

        TransferDescription td = compileModel(md, extractModelName(viewableName));
        Set<String> knownSourceNames = buildKnownSourceNames(columns);

        StringBuilder sb = new StringBuilder();
        sb.append(renderHeader(columns)).append('\n');

        IoxReader reader = null;
        boolean sawEndBasket = false;
        try {
            reader = new ReaderFactory().createReader(new File(xtfPath), null);
            if (reader == null) reader = new Xtf24Reader(new File(xtfPath));
            if (reader instanceof IoxIliReader iliReader) iliReader.setModel(td);

            String currentBid = "";
            IoxEvent event;
            while ((event = reader.read()) != null) {
                if (event instanceof StartBasketEvent sbe) {
                    currentBid = sbe.getBid() != null ? sbe.getBid() : "";
                } else if (event instanceof EndBasketEvent) {
                    sawEndBasket = true;
                } else if (event instanceof ObjectEvent oe) {
                    IomObject obj = oe.getIomObject();
                    if (obj == null) continue;
                    String tag = obj.getobjecttag();
                    if (tag == null || !tag.equals(viewableName)) continue;
                    appendNamedViewableRow(sb, obj, currentBid, knownSourceNames, columns, useHexWkb);
                    sb.append('\n');
                }
            }
        } catch (Exception ex) {
            if (sawEndBasket && ex instanceof IoxSyntaxException ise
                    && (ise.getMessage() == null || ise.getMessage().isBlank())) {
                if (IliLogger.isDebugEnabled()) {
                    System.err.println("[ili-debug] Ignoring trailing IoxSyntaxException after file end");
                }
            } else {
                throw new RuntimeException("XTF read error for " + xtfPath + ": " + ex.getMessage(), ex);
            }
        } finally {
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
        }

        String result = sb.toString();
        if (IliLogger.isDebugEnabled()) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            int rowCount = 0;
            for (int i = 0; i < result.length(); i++) {
                if (result.charAt(i) == '\n') rowCount++;
            }
            System.err.println("[ili-debug] Named XTF viewable read completed: " + durationMs + " ms, "
                    + rowCount + " data rows, " + result.length() + " chars (file=" + xtfFileSize + " bytes)");
        }
        return result;
    }

    private void appendNamedViewableRow(
            StringBuilder sb,
            IomObject obj,
            String currentBid,
            Set<String> knownSourceNames,
            List<ReaderColumnSpec> columns,
            boolean useHexWkb) {
        String tag = obj.getobjecttag();
        String shortClassName = tag != null && tag.contains(".")
                ? tag.substring(tag.lastIndexOf('.') + 1)
                : tag;
        String tid = obj.getobjectoid();
        if (tid == null) tid = "";

        for (int i = 0; i < columns.size(); i++) {
            ReaderColumnSpec column = columns.get(i);
            if (i > 0) sb.append('\t');
            switch (column.kind()) {
                case XTF_BID -> sb.append(TsvCodec.encodeNullable(currentBid));
                case XTF_TID -> sb.append(TsvCodec.encodeNullable(tid));
                case XTF_CLASS -> sb.append(TsvCodec.encodeNullable(shortClassName));
                case SCALAR -> sb.append(TsvCodec.encodeNullable(obj.getattrvalue(column.sourceName())));
                case GEOMETRY -> sb.append(TsvCodec.encodeNullable(
                        extractGeometryValue(obj, column.attributeDef(), column.geometryMetadata(), useHexWkb)));
                case STRUCTURE_JSON -> sb.append(TsvCodec.encodeNullable(buildSingleStructureJson(obj, column.sourceName())));
                case BAG_JSON -> sb.append(TsvCodec.encodeNullable(buildBagStructureJson(obj, column.sourceName())));
                case ROLE_REF -> sb.append(TsvCodec.encodeNullable(getRoleRef(obj, column.sourceName())));
                case UNSUPPORTED_JSON -> sb.append(TsvCodec.encodeNullable(buildUnsupported(obj, knownSourceNames)));
            }
        }
    }

    private String extractGeometryValue(
            IomObject obj,
            AttributeDef attributeDef,
            GeometryMetadata meta,
            boolean useHexWkb) {
        if (useHexWkb) {
            Optional<GeometryValue> value = geometryEncoder.encodeAttribute(obj, attributeDef, meta);
            return value.map(GeometryValue::hexWkbLE).orElse(null);
        }
        try {
            Optional<GeometryValue> value = geometryEncoder.encodeAttribute(obj, attributeDef, meta);
            return value.map(GeometryValue::wkt).orElse(null);
        } catch (GeometryConversionException e) {
            return "{\"_geometry_error\":\"" + escJson(e.getMessage()) + "\"}";
        } catch (Exception e) {
            return "{\"_geometry_error\":\"" + escJson(e.getClass().getSimpleName() + ": " + e.getMessage()) + "\"}";
        }
    }

    private Set<String> buildKnownSourceNames(List<ReaderColumnSpec> columns) {
        Set<String> known = new LinkedHashSet<>();
        for (ReaderColumnSpec column : columns) {
            if (column.sourceName() != null && !column.sourceName().isBlank()) {
                known.add(column.sourceName());
            }
        }
        return known;
    }

    private String readXtf(String xtfPath, String modelDir, String modelNames, String className) {
        return readXtfInternal(xtfPath, modelDir, modelNames, className, "json", false);
    }

    private String readXtf(String xtfPath, String modelDir, String modelNames, String className, String nested) {
        return readXtfInternal(xtfPath, modelDir, modelNames, className, nested, false);
    }

    private String readXtfInternal(String xtfPath, String modelDir, String modelNames,
                                    String className, String nested, boolean useHexWkb) {
        long startNanos = System.nanoTime();

        // Build effective modeldir: user-specified > XTF directory > default
        String xtfDir = "";
        try { xtfDir = new File(xtfPath).getAbsoluteFile().getParent(); } catch (Exception ignored) {}
        String md = (modelDir != null && !modelDir.isBlank()) ? modelDir
                : (!xtfDir.isBlank() ? xtfDir + ";" + DEFAULT_MODELDIR : DEFAULT_MODELDIR);

        long xtfFileSize = -1;
        try { xtfFileSize = Files.size(Path.of(xtfPath)); } catch (Exception ignored) {}

        if (IliLogger.isDebugEnabled()) {
            System.err.println("[ili-debug] Reading XTF: " + xtfPath
                + " (size=" + xtfFileSize + " bytes, class=" + className + ", nested=" + nested + ")");
        }

        TransferDescription td = compileModel(md, modelNames);

        AbstractClassDef classDef = null;
        if (className != null) {
            String[] parts = className.split("\\.");
            if (parts.length < 3) throw new IllegalArgumentException("Class name must be fully qualified (Model.Topic.Class), got: " + className);
            classDef = findClass(td, parts[0], parts[1], parts[2]);
            if (classDef == null) throw new IllegalArgumentException("Class not found in model: " + className);
        }

        List<String> attrNames = new ArrayList<>();
        List<String> scalarAttrs = new ArrayList<>();
        List<String> structureAttrs = new ArrayList<>();
        List<String> bagAttrs = new ArrayList<>();
        List<String> geomAttrs = new ArrayList<>();
        List<String> roleRefs = new ArrayList<>();
        Map<String, AttributeDef> attrDefMap = new HashMap<>();
        Map<String, GeometryMetadata> geomMetaMap = new HashMap<>();
        if (classDef != null) {
            Iterator<?> ait = classDef.getAttributesAndRoles2();
            while (ait.hasNext()) {
                ViewableTransferElement vte = (ViewableTransferElement) ait.next();
                if (vte.obj instanceof AttributeDef ad) {
                    String an = ad.getName();
                    attrNames.add(an);
                    attrDefMap.put(an, ad);
                    if (geometryTypeResolver.isGeometryAttribute(ad)) {
                        geomAttrs.add(an);
                        String[] parts = className.split("\\.");
                        String modelName = parts[0], topicName = parts[1], clsName = parts[2];
                        GeometryKind kind = geometryTypeResolver.resolveGeometryKind(ad);
                        geomMetaMap.put(an, new GeometryMetadata(
                                modelName, topicName, clsName, an, className + "." + an,
                                kind, GeometryDimension.XY, null, null, null, null, null,
                                true, 1, 1, false, false, false));
                    } else if (isStructureDomain(ad)) {
                        structureAttrs.add(an);
                    } else if (isCompositionDomain(ad)) {
                        bagAttrs.add(an);
                    } else {
                        scalarAttrs.add(an);
                    }
                } else if (vte.obj instanceof RoleDef rd) {
                    String rn = rd.getName();
                    attrNames.add(rn);
                    roleRefs.add(rn);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        String colSuffix = "duckdb".equals(nested) ? "" : "_json";

        // Header for class-specific mode
        if (className != null) {
            sb.append("xtf_bid\txtf_tid\txtf_class");
            for (String an : scalarAttrs) sb.append('\t').append(TsvCodec.encodeNullable(an));
            for (String an : geomAttrs) sb.append('\t').append(TsvCodec.encodeNullable(an + "_geom"));
            for (String an : structureAttrs) sb.append('\t').append(TsvCodec.encodeNullable(an + colSuffix));
            for (String an : bagAttrs) sb.append('\t').append(TsvCodec.encodeNullable(an + colSuffix));
            for (String rn : roleRefs) sb.append('\t').append(TsvCodec.encodeNullable(rn + "_ref"));
            sb.append("\tunsupported_json\n");
        }

        IoxReader reader = null;
        boolean sawEndBasket = false;
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
                } else if (event instanceof EndBasketEvent) {
                    sawEndBasket = true;
                } else if (event instanceof ObjectEvent oe) {
                    IomObject obj = oe.getIomObject();
                    if (obj == null) continue;
                    String tag = obj.getobjecttag();
                    if (tag == null) continue;

                    if (className != null) {
                        if (!tag.equals(className)) continue;
                    }

                    String cn = tag.contains(".") ? tag.substring(tag.lastIndexOf('.') + 1) : tag;
                    String tid = obj.getobjectoid();
                    if (tid == null) tid = "";

                    if (className != null) {
                        // Class-specific output
                        sb.append(TsvCodec.encodeNullable(currentBid)).append('\t').append(TsvCodec.encodeNullable(tid)).append('\t').append(TsvCodec.encodeNullable(cn));
                        for (String an : scalarAttrs)
                            sb.append('\t').append(TsvCodec.encodeNullable(obj.getattrvalue(an)));
                        for (String an : geomAttrs) {
                            AttributeDef ad = attrDefMap.get(an);
                            GeometryMetadata meta = geomMetaMap.get(an);
                            if (useHexWkb) {
                                Optional<GeometryValue> val = geometryEncoder.encodeAttribute(obj, ad, meta);
                                String hex = val.map(GeometryValue::hexWkbLE).orElse(null);
                                sb.append('\t').append(TsvCodec.encodeNullable(hex));
                            } else {
                                String wkt = null;
                                try {
                                    Optional<GeometryValue> val = geometryEncoder.encodeAttribute(obj, ad, meta);
                                    wkt = val.map(GeometryValue::wkt).orElse(null);
                                } catch (GeometryConversionException e) {
                                    wkt = "{\"_geometry_error\":\"" + escJson(e.getMessage()) + "\"}";
                                } catch (Exception e) {
                                    wkt = "{\"_geometry_error\":\"" + escJson(e.getClass().getSimpleName() + ": " + e.getMessage()) + "\"}";
                                }
                                sb.append('\t').append(TsvCodec.encodeNullable(wkt));
                            }
                        }
                        for (String an : structureAttrs)
                            sb.append('\t').append(TsvCodec.encodeNullable(buildSingleStructureJson(obj, an)));
                        for (String an : bagAttrs)
                            sb.append('\t').append(TsvCodec.encodeNullable(buildBagStructureJson(obj, an)));
                        for (String rn : roleRefs)
                            sb.append('\t').append(TsvCodec.encodeNullable(getRoleRef(obj, rn)));
                        sb.append('\t').append(TsvCodec.encodeNullable(buildUnsupported(obj, attrNames))).append('\n');
                    } else {
                        // Generic object stream output
                        int op = obj.getobjectoperation();
                        String operation = op == 1 ? "UPDATE" : op == 2 ? "DELETE" : "INSERT";
                        String modelName = tag.contains(".") ? tag.substring(0, tag.indexOf('.')) : "";
                        sb.append(TsvCodec.encodeNullable(currentBid)).append('\t').append(TsvCodec.encodeNullable(currentTopic)).append('\t');
                        sb.append(TsvCodec.encodeNullable(cn)).append('\t').append(TsvCodec.encodeNullable(tag)).append('\t').append(TsvCodec.encodeNullable(tid)).append('\t');
                        sb.append(TsvCodec.encodeNullable(operation)).append('\t').append(TsvCodec.encodeNullable(modelName)).append('\t');
                        sb.append(TsvCodec.encodeNullable(buildAttrs(obj))).append('\t').append(TsvCodec.encodeNullable(buildRefs(obj))).append('\t');
                        sb.append(TsvCodec.encodeNullable(buildGeom(obj, td))).append('\t').append(TsvCodec.encodeNullable(buildRaw(obj))).append('\n');
                    }
                }
            }
        } catch (Exception ex) {
            // Xtf24Reader may throw IoxSyntaxException with empty message after EndBasketEvent
            // when it reaches end of file. Treat this as normal EOF if we have seen the basket close.
            if (sawEndBasket && ex instanceof IoxSyntaxException ise
                    && (ise.getMessage() == null || ise.getMessage().isBlank())) {
                if (IliLogger.isDebugEnabled()) {
                    System.err.println("[ili-debug] Ignoring trailing IoxSyntaxException after file end");
                }
            } else {
                throw new RuntimeException("XTF read error for " + xtfPath + ": " + ex.getMessage(), ex);
            }
        } finally {
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
        }

        String result = sb.toString();

        if (IliLogger.isDebugEnabled()) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            int rowCount = 0;
            for (int i = 0; i < result.length(); i++) { if (result.charAt(i) == '\n') rowCount++; }
            System.err.println("[ili-debug] XTF read completed: " + durationMs + " ms, "
                + rowCount + " data rows, " + result.length() + " chars (file=" + xtfFileSize + " bytes)");
        }

        return result;
    }

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
                    if (!trimmed.isBlank()) {
                        entries.add(trimmed);
                    }
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

    private static String extractModelName(String className) {
        if (className == null) return null;
        int dot = className.indexOf('.');
        return dot > 0 ? className.substring(0, dot) : null;
    }

    private AbstractClassDef findClass(TransferDescription td, String mn, String tn, String cn) {
        Model m = findModel(td, mn);
        if (m == null) return null;
        Topic t = findTopic(m, tn);
        if (t == null) return null;
        for (Iterator<Element> tit = t.iterator(); tit.hasNext(); ) {
            Element tel = tit.next();
            if (tel instanceof AbstractClassDef c && !(tel instanceof AssociationDef) && cn.equals(c.getName()))
                return c;
        }
        return null;
    }

    private static Model findModel(TransferDescription td, String mn) {
        for (Iterator<Model> it = td.iterator(); it.hasNext(); ) {
            Model m = it.next();
            if (mn.equals(m.getName())) return m;
        }
        return null;
    }

    private static Topic findTopic(Model m, String tn) {
        for (Iterator<Element> eit = m.iterator(); eit.hasNext(); ) {
            Element el = eit.next();
            if (el instanceof Topic t && tn.equals(t.getName())) return t;
        }
        return null;
    }

    private String buildUnsupported(IomObject obj, Collection<String> known) {
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
        return first ? null : sb.toString();
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

    private String buildGeom(IomObject obj, TransferDescription td) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        int n = obj.getattrcount();
        for (int i = 0; i < n; i++) {
            String an = obj.getattrname(i);
            int cnt = obj.getattrvaluecount(an);
            if (cnt == 0) continue;
            IomObject geom = obj.getattrobj(an, 0);
            if (geom == null) continue;
            String geomTag = geom.getobjecttag();
            if (geomTag == null) continue;

            // Look up class definition from the object tag
            String objTag = obj.getobjecttag();
            if (objTag == null) continue;
            AbstractClassDef classDef = findClassByTag(td, objTag);

            if (!first) sb.append(",");
            first = false;

            sb.append("\"").append(escJson(an)).append("\":");
            if (classDef != null) {
                sb.append(buildGeomValueJson(obj, an, geom, geomTag, classDef, td));
            } else {
                // Fallback: basic tag info
                sb.append("{\"tag\":\"").append(escJson(geomTag)).append("\"}");
            }
        }
        sb.append("}");
        return first ? "{}" : sb.toString();
    }

    /**
     * Builds a detailed JSON object for a single geometry attribute, using the
     * model-aware geometry pipeline.
     */
    private String buildGeomValueJson(IomObject obj, String attrName, IomObject geom,
                                       String geomTag, AbstractClassDef classDef,
                                       TransferDescription td) {
        // Find the attribute definition to get model metadata
        AttributeDef ad = null;
        Iterator<?> ait = classDef.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof AttributeDef attr && attr.getName().equals(attrName)) {
                ad = attr;
                break;
            }
        }

        // Fallback if attribute not found in class
        if (ad == null || !geometryTypeResolver.isGeometryAttribute(ad)) {
            return "{\"tag\":\"" + escJson(geomTag) + "\",\"note\":\"attribute not found in class schema\"}";
        }

        // Use the full geometry pipeline
        String[] parts = classDef.getScopedName(null).split("\\.");
        String modelName = parts.length > 0 ? parts[0] : "";
        String topicName = parts.length > 1 ? parts[1] : "";
        String className = parts.length > 2 ? parts[2] : classDef.getName();

        Model model = findModel(td, modelName);
        Topic topic = (model != null) ? findTopic(model, topicName) : null;
        GeometryMetadata meta = geometryTypeResolver.resolveMetadata(
                model, topic, classDef, ad);

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"geometry_kind\":\"").append(escJson(meta.geometryKind().name())).append("\"");

        // Encode geometry (WKT for generic reader)
        String wkt = null;
        String error = null;
        try {
            Optional<GeometryValue> val = geometryEncoder.encodeAttribute(obj, ad, meta);
            wkt = val.map(GeometryValue::wkt).orElse(null);
        } catch (GeometryConversionException e) {
            error = e.getMessage();
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        if (error != null) {
            sb.append(",\"_geometry_error\":\"").append(escJson(error)).append("\"");
        } else if (wkt != null) {
            sb.append(",\"encoding\":\"WKT\"");
            sb.append(",\"wkt\":\"").append(escJson(wkt)).append("\"");
        }

        sb.append(",\"dimension\":").append(meta.dimension() == GeometryDimension.XYZ ? 3 : 2);

        if (meta.coordinateDomainName() != null) {
            sb.append(",\"coordinate_domain\":\"").append(escJson(meta.coordinateDomainName())).append("\"");
        }
        if (meta.coordinateDomainFqn() != null) {
            sb.append(",\"coordinate_domain_fqn\":\"").append(escJson(meta.coordinateDomainFqn())).append("\"");
        }

        // CRS info via centralized resolver
        if (meta.coordinateDomainFqn() != null) {
            GeometryMetadataContext ctx = new GeometryMetadataContext(
                    meta.modelName(), meta.topicName(),
                    meta.className(), meta.attributeName(),
                    meta.coordinateDomainFqn());
            var crs = crsResolver.resolve(ctx);
            if (crs.isPresent()) {
                sb.append(",\"crs_auth_name\":\"").append(escJson(crs.get().authority())).append("\"");
                sb.append(",\"crs_code\":\"").append(escJson(crs.get().code())).append("\"");
            }
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Looks up an AbstractClassDef by the full object tag (Model.Topic.Class).
     */
    private AbstractClassDef findClassByTag(TransferDescription td, String tag) {
        if (tag == null) return null;
        String[] parts = tag.split("\\.");
        if (parts.length < 3) return null;
        return findClass(td, parts[0], parts[1], parts[2]);
    }

    private String buildRaw(IomObject obj) {
        return "{\"tag\":\"" + escJson(obj.getobjecttag()) + "\",\"tid\":\"" +
            escJson(obj.getobjectoid()) + "\",\"attr_count\":" + obj.getattrcount() + "}";
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
        if (count == 0) return null;
        IomObject child = obj.getattrobj(attrName, 0);
        if (child == null) return null;
        return buildStructObjJson(child);
    }

    private String buildBagStructureJson(IomObject obj, String attrName) {
        int count = obj.getattrvaluecount(attrName);
        if (count == 0) return hasAttribute(obj, attrName) ? "[]" : null;
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

    private static boolean hasAttribute(IomObject obj, String attrName) {
        int n = obj.getattrcount();
        for (int i = 0; i < n; i++) {
            if (attrName.equals(obj.getattrname(i))) return true;
        }
        return false;
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
     * Returns TSV with all recursively reachable STRUCTURE definitions used by a class.
     */
    public String readStructures(String className, String modelDir) {
        TransferDescription td = compileModel(modelDir, extractModelName(className));
        String[] parts = splitViewableName(className, "Class");

        AbstractClassDef cdef = findClass(td, parts[0], parts[1], parts[2]);
        if (cdef == null) throw new IllegalArgumentException("Class not found in model: " + className);

        StringBuilder sb = new StringBuilder();
        sb.append("root_class_fqn\tstructure_fqn\tstructure_name\tattribute_fqn\tattribute_name\tinterlis_type\tlogical_type\tkind\tis_mandatory\tcard_min\tcard_max\tenum_values_json\n");

        for (StructureRow row : collectStructureRows(className, cdef)) {
            sb.append(TsvCodec.encodeNullable(row.rootClassFqn())).append('\t');
            sb.append(TsvCodec.encodeNullable(row.structureFqn())).append('\t');
            sb.append(TsvCodec.encodeNullable(row.structureName())).append('\t');
            sb.append(TsvCodec.encodeNullable(row.attributeFqn())).append('\t');
            sb.append(TsvCodec.encodeNullable(row.attributeName())).append('\t');
            sb.append(TsvCodec.encodeNullable(row.interlisType())).append('\t');
            sb.append(TsvCodec.encodeNullable(row.logicalType())).append('\t');
            sb.append(TsvCodec.encodeNullable(row.kind())).append('\t');
            sb.append(TsvCodec.encodeNullableBoolean(row.isMandatory())).append('\t');
            sb.append(TsvCodec.encodeNullableInteger(row.cardMin())).append('\t');
            sb.append(TsvCodec.encodeNullableLong(row.cardMax())).append('\t');
            sb.append(TsvCodec.encodeNullable(row.enumValuesJson())).append('\n');
        }
        return sb.toString();
    }

    private List<StructureRow> collectStructureRows(String rootClassFqn, AbstractClassDef rootClass) {
        List<StructureRow> rows = new ArrayList<>();
        Deque<Table> queue = new ArrayDeque<>();
        Set<String> seenStructures = new LinkedHashSet<>();

        Iterator<?> ait = rootClass.getAttributesAndRoles2();
        while (ait.hasNext()) {
            ViewableTransferElement vte = (ViewableTransferElement) ait.next();
            if (vte.obj instanceof AttributeDef ad) {
                Table table = resolveStructureTable(ad);
                if (table != null) queue.add(table);
            }
        }

        while (!queue.isEmpty()) {
            Table structureTable = queue.removeFirst();
            String structureFqn = structureTable.getScopedName(null);
            if (structureFqn == null || !seenStructures.add(structureFqn)) continue;

            Iterator<?> sait = structureTable.getAttributesAndRoles2();
            while (sait.hasNext()) {
                ViewableTransferElement svte = (ViewableTransferElement) sait.next();
                if (!(svte.obj instanceof AttributeDef sad)) continue;

                Table nestedTable = resolveStructureTable(sad);
                if (nestedTable != null) queue.add(nestedTable);

                Cardinality cardinality = sad.getCardinality();
                long max = cardinality != null ? cardinality.getMaximum() : 1L;
                Long cardMax = max == Cardinality.UNBOUND ? null : max;
                rows.add(new StructureRow(
                        rootClassFqn,
                        structureFqn,
                        structureTable.getName(),
                        sad.getScopedName(null),
                        sad.getName(),
                        resolveInterlisTypeName(sad),
                        resolveStructureLogicalType(sad).sqlTypeName(),
                        resolveStructureKind(sad),
                        cardinality != null ? cardinality.getMinimum() >= 1 : false,
                        cardinality != null ? (int) cardinality.getMinimum() : 0,
                        cardMax,
                        enumValuesJson(sad)));
            }
        }
        return rows;
    }

    private Table resolveStructureTable(AttributeDef attributeDef) {
        Type domain = attributeDef.getDomain();
        if (domain instanceof CompositionType compositionType) {
            return compositionType.getComponentType();
        }
        if (domain instanceof ObjectType objectType && !objectType.isObjects()) {
            Viewable<?> ref = objectType.getRef();
            if (ref instanceof Table table) return table;
        }
        return null;
    }

    private InterlisLogicalTypeMapper.LogicalType resolveStructureLogicalType(AttributeDef attributeDef) {
        if (geometryTypeResolver.isGeometryAttribute(attributeDef)) {
            return InterlisLogicalTypeMapper.LogicalType.GEOMETRY;
        }
        if (isStructureDomain(attributeDef) || isCompositionDomain(attributeDef)) {
            return InterlisLogicalTypeMapper.LogicalType.VARCHAR;
        }
        return logicalTypeMapper.mapAttribute(attributeDef);
    }

    private String resolveStructureKind(AttributeDef attributeDef) {
        if (geometryTypeResolver.isGeometryAttribute(attributeDef)) return "GEOMETRY";
        if (isStructureDomain(attributeDef)) return "STRUCTURE";
        if (isCompositionDomain(attributeDef)) return "COMPOSITION";

        Type base = logicalTypeMapper.resolveToBaseType(attributeDef);
        if (base instanceof EnumerationType) return "ENUM";
        if (base instanceof ReferenceType) return "REFERENCE";
        if (attributeDef.getDomain() instanceof ObjectType objectType && objectType.isObjects()) return "REFERENCE";
        return "SCALAR";
    }

    private String resolveInterlisTypeName(AttributeDef attributeDef) {
        Type domain = attributeDef.getDomain();
        if (domain instanceof TypeAlias typeAlias) {
            Domain aliasing = typeAlias.getAliasing();
            if (aliasing != null) {
                String scopedName = aliasing.getScopedName(null);
                if (scopedName != null) return scopedName;
                if (aliasing.getName() != null) return aliasing.getName();
            }
        }
        if (domain instanceof CompositionType compositionType) {
            Table componentType = compositionType.getComponentType();
            return componentType != null ? componentType.getScopedName(null) : "COMPOSITION";
        }
        if (domain instanceof ObjectType objectType && !objectType.isObjects()) {
            Viewable<?> ref = objectType.getRef();
            return ref != null ? ref.getScopedName(null) : "STRUCTURE";
        }
        Type base = logicalTypeMapper.resolveToBaseType(attributeDef);
        if (base instanceof EnumerationType) {
            String scopedName = base.getScopedName(null);
            return scopedName != null ? scopedName : "ENUM";
        }
        if (base instanceof NumericType) return "NUMERIC";
        if (base instanceof TextType) return "TEXT";
        if (base instanceof ReferenceType) return "REFERENCE";
        if (base instanceof MultiCoordType) return "MULTICOORD";
        if (base instanceof MultiPolylineType) return "MULTIPOLYLINE";
        if (base instanceof MultiSurfaceType) return "MULTISURFACE";
        if (base instanceof MultiAreaType) return "MULTIAREA";
        if (base instanceof AbstractCoordType) return "COORD";
        if (base instanceof LineType) return "POLYLINE";
        if (base instanceof AbstractSurfaceOrAreaType) return "SURFACE_OR_AREA";
        String scopedName = base != null ? base.getScopedName(null) : null;
        if (scopedName != null) return scopedName;
        String name = base != null ? base.getName() : null;
        return name != null ? name : "UNKNOWN";
    }

    private String enumValuesJson(AttributeDef attributeDef) {
        Type base = logicalTypeMapper.resolveToBaseType(attributeDef);
        if (!(base instanceof EnumerationType enumerationType)) return null;

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        ch.interlis.ili2c.metamodel.Enumeration consolidated = enumerationType.getConsolidatedEnumeration();
        for (Iterator<?> it = consolidated.getElements(); it.hasNext(); ) {
            ch.interlis.ili2c.metamodel.Enumeration.Element element =
                    (ch.interlis.ili2c.metamodel.Enumeration.Element) it.next();
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escJson(element.getName())).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Role / Reference extraction
    // -----------------------------------------------------------------------

    private static String getRoleRef(IomObject obj, String roleName) {
        int cnt = obj.getattrvaluecount(roleName);
        if (cnt == 0) return null;
        IomObject child = obj.getattrobj(roleName, 0);
        if (child == null) return null;
        String ref = child.getobjectrefoid();
        return ref != null ? ref : null;
    }

    // -----------------------------------------------------------------------
    // Association reading
    // -----------------------------------------------------------------------

    public String readAssociationSchema(String associationName, String modelDir) {
        return renderHeader(buildAssociationColumnSpecs(associationName, modelDir));
    }

    public String readAssociation(String xtfPath, String associationName, String modelDir) {
        return readNamedViewable(
                xtfPath,
                modelDir,
                associationName,
                "json",
                false,
                buildAssociationColumnSpecs(associationName, modelDir));
    }

    public String readAssociationSchemaV2(String associationName, String modelDir) {
        return renderSchemaV2(buildAssociationColumnSpecs(associationName, modelDir));
    }

    public String readAssociationV2(String xtfPath, String associationName, String modelDir) {
        return readNamedViewable(
                xtfPath,
                modelDir,
                associationName,
                "json",
                true,
                buildAssociationColumnSpecs(associationName, modelDir));
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
