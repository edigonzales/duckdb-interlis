#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include "libduckdb_ili_native_dynamic.h"

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

    // Test 1: ili_native_version
    fprintf(stderr, "\nTest 1: ili_native_version\n");
    char *payload = NULL;
    int rc = native_version(thread, &payload);
    fprintf(stderr, "  rc=%d payload=%p\n", rc, (void*)payload);
    if (rc == 0 && payload) {
        fprintf(stderr, "  payload='%s'\n", payload);
        fprintf(stderr, "  PASS\n");
    } else {
        fprintf(stderr, "  FAIL\n");
    }

    // Free payload from test 1
    if (payload) {
        fprintf(stderr, "  freeing payload...\n");
        free_string(thread, payload);
        fprintf(stderr, "  freed\n");
    }

    // Test 2: ili_native_echo
    fprintf(stderr, "\nTest 2: ili_native_echo\n");
    ili_native_echo_fn_t native_echo = (ili_native_echo_fn_t)dlsym(handle, "ili_native_echo");
    fprintf(stderr, "  native_echo=%p\n", (void*)native_echo);
    if (native_echo) {
        char *echo = NULL;
        rc = native_echo(thread, "{\"test\":true}", &echo);
        fprintf(stderr, "  rc=%d echo=%p\n", rc, (void*)echo);
        if (rc == 0 && echo) {
            fprintf(stderr, "  echo='%s'\n", echo);
            fprintf(stderr, "  PASS\n");
            free_string(thread, echo);
        } else {
            fprintf(stderr, "  FAIL\n");
        }
    }

    // Test 3: free NULL
    fprintf(stderr, "\nTest 3: ili_free_string(NULL)\n");
    free_string(thread, NULL);
    fprintf(stderr, "  PASS (no crash)\n");

    // Test 4: Validation
    fprintf(stderr, "\nTest 4: ili_native_validate (valid)\n");
    ili_native_validate_fn_t native_validate = (ili_native_validate_fn_t)dlsym(handle, "ili_native_validate");
    fprintf(stderr, "  native_validate=%p\n", (void*)native_validate);
    if (native_validate) {
        char *result = NULL;
        rc = native_validate(thread, "{\"input\":\"testdata/synthetic/simple/valid.xtf\",\"modeldir\":\"testdata/synthetic/simple\"}", &result);
        fprintf(stderr, "  rc=%d result=%p\n", rc, (void*)result);
        if (rc == 0 && result) {
            fprintf(stderr, "  result=%s\n", result);
            if (strstr(result, "\"valid\":true")) {
                fprintf(stderr, "  PASS\n");
            } else {
                fprintf(stderr, "  FAIL (expected valid=true)\n");
            }
            free_string(thread, result);
        } else {
            fprintf(stderr, "  FAIL\n");
        }
    }

    // Test 5: Validation (invalid)
    fprintf(stderr, "\nTest 5: ili_native_validate (invalid)\n");
    if (native_validate) {
        char *result = NULL;
        rc = native_validate(thread, "{\"input\":\"testdata/synthetic/simple/invalid.xtf\",\"modeldir\":\"testdata/synthetic/simple\"}", &result);
        fprintf(stderr, "  rc=%d result=%p\n", rc, (void*)result);
        if (rc == 0 && result) {
            fprintf(stderr, "  result=%s\n", result);
            if (strstr(result, "\"valid\":false")) {
                fprintf(stderr, "  PASS\n");
            } else {
                fprintf(stderr, "  FAIL (expected valid=false)\n");
            }
            free_string(thread, result);
        } else {
            fprintf(stderr, "  FAIL\n");
        }
    }

    // Test 6: Validation error path — non-existent file
    fprintf(stderr, "\nTest 6: ili_native_validate (non-existent file)\n");
    if (native_validate) {
        char *result = NULL;
        rc = native_validate(thread, "{\"input\":\"/nonexistent/file.xtf\",\"modeldir\":\".\"}", &result);
        fprintf(stderr, "  rc=%d result=%p\n", rc, (void*)result);
        // Note: currently returns rc=0 with validation result containing error message.
        // Phase 2 will change this to rc != 0 for technical errors.
        if (result) {
            fprintf(stderr, "  payload='%s'\n", result);
            fprintf(stderr, "  PASS (payload returned and freed)\n");
            free_string(thread, result);
            fprintf(stderr, "  freed error payload\n");
        } else {
            fprintf(stderr, "  FAIL (no payload)\n");
        }
    }

    // Test 7: Model info error path — non-existent modeldir
    fprintf(stderr, "\nTest 7: ili_native_model_info (non-existent modeldir)\n");
    ili_native_model_info_fn_t native_model_info = (ili_native_model_info_fn_t)dlsym(handle, "ili_native_model_info");
    fprintf(stderr, "  native_model_info=%p\n", (void*)native_model_info);
    if (native_model_info) {
        char *result = NULL;
        rc = native_model_info(thread, "{\"cmd\":\"models\",\"modeldir\":\"/nonexistent/path\"}", &result);
        fprintf(stderr, "  rc=%d result=%p\n", rc, (void*)result);
        if (result) {
            fprintf(stderr, "  payload='%s'\n", result);
            free_string(thread, result);
            fprintf(stderr, "  freed payload\n");
        }
        fprintf(stderr, "  PASS (no crash, payload freed)\n");
    }

    // Test 8: Import error path — non-existent file
    fprintf(stderr, "\nTest 8: ili_native_import_xtf (non-existent file)\n");
    ili_native_import_xtf_fn_t native_import_xtf = (ili_native_import_xtf_fn_t)dlsym(handle, "ili_native_import_xtf");
    fprintf(stderr, "  native_import_xtf=%p\n", (void*)native_import_xtf);
    if (native_import_xtf) {
        char *result = NULL;
        rc = native_import_xtf(thread, "{\"input\":\"/nonexistent/file.xtf\",\"schema\":\"test\",\"modeldir\":\".\"}", &result);
        fprintf(stderr, "  rc=%d result=%p\n", rc, (void*)result);
        // Note: currently returns rc=0 with \"ERROR: ...\" payload.
        // Phase 2 will change this to rc != 0 for technical errors.
        if (result) {
            fprintf(stderr, "  payload='%s'\n", result);
            fprintf(stderr, "  PASS (payload returned and freed)\n");
            free_string(thread, result);
            fprintf(stderr, "  freed error payload\n");
        } else {
            fprintf(stderr, "  FAIL (no payload)\n");
        }
    }

    if (tear_down) tear_down(thread);
    dlclose(handle);
    printf("\nDone.\n");
    return 0;
}
