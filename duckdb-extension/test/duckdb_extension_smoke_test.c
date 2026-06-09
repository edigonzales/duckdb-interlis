#define DUCKDB_API_EXCLUDE_FUNCTIONS
#include "duckdb.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

#define TESTDATA_DIR "testdata/synthetic/simple"
#define TESTDATA_XTF  TESTDATA_DIR "/valid.xtf"
#define ASSOC_DIR     "testdata/synthetic/associations"
#define ASSOC_XTF     ASSOC_DIR "/valid.xtf"

typedef duckdb_state (*fn_open_ext_t)(const char *, duckdb_database *,
                                       duckdb_config, char **);
typedef duckdb_state (*fn_create_config_t)(duckdb_config *);
typedef duckdb_state (*fn_set_config_t)(duckdb_config, const char *, const char *);
typedef void (*fn_destroy_config_t)(duckdb_config *);
typedef void (*fn_free_t)(void *);
typedef void (*fn_close_t)(duckdb_database *);
typedef duckdb_state (*fn_connect_t)(duckdb_database, duckdb_connection *);
typedef void (*fn_disconnect_t)(duckdb_connection *);
typedef duckdb_state (*fn_query_t)(duckdb_connection, const char *, duckdb_result *);
typedef void (*fn_destroy_result_t)(duckdb_result *);
typedef const char *(*fn_result_error_t)(duckdb_result *);
typedef idx_t (*fn_row_count_t)(duckdb_result *);

static int passed = 0, failed = 0;

static void *lib_handle = NULL;
static fn_open_ext_t       p_open_ext;
static fn_close_t          p_close;
static fn_connect_t        p_connect;
static fn_disconnect_t     p_disconnect;
static fn_query_t          p_query;
static fn_destroy_result_t p_destroy_result;
static fn_result_error_t   p_result_error;
static fn_row_count_t      p_row_count;
static fn_create_config_t  p_create_config;
static fn_set_config_t     p_set_config;
static fn_destroy_config_t p_destroy_config;
static fn_free_t           p_free;

static int verify_query(duckdb_connection conn, const char *sql,
                         const char *label, int min_rows) {
    duckdb_result result;
    if (p_query(conn, sql, &result) != DuckDBSuccess) {
        const char *err = p_result_error(&result);
        fprintf(stderr, "  FAIL (%s): %s\n", label, err ? err : "unknown error");
        p_destroy_result(&result);
        failed++;
        return 0;
    }
    idx_t n = p_row_count(&result);
    if ((int)n < min_rows) {
        fprintf(stderr, "  FAIL (%s): %lld rows (expected >= %d)\n",
                label, (long long)n, min_rows);
        p_destroy_result(&result);
        failed++;
        return 0;
    }
    fprintf(stderr, "  PASS (%s): %lld rows\n", label, (long long)n);
    p_destroy_result(&result);
    passed++;
    return 1;
}

int main(void) {
    const char *lib_path = getenv("DUCKDB_LIB");
    if (!lib_path) lib_path = "libduckdb.dylib";

    const char *ext_path = getenv("INTERLIS_EXTENSION");
    if (!ext_path) ext_path = "duckdb-extension/build/interlis.duckdb_extension";

    fprintf(stderr, "=== DuckDB Extension Smoke Test ===\n");
    fprintf(stderr, "libduckdb:  %s\n", lib_path);
    fprintf(stderr, "extension:  %s\n", ext_path);

    lib_handle = dlopen(lib_path, RTLD_NOW);
    if (!lib_handle) {
        fprintf(stderr, "FATAL: dlopen(%s): %s\n", lib_path, dlerror());
        return 1;
    }

#define RESOLVE(fn, name) do { \
    fn = (typeof(fn))dlsym(lib_handle, name); \
    if (!fn) { fprintf(stderr, "FATAL: dlsym(%s): %s\n", name, dlerror()); dlclose(lib_handle); return 1; } \
} while(0)

    RESOLVE(p_open_ext,       "duckdb_open_ext");
    RESOLVE(p_close,           "duckdb_close");
    RESOLVE(p_connect,         "duckdb_connect");
    RESOLVE(p_disconnect,      "duckdb_disconnect");
    RESOLVE(p_query,           "duckdb_query");
    RESOLVE(p_destroy_result,  "duckdb_destroy_result");
    RESOLVE(p_result_error,    "duckdb_result_error");
    RESOLVE(p_row_count,       "duckdb_row_count");
    RESOLVE(p_create_config,   "duckdb_create_config");
    RESOLVE(p_set_config,      "duckdb_set_config");
    RESOLVE(p_destroy_config,  "duckdb_destroy_config");
    RESOLVE(p_free,            "duckdb_free");

