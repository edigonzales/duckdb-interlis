#include "ili_duckdb_utils.h"
#include <stdlib.h>
#include <string.h>

DUCKDB_EXTENSION_EXTERN

char *ili_bind_copy_parameter_varchar(
    duckdb_bind_info info,
    idx_t parameter_index
) {
    duckdb_value dv = duckdb_bind_get_parameter(info, parameter_index);
    if (!dv) return NULL;
    if (duckdb_is_null_value(dv)) {
        duckdb_destroy_value(&dv);
        return NULL;
    }
    char *raw = duckdb_get_varchar(dv);
    char *copy = raw ? strdup(raw) : NULL;
    duckdb_free(raw);
    duckdb_destroy_value(&dv);
    return copy;
}

char *ili_bind_copy_named_varchar(
    duckdb_bind_info info,
    const char *name
) {
    duckdb_value dv = duckdb_bind_get_named_parameter(info, name);
    if (!dv) return NULL;
    if (duckdb_is_null_value(dv)) {
        duckdb_destroy_value(&dv);
        return NULL;
    }
    char *raw = duckdb_get_varchar(dv);
    char *copy = raw ? strdup(raw) : NULL;
    duckdb_free(raw);
    duckdb_destroy_value(&dv);
    return copy;
}

bool ili_bind_get_named_int32(
    duckdb_bind_info info,
    const char *name,
    int32_t *out_value
) {
    duckdb_value dv = duckdb_bind_get_named_parameter(info, name);
    if (!dv) return false;
    if (duckdb_is_null_value(dv)) {
        duckdb_destroy_value(&dv);
        return false;
    }
    *out_value = duckdb_get_int32(dv);
    duckdb_destroy_value(&dv);
    return true;
}
