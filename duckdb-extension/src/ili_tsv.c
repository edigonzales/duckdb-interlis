#include "ili_tsv.h"
#include <stdlib.h>
#include <string.h>

void ili_tsv_reader_init(
        ili_tsv_reader *reader,
        const char *data,
        size_t length) {

    if (!reader || !data) return;
    reader->cursor = data;
    reader->end = data + length;
    reader->pending_trailing_empty = false;
}

bool ili_tsv_reader_next(
        ili_tsv_reader *reader,
        ili_tsv_field *out) {

    if (!reader || !out) {
        return false;
    }

    if (reader->pending_trailing_empty) {
        reader->pending_trailing_empty = false;
        out->data = reader->end;
        out->length = 0;
        out->is_null = false;
        return true;
    }

    if (reader->cursor >= reader->end) {
        return false;
    }

    const char *start = reader->cursor;
    const char *p = start;

    while (p < reader->end && *p != '\t') {
        p++;
    }

    out->data = start;
    out->length = (size_t)(p - start);
    out->is_null =
        out->length == 2
        && start[0] == '\\'
        && start[1] == 'N';

    if (p < reader->end && *p == '\t') {
        p++;
        if (p == reader->end) {
            reader->pending_trailing_empty = true;
        }
    }

    reader->cursor = p;
    return true;
}

bool ili_tsv_next_field(
    const char **cursor,
    const char *end,
    ili_tsv_field *out
) {
    if (!cursor || !*cursor || !end || !out) return false;
    if (*cursor >= end) return false;

    const char *start = *cursor;
    const char *p = start;

    while (p < end && *p != '\t' && *p != '\n') {
        p++;
    }

    out->data = start;
    out->length = (size_t)(p - start);
    out->is_null = (out->length == 2 && start[0] == '\\' && start[1] == 'N');

    if (p < end && *p == '\t') {
        p++;
    }
    *cursor = p;
    return true;
}

char *ili_tsv_unescape_copy(
    const ili_tsv_field *field
) {
    if (!field || field->is_null) return NULL;

    size_t len = field->length;
    char *result = malloc(len + 1);
    if (!result) return NULL;

    const char *s = field->data;
    const char *end = s + len;
    char *d = result;

    while (s < end) {
        if (*s == '\\' && (s + 1) < end) {
            switch (s[1]) {
                case 't': *d++ = '\t'; s += 2; break;
                case 'n': *d++ = '\n'; s += 2; break;
                case 'r': *d++ = '\r'; s += 2; break;
                case '\\': *d++ = '\\'; s += 2; break;
                default: *d++ = *s++; break;
            }
        } else {
            *d++ = *s++;
        }
    }
    *d = '\0';
    return result;
}

ili_tsv_int_status ili_tsv_parse_nullable_int32(
    const ili_tsv_field *field,
    int32_t *out_value
) {
    if (!field || !out_value) return ILI_TSV_INT_INVALID;
    if (field->is_null) {
        return ILI_TSV_INT_NULL;
    }

    if (field->length == 0) {
        return ILI_TSV_INT_INVALID;
    }

    const char *s = field->data;
    const char *end = s + field->length;
    int32_t val = 0;
    bool neg = false;

    if (s < end && *s == '-') {
        neg = true;
        s++;
    }

    if (s >= end) return ILI_TSV_INT_INVALID;

    while (s < end && *s >= '0' && *s <= '9') {
        int32_t digit = *s - '0';
        if (neg) {
            if (val < (INT32_MIN + digit) / 10) return ILI_TSV_INT_INVALID;
            val = val * 10 - digit;
        } else {
            if (val > (INT32_MAX - digit) / 10) return ILI_TSV_INT_INVALID;
            val = val * 10 + digit;
        }
        s++;
    }

    if (s != end) return ILI_TSV_INT_INVALID;

    *out_value = val;
    return ILI_TSV_INT_OK;
}
