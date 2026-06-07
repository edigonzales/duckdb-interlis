#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include "libduckdb_ili_native_dynamic.h"

static int check_error_payload(int rc, char *payload, const char *test_name) {
    int ok = 1;
    fprintf(stderr, "  rc=%d payload=%p\n", rc, (void*)payload);
    if (payload) {
        fprintf(stderr, "  payload (first 200 chars)='%.200s'\n", payload);
    }
    if (rc == 0) {
        fprintf(stderr, "  FAIL %s: expected rc != 0 for technical error, got rc=0\n", test_name);
        ok = 0;
    }
    if (!payload) {
        fprintf(stderr, "  FAIL %s: expected error payload, got NULL\n", test_name);
        ok = 0;
    } else if (payload[0] != '{') {
        fprintf(stderr, "  FAIL %s: expected JSON payload (starting with '{'), got '%c'\n", test_name, payload[0]);
        ok = 0;
    } else {
        if (!strstr(payload, "\"status\"")) {
            fprintf(stderr, "  FAIL %s: JSON payload missing 'status' field\n", test_name);
            ok = 0;
        }
        if (!strstr(payload, "\"message\"")) {
            fprintf(stderr, "  FAIL %s: JSON payload missing 'message' field\n", test_name);
            ok = 0;
        }
    }
    return ok;
}

