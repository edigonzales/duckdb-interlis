#include "ili_tsv.h"
#include <stdlib.h>
#include <string.h>

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

bool ili_tsv_parse_nullable_int32(
    const ili_tsv_field *field,
    int32_t *out_value
) {
    if (!field || !out_value) return false;
    if (field->is_null) {
        *out_value = 0;
        return false;
    }

    const char *s = field->data;
    const char *end = s + field->length;
    int32_t val = 0;
    bool neg = false;

    if (s < end && *s == '-') {
        neg = true;
        s++;
    }

    if (s >= end) return false;

    while (s < end && *s >= '0' && *s <= '9') {
        int32_t digit = *s - '0';
        if (neg) {
            if (val < (INT32_MIN + digit) / 10) return false;
            val = val * 10 - digit;
        } else {
            if (val > (INT32_MAX - digit) / 10) return false;
            val = val * 10 + digit;
        }
        s++;
    }

    if (s != end) return false;

    *out_value = val;
    return true;
}
