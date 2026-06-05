#include "duckdb_extension.h"
#include "graal_isolate_dynamic.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdbool.h>
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
typedef int (*native_validate_tsv_fn)(graal_isolatethread_t*, char*, char**);
typedef int (*native_model_info_fn)(graal_isolatethread_t*, char*, char**);
typedef int (*native_read_xtf_fn)(graal_isolatethread_t*, char*, char**);
typedef void (*native_free_fn)(graal_isolatethread_t*, char*);

static native_version_fn g_native_version = NULL;
static native_validate_fn g_native_validate = NULL;
static native_validate_tsv_fn g_native_validate_tsv = NULL;
static native_model_info_fn g_native_model_info = NULL;
static native_read_xtf_fn g_native_read_xtf = NULL;
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
    g_native_validate_tsv = (native_validate_tsv_fn)dlsym(g_native_handle, "ili_native_validate_tsv");
    g_native_model_info = (native_model_info_fn)dlsym(g_native_handle, "ili_native_model_info");
    g_native_read_xtf = (native_read_xtf_fn)dlsym(g_native_handle, "ili_native_read_xtf");
    g_native_free = (native_free_fn)dlsym(g_native_handle, "ili_free_string");

    if (!g_native_version || !g_native_validate || !g_native_validate_tsv || !g_native_model_info || !g_native_read_xtf || !g_native_free) {
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

        // Extract C strings from DuckDB VARCHAR (may not be null-terminated)
        duckdb_string_t path_str = path_data[row];
        duckdb_string_t modeldir_str = modeldir_data[row];
        idx_t path_len = duckdb_string_t_length(path_str);
        idx_t modeldir_len = duckdb_string_t_length(modeldir_str);

        // Build JSON request with length-limited strings
        char request[8192];
        snprintf(request, sizeof(request),
            "{\"input\":\"%.*s\",\"modeldir\":\"%.*s\"}",
            (int)path_len, duckdb_string_t_data(&path_str),
            (int)modeldir_len, duckdb_string_t_data(&modeldir_str));

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
// Table function: ili_validate
// ---------------------------------------------------------------------------

#define ILI_VALIDATE_COLS 13

typedef struct {
    char *input;
    char *modeldir;
} ili_validate_bind_data;

typedef struct {
    char *severity;
    char *code;
    char *message;
    char *filename;
    int line;
    int column;
    char *xtf_tid;
    char *xtf_bid;
    char *model;
    char *topic;
    char *class_name;
    char *attribute_name;
    char *raw;
} ili_validate_row;

typedef struct {
    ili_validate_row *rows;
    idx_t row_count;
    idx_t current_row;
    int error_count;
    int warning_count;
    int info_count;
} ili_validate_init_data;

static void bind_data_destroy(void *data) {
    ili_validate_bind_data *bd = (ili_validate_bind_data *)data;
    if (bd) {
        free(bd->input);
        free(bd->modeldir);
        free(bd);
    }
}

static void init_data_destroy(void *data) {
    ili_validate_init_data *id = (ili_validate_init_data *)data;
    if (id) {
        for (idx_t i = 0; i < id->row_count; i++) {
            free(id->rows[i].severity);
            free(id->rows[i].code);
            free(id->rows[i].message);
            free(id->rows[i].filename);
            free(id->rows[i].xtf_tid);
            free(id->rows[i].xtf_bid);
            free(id->rows[i].model);
            free(id->rows[i].topic);
            free(id->rows[i].class_name);
            free(id->rows[i].attribute_name);
            free(id->rows[i].raw);
        }
        free(id->rows);
        free(id);
    }
}

static char *parse_tsv_field(const char **p) {
    if (!*p || !**p) return NULL;
    const char *start = *p;
    size_t len = 0;
    while (**p && **p != '\t' && **p != '\n') { (*p)++; len++; }
    char *result = malloc(len + 1);
    if (!result) return NULL;
    const char *s = start;
    char *d = result;
    while (s < *p) {
        if (*s == '\\' && (s + 1) < *p) {
            switch (s[1]) {
                case 't': *d++ = '\t'; s += 2; break;
                case 'n': *d++ = '\n'; s += 2; break;
                case 'r': *d++ = '\r'; s += 2; break;
                case '\\': *d++ = '\\'; s += 2; break;
                default: *d++ = *s++; break;
            }
        } else {
            *d++ = *s++;
        }
    }
    *d = '\0';
    if (**p == '\t') (*p)++;
    return result;
}

static int parse_tsv_int(const char **p) {
    int val = 0;
    if (**p == '\t' || **p == '\n') { (*p)++; return 0; }
    bool neg = false;
    if (**p == '-') { neg = true; (*p)++; }
    while (**p >= '0' && **p <= '9') { val = val * 10 + (**p - '0'); (*p)++; }
    if (**p == '\t') (*p)++;
    if (**p == '\n') (*p)++;
    return neg ? -val : val;
}

static void ili_validate_bind(duckdb_bind_info info) {
    duckdb_logical_type varchar_type = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
    duckdb_logical_type int_type = duckdb_create_logical_type(DUCKDB_TYPE_INTEGER);

    duckdb_bind_add_result_column(info, "severity", varchar_type);
    duckdb_bind_add_result_column(info, "code", varchar_type);
    duckdb_bind_add_result_column(info, "message", varchar_type);
    duckdb_bind_add_result_column(info, "filename", varchar_type);
    duckdb_bind_add_result_column(info, "line", int_type);
    duckdb_bind_add_result_column(info, "column", int_type);
    duckdb_bind_add_result_column(info, "xtf_tid", varchar_type);
    duckdb_bind_add_result_column(info, "xtf_bid", varchar_type);
    duckdb_bind_add_result_column(info, "model", varchar_type);
    duckdb_bind_add_result_column(info, "topic", varchar_type);
    duckdb_bind_add_result_column(info, "class_name", varchar_type);
    duckdb_bind_add_result_column(info, "attribute_name", varchar_type);
    duckdb_bind_add_result_column(info, "raw", varchar_type);

    // Extract parameter values
    duckdb_value input_val = duckdb_bind_get_parameter(info, 0);
    char *input_str = input_val ? duckdb_get_varchar(input_val) : NULL;

    duckdb_value modeldir_val = duckdb_bind_get_named_parameter(info, "modeldir");
    char *modeldir_str = modeldir_val ? duckdb_get_varchar(modeldir_val) : NULL;

    // Store in bind data
    ili_validate_bind_data *bd = malloc(sizeof(ili_validate_bind_data));
    bd->input = input_str ? strdup(input_str) : NULL;
    bd->modeldir = modeldir_str ? strdup(modeldir_str) : NULL;
    duckdb_bind_set_bind_data(info, bd, bind_data_destroy);

    if (input_val) duckdb_destroy_value(&input_val);
    if (modeldir_val) duckdb_destroy_value(&modeldir_val);
}

static void ili_validate_init(duckdb_init_info info) {
    if (!g_initialized && !init_native_library()) {
        duckdb_init_set_error(info, g_error_buf);
        return;
    }

    ili_validate_bind_data *bd = (ili_validate_bind_data *)duckdb_init_get_bind_data(info);
    if (!bd || !bd->input || !bd->modeldir) {
        duckdb_init_set_error(info, "Missing input path or modeldir");
        return;
    }

    // Build JSON request and call native validation
    char request[8192];
    snprintf(request, sizeof(request),
        "{\"input\":\"%s\",\"modeldir\":\"%s\"}", bd->input, bd->modeldir);

    fprintf(stderr, "Calling validate_tsv...\n");

    char *tsv_result = native_call_with_input_str(g_native_validate_tsv, request);
    if (!tsv_result) {
        duckdb_init_set_error(info, "Validation call failed");
        return;
    }

    // Parse TSV result
    ili_validate_init_data *id = malloc(sizeof(ili_validate_init_data));
    memset(id, 0, sizeof(*id));

    // Parse header line: errorCount\twarningCount\tinfoCount\n
    const char *p = tsv_result;
    id->error_count = parse_tsv_int(&p);
    id->warning_count = parse_tsv_int(&p);
    id->info_count = parse_tsv_int(&p);
    if (*p == '\n') p++;

    // Count rows first
    const char *tmp = p;
    id->row_count = 0;
    while (*tmp) {
        id->row_count++;
        while (*tmp && *tmp != '\n') tmp++;
        if (*tmp == '\n') tmp++;
    }

    // Allocate and parse rows
    id->rows = malloc(id->row_count * sizeof(ili_validate_row));
    memset(id->rows, 0, id->row_count * sizeof(ili_validate_row));

    for (idx_t i = 0; i < id->row_count; i++) {
        ili_validate_row *row = &id->rows[i];
        row->severity = parse_tsv_field(&p);        // 0
        row->code = parse_tsv_field(&p);             // 1
        row->message = parse_tsv_field(&p);          // 2
        row->filename = parse_tsv_field(&p);         // 3
        row->line = parse_tsv_int(&p);              // 4
        row->column = parse_tsv_int(&p);            // 5
        row->xtf_tid = parse_tsv_field(&p);          // 6
        row->xtf_bid = parse_tsv_field(&p);          // 7
        row->model = parse_tsv_field(&p);            // 8
        row->topic = parse_tsv_field(&p);            // 9
        row->class_name = parse_tsv_field(&p);       // 10
        row->attribute_name = parse_tsv_field(&p);   // 11
        row->raw = parse_tsv_field(&p);              // 12
        if (*p == '\n') p++;
    }

    native_free_str(tsv_result);
    duckdb_init_set_init_data(info, id, init_data_destroy);
}

static void ili_validate_function(duckdb_function_info tfinfo, duckdb_data_chunk output) {
    ili_validate_init_data *id = (ili_validate_init_data *)duckdb_function_get_init_data(tfinfo);
    if (!id || id->row_count == 0 || id->current_row >= id->row_count) {
        duckdb_data_chunk_set_size(output, 0);
        return;
    }

    // Use a fixed chunk size of 1024 (standard DuckDB vector size)
    idx_t count = id->row_count - id->current_row;
    if (count > 1024) count = 1024;

    if (count == 0) {
        duckdb_data_chunk_set_size(output, 0);
        return;
    }

    // Get output vectors
    duckdb_vector severity_vec = duckdb_data_chunk_get_vector(output, 0);
    duckdb_vector code_vec = duckdb_data_chunk_get_vector(output, 1);
    duckdb_vector message_vec = duckdb_data_chunk_get_vector(output, 2);
    duckdb_vector filename_vec = duckdb_data_chunk_get_vector(output, 3);
    duckdb_vector line_vec = duckdb_data_chunk_get_vector(output, 4);
    duckdb_vector column_vec = duckdb_data_chunk_get_vector(output, 5);
    duckdb_vector xtf_tid_vec = duckdb_data_chunk_get_vector(output, 6);
    duckdb_vector xtf_bid_vec = duckdb_data_chunk_get_vector(output, 7);
    duckdb_vector model_vec = duckdb_data_chunk_get_vector(output, 8);
    duckdb_vector topic_vec = duckdb_data_chunk_get_vector(output, 9);
    duckdb_vector class_name_vec = duckdb_data_chunk_get_vector(output, 10);
    duckdb_vector attribute_name_vec = duckdb_data_chunk_get_vector(output, 11);
    duckdb_vector raw_vec = duckdb_data_chunk_get_vector(output, 12);

    for (idx_t i = 0; i < count; i++) {
        ili_validate_row *row = &id->rows[id->current_row + i];

        duckdb_vector_assign_string_element(severity_vec, i, row->severity ? row->severity : "");
        duckdb_vector_assign_string_element(code_vec, i, row->code ? row->code : "");
        duckdb_vector_assign_string_element(message_vec, i, row->message ? row->message : "");
        duckdb_vector_assign_string_element(filename_vec, i, row->filename ? row->filename : "");
        ((int32_t *)duckdb_vector_get_data(line_vec))[i] = row->line;
        ((int32_t *)duckdb_vector_get_data(column_vec))[i] = row->column;
        duckdb_vector_assign_string_element(xtf_tid_vec, i, row->xtf_tid ? row->xtf_tid : "");
        duckdb_vector_assign_string_element(xtf_bid_vec, i, row->xtf_bid ? row->xtf_bid : "");
        duckdb_vector_assign_string_element(model_vec, i, row->model ? row->model : "");
        duckdb_vector_assign_string_element(topic_vec, i, row->topic ? row->topic : "");
        duckdb_vector_assign_string_element(class_name_vec, i, row->class_name ? row->class_name : "");
        duckdb_vector_assign_string_element(attribute_name_vec, i, row->attribute_name ? row->attribute_name : "");
        duckdb_vector_assign_string_element(raw_vec, i, row->raw ? row->raw : "");
    }

    duckdb_data_chunk_set_size(output, count);
    id->current_row += count;
}

static void register_table_functions(duckdb_connection connection) {
    duckdb_table_function fn = duckdb_create_table_function();
    duckdb_table_function_set_name(fn, "ili_validate");

    duckdb_logical_type varchar_type = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
    duckdb_table_function_add_parameter(fn, varchar_type);
    duckdb_table_function_add_named_parameter(fn, "modeldir", varchar_type);

    duckdb_table_function_set_bind(fn, ili_validate_bind);
    duckdb_table_function_set_init(fn, ili_validate_init);
    duckdb_table_function_set_function(fn, ili_validate_function);

    duckdb_register_table_function(connection, fn);
    duckdb_destroy_table_function(&fn);
    duckdb_destroy_logical_type(&varchar_type);
}

// ---------------------------------------------------------------------------
// Generic model info table function
// ---------------------------------------------------------------------------

typedef struct {
    char *cmd;         // "models", "topics", "classes", "attributes", "enums"
    char *modeldir;
    char *model;
    char *class;
} mi_bind_data;

typedef struct {
    char **rows;
    idx_t row_count;
    idx_t current_row;
    int col_count;
} mi_init_data;

static void mi_bind_destroy(void *d) {
    mi_bind_data *bd = (mi_bind_data *)d;
    if (bd) { free(bd->cmd); free(bd->modeldir); free(bd->model); free(bd->class); free(bd); }
}

static void mi_init_destroy(void *d) {
    mi_init_data *id = (mi_init_data *)d;
    if (id) {
        for (idx_t i = 0; i < id->row_count; i++) free(id->rows[i]);
        free(id->rows); free(id);
    }
}

static void mi_bind(duckdb_bind_info info, const char *cmd, int ncols,
                     const char **colnames) {
    duckdb_logical_type vt = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
    for (int i = 0; i < ncols; i++)
        duckdb_bind_add_result_column(info, colnames[i], vt);

    duckdb_value dv = duckdb_bind_get_parameter(info, 0);
    char *modeldir = dv ? duckdb_get_varchar(dv) : NULL;

    dv = duckdb_bind_get_named_parameter(info, "model");
    char *model = dv ? duckdb_get_varchar(dv) : NULL;

    dv = duckdb_bind_get_named_parameter(info, "class");
    char *cls = dv ? duckdb_get_varchar(dv) : NULL;

    mi_bind_data *bd = malloc(sizeof(mi_bind_data));
    bd->cmd = strdup(cmd);
    bd->modeldir = modeldir ? strdup(modeldir) : NULL;
    bd->model = model ? strdup(model) : NULL;
    bd->class = cls ? strdup(cls) : NULL;
    duckdb_bind_set_bind_data(info, bd, mi_bind_destroy);

    if (dv) duckdb_destroy_value(&dv);
}

static void mi_init(duckdb_init_info info) {
    if (!g_initialized && !init_native_library()) {
        duckdb_init_set_error(info, g_error_buf); return;
    }
    mi_bind_data *bd = (mi_bind_data *)duckdb_init_get_bind_data(info);
    if (!bd || !bd->modeldir) {
        duckdb_init_set_error(info, "Missing modeldir"); return;
    }

    char req[8192];
    snprintf(req, sizeof(req), "{\"cmd\":\"%s\",\"modeldir\":\"%s\"%s%s%s%s}",
        bd->cmd, bd->modeldir,
        bd->model ? ",\"model\":\"" : "", bd->model ? bd->model : "",
        bd->class ? ",\"class\":\"" : "", bd->class ? bd->class : "");

    char *result = native_call_with_input_str(g_native_model_info, req);
    if (!result) { duckdb_init_set_error(info, "Model info call failed"); return; }

    mi_init_data *id = malloc(sizeof(mi_init_data));
    memset(id, 0, sizeof(*id));

    // Count rows
    const char *p = result;
    while (*p) { id->row_count++; while (*p && *p != '\n') p++; if (*p == '\n') p++; }

    id->rows = malloc(id->row_count * sizeof(char*));
    p = result;
    for (idx_t i = 0; i < id->row_count; i++) {
        const char *start = p; size_t len = 0;
        while (*p && *p != '\n') { p++; len++; }
        id->rows[i] = malloc(len + 1);
        memcpy(id->rows[i], start, len);
        id->rows[i][len] = '\0';
        if (*p == '\n') p++;
    }
    native_free_str(result);
    duckdb_init_set_init_data(info, id, mi_init_destroy);
}

static void mi_function(duckdb_function_info tfinfo, duckdb_data_chunk output) {
    mi_init_data *id = (mi_init_data *)duckdb_function_get_init_data(tfinfo);
    if (!id || id->current_row >= id->row_count) {
        duckdb_data_chunk_set_size(output, 0); return;
    }
    idx_t count = id->row_count - id->current_row;
    if (count > 1024) count = 1024;

    idx_t col_count = duckdb_data_chunk_get_column_count(output);

    for (idx_t i = 0; i < count; i++) {
        char *row = id->rows[id->current_row + i];
        char *r = row;
        for (idx_t c = 0; c < col_count; c++) {
            char *tab = strchr(r, '\t');
            idx_t len = tab ? (idx_t)(tab - r) : strlen(r);
            duckdb_vector vec = duckdb_data_chunk_get_vector(output, c);
            duckdb_vector_assign_string_element_len(vec, i, r, len);
            r = tab ? tab + 1 : r + len;
        }
    }
    duckdb_data_chunk_set_size(output, count);
    id->current_row += count;
}

// Individual bind callbacks
static void models_bind(duckdb_bind_info info) {
    static const char *cols[] = {"name","version","issuer","language","ili_version"};
    mi_bind(info, "models", 5, cols);
}
static void topics_bind(duckdb_bind_info info) {
    static const char *cols[] = {"model_name","topic_name","kind"};
    mi_bind(info, "topics", 3, cols);
}
static void classes_bind(duckdb_bind_info info) {
    static const char *cols[] = {"model_name","topic_name","class_name","kind","is_abstract","is_extended","base_class"};
    mi_bind(info, "classes", 7, cols);
}
static void attrs_bind(duckdb_bind_info info) {
    static const char *cols[] = {"model_name","topic_name","class_name","attr_name","type_name","kind","is_mandatory","card_min","card_max"};
    mi_bind(info, "attributes", 9, cols);
}
static void enums_bind(duckdb_bind_info info) {
    static const char *cols[] = {"model_name","topic_name","enum_name","element","element_line"};
    mi_bind(info, "enumerations", 5, cols);
}

static void register_model_table_functions(duckdb_connection conn) {
    struct { const char *name; duckdb_table_function_bind_t bind; } fns[] = {
        {"ili_models", models_bind},
        {"ili_topics", topics_bind},
        {"ili_classes", classes_bind},
        {"ili_attributes", attrs_bind},
        {"ili_enumerations", enums_bind},
    };
    duckdb_logical_type vt = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
    for (int i = 0; i < 5; i++) {
        duckdb_table_function fn = duckdb_create_table_function();
        duckdb_table_function_set_name(fn, fns[i].name);
        duckdb_table_function_add_parameter(fn, vt);
        duckdb_table_function_add_named_parameter(fn, "model", vt);
        duckdb_table_function_add_named_parameter(fn, "class", vt);
        duckdb_table_function_set_bind(fn, fns[i].bind);
        duckdb_table_function_set_init(fn, mi_init);
        duckdb_table_function_set_function(fn, mi_function);
        duckdb_register_table_function(conn, fn);
        duckdb_destroy_table_function(&fn);
    }
    duckdb_destroy_logical_type(&vt);
}

// ---------------------------------------------------------------------------
// read_xtf_objects table function
// ---------------------------------------------------------------------------

static void xtf_objects_bind(duckdb_bind_info info) {
    static const char *cols[] = {
        "xtf_bid","xtf_topic","xtf_class","xtf_tid",
        "operation","attributes_json","refs_json","geom_json","raw_event_json"
    };
    duckdb_logical_type vt = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
    for (int i = 0; i < 9; i++)
        duckdb_bind_add_result_column(info, cols[i], vt);
    duckdb_destroy_logical_type(&vt);

    // Get input path (positional) and modeldir (named)
    duckdb_value dv = duckdb_bind_get_parameter(info, 0);
    char *input = dv ? duckdb_get_varchar(dv) : NULL;

    dv = duckdb_bind_get_named_parameter(info, "modeldir");
    char *modeldir = dv ? duckdb_get_varchar(dv) : NULL;

    mi_bind_data *bd = malloc(sizeof(mi_bind_data));
    bd->cmd = strdup("xtf");
    bd->modeldir = modeldir ? strdup(modeldir) : NULL;
    bd->model = input ? strdup(input) : NULL; // hijack 'model' field for input path
    bd->class = NULL;
    duckdb_bind_set_bind_data(info, bd, mi_bind_destroy);
    if (dv) duckdb_destroy_value(&dv);
}

static void xtf_objects_init(duckdb_init_info info) {
    if (!g_initialized && !init_native_library()) {
        duckdb_init_set_error(info, g_error_buf); return;
    }
    mi_bind_data *bd = (mi_bind_data *)duckdb_init_get_bind_data(info);
    if (!bd || !bd->model || !bd->modeldir) {
        duckdb_init_set_error(info, "Missing input path or modeldir"); return;
    }

    char req[8192];
    snprintf(req, sizeof(req), "{\"input\":\"%s\",\"modeldir\":\"%s\"}",
        bd->model, bd->modeldir);

    char *result = native_call_with_input_str(g_native_read_xtf, req);
    if (!result) { duckdb_init_set_error(info, "XTF read call failed"); return; }

    mi_init_data *id = malloc(sizeof(mi_init_data));
    memset(id, 0, sizeof(*id));

    const char *p = result;
    while (*p) { id->row_count++; while (*p && *p != '\n') p++; if (*p == '\n') p++; }
    id->rows = malloc(id->row_count * sizeof(char*));
    p = result;
    for (idx_t i = 0; i < id->row_count; i++) {
        const char *start = p; size_t len = 0;
        while (*p && *p != '\n') { p++; len++; }
        id->rows[i] = malloc(len + 1);
        memcpy(id->rows[i], start, len);
        id->rows[i][len] = '\0';
        if (*p == '\n') p++;
    }
    native_free_str(result);
    duckdb_init_set_init_data(info, id, mi_init_destroy);
}

// ---------------------------------------------------------------------------
// Extension entry point
// ---------------------------------------------------------------------------
DUCKDB_EXTENSION_ENTRYPOINT(duckdb_connection connection, duckdb_extension_info info, struct duckdb_extension_access *access) {
    register_functions(connection);
    register_table_functions(connection);
    register_model_table_functions(connection);

    // read_xtf_objects
    {
        duckdb_table_function fn = duckdb_create_table_function();
        duckdb_table_function_set_name(fn, "read_xtf_objects");
        duckdb_logical_type vt = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
        duckdb_table_function_add_parameter(fn, vt);
        duckdb_table_function_add_named_parameter(fn, "modeldir", vt);
        duckdb_table_function_set_bind(fn, xtf_objects_bind);
        duckdb_table_function_set_init(fn, xtf_objects_init);
        duckdb_table_function_set_function(fn, mi_function);
        duckdb_register_table_function(connection, fn);
        duckdb_destroy_table_function(&fn);
        duckdb_destroy_logical_type(&vt);
    }

    return true;
}