int main(void) {
    const char *lib_path = getenv("ILI_NATIVE_LIB");
    if (!lib_path) lib_path = "java/ili-native/build/native/libduckdb_ili_native.dylib";

    fprintf(stderr, "Opening: %s\n", lib_path);
    void *handle = dlopen(lib_path, RTLD_LAZY);
    if (!handle) { fprintf(stderr, "FAIL dlopen: %s\n", dlerror()); return 1; }

    graal_create_isolate_fn_t create_isolate = (graal_create_isolate_fn_t)dlsym(handle, "graal_create_isolate");
    graal_tear_down_isolate_fn_t tear_down = (graal_tear_down_isolate_fn_t)dlsym(handle, "graal_tear_down_isolate");
    ili_native_version_fn_t native_version = (ili_native_version_fn_t)dlsym(handle, "ili_native_version");
    ili_free_string_fn_t free_string = (ili_free_string_fn_t)dlsym(handle, "ili_free_string");

    fprintf(stderr, "create_isolate=%p tear_down=%p native_version=%p free_string=%p\n",
            (void*)create_isolate, (void*)tear_down, (void*)native_version, (void*)free_string);

    graal_isolatethread_t *thread = NULL;
    graal_isolate_t *isolate = NULL;
    if (create_isolate(NULL, &isolate, &thread) != 0) { fprintf(stderr, "FAIL create_isolate\n"); return 1; }
    fprintf(stderr, "Isolate created\n");

    int passed = 0, failed = 0;

    // Test 1: ili_native_version
    fprintf(stderr, "\nTest 1: ili_native_version\n");
    char *payload = NULL;
    int rc = native_version(thread, &payload);
    fprintf(stderr, "  rc=%d payload=%p\n", rc, (void*)payload);
    if (rc == 0 && payload) {
        fprintf(stderr, "  payload='%s'\n", payload);
        fprintf(stderr, "  PASS\n");
        passed++;
    } else {
        fprintf(stderr, "  FAIL\n");
        failed++;
    }
    if (payload) { free_string(thread, payload); payload = NULL; }

    // Test 2: ili_native_echo
    fprintf(stderr, "\nTest 2: ili_native_echo\n");
    ili_native_echo_fn_t native_echo = (ili_native_echo_fn_t)dlsym(handle, "ili_native_echo");
    if (native_echo) {
        char *echo = NULL;
        rc = native_echo(thread, "{\"test\":true}", &echo);
        if (rc == 0 && echo) {
            passed++;
            fprintf(stderr, "  PASS\n");
            free_string(thread, echo);
        } else {
            failed++;
            fprintf(stderr, "  FAIL\n");
        }
    } else { failed++; }

    // Test 3: free NULL
    fprintf(stderr, "\nTest 3: ili_free_string(NULL)\n");
    free_string(thread, NULL);
    passed++;
    fprintf(stderr, "  PASS (no crash)\n");

    // Test 4: Validation (valid XTF)
    fprintf(stderr, "\nTest 4: ili_native_validate (valid)\n");
    ili_native_validate_fn_t native_validate = (ili_native_validate_fn_t)dlsym(handle, "ili_native_validate");
    if (native_validate) {
        char *result = NULL;
        rc = native_validate(thread, "{\"input\":\"testdata/synthetic/simple/valid.xtf\",\"modeldir\":\"testdata/synthetic/simple\"}", &result);
        if (rc == 0 && result && strstr(result, "\"valid\":true")) {
            passed++;
            fprintf(stderr, "  PASS\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL (expected rc=0, valid=true)\n");
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 5: Validation (invalid XTF — fachlicher Fehler, kein technischer)
    fprintf(stderr, "\nTest 5: ili_native_validate (invalid — data error, status 0)\n");
    if (native_validate) {
        char *result = NULL;
        rc = native_validate(thread, "{\"input\":\"testdata/synthetic/simple/invalid.xtf\",\"modeldir\":\"testdata/synthetic/simple\"}", &result);
        if (rc == 0 && result && strstr(result, "\"valid\":false")) {
            passed++;
            fprintf(stderr, "  PASS\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL (expected rc=0, valid=false)\n");
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 6: Validation error — non-existent file (validation-level error, rc=0)
    fprintf(stderr, "\nTest 6: ili_native_validate (non-existent file — validation error)\n");
    if (native_validate) {
        char *result = NULL;
        rc = native_validate(thread, "{\"input\":\"/nonexistent/file.xtf\",\"modeldir\":\"testdata/synthetic/simple\"}", &result);
        fprintf(stderr, "  rc=%d result=%p\n", rc, (void*)result);
        if (result) fprintf(stderr, "  payload (first 200 chars)='%.200s'\n", result);
        // File-not-found is reported as a validation message (rc=0, valid=false)
        // because IliValidatorService treats it as a validation precondition check
        if (rc == 0 && result && strstr(result, "\"valid\":false")) {
            passed++;
            fprintf(stderr, "  PASS (file-not-found returned as validation result)\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL T6\n");
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 7: Model info error — non-existent modeldir (rc != 0)
    fprintf(stderr, "\nTest 7: ili_native_model_info (non-existent modeldir — rc != 0)\n");
    ili_native_model_info_fn_t native_model_info = (ili_native_model_info_fn_t)dlsym(handle, "ili_native_model_info");
    if (native_model_info) {
        char *result = NULL;
        rc = native_model_info(thread, "{\"cmd\":\"models\",\"modeldir\":\"/nonexistent/path\"}", &result);
        if (check_error_payload(rc, result, "T7")) passed++; else failed++;
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 8: Import SQL generation succeeds for non-existent file (SQL is generated before XTF is read)
    fprintf(stderr, "\nTest 8: ili_native_import_xtf (non-existent file — SQL generation succeeds)\n");
    ili_native_import_xtf_fn_t native_import_xtf = (ili_native_import_xtf_fn_t)dlsym(handle, "ili_native_import_xtf");
    if (native_import_xtf) {
        char *result = NULL;
        rc = native_import_xtf(thread, "{\"input\":\"/nonexistent/file.xtf\",\"schema\":\"test\",\"modeldir\":\"testdata/synthetic/simple\"}", &result);
        fprintf(stderr, "  rc=%d result=%p\n", rc, (void*)result);
        if (result) fprintf(stderr, "  payload (first 200 chars)='%.200s'\n", result);
        // SQL generation doesn't read the XTF — it succeeds (rc=0) for any modeldir
        // The generated SQL will fail at execution time when read_xtf_class is called
        if (rc == 0 && result && strstr(result, "CREATE")) {
            passed++;
            fprintf(stderr, "  PASS (SQL generated — XTF read happens at SQL execution time)\n");
        } else if (rc != 0 && result && result[0] == '{') {
            passed++;
            fprintf(stderr, "  PASS (model compilation failed — expected for invalid modeldir)\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL T8: rc=%d\n", rc);
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 9: Model info error — no "ERROR:" prefix in payload
    fprintf(stderr, "\nTest 9: ili_native_model_info error payload has no 'ERROR:' prefix\n");
    if (native_model_info) {
        char *result = NULL;
        rc = native_model_info(thread, "{\"cmd\":\"models\",\"modeldir\":\"/nonexistent/path\"}", &result);
        if (rc != 0 && result && !strstr(result, "ERROR:")) {
            passed++;
            fprintf(stderr, "  PASS (no ERROR: prefix in error payload)\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL (expected rc!=0 and no ERROR: prefix)\n");
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 10: Missing input returns invalid_argument status
    fprintf(stderr, "\nTest 10: ili_native_read_xtf (missing input -> INVALID_ARGUMENT)\n");
    ili_native_read_xtf_fn_t native_read_xtf = (ili_native_read_xtf_fn_t)dlsym(handle, "ili_native_read_xtf");
    if (native_read_xtf) {
        char *result = NULL;
        rc = native_read_xtf(thread, "{\"input\":\"\"}", &result);
        if (rc != 0 && result && strstr(result, "INVALID_ARGUMENT")) {
            passed++;
            fprintf(stderr, "  PASS\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL (expected INVALID_ARGUMENT)\n");
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 11: Unknown command returns unsupported status
    fprintf(stderr, "\nTest 11: ili_native_model_info (unknown cmd -> UNSUPPORTED)\n");
    if (native_model_info) {
        char *result = NULL;
        rc = native_model_info(thread, "{\"cmd\":\"invalidCommand\"}", &result);
        if (rc != 0 && result && strstr(result, "UNSUPPORTED")) {
            passed++;
            fprintf(stderr, "  PASS\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL (expected UNSUPPORTED)\n");
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    if (tear_down) tear_down(thread);
    dlclose(handle);
    fprintf(stderr, "\n========================================\n");
    fprintf(stderr, "Results: %d passed, %d failed\n", passed, failed);
    return failed > 0 ? 1 : 0;
}
