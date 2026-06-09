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

typedef struct {
    const char *cursor;
    const char *end;
    bool pending_trailing_empty;
} ili_tsv_reader;

void ili_tsv_reader_init(
    ili_tsv_reader *reader,
    const char *data,
    size_t length
);

bool ili_tsv_reader_next(
    ili_tsv_reader *reader,
    ili_tsv_field *out
);

typedef enum {
    ILI_TSV_INT_OK = 0,
    ILI_TSV_INT_NULL = 1,
    ILI_TSV_INT_INVALID = 2
} ili_tsv_int_status;

bool ili_tsv_next_field(
    const char **cursor,
    const char *end,
    ili_tsv_field *out
);

char *ili_tsv_unescape_copy(
    const ili_tsv_field *field
);

ili_tsv_int_status ili_tsv_parse_nullable_int32(
    const ili_tsv_field *field,
    int32_t *out_value
);

#ifdef __cplusplus
}
#endif

#endif
