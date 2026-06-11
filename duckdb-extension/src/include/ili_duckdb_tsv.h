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

bool ili_tsv_assign_bigint(
    duckdb_vector vector,
    idx_t row,
    const ili_tsv_field *field
);

bool ili_tsv_assign_int32(
    duckdb_vector vector,
    idx_t row,
    const ili_tsv_field *field
);

bool ili_tsv_assign_double(
    duckdb_vector vector,
    idx_t row,
    const ili_tsv_field *field
);

bool ili_tsv_assign_boolean(
    duckdb_vector vector,
    idx_t row,
    const ili_tsv_field *field
);

bool ili_tsv_assign_date(
    duckdb_vector vector,
    idx_t row,
    const ili_tsv_field *field
);

bool ili_tsv_assign_time(
    duckdb_vector vector,
    idx_t row,
    const ili_tsv_field *field
);

bool ili_tsv_assign_timestamp(
    duckdb_vector vector,
    idx_t row,
    const ili_tsv_field *field
);

#ifdef __cplusplus
}
#endif

#endif
