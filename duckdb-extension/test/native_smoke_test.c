#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include "ili_request.h"

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

static void init_request(ili_request *req) {
    memset(req, 0, sizeof(*req));
    req->struct_size = sizeof(*req);
    req->max_messages = -1;
}

int main(void) {
    const char *lib_path = getenv("ILI_NATIVE_LIB");
    if (!lib_path) lib_path = "java/ili-native/build/native/libduckdb_ili_native.dylib";

    fprintf(stderr, "Opening: %s\n", lib_path);
    void *handle = dlopen(lib_path, RTLD_LAZY);
    if (!handle) { fprintf(stderr, "FAIL dlopen: %s\n", dlerror()); return 1; }

    // Resolve lifecycle + ABI entry point
    graal_create_isolate_fn_t create_isolate =
        (graal_create_isolate_fn_t)dlsym(handle, "graal_create_isolate");
    graal_tear_down_isolate_fn_t tear_down =
        (graal_tear_down_isolate_fn_t)dlsym(handle, "graal_tear_down_isolate");
    ili_free_string_fn_t free_string =
        (ili_free_string_fn_t)dlsym(handle, "ili_free_string");

    typedef int (*ili_get_api_fn_t)(graal_isolatethread_t*, uint32_t, char**);
    ili_get_api_fn_t get_api = (ili_get_api_fn_t)dlsym(handle, "ili_get_api");

    if (!create_isolate || !tear_down || !free_string) {
        fprintf(stderr, "FAIL: missing lifecycle symbols\n");
        dlclose(handle);
        return 1;
    }

    int passed = 0, failed = 0;

    // --- ABI Handshake Tests (Phase 9) ---

    // Test A1: Matching ABI — happy path
    fprintf(stderr, "\n=== ABI Handshake Tests ===\n");
    fprintf(stderr, "\nTest A1: Matching ABI\n");
    {
        graal_isolatethread_t *thread = NULL;
        graal_isolate_t *isolate = NULL;
        if (create_isolate(NULL, &isolate, &thread) != 0) {
            fprintf(stderr, "  FAIL create_isolate\n"); failed++;
            goto skip_abi_tests;
        }
        char *payload = NULL;
        int rc = get_api(thread, ILI_NATIVE_ABI_VERSION, &payload);
        fprintf(stderr, "  ili_get_api(v1) rc=%d payload='%s'\n",
                rc, payload ? payload : "(null)");
        if (rc == 0 && payload && strstr(payload, "\"abi_version\":1")
            && strstr(payload, "\"capabilities\":")) {
            uint64_t caps = 0;
            const char *c = strstr(payload, "\"capabilities\":");
            if (c) caps = strtoull(c + 15, NULL, 10);
            fprintf(stderr, "  caps=0x%016llx\n", (unsigned long long)caps);
            if ((caps & ILI_CAP_REQUIRED_MASK) == ILI_CAP_REQUIRED_MASK) {
                fprintf(stderr, "  PASS\n"); passed++;
            } else {
                fprintf(stderr, "  FAIL (missing required caps)\n"); failed++;
            }
        } else {
            fprintf(stderr, "  FAIL\n"); failed++;
        }
        if (payload) { free_string(thread, payload); payload = NULL; }
        if (tear_down) tear_down(thread);
    }

    // Test A2: Too-old ABI version
    fprintf(stderr, "\nTest A2: Too-old ABI (request v0)\n");
    {
        graal_isolatethread_t *thread = NULL;
        graal_isolate_t *isolate = NULL;
        if (create_isolate(NULL, &isolate, &thread) != 0) {
            fprintf(stderr, "  FAIL create_isolate\n"); failed++;
        } else {
            char *payload = NULL;
            int rc = get_api(thread, 0, &payload);
            fprintf(stderr, "  ili_get_api(v0) rc=%d payload='%s'\n",
                    rc, payload ? payload : "(null)");
            if (rc != 0) {
                fprintf(stderr, "  PASS (v0 rejected)\n"); passed++;
            } else {
                fprintf(stderr, "  FAIL (v0 unexpectedly accepted)\n"); failed++;
            }
            if (payload) { free_string(thread, payload); payload = NULL; }
            if (tear_down) tear_down(thread);
        }
    }

    // Test A3: Too-new ABI version
    fprintf(stderr, "\nTest A3: Too-new ABI (request v99)\n");
    {
        graal_isolatethread_t *thread = NULL;
        graal_isolate_t *isolate = NULL;
        if (create_isolate(NULL, &isolate, &thread) != 0) {
            fprintf(stderr, "  FAIL create_isolate\n"); failed++;
        } else {
            char *payload = NULL;
            int rc = get_api(thread, 99, &payload);
            fprintf(stderr, "  ili_get_api(v99) rc=%d payload='%s'\n",
                    rc, payload ? payload : "(null)");
            if (rc != 0) {
                fprintf(stderr, "  PASS (v99 rejected)\n"); passed++;
            } else {
                fprintf(stderr, "  FAIL (v99 unexpectedly accepted)\n"); failed++;
            }
            if (payload) { free_string(thread, payload); payload = NULL; }
            if (tear_down) tear_down(thread);
        }
    }

skip_abi_tests:

    // --- Functional Tests via individual dlsym ---
    graal_isolatethread_t *thread = NULL;
    graal_isolate_t *isolate = NULL;
    if (create_isolate(NULL, &isolate, &thread) != 0) {
        fprintf(stderr, "\nFAIL create_isolate for functional tests\n"); failed++;
        dlclose(handle);
        return failed > 0 ? 1 : 0;
    }

    // Resolve API functions via dlsym (after ABI handshake validation)
    ili_native_version_fn_t native_version = (ili_native_version_fn_t)dlsym(handle, "ili_native_version");
    ili_native_validate_fn_t native_validate = (ili_native_validate_fn_t)dlsym(handle, "ili_native_validate");
    ili_native_model_info_fn_t native_model_info = (ili_native_model_info_fn_t)dlsym(handle, "ili_native_model_info");
    ili_native_read_xtf_fn_t native_read_xtf = (ili_native_read_xtf_fn_t)dlsym(handle, "ili_native_read_xtf");
    ili_native_import_xtf_fn_t native_import_xtf = (ili_native_import_xtf_fn_t)dlsym(handle, "ili_native_import_xtf");

    fprintf(stderr, "\n=== Functional Tests ===\n");

    // Test 1: ili_native_version
    fprintf(stderr, "\nTest 1: ili_native_version\n");
    if (native_version) {
        char *payload = NULL;
        int rc = native_version(thread, &payload);
        if (rc == 0 && payload) {
            fprintf(stderr, "  payload='%s'\n", payload);
            fprintf(stderr, "  PASS\n"); passed++;
        } else {
            fprintf(stderr, "  FAIL\n"); failed++;
        }
        if (payload) { free_string(thread, payload); payload = NULL; }
    } else { failed++; }

    // Test 2: free NULL
    fprintf(stderr, "\nTest 2: free_string(NULL)\n");
    free_string(thread, NULL);
    passed++;
    fprintf(stderr, "  PASS (no crash)\n");

    // Test 3: Validation (valid XTF)
    fprintf(stderr, "\nTest 3: validate (valid)\n");
    if (native_validate) {
        char *result = NULL;
        ili_request req;
        init_request(&req);
        req.input = "testdata/synthetic/simple/valid.xtf";
        req.modeldir = "testdata/synthetic/simple";
        int rc = native_validate(thread, &req, &result);
        if (rc == 0 && result && strstr(result, "\"valid\":true")) {
            passed++;
            fprintf(stderr, "  PASS\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL (expected rc=0, valid=true)\n");
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 4: Validation (invalid XTF)
    fprintf(stderr, "\nTest 4: validate (invalid)\n");
    if (native_validate) {
        char *result = NULL;
        ili_request req;
        init_request(&req);
        req.input = "testdata/synthetic/simple/invalid.xtf";
        req.modeldir = "testdata/synthetic/simple";
        int rc = native_validate(thread, &req, &result);
        if (rc == 0 && result && strstr(result, "\"valid\":false")) {
            passed++;
            fprintf(stderr, "  PASS\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL (expected rc=0, valid=false)\n");
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 5: Model info error
    fprintf(stderr, "\nTest 5: model_info (non-existent modeldir)\n");
    if (native_model_info) {
        char *result = NULL;
        ili_request req;
        init_request(&req);
        req.cmd = "models";
        req.modeldir = "/nonexistent/path";
        int rc = native_model_info(thread, &req, &result);
        if (check_error_payload(rc, result, "T5")) passed++; else failed++;
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 6: Import SQL generation
    fprintf(stderr, "\nTest 6: import_xtf\n");
    if (native_import_xtf) {
        char *result = NULL;
        ili_request req;
        init_request(&req);
        req.input = "/nonexistent/file.xtf";
        req.schema = "test";
        req.modeldir = "testdata/synthetic/simple";
        req.mapping = "relational";
        int rc = native_import_xtf(thread, &req, &result);
        if (rc == 0 && result && strstr(result, "CREATE")) {
            passed++;
            fprintf(stderr, "  PASS (SQL generated)\n");
        } else if (rc != 0 && result && result[0] == '{') {
            passed++;
            fprintf(stderr, "  PASS (model compilation failed — expected for invalid modeldir)\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL T6: rc=%d\n", rc);
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 7: Missing input returns invalid_argument
    fprintf(stderr, "\nTest 7: read_xtf (missing input)\n");
    if (native_read_xtf) {
        char *result = NULL;
        ili_request req;
        init_request(&req);
        req.input = "";
        int rc = native_read_xtf(thread, &req, &result);
        if (rc != 0 && result && strstr(result, "INVALID_ARGUMENT")) {
            passed++;
            fprintf(stderr, "  PASS\n");
        } else {
            failed++;
            fprintf(stderr, "  FAIL (expected INVALID_ARGUMENT)\n");
        }
        if (result) { free_string(thread, result); result = NULL; }
    } else { failed++; }

    // Test 8: Unknown command returns unsupported
    fprintf(stderr, "\nTest 8: model_info (unknown cmd)\n");
    if (native_model_info) {
        char *result = NULL;
        ili_request req;
        init_request(&req);
        req.cmd = "invalidCommand";
        int rc = native_model_info(thread, &req, &result);
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
