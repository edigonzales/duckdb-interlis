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
extern native_struct_fn g_native_read_xtf_association_schema_v2;
extern native_struct_fn g_native_read_xtf_association_v2;

typedef enum {
    XTF_TYPED_ENTITY_CLASS,
    XTF_TYPED_ENTITY_ASSOCIATION
} xtf_typed_entity_kind;

typedef struct {
    char *input;
    char *entity_name;
    char *modeldir;
    char *nested;
    ili_column_descriptor *columns;
    int col_count;
    xtf_typed_entity_kind entity_kind;
} xtf_typed_bind_data;

typedef struct {
    char **rows;
    idx_t row_count;
    idx_t current_row;
} xtf_typed_init_data;

static const char *entity_label(xtf_typed_entity_kind kind) {
    return kind == XTF_TYPED_ENTITY_ASSOCIATION ? "association" : "class";
}

static const char *bind_data_label(xtf_typed_entity_kind kind) {
    return kind == XTF_TYPED_ENTITY_ASSOCIATION
        ? "xtf_assoc_typed_bind_data"
        : "xtf_class_typed_bind_data";
}

static const char *init_data_label(xtf_typed_entity_kind kind) {
    return kind == XTF_TYPED_ENTITY_ASSOCIATION
        ? "xtf_assoc_typed_init_data"
        : "xtf_class_typed_init_data";
}

static native_struct_fn schema_fn_for(xtf_typed_entity_kind kind) {
    return kind == XTF_TYPED_ENTITY_ASSOCIATION
        ? g_native_read_xtf_association_schema_v2
        : g_native_read_xtf_class_schema_v2;
}

static native_struct_fn read_fn_for(xtf_typed_entity_kind kind) {
    return kind == XTF_TYPED_ENTITY_ASSOCIATION
        ? g_native_read_xtf_association_v2
        : g_native_read_xtf_class_v2;
}

static void xtf_typed_bind_destroy(void *d) {
    xtf_typed_bind_data *bd = (xtf_typed_bind_data *)d;
    if (bd) {
        free(bd->input);
        free(bd->entity_name);
        free(bd->modeldir);
        free(bd->nested);
        ili_typed_schema_free(bd->columns, bd->col_count);
        free(bd);
    }
}

static void xtf_typed_init_destroy(void *d) {
    xtf_typed_init_data *id = (xtf_typed_init_data *)d;
    if (id) {
        for (idx_t i = 0; i < id->row_count; i++) free(id->rows[i]);
        free(id->rows);
        free(id);
    }
}

static duckdb_logical_type logical_type_for_column(const ili_column_descriptor *col) {
    switch (col->kind) {
        case ILI_COLUMN_BIGINT:
            return duckdb_create_logical_type(DUCKDB_TYPE_BIGINT);
        case ILI_COLUMN_DOUBLE:
            return duckdb_create_logical_type(DUCKDB_TYPE_DOUBLE);
        case ILI_COLUMN_BOOLEAN:
            return duckdb_create_logical_type(DUCKDB_TYPE_BOOLEAN);
        case ILI_COLUMN_DATE:
            return duckdb_create_logical_type(DUCKDB_TYPE_DATE);
        case ILI_COLUMN_TIME:
            return duckdb_create_logical_type(DUCKDB_TYPE_TIME);
        case ILI_COLUMN_TIMESTAMP:
            return duckdb_create_logical_type(DUCKDB_TYPE_TIMESTAMP);
        case ILI_COLUMN_GEOMETRY:
            return duckdb_create_logical_type(DUCKDB_TYPE_GEOMETRY);
        case ILI_COLUMN_VARCHAR:
        default:
            return duckdb_create_logical_type(DUCKDB_TYPE_VARCHAR);
    }
}

