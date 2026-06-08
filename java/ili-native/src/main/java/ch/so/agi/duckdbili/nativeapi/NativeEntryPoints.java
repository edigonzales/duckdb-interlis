package ch.so.agi.duckdbili.nativeapi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

import ch.so.agi.duckdbili.core.CoreVersion;
import ch.so.agi.duckdbili.core.NativeError;
import ch.so.agi.duckdbili.core.NativeStatus;
import ch.so.agi.duckdbili.core.importer.IliImportService;
import ch.so.agi.duckdbili.core.model.IliModelService;
import ch.so.agi.duckdbili.core.validation.IliValidatorService;
import ch.so.agi.duckdbili.core.validation.ValidationMessage;
import ch.so.agi.duckdbili.core.validation.ValidationProfile;
import ch.so.agi.duckdbili.core.validation.ValidationResult;
import ch.so.agi.duckdbili.core.xtf.XtfObjectReader;

public class NativeEntryPoints {

    private static volatile IliModelService modelService;
    private static volatile XtfObjectReader xtfReader;
    private static volatile IliImportService importService;

    // ABI handshake constants
    static final int ABI_VERSION = 1; // Must match ILI_NATIVE_ABI_VERSION in ili_api.h

    public static void main(String[] args) {
        System.out.println("ILI Native Library - use as shared library only");
    }

    private static IliModelService getModelService() {
        IliModelService s = modelService;
        if (s == null) {
            synchronized (NativeEntryPoints.class) {
                s = modelService;
                if (s == null) {
                    modelService = s = new IliModelService();
                }
            }
        }
        return s;
    }

    private static XtfObjectReader getXtfReader() {
        XtfObjectReader r = xtfReader;
        if (r == null) {
            synchronized (NativeEntryPoints.class) {
                r = xtfReader;
                if (r == null) {
                    xtfReader = r = new XtfObjectReader();
                }
            }
        }
        return r;
    }

    private static IliImportService getImportService() {
        IliImportService s = importService;
        if (s == null) {
            synchronized (NativeEntryPoints.class) {
                s = importService;
                if (s == null) {
                    importService = s = new IliImportService();
                }
            }
        }
        return s;
    }

    @CEntryPoint(name = "ili_native_version")
    public static int nativeVersion(
            IsolateThread thread,
            CCharPointerPointer outPayload) {

        String json = "{"
                + "\"native_version\": \"" + NativeVersion.VERSION + "\","
                + "\"core_version\": \"" + CoreVersion.VERSION + "\","
                + "\"graalvm_version\": \"" + NativeVersion.GRAALVM_VERSION + "\","
                + "\"platform\": \"" + NativeVersion.PLATFORM + "\","
                + "\"native_lib\": \"" + NativeVersion.NATIVE_LIB + "\""
                + "}";
        outPayload.write(allocCString(json));
        return NativeStatus.OK;
    }

    @CEntryPoint(name = "ili_native_echo")
    public static int nativeEcho(
            IsolateThread thread,
            CCharPointer requestJson,
            CCharPointerPointer outPayload) {

        String request = CTypeConversion.toJavaString(requestJson);
        String json = "{\"echo\": \"" + escapeJson(request) + "\"}";
        outPayload.write(allocCString(json));
        return NativeStatus.OK;
    }

    @CEntryPoint(name = "ili_free_string")
    public static void freeString(
            IsolateThread thread,
            CCharPointer str) {

        if (str.isNonNull()) {
            UnmanagedMemory.free(str);
        }
    }

    // -----------------------------------------------------------------------
    // Validate entry points
    // -----------------------------------------------------------------------

