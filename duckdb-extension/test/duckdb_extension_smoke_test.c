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

/* ------------------------------------------------------------------ */
/* DuckDB C API function pointer typedefs                             */
/* ------------------------------------------------------------------ */

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
typedef idx_t (*fn_column_count_t)(duckdb_result *);
typedef const char *(*fn_column_name_t)(duckdb_result *, idx_t);
typedef duckdb_type (*fn_column_type_t)(duckdb_result *, idx_t);
typedef char *(*fn_value_varchar_t)(duckdb_result *, idx_t, idx_t);
typedef int32_t (*fn_value_int32_t)(duckdb_result *, idx_t, idx_t);
typedef bool (*fn_value_is_null_t)(duckdb_result *, idx_t, idx_t);

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
static fn_column_count_t   p_column_count;
static fn_column_name_t    p_column_name;
static fn_column_type_t    p_column_type;
static fn_value_varchar_t  p_value_varchar;
static fn_value_int32_t    p_value_int32;
static fn_value_is_null_t  p_value_is_null;
static fn_create_config_t  p_create_config;
static fn_set_config_t     p_set_config;
static fn_destroy_config_t p_destroy_config;
static fn_free_t           p_free;

/* ------------------------------------------------------------------ */
/* helper: verify a query returns >= min_rows                          */
/* ------------------------------------------------------------------ */

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

/* ------------------------------------------------------------------ */
/* helper: verify cell values in first row                            */
/* ------------------------------------------------------------------ */

static int verify_cell_varchar(duckdb_result *res, idx_t col, idx_t row,
                                const char *expected, const char *label) {
    char *val = p_value_varchar(res, col, row);
    int ok = val && strcmp(val, expected) == 0;
    if (!ok) {
        fprintf(stderr, "  FAIL (%s col %lld): expected '%s', got '%s'\n",
                label, (long long)col, expected, val ? val : "(null)");
        failed++;
    } else {
        fprintf(stderr, "  PASS (%s col %lld): '%s'\n",
                label, (long long)col, expected);
        passed++;
    }
    if (val) p_free(val);
    return ok;
}

static int verify_cell_int32(duckdb_result *res, idx_t col, idx_t row,
                              int32_t expected, const char *label) {
    int32_t val = p_value_int32(res, col, row);
    int ok = (val == expected);
    if (!ok) {
        fprintf(stderr, "  FAIL (%s col %lld): expected %d, got %d\n",
                label, (long long)col, expected, val);
        failed++;
    } else {
        fprintf(stderr, "  PASS (%s col %lld): %d\n",
                label, (long long)col, expected);
        passed++;
    }
    return ok;
}

static int verify_cell_is_null(duckdb_result *res, idx_t col, idx_t row,
                                const char *label) {
    int is_null = p_value_is_null(res, col, row);
    if (!is_null) {
        fprintf(stderr, "  FAIL (%s col %lld): expected NULL, got non-NULL\n",
                label, (long long)col);
        failed++;
        return 0;
    }
    fprintf(stderr, "  PASS (%s col %lld): NULL\n", label, (long long)col);
    passed++;
    return 1;
}

static int verify_column_name(duckdb_result *res, idx_t col,
                               const char *expected, const char *label) {
    const char *name = p_column_name(res, col);
    int ok = name && strcmp(name, expected) == 0;
    if (!ok) {
        fprintf(stderr, "  FAIL (%s col %lld name): expected '%s', got '%s'\n",
                label, (long long)col, expected, name ? name : "(null)");
        failed++;
    } else {
        fprintf(stderr, "  PASS (%s col %lld name): '%s'\n",
                label, (long long)col, expected);
        passed++;
    }
    return ok;
}

/* ------------------------------------------------------------------ */
/* helper: verify a query returns an error (for error-path tests)     */
/* ------------------------------------------------------------------ */

static int verify_query_error(duckdb_connection conn, const char *sql,
                               const char *label) {
    duckdb_result result;
    duckdb_state st = p_query(conn, sql, &result);
    if (st == DuckDBSuccess) {
        fprintf(stderr, "  FAIL (%s): expected error, got success\n", label);
        p_destroy_result(&result);
        failed++;
        return 0;
    }
    fprintf(stderr, "  PASS (%s): error as expected\n", label);
    p_destroy_result(&result);
    passed++;
    return 1;
}