static bool assign_typed_field(
        duckdb_function_info tfinfo,
        duckdb_vector vec,
        idx_t row,
        const ili_column_descriptor *col,
        const ili_tsv_field *field) {
    switch (col->kind) {
        case ILI_COLUMN_GEOMETRY:
            return ili_duckdb_assign_geometry_from_hex(
                vec, row, field->data, field->length, col->name, NULL, tfinfo);
        case ILI_COLUMN_BIGINT:
            return ili_tsv_assign_bigint(vec, row, field);
        case ILI_COLUMN_DOUBLE:
            return ili_tsv_assign_double(vec, row, field);
        case ILI_COLUMN_BOOLEAN:
            return ili_tsv_assign_boolean(vec, row, field);
        case ILI_COLUMN_DATE:
            return ili_tsv_assign_date(vec, row, field);
        case ILI_COLUMN_TIME:
            return ili_tsv_assign_time(vec, row, field);
        case ILI_COLUMN_TIMESTAMP:
            return ili_tsv_assign_timestamp(vec, row, field);
        case ILI_COLUMN_VARCHAR:
        default:
            return ili_tsv_assign_varchar(vec, row, field);
    }
}

static void populate_schema_request(ili_request *req, const xtf_typed_bind_data *bd) {
    req->struct_size = sizeof(*req);
    req->modeldir = bd->modeldir;
    req->max_messages = -1;
    if (bd->entity_kind == XTF_TYPED_ENTITY_ASSOCIATION) {
        req->association = bd->entity_name;
    } else {
        req->class_name = bd->entity_name;
    }
}

static void populate_read_request(ili_request *req, const xtf_typed_bind_data *bd) {
    populate_schema_request(req, bd);
    req->input = bd->input;
    if (bd->entity_kind == XTF_TYPED_ENTITY_CLASS) {
        req->nested = bd->nested ? bd->nested : "json";
    }
}

static void xtf_typed_bind_common(duckdb_bind_info info, xtf_typed_entity_kind kind) {
    char *input = ili_bind_copy_parameter_varchar_or_error(info, 0, "input");
    char *entity = kind == XTF_TYPED_ENTITY_ASSOCIATION
        ? ili_bind_copy_named_varchar_or_error(info, "association")
        : ili_bind_copy_named_varchar_or_error(info, "class");
    char *modeldir = ili_bind_copy_named_varchar_or_error(info, "modeldir");
    char *nested = kind == XTF_TYPED_ENTITY_CLASS
        ? ili_bind_copy_named_varchar_or_error(info, "nested")
        : NULL;

    xtf_typed_bind_data *bd = ili_calloc_or_error_bind(info, 1, sizeof(*bd), bind_data_label(kind));
    if (!bd) {
        free(input); free(entity); free(modeldir); free(nested);
        return;
    }
    bd->input = input;
    bd->entity_name = entity;
    bd->modeldir = modeldir;
    bd->nested = nested;
    bd->entity_kind = kind;

    duckdb_bind_set_bind_data(info, bd, xtf_typed_bind_destroy);

    if (!ensure_native_ready()) {
        duckdb_bind_set_error(info, get_init_error());
        return;
    }
    if (!schema_fn_for(kind)) {
        char err_buf[192];
        snprintf(err_buf, sizeof(err_buf),
            "Native function read_xtf_%s_schema_v2 not available", entity_label(kind));
        duckdb_bind_set_error(info, err_buf);
        return;
    }
    if (!bd->entity_name || bd->entity_name[0] == '\0') {
        char err_buf[128];
        snprintf(err_buf, sizeof(err_buf),
            "Missing required parameter: %s", entity_label(kind));
        duckdb_bind_set_error(info, err_buf);
        return;
    }

    ili_request req;
    memset(&req, 0, sizeof(req));
    populate_schema_request(&req, bd);

    int status = -1;
    char *schema_result = ili_call_struct_str(schema_fn_for(kind), &req, &status);
    if (status != 0 || !schema_result || schema_result[0] == '\0') {
        char err_buf[192];
        snprintf(err_buf, sizeof(err_buf), "Schema v2 read failed for %s", entity_label(kind));
        ili_report_bind_error(info, status, schema_result, err_buf);
        return;
    }

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

    for (int i = 0; i < bd->col_count; i++) {
        duckdb_logical_type lt = logical_type_for_column(&bd->columns[i]);
        duckdb_bind_add_result_column(info, bd->columns[i].name, lt);
        duckdb_destroy_logical_type(&lt);
    }
}

