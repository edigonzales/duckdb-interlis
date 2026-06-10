#ifndef ILI_DUCKDB_GEOMETRY_H
#define ILI_DUCKDB_GEOMETRY_H

#include "duckdb_extension.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Assign a hex-WKB geometry value to a DuckDB GEOMETRY vector element.
 *
 * This function:
 *   1. Validates the hex string
 *   2. Decodes it to binary WKB
 *   3. Assigns the binary to the DuckDB GEOMETRY vector using
 *      duckdb_vector_assign_string_element_len()
 *
 * For NULL values, the caller should set the validity mask directly.
 * This function should NOT be called with hex=NULL.
 *
 * On error, sets a function error via duckdb_function_set_error() and returns false.
 * The error message includes col_name and xtf_tid when available.
 *
 * Returns true on success.
 */
bool ili_duckdb_assign_geometry_from_hex(
    duckdb_vector vector, idx_t row_idx,
    const char *hex, size_t hex_len,
    const char *col_name, const char *xtf_tid,
    duckdb_function_info tfinfo
);

#ifdef __cplusplus
}
#endif

#endif
