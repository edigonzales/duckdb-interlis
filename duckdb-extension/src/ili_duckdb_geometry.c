#include "ili_duckdb_geometry.h"
#include "ili_hex.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

DUCKDB_EXTENSION_EXTERN

bool ili_duckdb_assign_geometry_from_hex(
    duckdb_vector vector, idx_t row_idx,
    const char *hex, size_t hex_len,
    const char *col_name, const char *xtf_tid,
    duckdb_function_info tfinfo)
{
    if (!hex) {
        char err_buf[512];
        snprintf(err_buf, sizeof(err_buf),
            "Internal error: NULL hex passed to assign_geometry_from_hex "
            "(col=%s, tid=%s)",
            col_name ? col_name : "?", xtf_tid ? xtf_tid : "?");
        duckdb_function_set_error(tfinfo, err_buf);
        return false;
    }

    // Decode hex to binary WKB bytes (little-endian from Java)
    uint8_t *wkb_buf = NULL;
    size_t wkb_len = 0;
    char *hex_err = NULL;

    if (!ili_hex_decode(hex, hex_len, &wkb_buf, &wkb_len, &hex_err)) {
        char err_buf[512];
        snprintf(err_buf, sizeof(err_buf),
            "Hex-WKB decode failed for column '%s' (tid=%s): %s",
            col_name ? col_name : "?",
            xtf_tid ? xtf_tid : "?",
            hex_err ? hex_err : "unknown error");
        duckdb_function_set_error(tfinfo, err_buf);
        free(hex_err);
        return false;
    }

    // Assign binary WKB directly to the GEOMETRY vector.
    // DuckDB stores GEOMETRY as WKB blob; Java already produces little-endian WKB.
    duckdb_vector_assign_string_element_len(
        vector, row_idx,
        (const char *)wkb_buf, (idx_t)wkb_len);

    free(wkb_buf);
    return true;
}
