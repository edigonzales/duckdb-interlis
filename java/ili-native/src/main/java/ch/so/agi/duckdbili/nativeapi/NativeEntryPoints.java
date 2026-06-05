package ch.so.agi.duckdbili.nativeapi;

import java.nio.file.Path;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.word.WordFactory;

import ch.so.agi.duckdbili.core.CoreVersion;
import ch.so.agi.duckdbili.core.model.IliModelService;
import ch.so.agi.duckdbili.core.validation.IliValidatorService;
import ch.so.agi.duckdbili.core.validation.ValidationMessage;
import ch.so.agi.duckdbili.core.validation.ValidationResult;
import ch.so.agi.duckdbili.core.xtf.XtfObjectReader;

public class NativeEntryPoints {

    private static final IliValidatorService VALIDATOR = new IliValidatorService();
    private static final IliModelService MODEL_SERVICE = new IliModelService();

    public static void main(String[] args) {
        System.out.println("ILI Native Library - use as shared library only");
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
        return 0;
    }

    @CEntryPoint(name = "ili_native_echo")
    public static int nativeEcho(
            IsolateThread thread,
            CCharPointer requestJson,
            CCharPointerPointer outPayload) {

        String request = CTypeConversion.toJavaString(requestJson);
        String json = "{\"echo\": \"" + escapeJson(request) + "\"}";
        outPayload.write(allocCString(json));
        return 0;
    }

    @CEntryPoint(name = "ili_free_string")
    public static void freeString(
            IsolateThread thread,
            CCharPointer str) {

        if (str.isNonNull()) {
            UnmanagedMemory.free(str);
        }
    }

    @CEntryPoint(name = "ili_native_validate")
    public static int nativeValidate(
            IsolateThread thread,
            CCharPointer requestJson,
            CCharPointerPointer outPayload) {

        String request = CTypeConversion.toJavaString(requestJson);
        String input = extractJsonField(request, "input");
        String modelDir = extractJsonField(request, "modeldir");
        int maxMessages = extractJsonInt(request, "maxMessages", -1);

        if (input == null || input.isBlank()) {
            outPayload.write(allocCString("{\"valid\":false,\"error\":\"Missing 'input' field\"}"));
            return 1;
        }
        if (modelDir == null || modelDir.isBlank()) {
            outPayload.write(allocCString("{\"valid\":false,\"error\":\"Missing 'modeldir' field\"}"));
            return 1;
        }

        IliValidatorService service = new IliValidatorService();
        ValidationResult result = service.validate(Path.of(input), modelDir, maxMessages);

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
        return 0;
    }

    @CEntryPoint(name = "ili_native_validate_tsv")
    public static int nativeValidateTsv(
            IsolateThread thread,
            CCharPointer requestJson,
            CCharPointerPointer outPayload) {

        String request = CTypeConversion.toJavaString(requestJson);
        String input = extractJsonField(request, "input");
        String modelDir = extractJsonField(request, "modeldir");
        int maxMessages = extractJsonInt(request, "maxMessages", -1);

        if (input == null || input.isBlank()) {
            outPayload.write(allocCString("-1\t0\t0\nMissing 'input' field"));
            return 1;
        }
        if (modelDir == null || modelDir.isBlank()) {
            outPayload.write(allocCString("-1\t0\t0\nMissing 'modeldir' field"));
            return 1;
        }

        IliValidatorService service = new IliValidatorService();
        ValidationResult result = service.validate(Path.of(input), modelDir, maxMessages);

        StringBuilder tsv = new StringBuilder();
        // Header line: errorCount, warningCount, infoCount
        tsv.append(result.getErrorCount()).append('\t');
        tsv.append(result.getWarningCount()).append('\t');
        tsv.append(result.getInfoCount()).append('\n');

        for (ValidationMessage msg : result.getMessages()) {
            tsv.append(escapeTsv(msg.getSeverity())).append('\t');
            tsv.append(escapeTsv("")).append('\t');  // code
            tsv.append(escapeTsv(msg.getMessage())).append('\t');
            tsv.append(escapeTsv(msg.getFileName())).append('\t');
            tsv.append(msg.getLine() == null ? "" : String.valueOf(msg.getLine())).append('\t');
            tsv.append("").append('\t');  // column
            tsv.append(escapeTsv(msg.getXtfTid())).append('\t');
            tsv.append(escapeTsv(null)).append('\t');  // xtfBid
            tsv.append(escapeTsv(msg.getModel())).append('\t');
            tsv.append(escapeTsv(msg.getTopic())).append('\t');
            tsv.append(escapeTsv(msg.getClassName())).append('\t');
            tsv.append(escapeTsv(msg.getAttributeName())).append('\t');
            tsv.append(escapeTsv(msg.getRaw())).append('\n');
        }

        outPayload.write(allocCString(tsv.toString()));
        return 0;
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

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(":", keyIdx + key.length());
        if (colonIdx < 0) return null;
        int start = colonIdx + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') end++;
        return json.substring(start, end).trim();
    }

