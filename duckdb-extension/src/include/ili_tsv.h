#ifndef ILI_TSV_H
#define ILI_TSV_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const char *data;
    size_t length;
    bool is_null;
} ili_tsv_field;

bool ili_tsv_next_field(
    const char **cursor,
    const char *end,
    ili_tsv_field *out
);

char *ili_tsv_unescape_copy(
    const ili_tsv_field *field
);

bool ili_tsv_parse_nullable_int32(
    const ili_tsv_field *field,
    int32_t *out_value
);

#ifdef __cplusplus
}
#endif

#endif
