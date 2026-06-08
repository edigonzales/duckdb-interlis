package ch.so.agi.duckdbili.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class NativeErrorTest {

    @Test
    @DisplayName("JSON format contains all required fields")
    void testErrorJsonFormat() {
        NativeError err = NativeError.modelError("compile_model",
                "Model compilation failed", "File not found: /tmp/models",
                "/tmp/models", "MyModel");
        String json = err.toJson();

        assertTrue(json.startsWith("{"), "Should start with {");
        assertTrue(json.endsWith("}"), "Should end with }");
        assertTrue(json.contains("\"status\":\"MODEL_ERROR\""), "Should contain status");
        assertTrue(json.contains("\"operation\":\"compile_model\""), "Should contain operation");
        assertTrue(json.contains("\"message\":\"Model compilation failed\""), "Should contain message");
        assertTrue(json.contains("\"detail\":\"File not found: /tmp/models\""), "Should contain detail");
        assertTrue(json.contains("\"path\":\"/tmp/models\""), "Should contain path");
        assertTrue(json.contains("\"model\":\"MyModel\""), "Should contain model");
    }

    @Test
    @DisplayName("Null optional fields are excluded from JSON")
    void testNullFieldsExcluded() {
        NativeError err = NativeError.ioError("read_xtf",
                "XTF read failed", "File not found",
                "/path/file.xtf");
        String json = err.toJson();

        assertFalse(json.contains("\"model\""), "Null model should be excluded");
        assertFalse(json.contains("\"exception\""), "Null exception should be excluded");
    }

    @Test
    @DisplayName("All status codes produce valid JSON")
    void testAllStatusCodes() {
        NativeError[] errors = {
            NativeError.invalidArgument("test", "Invalid argument", "detail"),
            NativeError.ioError("test", "IO error", "detail", "/path"),
            NativeError.modelError("test", "Model error", "detail", "/path"),
            NativeError.parseError("test", "Parse error", "detail"),
            NativeError.validationError("test", "Validation error", "detail"),
            NativeError.unsupported("test", "Unsupported operation", "detail"),
            NativeError.internalError("test", "Internal error", "RuntimeException")
        };
        for (NativeError err : errors) {
            String json = err.toJson();
            assertTrue(json.startsWith("{"), "JSON should start with { for status " + err.status());
            assertTrue(json.contains("\"status\""), "Should contain status");
            assertTrue(json.contains("\"message\""), "Should contain message");
        }
    }

    @Test
    @DisplayName("Internal error from exception preserves message and class")
    void testInternalErrorFromException() {
        RuntimeException cause = new RuntimeException("Something broke");
        NativeError err = NativeError.internalError("import_xtf", cause);

        assertEquals(NativeStatus.INTERNAL_ERROR, err.status());
        assertEquals("import_xtf", err.operation());
        assertTrue(err.message().contains("Something broke"));
        assertEquals("java.lang.RuntimeException", err.exceptionClass());

        String json = err.toJson();
        assertTrue(json.contains("\"status\":\"INTERNAL_ERROR\""));
        assertTrue(json.contains("\"exception\":\"java.lang.RuntimeException\""));
    }

    @Test
    @DisplayName("Status name mapping is correct")
    void testStatusNames() {
        assertEquals("OK", NativeStatus.nameFor(0));
        assertEquals("INVALID_ARGUMENT", NativeStatus.nameFor(1));
        assertEquals("IO_ERROR", NativeStatus.nameFor(2));
        assertEquals("MODEL_ERROR", NativeStatus.nameFor(3));
        assertEquals("INTERNAL_ERROR", NativeStatus.nameFor(100));
        assertEquals("UNKNOWN_999", NativeStatus.nameFor(999));
    }

    @Test
    @DisplayName("JSON escaping handles special characters")
    void testJsonEscaping() {
        NativeError err = NativeError.internalError("test_op",
                "Error with \"quotes\" and \\backslashes",
                "SpecialChars");
        String json = err.toJson();

        assertTrue(json.contains("\\\"quotes\\\""));
        assertTrue(json.contains("\\\\backslashes"));
    }

    @Test
    @DisplayName("Factory methods use correct status codes")
    void testFactoryMethods() {
        NativeError arg = NativeError.invalidArgument("op", "msg", "detail");
        assertEquals(NativeStatus.INVALID_ARGUMENT, arg.status());

        NativeError io = NativeError.ioError("op", "msg", "detail", "/path");
        assertEquals(NativeStatus.IO_ERROR, io.status());
        assertEquals("/path", io.path());

        NativeError model = NativeError.modelError("op", "msg", "detail", "/md");
        assertEquals(NativeStatus.MODEL_ERROR, model.status());

        NativeError unsup = NativeError.unsupported("op", "msg", "detail");
        assertEquals(NativeStatus.UNSUPPORTED, unsup.status());
    }

    @Test
    @DisplayName("JSON handles Unicode characters in message fields")
    void testUnicodeInErrorPayload() {
        NativeError err = NativeError.validationError("validate_xml",
                "Ungültiges Zeichen in Feld «Höhe ü. M.»: äöü ÄÖÜ éèê 中文",
                "Attribut 'Höhe' enthält Unicode: 🎉");
        String json = err.toJson();

        assertTrue(json.contains("Höhe"));
        assertTrue(json.contains("äöü"));
        assertTrue(json.contains("中文"));
        assertTrue(json.contains("🎉"));
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
    }
}
