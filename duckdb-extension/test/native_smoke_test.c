#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include "ili_request.h"
#include "libduckdb_ili_native_dynamic.h"

static int check_error_payload(int rc, char *payload, const char *test_name) {
    int ok = 1;
    fprintf(stderr, "  rc=%d\n", rc);
    if (payload) fprintf(stderr, "  payload='%.200s'\n", payload);
    if (rc == 0) { fprintf(stderr, "  FAIL %s: expected rc != 0\n", test_name); ok = 0; }
    if (!payload) { fprintf(stderr, "  FAIL %s: expected error payload\n", test_name); ok = 0; }
    else if (payload[0] != '{') { fprintf(stderr, "  FAIL %s: expected JSON\n", test_name); ok = 0; }
    else {
        if (!strstr(payload, "\"status\"")) { fprintf(stderr, "  FAIL %s: JSON missing 'status'\n", test_name); ok = 0; }
        if (!strstr(payload, "\"message\"")) { fprintf(stderr, "  FAIL %s: JSON missing 'message'\n", test_name); ok = 0; }
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

    graal_create_isolate_fn_t create_isolate =
        (graal_create_isolate_fn_t)dlsym(handle, "graal_create_isolate");
    graal_tear_down_isolate_fn_t tear_down =
        (graal_tear_down_isolate_fn_t)dlsym(handle, "graal_tear_down_isolate");
    ili_free_string_fn_t free_string =
        (ili_free_string_fn_t)dlsym(handle, "ili_free_string");

    if (!create_isolate || !tear_down || !free_string) {
        fprintf(stderr, "FAIL: missing lifecycle symbols\n"); dlclose(handle); return 1;
    }

    int passed = 0, failed = 0;

    /* ===== ABI Handshake Tests ===== */
    typedef int (*ili_get_api_fn_t)(graal_isolatethread_t*, uint32_t, char**);
    ili_get_api_fn_t get_api = (ili_get_api_fn_t)dlsym(handle, "ili_get_api");

    fprintf(stderr, "\n=== ABI Tests ===\n");
    fprintf(stderr, "\nTest A1: Matching ABI\n");
    {
        graal_isolatethread_t *thread = NULL; graal_isolate_t *isolate = NULL;
        if (create_isolate(NULL, &isolate, &thread) != 0) { failed++; }
        else {
            char *p = NULL;
            int rc = get_api(thread, ILI_NATIVE_ABI_VERSION, &p);
            if (rc == 0 && p && strstr(p, "\"abi_version\":1")
                && strstr(p, "\"capabilities\":")) {
                uint64_t caps = 0;
                const char *c = strstr(p, "\"capabilities\":");
                if (c) caps = strtoull(c + 15, NULL, 10);
                if ((caps & ILI_CAP_REQUIRED_MASK) == ILI_CAP_REQUIRED_MASK) {
                    fprintf(stderr, "  PASS (caps=0x%llx)\n", (unsigned long long)caps); passed++;
                } else { fprintf(stderr, "  FAIL (missing required caps)\n"); failed++; }
            } else { fprintf(stderr, "  FAIL\n"); failed++; }
            if (p) free_string(thread, p);
            tear_down(thread);
        }
    }

    fprintf(stderr, "\nTest A2: ABI v0 rejected\n");
    {
        graal_isolatethread_t *thread = NULL; graal_isolate_t *isolate = NULL;
        if (create_isolate(NULL, &isolate, &thread) == 0) {
            char *p = NULL;
            int rc = get_api(thread, 0, &p);
            if (rc != 0) { fprintf(stderr, "  PASS\n"); passed++; }
            else { fprintf(stderr, "  FAIL\n"); failed++; }
            if (p) free_string(thread, p);
            tear_down(thread);
        } else { failed++; }
    }

    fprintf(stderr, "\nTest A3: ABI v99 rejected\n");
    {
        graal_isolatethread_t *thread = NULL; graal_isolate_t *isolate = NULL;
        if (create_isolate(NULL, &isolate, &thread) == 0) {
            char *p = NULL;
            int rc = get_api(thread, 99, &p);
            if (rc != 0) { fprintf(stderr, "  PASS\n"); passed++; }
            else { fprintf(stderr, "  FAIL\n"); failed++; }
            if (p) free_string(thread, p);
            tear_down(thread);
        } else { failed++; }
    }

    /* Create isolate for functional tests */
    graal_isolatethread_t *thread = NULL;
    graal_isolate_t *isolate = NULL;
    if (create_isolate(NULL, &isolate, &thread) != 0) { failed++; dlclose(handle); return 1; }

    /* Resolve API functions */
    ili_native_version_fn_t native_version = (ili_native_version_fn_t)dlsym(handle, "ili_native_version");
    ili_native_validate_fn_t native_validate = (ili_native_validate_fn_t)dlsym(handle, "ili_native_validate");
    ili_native_model_info_fn_t native_model_info = (ili_native_model_info_fn_t)dlsym(handle, "ili_native_model_info");

    fprintf(stderr, "\n=== Functional Tests ===\n");

    /* === Test 1: version === */
    fprintf(stderr, "\nTest 1: ili_native_version\n");
    if (native_version) {
        char *p = NULL;
        if (native_version(thread, &p) == 0 && p) { fprintf(stderr, "  PASS\n"); passed++; }
        else { fprintf(stderr, "  FAIL\n"); failed++; }
        if (p) free_string(thread, p);
    } else { failed++; }

    /* === Test 2: free_string(NULL) === */
    fprintf(stderr, "\nTest 2: free_string(NULL)\n");
    free_string(thread, NULL);
    fprintf(stderr, "  PASS (no crash)\n"); passed++;

    /* === Test 3: validate valid XTF === */
    fprintf(stderr, "\nTest 3: validate (valid)\n");
    if (native_validate) {
        ili_request req; init_request(&req);
        req.input = "testdata/synthetic/simple/valid.xtf";
        req.modeldir = "testdata/synthetic/simple";
        char *r = NULL;
        if (native_validate(thread, &req, &r) == 0 && r && strstr(r, "\"valid\":true")) {
            fprintf(stderr, "  PASS\n"); passed++;
        } else { fprintf(stderr, "  FAIL\n"); failed++; }
        if (r) free_string(thread, r);
    } else { failed++; }

    /* === Test 4: validate invalid XTF === */
    fprintf(stderr, "\nTest 4: validate (invalid)\n");
    if (native_validate) {
        ili_request req; init_request(&req);
        req.input = "testdata/synthetic/simple/invalid.xtf";
        req.modeldir = "testdata/synthetic/simple";
        char *r = NULL;
        if (native_validate(thread, &req, &r) == 0 && r && strstr(r, "\"valid\":false")) {
            fprintf(stderr, "  PASS\n"); passed++;
        } else { fprintf(stderr, "  FAIL\n"); failed++; }
        if (r) free_string(thread, r);
    } else { failed++; }

    /* === Test 5: missing modeldir error === */
    fprintf(stderr, "\nTest 5: model_info (missing modeldir)\n");
    if (native_model_info) {
        ili_request req; init_request(&req);
        req.cmd = "models";
        req.modeldir = "/nonexistent/path";
        char *r = NULL;
        int rc = native_model_info(thread, &req, &r);
        if (check_error_payload(rc, r, "T5")) passed++; else failed++;
        if (r) free_string(thread, r);
    } else { failed++; }

    /* === Test 6: missing input returns INVALID_ARGUMENT === */
    fprintf(stderr, "\nTest 6: read_xtf (missing input)\n");
    {
        ili_native_read_xtf_fn_t read_xtf = (ili_native_read_xtf_fn_t)dlsym(handle, "ili_native_read_xtf");
        if (read_xtf) {
            ili_request req; init_request(&req);
            req.input = "";
            char *r = NULL;
            int rc = read_xtf(thread, &req, &r);
            if (rc != 0 && r && strstr(r, "INVALID_ARGUMENT")) {
                fprintf(stderr, "  PASS\n"); passed++;
            } else { fprintf(stderr, "  FAIL\n"); failed++; }
            if (r) free_string(thread, r);
        } else { failed++; }
    }

    /* === Test 7: unknown cmd returns UNSUPPORTED === */
    fprintf(stderr, "\nTest 7: model_info (unknown cmd)\n");
    if (native_model_info) {
        ili_request req; init_request(&req);
        req.cmd = "invalidCommand";
        char *r = NULL;
        int rc = native_model_info(thread, &req, &r);
        if (rc != 0 && r && strstr(r, "UNSUPPORTED")) {
            fprintf(stderr, "  PASS\n"); passed++;
        } else { fprintf(stderr, "  FAIL\n"); failed++; }
        if (r) free_string(thread, r);
    } else { failed++; }

    /* ===== PHASE 12: Ownership Tests ===== */
    fprintf(stderr, "\n=== Ownership Tests ===\n");

    /* O1: allocate/free cycle x10 */
    fprintf(stderr, "\nTest O1: allocate/free cycle x10\n");
    {
        int ok = 1;
        for (int i = 0; i < 10; i++) {
            char *p = NULL;
            if (native_version(thread, &p) != 0 || !p) { ok = 0; fprintf(stderr, "  FAIL round %d\n", i); break; }
            free_string(thread, p);
        }
        if (ok) { fprintf(stderr, "  PASS\n"); passed++; }
        else failed++;
    }

    /* O2: free does not corrupt subsequent allocations */
    fprintf(stderr, "\nTest O2: free then re-allocate\n");
    {
        char *p1 = NULL;
        if (native_version(thread, &p1) == 0 && p1) {
            free_string(thread, p1);
            char *p2 = NULL;
            if (native_version(thread, &p2) == 0 && p2) {
                free_string(thread, p2);
                fprintf(stderr, "  PASS\n"); passed++;
            } else { fprintf(stderr, "  FAIL re-alloc\n"); failed++; }
        } else { fprintf(stderr, "  FAIL first alloc\n"); failed++; }
    }

    /* O3: allocate many and free all */
    fprintf(stderr, "\nTest O3: 100 alloc/free pairs\n");
    {
        int ok = 1;
        for (int i = 0; i < 100; i++) {
            char *p = NULL;
            if (native_version(thread, &p) != 0) { ok = 0; break; }
            free_string(thread, p);
        }
        if (ok) { fprintf(stderr, "  PASS\n"); passed++; }
        else failed++;
    }

    /* ===== PHASE 12: Length Tests ===== */
    fprintf(stderr, "\n=== Length Tests ===\n");

    /* L1: struct_size=0 rejected */
    fprintf(stderr, "\nTest L1: struct_size=0\n");
    {
        ili_request req; memset(&req, 0, sizeof(req)); req.struct_size = 0;
        char *r = NULL;
        int rc = native_validate(thread, &req, &r);
        if (rc != 0) { fprintf(stderr, "  PASS\n"); passed++; }
        else { fprintf(stderr, "  FAIL\n"); failed++; }
        if (r) free_string(thread, r);
    }

    /* L2: struct_size too small rejected */
    fprintf(stderr, "\nTest L2: struct_size=4\n");
    {
        ili_request req; memset(&req, 0, sizeof(req)); req.struct_size = 4;
        char *r = NULL;
        int rc = native_validate(thread, &req, &r);
        if (rc != 0) { fprintf(stderr, "  PASS\n"); passed++; }
        else { fprintf(stderr, "  FAIL\n"); failed++; }
        if (r) free_string(thread, r);
    }

    /* L3: max_messages=0 */
    fprintf(stderr, "\nTest L3: max_messages=0\n");
    {
        ili_request req; init_request(&req);
        req.input = "testdata/synthetic/simple/invalid.xtf";
        req.modeldir = "testdata/synthetic/simple";
        req.max_messages = 0;
        char *r = NULL;
        int rc = native_validate(thread, &req, &r);
        if (rc == 0 && r) { fprintf(stderr, "  PASS\n"); passed++; }
        else { fprintf(stderr, "  FAIL\n"); failed++; }
        if (r) free_string(thread, r);
    }

    /* L4: max_messages=500 (large) */
    fprintf(stderr, "\nTest L4: max_messages=500\n");
    {
        ili_request req; init_request(&req);
        req.input = "testdata/synthetic/simple/invalid.xtf";
        req.modeldir = "testdata/synthetic/simple";
        req.max_messages = 500;
        char *r = NULL;
        int rc = native_validate(thread, &req, &r);
        if (rc == 0 && r) { fprintf(stderr, "  PASS\n"); passed++; }
        else { fprintf(stderr, "  FAIL\n"); failed++; }
        if (r) free_string(thread, r);
    }

    /* ===== PHASE 12: Unicode Tests ===== */
    fprintf(stderr, "\n=== Unicode Tests ===\n");

    /* U1: version contains valid JSON/UTF-8 */
    fprintf(stderr, "\nTest U1: version returns valid JSON\n");
    {
        char *p = NULL;
        if (native_version(thread, &p) == 0 && p && p[0] == '{' && strstr(p, "native_version")) {
            fprintf(stderr, "  PASS\n"); passed++;
        } else { fprintf(stderr, "  FAIL\n"); failed++; }
        if (p) free_string(thread, p);
    }

    /* U2: echo round-trip with special chars */
    fprintf(stderr, "\nTest U2: echo with umlauts and special chars\n");
    {
        typedef int (*ili_native_echo_fn_t)(graal_isolatethread_t*, const char*, char**);
        ili_native_echo_fn_t native_echo = (ili_native_echo_fn_t)dlsym(handle, "ili_native_echo");
        if (native_echo) {
            const char *input = "{\"message\":\"H\u00f6he \u00fc.M. \u00e4\u00f6\u00fc \u00c4\u00d6\u00dc\"}";
            char *r = NULL;
            int rc = native_echo(thread, input, &r);
            if (rc == 0 && r && strstr(r, "\u00e4")) {
                fprintf(stderr, "  PASS\n"); passed++;
            } else { fprintf(stderr, "  FAIL (rc=%d)\n", rc); failed++; }
            if (r) free_string(thread, r);
        } else { fprintf(stderr, "  SKIP (echo not available)\n"); }
    }

    /* U3: error handler handles Unicode paths */
    fprintf(stderr, "\nTest U3: error with Unicode in message\n");
    if (native_model_info) {
        ili_request req; init_request(&req);
        req.cmd = "models";
        req.modeldir = "/tmp/dir_\u00e4\u00f6\u00fc/test";
        char *r = NULL;
        int rc = native_model_info(thread, &req, &r);
        if (rc != 0 && r && r[0] == '{') {
            fprintf(stderr, "  PASS (error returned correctly)\n"); passed++;
        } else { fprintf(stderr, "  FAIL\n"); failed++; }
        if (r) free_string(thread, r);
    } else { failed++; }

    /* Cleanup */
    tear_down(thread);
    dlclose(handle);

    fprintf(stderr, "\n========================================\n");
    fprintf(stderr, "Results: %d passed, %d failed\n", passed, failed);
    return failed > 0 ? 1 : 0;
}
