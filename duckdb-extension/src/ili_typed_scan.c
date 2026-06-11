#include "ili_typed_scan.h"
#include "ili_typed_schema.h"
#include "ili_duckdb_geometry.h"
#include "ili_duckdb_utils.h"
#include "ili_duckdb_tsv.h"
#include "ili_tsv.h"
#include "ili_request.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

DUCKDB_EXTENSION_EXTERN

// Shared globals from interlis_extension.c
extern bool ensure_native_ready(void);
extern const char *get_init_error(void);
extern native_struct_fn g_native_read_xtf_class_schema_v2;
extern native_struct_fn g_native_read_xtf_class_v2;

// ---------------------------------------------------------------------------
// Bind data
// ---------------------------------------------------------------------------
typedef struct {
    char *input;
    char *class_name;
    char *modeldir;
    char *nested;
    ili_column_descriptor *columns;
    int col_count;
} xtf_class_typed_bind_data;

static void xtf_class_typed_bind_destroy(void *d) {
    xtf_class_typed_bind_data *bd = (xtf_class_typed_bind_data *)d;
    if (bd) {
        free(bd->input);
        free(bd->class_name);
        free(bd->modeldir);
        free(bd->nested);
        ili_typed_schema_free(bd->columns, bd->col_count);
        free(bd);
    }
}

// ---------------------------------------------------------------------------
// Init data (same row-based structure as mi_init_data)
// ---------------------------------------------------------------------------
typedef struct {
    char **rows;
    idx_t row_count;
    idx_t current_row;
} xtf_class_typed_init_data;

static void xtf_class_typed_init_destroy(void *d) {
    xtf_class_typed_init_data *id = (xtf_class_typed_init_data *)d;
    if (id) {
        for (idx_t i = 0; i < id->row_count; i++) free(id->rows[i]);
        free(id->rows);
        free(id);
    }
}

// ---------------------------------------------------------------------------
// Bind
// ---------------------------------------------------------------------------
void xtf_class_typed_bind(duckdb_bind_info info) {
    char *input = ili_bind_copy_parameter_varchar(info, 0);
    char *cls = ili_bind_copy_named_varchar(info, "class");
    char *modeldir = ili_bind_copy_named_varchar(info, "modeldir");
    char *nested = ili_bind_copy_named_varchar(info, "nested");

    xtf_class_typed_bind_data *bd = ili_calloc_or_error_bind(info, 1, sizeof(*bd), "xtf_class_typed_bind_data");
    if (!bd) {
        free(input); free(cls); free(modeldir); free(nested);
        return;
    }
    bd->input = input;
    bd->class_name = cls;
    bd->modeldir = modeldir;
    bd->nested = nested;

    duckdb_bind_set_bind_data(info, bd, xtf_class_typed_bind_destroy);

    if (!ensure_native_ready()) {
        duckdb_bind_set_error(info, get_init_error());
        return;
    }
    if (!g_native_read_xtf_class_schema_v2) {
        duckdb_bind_set_error(info, "Native function read_xtf_class_schema_v2 not available");
        return;
    }
    if (!bd->class_name || bd->class_name[0] == '\0') {
        duckdb_bind_set_error(info, "Missing required parameter: class");
        return;
    }

    // Call schema v2
    ili_request req;
    memset(&req, 0, sizeof(req));
    req.struct_size = sizeof(req);
    req.class_name = bd->class_name;
    req.modeldir = bd->modeldir;
    req.max_messages = -1;

    int status = -1;
    char *schema_result = ili_call_struct_str(g_native_read_xtf_class_schema_v2, &req, &status);
    if (status != 0 || !schema_result || schema_result[0] == '\0') {
        ili_report_bind_error(info, status, schema_result, "Schema v2 read failed");
        return;
    }

    // Parse schema v2
    char *parse_err = NULL;
    if (!ili_typed_schema_parse(schema_result, &bd->columns, &bd->col_count, &parse_err)) {
        char err_buf[512];
        snprintf(err_buf, sizeof(err_buf), "Schema v2 parse error: %s",
            parse_err ? parse_err : "unknown error");
        duckdb_bind_set_error(info, err_buf);
        free(parse_err);
        free(schema_result);
        return;
    }
    free(schema_result);

    // Register typed result columns
    for (int i = 0; i < bd->col_count; i++) {
        ili_column_descriptor *col = &bd->columns[i];
        duckdb_logical_type lt;
        switch (col->kind) {
            case ILI_COLUMN_GEOMETRY:
                lt = duckdb_create_logical_type(DUCKDB_TYPE_GEOMETRY);
                break;
            case ILI_COLUMN_VARCHAR:
            default:
                lt = duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
                break;
        }
        duckdb_bind_add_result_column(info, col->name, lt);
        duckdb_destroy_logical_type(&lt);
    }
}

