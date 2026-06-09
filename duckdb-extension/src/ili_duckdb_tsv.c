#include "ili_duckdb_tsv.h"
#include <stdlib.h>

DUCKDB_EXTENSION_EXTERN

bool ili_tsv_assign_varchar(
        duckdb_vector vector,
        idx_t row,
        const ili_tsv_field *field) {

    if (!vector || !field) {
        return false;
    }

    if (field->is_null) {
        duckdb_vector_ensure_validity_writable(vector);
        uint64_t *validity = duckdb_vector_get_validity(vector);
        duckdb_validity_set_row_invalid(validity, row);
        return true;
    }

    char *decoded = ili_tsv_unescape_copy(field);
    if (!decoded) {
        return false;
    }

    duckdb_vector_assign_string_element(vector, row, decoded);

    free(decoded);
    return true;
}
