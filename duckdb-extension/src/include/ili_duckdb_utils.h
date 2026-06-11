#ifndef ILI_DUCKDB_UTILS_H
#define ILI_DUCKDB_UTILS_H

#include "duckdb_extension.h"
#include "ili_request.h"

char *ili_bind_copy_parameter_varchar(
    duckdb_bind_info info,
    idx_t parameter_index
);

char *ili_bind_copy_named_varchar(
    duckdb_bind_info info,
    const char *name
);

// OOM-safe variants: set duckdb_bind_set_error when strdup fails
char *ili_bind_copy_parameter_varchar_or_error(
    duckdb_bind_info info,
    idx_t parameter_index,
    const char *param_name
);

char *ili_bind_copy_named_varchar_or_error(
    duckdb_bind_info info,
    const char *param_name
);

bool ili_bind_get_named_int32(
    duckdb_bind_info info,
    const char *name,
    int32_t *out_value
);

// Native call helper: sends an ili_request to a native function,
// returns a C-allocated (strdup) copy of the result.
// Caller must free the result. Sets *out_status to the native return code.
char *ili_call_struct_str(
    native_struct_fn fn, const ili_request *req, int *out_status
);

// Error reporting helpers
char *extract_error_message(const char *json_payload);
void ili_report_error(duckdb_init_info info, int status, char *payload, const char *fallback);
void ili_report_bind_error(duckdb_bind_info info, int status, char *payload, const char *fallback);

// Safe allocation helpers that set DuckDB error on OOM
void *ili_malloc_or_error_bind(duckdb_bind_info info, size_t size, const char *context);
void *ili_malloc_or_error_init(duckdb_init_info info, size_t size, const char *context);
void *ili_calloc_or_error_bind(duckdb_bind_info info, size_t nmemb, size_t size, const char *context);
void *ili_calloc_or_error_init(duckdb_init_info info, size_t nmemb, size_t size, const char *context);

#endif
