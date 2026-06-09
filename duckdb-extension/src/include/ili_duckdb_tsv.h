#ifndef ILI_DUCKDB_TSV_H
#define ILI_DUCKDB_TSV_H

#include "duckdb_extension.h"
#include "ili_tsv.h"

#ifdef __cplusplus
extern "C" {
#endif

bool ili_tsv_assign_varchar(
    duckdb_vector vector,
    idx_t row,
    const ili_tsv_field *field
);

#ifdef __cplusplus
}
#endif

#endif
