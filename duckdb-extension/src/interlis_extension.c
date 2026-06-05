#include "duckdb_extension.h"
#include <string.h>

DUCKDB_EXTENSION_EXTERN

static const char *ILI_EXTENSION_VERSION = "0.1.0-dev";

static void ili_extension_version_function(duckdb_function_info info, duckdb_data_chunk input, duckdb_vector output) {
    idx_t size = duckdb_data_chunk_get_size(input);
    duckdb_vector_ensure_validity_writable(output);
    uint64_t *validity = duckdb_vector_get_validity(output);

    for (idx_t row = 0; row < size; row++) {
        duckdb_validity_set_row_valid(validity, row);
        duckdb_vector_assign_string_element(output, row, ILI_EXTENSION_VERSION);
    }
}

static void register_ili_extension_version(duckdb_connection connection) {
    duckdb_scalar_function function = duckdb_create_scalar_function();
    duckdb_scalar_function_set_name(function, "ili_extension_version");

    duckdb_logical_type return_type = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
    duckdb_scalar_function_set_return_type(function, return_type);
    duckdb_destroy_logical_type(&return_type);

    duckdb_scalar_function_set_function(function, ili_extension_version_function);

    duckdb_register_scalar_function(connection, function);
    duckdb_destroy_scalar_function(&function);
}

DUCKDB_EXTENSION_ENTRYPOINT(duckdb_connection connection, duckdb_extension_info info, struct duckdb_extension_access *access) {
    register_ili_extension_version(connection);
    return true;
}
