#ifndef ILI_DUCKDB_UTILS_H
#define ILI_DUCKDB_UTILS_H

#include "duckdb_extension.h"

char *ili_bind_copy_parameter_varchar(
    duckdb_bind_info info,
    idx_t parameter_index
);

char *ili_bind_copy_named_varchar(
    duckdb_bind_info info,
    const char *name
);

bool ili_bind_get_named_int32(
    duckdb_bind_info info,
    const char *name,
    int32_t *out_value
);

#endif
