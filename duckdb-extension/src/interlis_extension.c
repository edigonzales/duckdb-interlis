#include "duckdb_extension.h"
#include "graal_isolate_dynamic.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <dlfcn.h>

DUCKDB_EXTENSION_EXTERN

static const char *EXT_VERSION = "0.1.0-dev";

// --- Native library state ---
static void *g_native_handle = NULL;
static graal_isolate_t *g_isolate = NULL;

// GraalVM lifecycle
static graal_create_isolate_fn_t g_create_isolate = NULL;
static graal_attach_thread_fn_t g_attach_thread = NULL;
static graal_detach_thread_fn_t g_detach_thread = NULL;
static graal_tear_down_isolate_fn_t g_tear_down = NULL;

// Native API functions
typedef int (*native_version_fn)(graal_isolatethread_t*, char**);
typedef int (*native_validate_fn)(graal_isolatethread_t*, char*, char**);
typedef void (*native_free_fn)(graal_isolatethread_t*, char*);

static native_version_fn g_native_version = NULL;
static native_validate_fn g_native_validate = NULL;
static native_free_fn g_native_free = NULL;

static bool g_initialized = false;
static char g_error_buf[512];

// ---------------------------------------------------------------------------
// Library path resolution
// ---------------------------------------------------------------------------
static const char *resolve_native_lib_path(void) {
    const char *env = getenv("DUCKDB_ILI_NATIVE_LIB");
    if (env && env[0]) return env;

    // Try next to the extension
    static const char *fallbacks[] = {
        "build/native/current/libduckdb_ili_native.dylib",
        "../java/ili-native/build/native/libduckdb_ili_native.dylib",
        "../../java/ili-native/build/native/libduckdb_ili_native.dylib",
        NULL
    };
    for (int i = 0; fallbacks[i]; i++) {
        if (access(fallbacks[i], F_OK) == 0) return fallbacks[i];
    }
    return NULL;
}

// ---------------------------------------------------------------------------
// Initialization
// ---------------------------------------------------------------------------
static bool init_native_library(void) {
    if (g_initialized) return true;

    const char *lib_path = resolve_native_lib_path();
    if (!lib_path) {
        snprintf(g_error_buf, sizeof(g_error_buf),
            "Native library not found. Set DUCKDB_ILI_NATIVE_LIB or run scripts/build-native.sh");
        return false;
    }

    g_native_handle = dlopen(lib_path, RTLD_LAZY);
    if (!g_native_handle) {
        snprintf(g_error_buf, sizeof(g_error_buf),
            "Failed to load native library '%s': %s", lib_path, dlerror());
        return false;
    }

    // Resolve lifecycle functions
    g_create_isolate = (graal_create_isolate_fn_t)dlsym(g_native_handle, "graal_create_isolate");
    g_attach_thread = (graal_attach_thread_fn_t)dlsym(g_native_handle, "graal_attach_thread");
    g_detach_thread = (graal_detach_thread_fn_t)dlsym(g_native_handle, "graal_detach_thread");
    g_tear_down = (graal_tear_down_isolate_fn_t)dlsym(g_native_handle, "graal_tear_down_isolate");

    if (!g_create_isolate || !g_attach_thread || !g_detach_thread || !g_tear_down) {
        snprintf(g_error_buf, sizeof(g_error_buf),
            "Failed to resolve GraalVM lifecycle symbols in '%s'", lib_path);
        dlclose(g_native_handle);
        g_native_handle = NULL;
        return false;
    }

    // Resolve API functions
    g_native_version = (native_version_fn)dlsym(g_native_handle, "ili_native_version");
    g_native_validate = (native_validate_fn)dlsym(g_native_handle, "ili_native_validate");
    g_native_free = (native_free_fn)dlsym(g_native_handle, "ili_free_string");

    if (!g_native_version || !g_native_validate || !g_native_free) {
        snprintf(g_error_buf, sizeof(g_error_buf),
            "Failed to resolve ILI API symbols in '%s'", lib_path);
        dlclose(g_native_handle);
        g_native_handle = NULL;
        return false;
    }

    // Create isolate
    graal_isolatethread_t *init_thread = NULL;
    int rc = g_create_isolate(NULL, &g_isolate, &init_thread);
    if (rc != 0 || !g_isolate) {
        snprintf(g_error_buf, sizeof(g_error_buf),
            "Failed to create GraalVM isolate (code=%d)", rc);
        dlclose(g_native_handle);
        g_native_handle = NULL;
        return false;
    }

    // Detach the init thread since DuckDB will attach its own threads
    g_detach_thread(init_thread);

    g_initialized = true;
    return true;
}