    @CEntryPoint(name = "ili_native_validate")
    public static int nativeValidate(
            IsolateThread thread,
            IliRequest request,
            CCharPointerPointer outPayload) {

        String input = getField(request.input());
        String modelDir = getField(request.modeldir());
        int maxMessages = request.max_messages();
        String profileStr = getField(request.profile());

        if (input == null || input.isBlank()) {
            NativeError err = NativeError.invalidArgument("validate", "Missing required field", "input");
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }

        try {
            IliValidatorService service = new IliValidatorService();
            ValidationProfile profile = ValidationProfile.fromString(profileStr);
            ValidationResult result = service.validate(Path.of(input), modelDir, maxMessages, profile);

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"valid\":").append(result.isValid()).append(",");
            json.append("\"errorCount\":").append(result.getErrorCount()).append(",");
            json.append("\"warningCount\":").append(result.getWarningCount()).append(",");
            json.append("\"infoCount\":").append(result.getInfoCount()).append(",");
            json.append("\"messages\":[");
            boolean first = true;
            for (ValidationMessage msg : result.getMessages()) {
                if (!first) json.append(",");
                first = false;
                json.append("{");
                json.append("\"severity\":\"").append(escapeJson(msg.getSeverity())).append("\",");
                json.append("\"code\":\"").append(escapeJson(msg.getCode())).append("\",");
                json.append("\"message\":\"").append(escapeJson(msg.getMessage())).append("\",");
                json.append("\"fileName\":\"").append(escapeJson(msg.getFileName())).append("\",");
                json.append("\"line\":").append(msg.getLine() == null ? "null" : msg.getLine()).append(",");
                json.append("\"column\":null,");
                json.append("\"xtfTid\":").append(quoteOrNull(msg.getXtfTid())).append(",");
                json.append("\"xtfBid\":null,");
                json.append("\"model\":").append(quoteOrNull(msg.getModel())).append(",");
                json.append("\"topic\":").append(quoteOrNull(msg.getTopic())).append(",");
                json.append("\"className\":").append(quoteOrNull(msg.getClassName())).append(",");
                json.append("\"attributeName\":").append(quoteOrNull(msg.getAttributeName()));
                json.append("}");
            }
            json.append("]}");

            outPayload.write(allocCString(json.toString()));
            return NativeStatus.OK;
        } catch (Exception e) {
            NativeError err = NativeError.internalError("validate", e);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INTERNAL_ERROR;
        }
    }

    @CEntryPoint(name = "ili_native_validate_tsv")
    public static int nativeValidateTsv(
            IsolateThread thread,
            IliRequest request,
            CCharPointerPointer outPayload) {

        String input = getField(request.input());
        String modelDir = getField(request.modeldir());
        int maxMessages = request.max_messages();
        String profileStr = getField(request.profile());

        if (input == null || input.isBlank()) {
            NativeError err = NativeError.invalidArgument("validate_tsv", "Missing required field", "input");
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }

        try {
            IliValidatorService service = new IliValidatorService();
            ValidationProfile profile = ValidationProfile.fromString(profileStr);
            ValidationResult result = service.validate(Path.of(input), modelDir, maxMessages, profile);

            StringBuilder tsv = new StringBuilder();
            tsv.append(result.getErrorCount()).append('\t');
            tsv.append(result.getWarningCount()).append('\t');
            tsv.append(result.getInfoCount()).append('\n');

            for (ValidationMessage msg : result.getMessages()) {
                tsv.append(escapeTsv(msg.getSeverity())).append('\t');
                tsv.append(escapeTsv(msg.getCode())).append('\t');
                tsv.append(escapeTsv(msg.getMessage())).append('\t');
                tsv.append(escapeTsv(msg.getFileName())).append('\t');
                tsv.append(msg.getLine() == null ? "" : String.valueOf(msg.getLine())).append('\t');
                tsv.append("").append('\t');
                tsv.append(escapeTsv(msg.getXtfTid())).append('\t');
                tsv.append(escapeTsv(null)).append('\t');
                tsv.append(escapeTsv(msg.getModel())).append('\t');
                tsv.append(escapeTsv(msg.getTopic())).append('\t');
                tsv.append(escapeTsv(msg.getClassName())).append('\t');
                tsv.append(escapeTsv(msg.getAttributeName())).append('\t');
                tsv.append(escapeTsv(msg.getRaw())).append('\n');
            }

            outPayload.write(allocCString(tsv.toString()));
            return NativeStatus.OK;
        } catch (Exception e) {
            NativeError err = NativeError.internalError("validate_tsv", e);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INTERNAL_ERROR;
        }
    }

    // -----------------------------------------------------------------------
    // Model analysis entry point
    // -----------------------------------------------------------------------

    @CEntryPoint(name = "ili_native_model_info")
    public static int nativeModelInfo(
            IsolateThread thread,
            IliRequest request,
            CCharPointerPointer outPayload) {

        String cmd = getField(request.cmd());
        String modelDir = getField(request.modeldir());
        String modelName = getField(request.model());
        String className = getField(request.class_name());

        try {
            String result = switch (cmd != null ? cmd : "") {
                case "models" -> getModelService().getModels(modelDir);
                case "topics" -> getModelService().getTopics(modelDir, modelName);
                case "classes" -> getModelService().getClasses(modelDir, modelName);
                case "attributes" -> getModelService().getAttributes(modelDir, className);
                case "enumerations" -> getModelService().getEnumerations(modelDir, modelName);
                default -> {
                    NativeError err = NativeError.unsupported("model_info", "Unknown command", cmd);
                    outPayload.write(allocCString(err.toJson()));
                    yield null;
                }
            };
            if (result == null) return NativeStatus.UNSUPPORTED;

            outPayload.write(allocCString(result));
            return NativeStatus.OK;
        } catch (Exception e) {
            NativeError err = NativeError.modelError("model_info",
                    "Model info failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    e.toString(), modelDir);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.MODEL_ERROR;
        }
    }

    // -----------------------------------------------------------------------
    // XTF reading entry points
    // -----------------------------------------------------------------------

    @CEntryPoint(name = "ili_native_read_xtf")
    public static int nativeReadXtf(
            IsolateThread thread,
            IliRequest request,
            CCharPointerPointer outPayload) {

        String input = getField(request.input());
        String modelDir = getField(request.modeldir());
        String modelNames = getField(request.models());

        if (input == null || input.isBlank()) {
            NativeError err = NativeError.invalidArgument("read_xtf", "Missing required field", "input");
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }

        try {
            String tsv = getXtfReader().readObjects(input, modelDir, modelNames);
            outPayload.write(allocCString(tsv));
            return NativeStatus.OK;
        } catch (Exception e) {
            NativeError err = NativeError.ioError("read_xtf",
                    "XTF read failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    e.toString(), input);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.IO_ERROR;
        }
    }

    @CEntryPoint(name = "ili_native_read_xtf_class")
    public static int nativeReadXtfClass(
            IsolateThread thread,
            IliRequest request,
            CCharPointerPointer outPayload) {

        String input = getField(request.input());
        String className = getField(request.class_name());
        String modelDir = getField(request.modeldir());
        String nested = getField(request.nested());

        if (input == null || className == null) {
            String missing = input == null ? "input" : "class";
            NativeError err = NativeError.invalidArgument("read_xtf_class", "Missing required field", missing);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }
        try {
            String tsv = getXtfReader().readClass(input, className, modelDir, nested);
            outPayload.write(allocCString(tsv));
            return NativeStatus.OK;
        } catch (Exception e) {
            NativeError err = NativeError.ioError("read_xtf_class",
                    "XTF class read failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    e.toString(), input);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.IO_ERROR;
        }
    }

    @CEntryPoint(name = "ili_native_read_xtf_class_schema")
    public static int nativeReadXtfClassSchema(
            IsolateThread thread,
            IliRequest request,
            CCharPointerPointer outPayload) {

        String className = getField(request.class_name());
        String modelDir = getField(request.modeldir());
        String nested = getField(request.nested());

        if (className == null) {
            NativeError err = NativeError.invalidArgument("read_xtf_class_schema", "Missing required field", "class");
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }
        try {
            String tsv = getXtfReader().readClassSchema(className, modelDir, nested);
            outPayload.write(allocCString(tsv));
            return NativeStatus.OK;
        } catch (Exception e) {
            NativeError err = NativeError.modelError("read_xtf_class_schema",
                    "Schema read failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    e.toString(), modelDir);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.MODEL_ERROR;
        }
    }

    @CEntryPoint(name = "ili_native_read_xtf_structures")
    public static int nativeReadXtfStructures(
            IsolateThread thread,
            IliRequest request,
            CCharPointerPointer outPayload) {

        String className = getField(request.class_name());
        String modelDir = getField(request.modeldir());

        if (className == null) {
            NativeError err = NativeError.invalidArgument("read_xtf_structures", "Missing required field", "class");
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }
        try {
            String tsv = getXtfReader().readStructures(className, modelDir);
            outPayload.write(allocCString(tsv));
            return NativeStatus.OK;
        } catch (Exception e) {
            NativeError err = NativeError.modelError("read_xtf_structures",
                    "Structures read failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    e.toString(), modelDir);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.MODEL_ERROR;
        }
    }

    @CEntryPoint(name = "ili_native_read_xtf_association")
    public static int nativeReadXtfAssociation(
            IsolateThread thread,
            IliRequest request,
            CCharPointerPointer outPayload) {

        String input = getField(request.input());
        String associationName = getField(request.association());
        String modelDir = getField(request.modeldir());

        if (input == null || associationName == null) {
            String missing = input == null ? "input" : "association";
            NativeError err = NativeError.invalidArgument("read_xtf_association", "Missing required field", missing);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }
        try {
            String tsv = getXtfReader().readAssociation(input, associationName, modelDir);
            outPayload.write(allocCString(tsv));
            return NativeStatus.OK;
        } catch (Exception e) {
            NativeError err = NativeError.ioError("read_xtf_association",
                    "Association read failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    e.toString(), input);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.IO_ERROR;
        }
    }

    @CEntryPoint(name = "ili_native_read_xtf_association_schema")
    public static int nativeReadXtfAssociationSchema(
            IsolateThread thread,
            IliRequest request,
            CCharPointerPointer outPayload) {

        String associationName = getField(request.association());
        String modelDir = getField(request.modeldir());

        if (associationName == null) {
            NativeError err = NativeError.invalidArgument("read_xtf_association_schema",
                    "Missing required field", "association");
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }
        try {
            String tsv = getXtfReader().readAssociationSchema(associationName, modelDir);
            outPayload.write(allocCString(tsv));
            return NativeStatus.OK;
        } catch (Exception e) {
            NativeError err = NativeError.modelError("read_xtf_association_schema",
                    "Schema read failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    e.toString(), modelDir);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.MODEL_ERROR;
        }
    }

    // -----------------------------------------------------------------------
    // XTF import entry point
    // -----------------------------------------------------------------------

    @CEntryPoint(name = "ili_native_generate_import_sql")
    public static int nativeGenerateImportSql(
            IsolateThread thread,
            IliRequest request,
            CCharPointerPointer outPayload) {

        String input = getField(request.input());
        String modelDir = getField(request.modeldir());
        String schema = getField(request.schema());
        String mapping = getField(request.mapping());
        String mode = getField(request.mode());

        if (input == null || input.isBlank()) {
            NativeError err = NativeError.invalidArgument("import_xtf", "Missing required field", "input");
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }
        if (schema == null || schema.isBlank()) {
            NativeError err = NativeError.invalidArgument("import_xtf", "Missing required field", "schema");
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }
        if (mapping != null && !mapping.equals("relational")) {
            NativeError err = NativeError.unsupported("import_xtf",
                    "Unsupported mapping mode: '" + mapping + "'. Only 'relational' is currently supported.",
                    "mapping");
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.UNSUPPORTED;
        }

        try {
            IliImportService impSvc = getImportService();
            String sql = impSvc.generateImportSql(input, modelDir, schema, mapping, mode);
            outPayload.write(allocCString(sql));
            return NativeStatus.OK;
        } catch (Exception e) {
            NativeError err = NativeError.modelError("import_xtf",
                    "Import SQL generation failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    e.toString(), modelDir);
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.MODEL_ERROR;
        }
    }

    // -----------------------------------------------------------------------
    // ABI Handshake entry point
    // -----------------------------------------------------------------------

    @CEntryPoint(name = "ili_get_api")
    public static int iliGetApi(
            IsolateThread thread,
            int requestedAbiVersion,
            CCharPointerPointer outPayload) {

        if (requestedAbiVersion < 1) {
            NativeError err = NativeError.invalidArgument("ili_get_api",
                    "Requested ABI version too low", String.valueOf(requestedAbiVersion));
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.INVALID_ARGUMENT;
        }
        if (requestedAbiVersion > ABI_VERSION) {
            NativeError err = NativeError.unsupported("ili_get_api",
                    "Requested ABI version too high", String.valueOf(requestedAbiVersion));
            outPayload.write(allocCString(err.toJson()));
            return NativeStatus.UNSUPPORTED;
        }

        long capabilities =
            (1L << 0)  // VERSION
            | (1L << 1)  // VALIDATE
            | (1L << 2)  // VALIDATE_TSV
            | (1L << 3)  // MODEL_INFO
            | (1L << 4)  // READ_XTF
            | (1L << 5)  // READ_XTF_CLASS
            | (1L << 6)  // READ_XTF_CLASS_SCHEMA
            | (1L << 7)  // READ_XTF_STRUCTURES
            | (1L << 8)  // READ_XTF_ASSOCIATION
            | (1L << 9)  // READ_XTF_ASSOC_SCHEMA
            | (1L << 10) // IMPORT_XTF
            | (1L << 11); // FREE_STRING

        // Return ABI metadata as simple JSON
        String json = "{\"abi_version\":" + ABI_VERSION + ",\"capabilities\":" + capabilities + "}";
        outPayload.write(allocCString(json));
        return NativeStatus.OK;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String getField(CCharPointer ptr) {
        if (ptr.isNull()) {
            return null;
        }
        return CTypeConversion.toJavaString(ptr);
    }

    private static String escapeTsv(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String quoteOrNull(String s) {
        if (s == null) return "null";
        return "\"" + escapeJson(s) + "\"";
    }

    private static CCharPointer allocCString(String s) {
        if (s == null) {
            return WordFactory.nullPointer();
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        CCharPointer ptr = UnmanagedMemory.malloc(bytes.length + 1);
        for (int i = 0; i < bytes.length; i++) {
            ptr.write(i, bytes[i]);
        }
        ptr.write(bytes.length, (byte) 0);
        return ptr;
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == '\u00A0') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
