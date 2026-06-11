#ifndef ILI_TYPED_SCHEMA_H
#define ILI_TYPED_SCHEMA_H

#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * ili_column_kind — logical type of a column.
 */
typedef enum {
    ILI_COLUMN_VARCHAR,
    ILI_COLUMN_BIGINT,
    ILI_COLUMN_DOUBLE,
    ILI_COLUMN_BOOLEAN,
    ILI_COLUMN_DATE,
    ILI_COLUMN_TIME,
    ILI_COLUMN_TIMESTAMP,
    ILI_COLUMN_GEOMETRY
} ili_column_kind;

/*
 * ili_wire_encoding — how the value is transmitted in the TSV data protocol.
 */
typedef enum {
    ILI_WIRE_TEXT,
    ILI_WIRE_HEX_WKB
} ili_wire_encoding;

/*
 * ili_column_descriptor — full per-column type information from schema v2.
 *
 * Ownership:
 *   - All char * fields are malloc'd and owned by the descriptor.
 *   - geometry_kind, crs_auth_name, crs_code may be NULL if not applicable.
 *   - Free with ili_typed_schema_free().
 */
typedef struct {
    char *name;
    ili_column_kind kind;
    ili_wire_encoding encoding;
    bool nullable;
    char *geometry_kind;
    char *crs_auth_name;
    char *crs_code;
} ili_column_descriptor;

/*
 * Parse a schema-v2 TSV payload into an array of ili_column_descriptor.
 *
 * The input is a TSV with one line per column:
 *   name\tlogical_type\twire_encoding\tnullable\tgeometry_kind\tcrs_auth_name\tcrs_code
 *
 * On success, *out_cols is allocated and *out_count is set.
 * The caller owns the result and MUST call ili_typed_schema_free().
 *
 * Returns true on success; on failure, *out_error contains a description.
 */
bool ili_typed_schema_parse(const char *tsv,
                            ili_column_descriptor **out_cols, int *out_count,
                            char **out_error);

/*
 * Free all memory owned by a schema descriptor array.
 */
void ili_typed_schema_free(ili_column_descriptor *cols, int count);

#ifdef __cplusplus
}
#endif

#endif