static void shutdown_native_library(void) {
    if (!g_initialized) return;

    graal_isolatethread_t *thread = NULL;
    if (g_attach_thread(g_isolate, &thread) == 0 && thread) {
        g_tear_down(thread);
    }
    if (g_native_handle) {
        dlclose(g_native_handle);
        g_native_handle = NULL;
    }
    g_isolate = NULL;
    g_initialized = false;
}

// ---------------------------------------------------------------------------
// Thread-safe native call helper
// ---------------------------------------------------------------------------
static char *call_native_str(graal_isolatethread_t *thread,
                              int (*fn)(graal_isolatethread_t *, char **)) {
    char *result = NULL;
    int rc = fn(thread, &result);
    if (rc != 0 || !result) {
        return NULL;
    }
    return result; // caller must free via g_native_free
}

static char *call_native_with_input(graal_isolatethread_t *thread,
                                     int (*fn)(graal_isolatethread_t *, char *, char **),
                                     const char *input) {
    char *result = NULL;
    int rc = fn(thread, (char *)input, &result);
    if (rc != 0 || !result) {
        return NULL;
    }
    return result;
}

// Helper: attach thread, call fn, detach thread
static char *native_call_str(int (*fn)(graal_isolatethread_t *, char **)) {
    if (!g_initialized || !g_isolate) return NULL;

    graal_isolatethread_t *thread = NULL;
    if (g_attach_thread(g_isolate, &thread) != 0 || !thread) return NULL;

    char *result = call_native_str(thread, fn);
    g_detach_thread(thread);
    return result;
}

static char *native_call_with_input_str(
        int (*fn)(graal_isolatethread_t *, char *, char **), const char *input) {
    if (!g_initialized || !g_isolate) return NULL;

    graal_isolatethread_t *thread = NULL;
    if (g_attach_thread(g_isolate, &thread) != 0 || !thread) return NULL;

    char *result = call_native_with_input(thread, fn, input);
    g_detach_thread(thread);
    return result;
}

static void native_free_str(char *str) {
    if (!str || !g_isolate || !g_native_free) return;

    graal_isolatethread_t *thread = NULL;
    if (g_attach_thread(g_isolate, &thread) != 0 || !thread) return;
    g_native_free(thread, str);
    g_detach_thread(thread);
}

// ---------------------------------------------------------------------------
// SQL function: ili_extension_version()
// ---------------------------------------------------------------------------
static void ili_extension_version_fn(duckdb_function_info info, duckdb_data_chunk input, duckdb_vector output) {
    idx_t size = duckdb_data_chunk_get_size(input);
    duckdb_vector_ensure_validity_writable(output);
    uint64_t *validity = duckdb_vector_get_validity(output);

    for (idx_t row = 0; row < size; row++) {
        duckdb_validity_set_row_valid(validity, row);
        duckdb_vector_assign_string_element(output, row, EXT_VERSION);
    }
}

// ---------------------------------------------------------------------------
// SQL function: ili_native_version()
// ---------------------------------------------------------------------------
static void ili_native_version_fn_cb(duckdb_function_info info, duckdb_data_chunk input, duckdb_vector output) {
    idx_t size = duckdb_data_chunk_get_size(input);
    duckdb_vector_ensure_validity_writable(output);
    uint64_t *validity = duckdb_vector_get_validity(output);

    for (idx_t row = 0; row < size; row++) {
        if (!g_initialized && !init_native_library()) {
            duckdb_validity_set_row_invalid(validity, row);
            duckdb_scalar_function_set_error(info, g_error_buf);
            return;
        }

        char *result = native_call_str(g_native_version);
        if (result) {
            duckdb_validity_set_row_valid(validity, row);
            duckdb_vector_assign_string_element(output, row, result);
            native_free_str(result);
        } else {
            duckdb_validity_set_row_invalid(validity, row);
            duckdb_scalar_function_set_error(info, "ili_native_version failed");
            return;
        }
    }
}

