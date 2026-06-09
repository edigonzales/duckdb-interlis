#define DUCKDB_API_EXCLUDE_FUNCTIONS
#include "duckdb.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <pthread.h>

#define TESTDATA_DIR "testdata/synthetic/simple"
#define TESTDATA_XTF  TESTDATA_DIR "/valid.xtf"
#define NUM_THREADS 8

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

static duckdb_database g_db;
static pthread_mutex_t g_barrier_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_barrier_cv   = PTHREAD_COND_INITIALIZER;
static int g_barrier_count = 0;
static int g_barrier_total = 0;

static int thread_errors[NUM_THREADS];

static void barrier_init(int total) {
    pthread_mutex_lock(&g_barrier_lock);
    g_barrier_total = total;
    g_barrier_count = 0;
    pthread_mutex_unlock(&g_barrier_lock);
}

static void barrier_wait(void) {
    pthread_mutex_lock(&g_barrier_lock);
    g_barrier_count++;
    if (g_barrier_count >= g_barrier_total) {
        pthread_cond_broadcast(&g_barrier_cv);
    } else {
        while (g_barrier_count < g_barrier_total) {
            pthread_cond_wait(&g_barrier_cv, &g_barrier_lock);
        }
    }
    pthread_mutex_unlock(&g_barrier_lock);
}

static const char *queries[] = {
    "SELECT * FROM ili_models('" TESTDATA_DIR "')",
    "SELECT * FROM ili_topics('" TESTDATA_DIR "')",
    "SELECT * FROM ili_classes('" TESTDATA_DIR "')",
    "SELECT * FROM read_xtf_objects('" TESTDATA_XTF "', "
        "modeldir := '" TESTDATA_DIR "')",
    "SELECT * FROM read_xtf_class('" TESTDATA_XTF "', "
        "class := 'SO_AGI_Simple_20260605.Topic.Gemeinde', "
        "modeldir := '" TESTDATA_DIR "')",
    "SELECT * FROM ili_validate('" TESTDATA_XTF "', "
        "modeldir := '" TESTDATA_DIR "', profile := 'full')",
    "SELECT * FROM ili_generate_import_sql('" TESTDATA_XTF "', "
        "schema := 'conc_test', modeldir := '" TESTDATA_DIR "')",
    "SELECT ili_extension_version()",
    "SELECT ili_native_version()",
};
#define NUM_QUERIES (sizeof(queries) / sizeof(queries[0]))

static void *thread_func(void *arg) {
    int tid = *(int *)arg;
    free(arg);

    barrier_wait();

    duckdb_connection conn = NULL;
    if (p_connect(g_db, &conn) != DuckDBSuccess) {
        fprintf(stderr, "[T%d] FAIL: duckdb_connect\n", tid);
        thread_errors[tid] = 1;
        return NULL;
    }

    int q_start = (tid * 3) % NUM_QUERIES;
    int q_count = 3;

    for (int i = 0; i < q_count; i++) {
        int qi = (q_start + i) % NUM_QUERIES;
        duckdb_result r;
        if (p_query(conn, queries[qi], &r) != DuckDBSuccess) {
            const char *err = p_result_error(&r);
            fprintf(stderr, "[T%d] FAIL query %d: %s\n", tid, qi,
                    err ? err : "unknown");
            thread_errors[tid]++;
        }
        p_destroy_result(&r);
    }

    p_disconnect(&conn);
    return NULL;
}

int main(void) {
    const char *lib_path = getenv("DUCKDB_LIB");
    if (!lib_path) lib_path = "libduckdb.dylib";

    const char *ext_path = getenv("INTERLIS_EXTENSION");
    if (!ext_path) ext_path = "duckdb-extension/build/interlis.duckdb_extension";

    fprintf(stderr, "=== DuckDB Extension Concurrency Test ===\n");
    fprintf(stderr, "libduckdb:  %s\n", lib_path);
    fprintf(stderr, "extension:  %s\n", ext_path);
    fprintf(stderr, "threads:    %d\n", NUM_THREADS);

    void *lib_handle = dlopen(lib_path, RTLD_NOW);
    if (!lib_handle) {
        fprintf(stderr, "FATAL: dlopen(%s): %s\n", lib_path, dlerror());
        return 1;
    }

#define RS(fn, name) do { \
    fn = (typeof(fn))dlsym(lib_handle, name); \
    if (!fn) { fprintf(stderr, "FATAL: dlsym(%s): %s\n", name, dlerror()); dlclose(lib_handle); return 1; } \
} while(0)

    RS(p_open_ext,        "duckdb_open_ext");
    RS(p_close,            "duckdb_close");
    RS(p_connect,          "duckdb_connect");
    RS(p_disconnect,       "duckdb_disconnect");
    RS(p_query,            "duckdb_query");
    RS(p_destroy_result,   "duckdb_destroy_result");
    RS(p_result_error,     "duckdb_result_error");
    RS(p_row_count,        "duckdb_row_count");
    RS(p_create_config,    "duckdb_create_config");
    RS(p_set_config,       "duckdb_set_config");
    RS(p_destroy_config,   "duckdb_destroy_config");
    RS(p_free,             "duckdb_free");