    private static int extractJsonInt(String json, String field, int defaultValue) {
        String val = extractJsonField(json, field);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static CCharPointer allocCString(String s) {
        if (s == null) {
            return WordFactory.nullPointer();
        }
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    // -----------------------------------------------------------------------
    // Model analysis entry point
    // -----------------------------------------------------------------------

    @CEntryPoint(name = "ili_native_model_info")
    public static int nativeModelInfo(
            IsolateThread thread,
            CCharPointer requestJson,
            CCharPointerPointer outPayload) {

        String request = CTypeConversion.toJavaString(requestJson);
        String cmd = extractJsonField(request, "cmd");      // models, topics, classes, attributes, enumerations
        String modelDir = extractJsonField(request, "modeldir");
        String modelName = extractJsonField(request, "model");
        String className = extractJsonField(request, "class");

        if (modelDir == null || modelDir.isBlank()) {
            outPayload.write(allocCString("ERROR: Missing 'modeldir' field"));
            return 1;
        }

        String result;
        try {
            result = switch (cmd != null ? cmd : "") {
                case "models" -> MODEL_SERVICE.getModels(modelDir);
                case "topics" -> MODEL_SERVICE.getTopics(modelDir, modelName);
                case "classes" -> MODEL_SERVICE.getClasses(modelDir, modelName);
                case "attributes" -> MODEL_SERVICE.getAttributes(modelDir, className);
                case "enumerations" -> MODEL_SERVICE.getEnumerations(modelDir, modelName);
                default -> "ERROR: Unknown command: " + cmd;
            };
        } catch (Exception e) {
            result = "ERROR: " + e.getMessage();
        }

        outPayload.write(allocCString(result));
        return 0;
    }

    // -----------------------------------------------------------------------
    // XTF reading entry point
    // -----------------------------------------------------------------------

    private static final XtfObjectReader XTF_READER = new XtfObjectReader();

    @CEntryPoint(name = "ili_native_read_xtf")
    public static int nativeReadXtf(
            IsolateThread thread,
            CCharPointer requestJson,
            CCharPointerPointer outPayload) {

        String request = CTypeConversion.toJavaString(requestJson);
        String input = extractJsonField(request, "input");
        String modelDir = extractJsonField(request, "modeldir");
        String modelNames = extractJsonField(request, "models");

        if (input == null || input.isBlank()) {
            outPayload.write(allocCString("ERROR: Missing 'input' field"));
            return 1;
        }

        try {
            String tsv = XTF_READER.readObjects(input, modelDir, modelNames);
            outPayload.write(allocCString(tsv));
            return 0;
        } catch (Exception e) {
            outPayload.write(allocCString("ERROR: " + e.getMessage()));
            return 1;
        }
    }

    @CEntryPoint(name = "ili_native_read_xtf_class")
    public static int nativeReadXtfClass(
            IsolateThread thread,
            CCharPointer requestJson,
            CCharPointerPointer outPayload) {

        String request = CTypeConversion.toJavaString(requestJson);
        String input = extractJsonField(request, "input");
        String className = extractJsonField(request, "class");
        String modelDir = extractJsonField(request, "modeldir");

        if (input == null || className == null) {
            outPayload.write(allocCString("ERROR: Missing input/class fields"));
            return 1;
        }
        try {
            String tsv = XTF_READER.readClass(input, className, modelDir);
            outPayload.write(allocCString(tsv));
            return 0;
        } catch (Exception e) {
            outPayload.write(allocCString("ERROR: " + e.getMessage()));
            return 1;
        }
    }

    @CEntryPoint(name = "ili_native_read_xtf_class_schema")
    public static int nativeReadXtfClassSchema(
            IsolateThread thread,
            CCharPointer requestJson,
            CCharPointerPointer outPayload) {

        String request = CTypeConversion.toJavaString(requestJson);
        String className = extractJsonField(request, "class");
        String modelDir = extractJsonField(request, "modeldir");

        if (className == null) {
            outPayload.write(allocCString(""));
            return 1;
        }
        // Returns just the column names as tab-separated line
        // Same logic as readClass but returns only header
        String tsv = XTF_READER.readClassSchema(className, modelDir);
        outPayload.write(allocCString(tsv));
        return 0;
    }
}