#undef RESOLVE

    fprintf(stderr, "\n--- Open database ---\n");
    duckdb_config config = NULL;
    if (p_create_config(&config) != DuckDBSuccess) {
        fprintf(stderr, "FATAL: duckdb_create_config failed\n");
        dlclose(lib_handle);
        return 1;
    }
    p_set_config(config, "allow_unsigned_extensions", "true");

    duckdb_database db = NULL;
    char *open_err = NULL;
    if (p_open_ext(NULL, &db, config, &open_err) != DuckDBSuccess) {
        fprintf(stderr, "FATAL: duckdb_open_ext: %s\n", open_err ? open_err : "unknown");
        if (open_err) p_free(open_err);
        p_destroy_config(&config);
        dlclose(lib_handle);
        return 1;
    }
    p_destroy_config(&config);

    duckdb_connection conn = NULL;
    if (p_connect(db, &conn) != DuckDBSuccess) {
        fprintf(stderr, "FATAL: duckdb_connect failed\n");
        p_close(&db);
        dlclose(lib_handle);
        return 1;
    }

    fprintf(stderr, "\n--- Load extension ---\n");
    {
        char sql[4096];
        snprintf(sql, sizeof(sql), "LOAD '%s'", ext_path);
        duckdb_result r;
        if (p_query(conn, sql, &r) != DuckDBSuccess) {
            fprintf(stderr, "  FAIL (load): %s\n", p_result_error(&r));
            p_destroy_result(&r);
            failed++;
            p_disconnect(&conn);
            p_close(&db);
            dlclose(lib_handle);
            return 1;
        }
        fprintf(stderr, "  PASS: extension loaded\n");
        p_destroy_result(&r);
        passed++;
    }

    fprintf(stderr, "\n=== Extension version ===\n");
    {
        duckdb_result r;
        if (p_query(conn, "SELECT ili_extension_version()", &r) == DuckDBSuccess) {
            fprintf(stderr, "  PASS\n");
            passed++;
        } else {
            fprintf(stderr, "  FAIL: %s\n", p_result_error(&r));
            failed++;
        }
        p_destroy_result(&r);
    }

    fprintf(stderr, "\n=== Native version ===\n");
    {
        duckdb_result r;
        if (p_query(conn, "SELECT ili_native_version()", &r) == DuckDBSuccess) {
            fprintf(stderr, "  PASS\n");
            passed++;
        } else {
            fprintf(stderr, "  FAIL: %s\n", p_result_error(&r));
            failed++;
        }
        p_destroy_result(&r);
    }

    fprintf(stderr, "\n=== Table function: ili_models ===\n");
    verify_query(conn,
        "SELECT * FROM ili_models('" TESTDATA_DIR "')",
        "ili_models", 1);

    fprintf(stderr, "\n=== Table function: ili_topics ===\n");
    verify_query(conn,
        "SELECT * FROM ili_topics('" TESTDATA_DIR "')",
        "ili_topics", 1);

    fprintf(stderr, "\n=== Table function: ili_classes ===\n");
    verify_query(conn,
        "SELECT * FROM ili_classes('" TESTDATA_DIR "')",
        "ili_classes", 1);

    fprintf(stderr, "\n=== Table function: read_xtf_objects ===\n");
    verify_query(conn,
        "SELECT * FROM read_xtf_objects('" TESTDATA_XTF "', "
        "modeldir := '" TESTDATA_DIR "')",
        "read_xtf_objects", 1);

    fprintf(stderr, "\n=== Table function: read_xtf_class ===\n");
    verify_query(conn,
        "SELECT * FROM read_xtf_class('" TESTDATA_XTF "', "
        "class := 'SO_AGI_Simple_20260605.Topic.Gemeinde', "
        "modeldir := '" TESTDATA_DIR "')",
        "read_xtf_class", 1);

    fprintf(stderr, "\n=== Table function: read_xtf_association ===\n");
    verify_query(conn,
        "SELECT * FROM read_xtf_association('" ASSOC_XTF "', "
        "association := 'SO_AGI_Associations_20260605.Topic.Besitz', "
        "modeldir := '" ASSOC_DIR "')",
        "read_xtf_association", 1);

    fprintf(stderr, "\n=== Table function: ili_validate ===\n");
    verify_query(conn,
        "SELECT * FROM ili_validate('" TESTDATA_XTF "', "
        "modeldir := '" TESTDATA_DIR "', profile := 'full')",
        "ili_validate", 1);

    fprintf(stderr, "\n=== Table function: ili_generate_import_sql ===\n");
    verify_query(conn,
        "SELECT * FROM ili_generate_import_sql('" TESTDATA_XTF "', "
        "schema := 'smoke_test', modeldir := '" TESTDATA_DIR "')",
        "ili_generate_import_sql", 1);

    fprintf(stderr, "\n--- Disconnect and close ---\n");
    p_disconnect(&conn);
    p_close(&db);
    dlclose(lib_handle);

    fprintf(stderr, "\n========================================\n");
    fprintf(stderr, "Results: %d passed, %d failed\n", passed, failed);
    return failed > 0 ? 1 : 0;
}
