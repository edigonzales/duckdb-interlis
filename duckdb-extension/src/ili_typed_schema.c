#include "ili_typed_schema.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char *strdup_safe(const char *s) {
    if (!s) return NULL;
    char *dup = strdup(s);
    if (!dup) return NULL;
    return dup;
}

static ili_column_kind parse_kind(const char *s, char **out_error) {
    if (!s || strcmp(s, "VARCHAR") == 0) return ILI_COLUMN_VARCHAR;
    if (strcmp(s, "GEOMETRY") == 0) return ILI_COLUMN_GEOMETRY;

    if (out_error) {
        size_t len = strlen(s ? s : "NULL") + 64;
        *out_error = (char *)malloc(len);
        if (*out_error) {
            snprintf(*out_error, len, "Unknown column kind: '%s'", s ? s : "NULL");
        }
    }
    return ILI_COLUMN_VARCHAR; // will be caught by caller
}

static ili_wire_encoding parse_encoding(const char *s, char **out_error) {
    if (!s || strcmp(s, "TEXT") == 0) return ILI_WIRE_TEXT;
    if (strcmp(s, "HEX_WKB") == 0) return ILI_WIRE_HEX_WKB;

    if (out_error) {
        size_t len = strlen(s ? s : "NULL") + 64;
        *out_error = (char *)malloc(len);
        if (*out_error) {
            snprintf(*out_error, len, "Unknown wire encoding: '%s'", s ? s : "NULL");
        }
    }
    return ILI_WIRE_TEXT;
}

static bool parse_bool(const char *s) {
    return s && strcmp(s, "true") == 0;
}

/*
 * Parse a single TSV field, returning the field value.
 * *cursor is advanced to after the field delimiter.
 * Does NOT skip newlines (that is handled by the caller).
 * Returns NULL if at end of input.
 * The returned value is malloc'd; caller must free.
 */
static char *parse_tsv_field(const char **cursor, const char *end) {
    if (*cursor >= end) return NULL;

    const char *start = *cursor;
    const char *p = start;
    while (p < end && *p != '\t' && *p != '\n' && *p != '\r' && *p != '\0') {
        p++;
    }

    size_t len = (size_t)(p - start);
    char *val = (char *)malloc(len + 1);
    if (!val) return NULL;
    memcpy(val, start, len);
    val[len] = '\0';

    *cursor = p;
    // Advance past the delimiter: only skip \t (not \n!)
    if (*cursor < end && **cursor == '\t') {
        (*cursor)++;
    }

    return val;
}

/*
 * Skip past any newlines at the cursor position.
 */
static void skip_newlines(const char **cursor, const char *end) {
    while (*cursor < end && (**cursor == '\n' || **cursor == '\r')) {
        (*cursor)++;
    }
}

bool ili_typed_schema_parse(const char *tsv,
                            ili_column_descriptor **out_cols, int *out_count,
                            char **out_error)
{
    *out_cols = NULL;
    *out_count = 0;
    if (out_error) *out_error = NULL;

    if (!tsv || !*tsv) {
        if (out_error) *out_error = strdup("Empty schema v2 payload");
        return false;
    }

    // First pass: count columns
    int count = 0;
    const char *end = tsv + strlen(tsv);
    const char *p = tsv;
    while (p < end) {
        skip_newlines(&p, end);
        if (p >= end) break;
        count++;
        while (p < end && *p != '\n' && *p != '\r' && *p != '\0') p++;
    }

    if (count == 0) {
        if (out_error) *out_error = strdup("Schema v2 has no column definitions");
        return false;
    }

    ili_column_descriptor *cols = (ili_column_descriptor *)calloc((size_t)count, sizeof(ili_column_descriptor));
    if (!cols) {
        if (out_error) *out_error = strdup("Out of memory allocating column descriptors");
        return false;
    }

    p = tsv;
    for (int i = 0; i < count; i++) {
        skip_newlines(&p, end);
        if (p >= end) break;

        // Parse 7 fields: name, kind, encoding, nullable, geom_kind, crs_auth, crs_code
        char *fields[7] = {NULL};
        for (int f = 0; f < 7; f++) {
            fields[f] = parse_tsv_field(&p, end);
        }

        // Advance to next line
        skip_newlines(&p, end);

        // Validate minimum fields
        if (!fields[0] || !fields[1] || !fields[2]) {
            if (out_error) {
                size_t len = 128;
                *out_error = (char *)malloc(len);
                if (*out_error) {
                    snprintf(*out_error, len, "Schema v2 row %d: missing required fields", i + 1);
                }
            }
            for (int f = 0; f < 7; f++) free(fields[f]);
            ili_typed_schema_free(cols, i);
            return false;
        }

        ili_column_kind kind = parse_kind(fields[1], out_error);
        ili_wire_encoding enc = parse_encoding(fields[2], out_error);

        // Validate kind matches encoding
        if (kind == ILI_COLUMN_GEOMETRY && fields[1] && strcmp(fields[1], "GEOMETRY") == 0) {
            if (out_error && *out_error) {
                // parse_kind already set an error for unknown kind
                for (int f = 0; f < 7; f++) free(fields[f]);
                ili_typed_schema_free(cols, i);
                return false;
            }
        }
        if (kind == ILI_COLUMN_VARCHAR && fields[1] && strcmp(fields[1], "VARCHAR") != 0) {
            // Unknown kind — parse_kind already set error
            for (int f = 0; f < 7; f++) free(fields[f]);
            ili_typed_schema_free(cols, i);
            return false;
        }

        cols[i].name = fields[0];
        cols[i].kind = kind;
        cols[i].encoding = enc;
        cols[i].nullable = parse_bool(fields[3]);
        cols[i].geometry_kind = fields[4] && fields[4][0] != '\0' ? fields[4] : NULL;
        cols[i].crs_auth_name = fields[5] && fields[5][0] != '\0' ? fields[5] : NULL;
        cols[i].crs_code = fields[6] && fields[6][0] != '\0' ? fields[6] : NULL;

        // Free unused field pointers
        if (fields[4] != cols[i].geometry_kind) free(fields[4]);
        if (fields[5] != cols[i].crs_auth_name) free(fields[5]);
        if (fields[6] != cols[i].crs_code) free(fields[6]);
    }

    *out_cols = cols;
    *out_count = count;
    return true;
}

void ili_typed_schema_free(ili_column_descriptor *cols, int count) {
    if (!cols) return;
    for (int i = 0; i < count; i++) {
        free(cols[i].name);
        free(cols[i].geometry_kind);
        free(cols[i].crs_auth_name);
        free(cols[i].crs_code);
    }
    free(cols);
}