static void xtf_typed_init_common(duckdb_init_info info, xtf_typed_entity_kind kind) {
    if (!ensure_native_ready()) {
        duckdb_init_set_error(info, get_init_error());
        return;
    }

    xtf_typed_bind_data *bd = (xtf_typed_bind_data *)duckdb_init_get_bind_data(info);
    if (!bd || !bd->input || !bd->entity_name) {
        char err_buf[160];
        snprintf(err_buf, sizeof(err_buf), "Missing input or %s", entity_label(kind));
        duckdb_init_set_error(info, err_buf);
        return;
    }
    if (!read_fn_for(kind)) {
        char err_buf[192];
        snprintf(err_buf, sizeof(err_buf),
            "Native function read_xtf_%s_v2 not available", entity_label(kind));
        duckdb_init_set_error(info, err_buf);
        return;
    }

    ili_request req;
    memset(&req, 0, sizeof(req));
    populate_read_request(&req, bd);

    int status = -1;
    char *result = ili_call_struct_str(read_fn_for(kind), &req, &status);
    if (status != 0 || !result) {
        char err_buf[192];
        snprintf(err_buf, sizeof(err_buf), "XTF %s read v2 failed", entity_label(kind));
        ili_report_error(info, status, result, err_buf);
        return;
    }

    xtf_typed_init_data *id = ili_calloc_or_error_init(info, 1, sizeof(*id), init_data_label(kind));
    if (!id) { free(result); return; }

    const char *p = result;
    while (*p && *p != '\n') p++;
    if (*p == '\n') p++;

    const char *tmp = p;
    while (*tmp) {
        id->row_count++;
        while (*tmp && *tmp != '\n') tmp++;
        if (*tmp == '\n') tmp++;
    }

    if (id->row_count == 0) {
        free(id);
        free(result);
        duckdb_init_set_init_data(info, NULL, NULL);
        return;
    }

    id->rows = ili_malloc_or_error_init(info, id->row_count * sizeof(char*), "typed rows");
    if (!id->rows) { free(id); free(result); return; }
    for (idx_t i = 0; i < id->row_count; i++) {
        const char *start = p;
        size_t len = 0;
        while (*p && *p != '\n') { p++; len++; }
        id->rows[i] = malloc(len + 1);
        if (!id->rows[i]) {
            duckdb_init_set_error(info, "Out of memory allocating typed row");
            free(result);
            xtf_typed_init_destroy(id);
            return;
        }
        memcpy(id->rows[i], start, len);
        id->rows[i][len] = '\0';
        if (*p == '\n') p++;
    }
    free(result);
    duckdb_init_set_init_data(info, id, xtf_typed_init_destroy);
}

static void xtf_typed_function_common(duckdb_function_info tfinfo, duckdb_data_chunk output) {
    xtf_typed_init_data *id = (xtf_typed_init_data *)duckdb_function_get_init_data(tfinfo);
    if (!id || id->current_row >= id->row_count) {
        duckdb_data_chunk_set_size(output, 0);
        return;
    }

    xtf_typed_bind_data *bd = (xtf_typed_bind_data *)duckdb_function_get_bind_data(tfinfo);
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
                duckdb_validity_set_row_invalid(duckdb_vector_get_validity(vec), i);
                continue;
            }
            if (!assign_typed_field(tfinfo, vec, i, col, &field)) {
                char err_buf[256];
                snprintf(err_buf, sizeof(err_buf),
                    "Failed to assign typed value for column '%s'", col->name);
                duckdb_function_set_error(tfinfo, err_buf);
                duckdb_data_chunk_set_size(output, 0);
                return;
            }
        }
    }

    duckdb_data_chunk_set_size(output, count);
    id->current_row += count;
}

void xtf_class_typed_bind(duckdb_bind_info info) {
    xtf_typed_bind_common(info, XTF_TYPED_ENTITY_CLASS);
}

void xtf_class_typed_init(duckdb_init_info info) {
    xtf_typed_init_common(info, XTF_TYPED_ENTITY_CLASS);
}

void xtf_class_typed_function(duckdb_function_info tfinfo, duckdb_data_chunk output) {
    xtf_typed_function_common(tfinfo, output);
}

void xtf_assoc_typed_bind(duckdb_bind_info info) {
    xtf_typed_bind_common(info, XTF_TYPED_ENTITY_ASSOCIATION);
}

void xtf_assoc_typed_init(duckdb_init_info info) {
    xtf_typed_init_common(info, XTF_TYPED_ENTITY_ASSOCIATION);
}

void xtf_assoc_typed_function(duckdb_function_info tfinfo, duckdb_data_chunk output) {
    xtf_typed_function_common(tfinfo, output);
}