// ---------------------------------------------------------------------------
// SQL function: ili_validate_summary_json(path, modeldir)
// ---------------------------------------------------------------------------
static void ili_validate_summary_json_fn(duckdb_function_info info, duckdb_data_chunk input, duckdb_vector output) {
    idx_t size = duckdb_data_chunk_get_size(input);
    duckdb_vector_ensure_validity_writable(output);
    uint64_t *validity = duckdb_vector_get_validity(output);

    duckdb_vector path_vec = duckdb_data_chunk_get_vector(input, 0);
    duckdb_vector modeldir_vec = duckdb_data_chunk_get_vector(input, 1);

    uint64_t *path_validity = duckdb_vector_get_validity(path_vec);
    uint64_t *modeldir_validity = duckdb_vector_get_validity(modeldir_vec);
    duckdb_string_t *path_data = (duckdb_string_t *)duckdb_vector_get_data(path_vec);
    duckdb_string_t *modeldir_data = (duckdb_string_t *)duckdb_vector_get_data(modeldir_vec);

    for (idx_t row = 0; row < size; row++) {
        if (!duckdb_validity_row_is_valid(path_validity, row) ||
            !duckdb_validity_row_is_valid(modeldir_validity, row)) {
            duckdb_validity_set_row_invalid(validity, row);
            duckdb_scalar_function_set_error(info, "Path and modeldir must not be NULL");
            return;
        }

        if (!g_initialized && !init_native_library()) {
            duckdb_validity_set_row_invalid(validity, row);
            duckdb_scalar_function_set_error(info, g_error_buf);
            return;
        }

        // Extract C strings from DuckDB VARCHAR
        const char *path_str = duckdb_string_t_data(&path_data[row]);
        const char *modeldir_str = duckdb_string_t_data(&modeldir_data[row]);

        // Build JSON request
        char request[4096];
        snprintf(request, sizeof(request),
            "{\"input\":\"%s\",\"modeldir\":\"%s\"}", path_str, modeldir_str);

        char *result = native_call_with_input_str(g_native_validate, request);
        if (result) {
            duckdb_validity_set_row_valid(validity, row);
            duckdb_vector_assign_string_element(output, row, result);
            native_free_str(result);
        } else {
            duckdb_validity_set_row_invalid(validity, row);
            duckdb_scalar_function_set_error(info, "Validation call failed");
            return;
        }
    }
}

// ---------------------------------------------------------------------------
// Registration
// ---------------------------------------------------------------------------
static void register_functions(duckdb_connection connection) {
    // ili_extension_version() -> VARCHAR
    {
        duckdb_scalar_function fn = duckdb_create_scalar_function();
        duckdb_scalar_function_set_name(fn, "ili_extension_version");
        duckdb_logical_type rt = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
        duckdb_scalar_function_set_return_type(fn, rt);
        duckdb_destroy_logical_type(&rt);
        duckdb_scalar_function_set_function(fn, ili_extension_version_fn);
        duckdb_register_scalar_function(connection, fn);
        duckdb_destroy_scalar_function(&fn);
    }

    // ili_native_version() -> VARCHAR
    {
        duckdb_scalar_function fn = duckdb_create_scalar_function();
        duckdb_scalar_function_set_name(fn, "ili_native_version");
        duckdb_logical_type rt = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
        duckdb_scalar_function_set_return_type(fn, rt);
        duckdb_destroy_logical_type(&rt);
        duckdb_scalar_function_set_function(fn, ili_native_version_fn_cb);
        duckdb_register_scalar_function(connection, fn);
        duckdb_destroy_scalar_function(&fn);
    }

    // ili_validate_summary_json(path VARCHAR, modeldir VARCHAR) -> VARCHAR
    {
        duckdb_scalar_function fn = duckdb_create_scalar_function();
        duckdb_scalar_function_set_name(fn, "ili_validate_summary_json");

        duckdb_logical_type param_type = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
        duckdb_scalar_function_add_parameter(fn, param_type);
        duckdb_scalar_function_add_parameter(fn, param_type);

        duckdb_logical_type rt = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
        duckdb_scalar_function_set_return_type(fn, rt);

        duckdb_destroy_logical_type(&param_type);
        duckdb_destroy_logical_type(&rt);

        duckdb_scalar_function_set_function(fn, ili_validate_summary_json_fn);
        duckdb_register_scalar_function(connection, fn);
        duckdb_destroy_scalar_function(&fn);
    }
}

// ---------------------------------------------------------------------------
// Extension entry point
// ---------------------------------------------------------------------------
DUCKDB_EXTENSION_ENTRYPOINT(duckdb_connection connection, duckdb_extension_info info, struct duckdb_extension_access *access) {
    register_functions(connection);
    return true;
}