#undef RS

    fprintf(stderr, "\n--- Open database ---\n");
    duckdb_config config = NULL;
    if (p_create_config(&config) != DuckDBSuccess) {
        fprintf(stderr, "FATAL: duckdb_create_config failed\n");
        dlclose(lib_handle);
        return 1;
    }
    p_set_config(config, "allow_unsigned_extensions", "true");

    char *open_err = NULL;
    if (p_open_ext(NULL, &g_db, config, &open_err) != DuckDBSuccess) {
        fprintf(stderr, "FATAL: duckdb_open_ext: %s\n", open_err ? open_err : "unknown");
        if (open_err) p_free(open_err);
        p_destroy_config(&config);
        dlclose(lib_handle);
        return 1;
    }
    p_destroy_config(&config);

    duckdb_connection load_conn = NULL;
    if (p_connect(g_db, &load_conn) != DuckDBSuccess) {
        fprintf(stderr, "FATAL: duckdb_connect\n");
        p_close(&g_db);
        dlclose(lib_handle);
        return 1;
    }

    fprintf(stderr, "--- Load extension ---\n");
    {
        char sql[4096];
        snprintf(sql, sizeof(sql), "LOAD '%s'", ext_path);
        duckdb_result r;
        if (p_query(load_conn, sql, &r) != DuckDBSuccess) {
            fprintf(stderr, "FATAL: %s\n", p_result_error(&r));
            p_destroy_result(&r);
            p_disconnect(&load_conn);
            p_close(&g_db);
            dlclose(lib_handle);
            return 1;
        }
        fprintf(stderr, "  PASS: extension loaded\n");
        p_destroy_result(&r);
    }
    p_disconnect(&load_conn);

    /* optionally disable parallel execution in DuckDB for cleaner TSan output */
    {
        duckdb_connection cfg_conn = NULL;
        if (p_connect(g_db, &cfg_conn) == DuckDBSuccess) {
            duckdb_result r;
            p_query(cfg_conn, "SET threads TO 1", &r);
            p_destroy_result(&r);
            p_disconnect(&cfg_conn);
        }
    }

    fprintf(stderr, "\n--- Starting %d threads ---\n", NUM_THREADS);
    barrier_init(NUM_THREADS + 1);

    pthread_t threads[NUM_THREADS];
    for (int i = 0; i < NUM_THREADS; i++) {
        int *tid = malloc(sizeof(int));
        *tid = i;
        thread_errors[i] = 0;
        if (pthread_create(&threads[i], NULL, thread_func, tid) != 0) {
            fprintf(stderr, "FATAL: pthread_create(%d)\n", i);
            return 1;
        }
    }

    barrier_wait();

    int total_errors = 0;
    for (int i = 0; i < NUM_THREADS; i++) {
        pthread_join(threads[i], NULL);
        if (thread_errors[i]) {
            fprintf(stderr, "FAIL: thread %d had %d errors\n", i, thread_errors[i]);
        } else {
            fprintf(stderr, "  PASS: thread %d\n", i);
        }
        total_errors += thread_errors[i];
    }

    /* barrier resources cleaned up automatically */
    p_close(&g_db);
    dlclose(lib_handle);

    fprintf(stderr, "\n========================================\n");
    fprintf(stderr, "Threads: %d, Errors: %d\n", NUM_THREADS, total_errors);
    if (total_errors > 0) {
        fprintf(stderr, "FAIL: %d total errors\n", total_errors);
        return 1;
    }
    fprintf(stderr, "PASS: no errors\n");
    return 0;
}