/* ------------------------------------------------------------------ */
/* main                                                                */
/* ------------------------------------------------------------------ */

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
    *(void**)&(fn) = dlsym(lib_handle, name); \
    if (!(fn)) { fprintf(stderr, "FATAL: dlsym(%s): %s\n", name, dlerror()); dlclose(lib_handle); return 1; } \
} while(0)

    RESOLVE(p_open_ext,       "duckdb_open_ext");
    RESOLVE(p_close,           "duckdb_close");
    RESOLVE(p_connect,         "duckdb_connect");
    RESOLVE(p_disconnect,      "duckdb_disconnect");
    RESOLVE(p_query,           "duckdb_query");
    RESOLVE(p_destroy_result,  "duckdb_destroy_result");
    RESOLVE(p_result_error,    "duckdb_result_error");
    RESOLVE(p_row_count,       "duckdb_row_count");
    RESOLVE(p_column_count,    "duckdb_column_count");
    RESOLVE(p_column_name,     "duckdb_column_name");
    RESOLVE(p_column_type,     "duckdb_column_type");
    RESOLVE(p_value_varchar,   "duckdb_value_varchar");
    RESOLVE(p_value_int32,     "duckdb_value_int32");
    RESOLVE(p_value_is_null,   "duckdb_value_is_null");
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

    /* -------------------------------------------------------------- */
    /* Repeated Bind/Init/Destroy cycles (13.2)                       */
    /* -------------------------------------------------------------- */

    fprintf(stderr, "\n=== Repeated lifecycle cycles ===\n");

    /* cheap scalar functions: 1000 cycles */
    fprintf(stderr, "\n--- Repeated: ili_extension_version (1000x) ---\n");
    for (int rep = 0; rep < 1000; rep++) {
        duckdb_result r;
        if (p_query(conn, "SELECT ili_extension_version()", &r) != DuckDBSuccess) {
            fprintf(stderr, "  FAIL (ili_extension_version rep %d): %s\n", rep,
                    p_result_error(&r));
            p_destroy_result(&r);
            failed++;
            break;
        }
        if (rep == 0) {
            verify_cell_varchar(&r, 0, 0, "0.1.0-dev", "extension_version");
        }
        p_destroy_result(&r);
    }

    fprintf(stderr, "\n--- Repeated: ili_native_version (1000x) ---\n");
    for (int rep = 0; rep < 1000; rep++) {
        duckdb_result r;
        if (p_query(conn, "SELECT ili_native_version()", &r) != DuckDBSuccess) {
            fprintf(stderr, "  FAIL (ili_native_version rep %d): %s\n", rep,
                    p_result_error(&r));
            p_destroy_result(&r);
            failed++;
            break;
        }
        p_destroy_result(&r);
    }

    /* expensive table functions: 100 cycles */
    fprintf(stderr, "\n--- Repeated: ili_models (100x) ---\n");
    for (int rep = 0; rep < 100; rep++) {
        char sql[512];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM ili_models('" TESTDATA_DIR "')");
        verify_query(conn, sql, "ili_models_repeated", 1);
    }

    fprintf(stderr, "\n--- Repeated: ili_validate (100x) ---\n");
    for (int rep = 0; rep < 100; rep++) {
        char sql[1024];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM ili_validate('" TESTDATA_XTF "', "
            "modeldir := '" TESTDATA_DIR "', profile := 'full')");
        verify_query(conn, sql, "ili_validate_repeated", 1);
    }

    fprintf(stderr, "\n--- Repeated: read_xtf_class (100x) ---\n");
    for (int rep = 0; rep < 100; rep++) {
        char sql[1024];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM read_xtf_class('" TESTDATA_XTF "', "
            "class := 'SO_AGI_Simple_20260605.Topic.Gemeinde', "
            "modeldir := '" TESTDATA_DIR "')");
        verify_query(conn, sql, "read_xtf_class_repeated", 1);
    }

    /* -------------------------------------------------------------- */
    /* Value checks (13.3)                                            */
    /* -------------------------------------------------------------- */

    fprintf(stderr, "\n=== Value checks ===\n");

    /* ili_native_version: check JSON fields */
    {
        duckdb_result r;
        p_query(conn, "SELECT ili_native_version()", &r);
        char *json = p_value_varchar(&r, 0, 0);
        fprintf(stderr, "  native_version JSON: %s\n", json);
        /* verify key fields exist in JSON */
        int has_lib = (strstr(json, "native_lib") != NULL);
        int has_platform = (strstr(json, "platform") != NULL);
        if (has_lib && has_platform) {
            fprintf(stderr, "  PASS (native_version fields): found native_lib and platform\n");
            passed++;
        } else {
            fprintf(stderr, "  FAIL (native_version fields): missing expected JSON keys\n");
            failed++;
        }
        p_free(json);
        p_destroy_result(&r);
    }

    /* ili_validate: check column count, names, and values */
    {
        char sql[1024];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM ili_validate('" TESTDATA_XTF "', "
            "modeldir := '" TESTDATA_DIR "', profile := 'full')");
        duckdb_result r;
        p_query(conn, sql, &r);
        idx_t ncols = p_column_count(&r);
        idx_t nrows = p_row_count(&r);
        fprintf(stderr, "  ili_validate: %lld columns, %lld rows\n",
                (long long)ncols, (long long)nrows);

        if (ncols >= 13) {
            verify_column_name(&r, 0, "severity", "validate_col0");
            verify_column_name(&r, 1, "code", "validate_col1");
            verify_column_name(&r, 2, "message", "validate_col2");
            verify_column_name(&r, 3, "filename", "validate_col3");
            verify_column_name(&r, 4, "line", "validate_col4");
            verify_column_name(&r, 5, "column", "validate_col5");
            verify_column_name(&r, 6, "xtf_tid", "validate_col6");
            verify_column_name(&r, 7, "xtf_bid", "validate_col7");
            verify_column_name(&r, 8, "model", "validate_col8");
            verify_column_name(&r, 12, "raw", "validate_col12");

            /* first row severity should not be NULL (valid XTF produces at least INFO) */
            int col0_null = p_value_is_null(&r, 0, 0);
            if (col0_null) {
                fprintf(stderr, "  FAIL (validate severity): unexpected NULL\n");
                failed++;
            } else {
                fprintf(stderr, "  PASS (validate severity): non-NULL\n");
                passed++;
            }
        }
        p_destroy_result(&r);
    }

    /* read_xtf_class: check Gemeinde row values */
    {
        char sql[1024];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM read_xtf_class('" TESTDATA_XTF "', "
            "class := 'SO_AGI_Simple_20260605.Topic.Gemeinde', "
            "modeldir := '" TESTDATA_DIR "')");
        duckdb_result r;
        p_query(conn, sql, &r);

        idx_t ncols = p_column_count(&r);
        fprintf(stderr, "  read_xtf_class Gemeinde: %lld columns, %lld rows\n",
                (long long)ncols, (long long)p_row_count(&r));

        /* find Name and BFS_Nr columns */
        idx_t col_xtf_tid = (idx_t)-1;
        idx_t col_name = (idx_t)-1;
        idx_t col_bfsnr = (idx_t)-1;
        for (idx_t c = 0; c < ncols; c++) {
            const char *nm = p_column_name(&r, c);
            if (nm && strcmp(nm, "xtf_tid") == 0) col_xtf_tid = c;
            if (nm && strcmp(nm, "name") == 0) col_name = c;
            if (nm && strcmp(nm, "bfs_nr") == 0) col_bfsnr = c;
        }

        if (col_name != (idx_t)-1) {
            verify_cell_varchar(&r, col_name, 0, "TestGemeinde", "gemeinde_name");
        } else {
            fprintf(stderr, "  FAIL (gemeinde_name): 'name' column not found\n");
            failed++;
        }
        if (col_bfsnr != (idx_t)-1) {
            verify_cell_int32(&r, col_bfsnr, 0, 1234, "gemeinde_bfsnr");
        } else {
            fprintf(stderr, "  FAIL (gemeinde_bfsnr): 'bfs_nr' column not found\n");
            failed++;
        }

        p_destroy_result(&r);
    }

    /* read_xtf_class: check Abbaustelle row */
    {
        char sql[1024];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM read_xtf_class('" TESTDATA_XTF "', "
            "class := 'SO_AGI_Simple_20260605.Topic.Abbaustelle', "
            "modeldir := '" TESTDATA_DIR "')");
        duckdb_result r;
        p_query(conn, sql, &r);

        idx_t ncols = p_column_count(&r);
        idx_t col_name = (idx_t)-1;
        idx_t col_status = (idx_t)-1;
        for (idx_t c = 0; c < ncols; c++) {
            const char *nm = p_column_name(&r, c);
            if (nm && strcmp(nm, "name") == 0) col_name = c;
            if (nm && strcmp(nm, "status") == 0) col_status = c;
        }
        if (col_name != (idx_t)-1) {
            verify_cell_varchar(&r, col_name, 0, "Kiesgrube Nord", "abbaustelle_name");
        } else {
            fprintf(stderr, "  FAIL (abbaustelle_name): 'name' column not found\n");
            failed++;
        }
        if (col_status != (idx_t)-1) {
            verify_cell_varchar(&r, col_status, 0, "aktiv", "abbaustelle_status");
        } else {
            fprintf(stderr, "  FAIL (abbaustelle_status): 'status' column not found\n");
            failed++;
        }

        p_destroy_result(&r);
    }

    /* ili_models: check column names and non-null name */
    {
        char sql[512];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM ili_models('" TESTDATA_DIR "')");
        duckdb_result r;
        p_query(conn, sql, &r);
        verify_column_name(&r, 0, "name", "models_col0");
        verify_column_name(&r, 1, "version", "models_col1");
        verify_column_name(&r, 2, "issuer", "models_col2");
        verify_column_name(&r, 3, "language", "models_col3");
        verify_column_name(&r, 4, "ili_version", "models_col4");
        /* name should not be NULL */
        int null_name = p_value_is_null(&r, 0, 0);
        if (null_name) {
            fprintf(stderr, "  FAIL (models name): unexpected NULL\n");
            failed++;
        } else {
            fprintf(stderr, "  PASS (models name): non-NULL\n");
            passed++;
        }
        p_destroy_result(&r);
    }

    /* ili_topics: check columns and non-null */
    {
        char sql[512];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM ili_topics('" TESTDATA_DIR "')");
        duckdb_result r;
        p_query(conn, sql, &r);
        verify_column_name(&r, 0, "model_name", "topics_col0");
        verify_column_name(&r, 1, "topic_name", "topics_col1");
        verify_column_name(&r, 2, "kind", "topics_col2");
        p_destroy_result(&r);
    }

    /* ili_generate_import_sql: check DDL is generated */
    {
        char sql[1024];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM ili_generate_import_sql('" TESTDATA_XTF "', "
            "schema := 'smoke_test', modeldir := '" TESTDATA_DIR "')");
        duckdb_result r;
        p_query(conn, sql, &r);
        idx_t nrows = p_row_count(&r);
        fprintf(stderr, "  ili_generate_import_sql: %lld rows\n", (long long)nrows);
        if (nrows > 0) {
            /* first row should have non-NULL sql */
            int null_sql = p_value_is_null(&r, 0, 0);
            if (null_sql) {
                fprintf(stderr, "  FAIL (gen_sql): unexpected NULL in sql column\n");
                failed++;
            } else {
                fprintf(stderr, "  PASS (gen_sql): sql column non-NULL\n");
                passed++;
            }
        }
        p_destroy_result(&r);
    }

    /* read_xtf_objects: check columns */
    {
        char sql[1024];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM read_xtf_objects('" TESTDATA_XTF "', "
            "modeldir := '" TESTDATA_DIR "')");
        duckdb_result r;
        p_query(conn, sql, &r);
        verify_column_name(&r, 0, "xtf_tid", "objects_col0");
        verify_column_name(&r, 1, "class_name", "objects_col1");
        verify_column_name(&r, 2, "object_json", "objects_col2");
        /* verify xtf_tid values exist */
        int null_tid = p_value_is_null(&r, 0, 0);
        if (null_tid) {
            fprintf(stderr, "  FAIL (objects xtf_tid): unexpected NULL\n");
            failed++;
        } else {
            fprintf(stderr, "  PASS (objects xtf_tid): non-NULL\n");
            passed++;
        }
        p_destroy_result(&r);
    }

    /* -------------------------------------------------------------- */
    /* Error path tests (13.4)                                         */
    /* -------------------------------------------------------------- */

    fprintf(stderr, "\n=== Error path tests ===\n");

    /* missing XTF file */
    verify_query_error(conn,
        "SELECT * FROM read_xtf_class('nonexistent_missing.xtf', "
        "class := 'Model.Topic.Class')",
        "err_missing_xtf");

    /* unknown class name */
    {
        char sql[1024];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM read_xtf_class('" TESTDATA_XTF "', "
            "class := 'Unknown.Topic.Class', "
            "modeldir := '" TESTDATA_DIR "')");
        verify_query_error(conn, sql, "err_unknown_class");
    }

    /* missing XTF for validate */
    verify_query_error(conn,
        "SELECT * FROM ili_validate('nonexistent_missing.xtf')",
        "err_validate_missing");

    /* unknown validation profile */
    {
        char sql[1024];
        snprintf(sql, sizeof(sql),
            "SELECT * FROM ili_validate('" TESTDATA_XTF "', "
            "modeldir := '" TESTDATA_DIR "', profile := 'unknown_profile')");
        verify_query_error(conn, sql, "err_unknown_profile");
    }

    /* -------------------------------------------------------------- */
    /* Cleanup                                                         */
    /* -------------------------------------------------------------- */

    fprintf(stderr, "\n--- Disconnect and close ---\n");
    p_disconnect(&conn);
    p_close(&db);
    dlclose(lib_handle);

    fprintf(stderr, "\n========================================\n");
    fprintf(stderr, "Results: %d passed, %d failed\n", passed, failed);
    return failed > 0 ? 1 : 0;
}