// ---------------------------------------------------------------------------
// Init (materialize all rows via v2 native call)
// ---------------------------------------------------------------------------
void xtf_class_typed_init(duckdb_init_info info) {
    if (!ensure_native_ready()) {
        duckdb_init_set_error(info, get_init_error());
        return;
    }

    xtf_class_typed_bind_data *bd = (xtf_class_typed_bind_data *)duckdb_init_get_bind_data(info);
    if (!bd || !bd->input || !bd->class_name) {
        duckdb_init_set_error(info, "Missing input or class");
        return;
    }
    if (!g_native_read_xtf_class_v2) {
        duckdb_init_set_error(info, "Native function read_xtf_class_v2 not available");
        return;
    }

    ili_request req;
    memset(&req, 0, sizeof(req));
    req.struct_size = sizeof(req);
    req.input = bd->input;
    req.class_name = bd->class_name;
    req.modeldir = bd->modeldir;
    req.nested = bd->nested ? bd->nested : "json";
    req.max_messages = -1;

    int status = -1;
    char *result = ili_call_struct_str(g_native_read_xtf_class_v2, &req, &status);
    if (status != 0 || !result) {
        ili_report_error(info, status, result, "XTF class read v2 failed");
        return;
    }

    xtf_class_typed_init_data *id = ili_calloc_or_error_init(info, 1, sizeof(*id), "xtf_class_typed_init_data");
    if (!id) { free(result); return; }

    // Skip header line
    const char *p = result;
    while (*p && *p != '\n') p++;
    if (*p == '\n') p++;

    // Count and parse data rows
    const char *tmp = p;
    while (*tmp) { id->row_count++; while (*tmp && *tmp != '\n') tmp++; if (*tmp == '\n') tmp++; }

    if (id->row_count == 0) {
        free(id);
        free(result);
        duckdb_init_set_init_data(info, NULL, NULL);
        return;
    }

    id->rows = ili_malloc_or_error_init(info, id->row_count * sizeof(char*), "typed class rows");
    if (!id->rows) { free(id); free(result); return; }
    for (idx_t i = 0; i < id->row_count; i++) {
        const char *start = p; size_t len = 0;
        while (*p && *p != '\n') { p++; len++; }
        id->rows[i] = malloc(len + 1);
        if (!id->rows[i]) {
            duckdb_init_set_error(info, "Out of memory allocating typed class row");
            free(result);
            xtf_class_typed_init_destroy(id);
            return;
        }
        memcpy(id->rows[i], start, len);
        id->rows[i][len] = '\0';
        if (*p == '\n') p++;
    }
    free(result);
    duckdb_init_set_init_data(info, id, xtf_class_typed_init_destroy);
}

// ---------------------------------------------------------------------------
// Function (per-chunk typed dispatch)
// ---------------------------------------------------------------------------
void xtf_class_typed_function(duckdb_function_info tfinfo, duckdb_data_chunk output) {
    xtf_class_typed_init_data *id = (xtf_class_typed_init_data *)duckdb_function_get_init_data(tfinfo);
    if (!id || id->current_row >= id->row_count) {
        duckdb_data_chunk_set_size(output, 0);
        return;
    }

    xtf_class_typed_bind_data *bd = (xtf_class_typed_bind_data *)duckdb_function_get_bind_data(tfinfo);
    if (!bd) {
        duckdb_function_set_error(tfinfo, "Missing bind data");
        duckdb_data_chunk_set_size(output, 0);
        return;
    }

    idx_t count = id->row_count - id->current_row;
    if (count > 1024) count = 1024;

    idx_t col_count = duckdb_data_chunk_get_column_count(output);

    for (idx_t i = 0; i < count; i++) {
        char *row = id->rows[id->current_row + i];
        const char *end = row + strlen(row);
        const char *cursor = row;
        ili_tsv_field field;

        for (idx_t c = 0; c < col_count && c < (idx_t)bd->col_count; c++) {
            duckdb_vector vec = duckdb_data_chunk_get_vector(output, c);
            ili_column_descriptor *col = &bd->columns[c];

            if (!ili_tsv_next_field(&cursor, end, &field)) {
                break;
            }

            if (field.is_null) {
                duckdb_vector_ensure_validity_writable(vec);
                duckdb_validity_set_row_invalid(
                    duckdb_vector_get_validity(vec), i);
                continue;
            }

            switch (col->kind) {
                case ILI_COLUMN_GEOMETRY: {
                    if (!ili_duckdb_assign_geometry_from_hex(
                            vec, i,
                            field.data, field.length,
                            col->name, NULL, tfinfo)) {
                        duckdb_data_chunk_set_size(output, 0);
                        return;
                    }
                    break;
                }
                case ILI_COLUMN_VARCHAR:
                default: {
                    if (!ili_tsv_assign_varchar(vec, i, &field)) {
                        char err_buf[256];
                        snprintf(err_buf, sizeof(err_buf),
                            "Failed to assign VARCHAR for column '%s'", col->name);
                        duckdb_function_set_error(tfinfo, err_buf);
                        duckdb_data_chunk_set_size(output, 0);
                        return;
                    }
                    break;
                }
            }
        }
    }

    duckdb_data_chunk_set_size(output, count);
    id->current_row += count;
}
