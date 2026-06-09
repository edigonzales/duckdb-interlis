#include "ili_tsv.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

static int g_failures = 0;

#define TEST(name) static void name(void)
#define ASSERT(cond) do { if (!(cond)) { fprintf(stderr, "FAIL: %s:%d: %s\n", __FILE__, __LINE__, #cond); g_failures++; } } while(0)

// ---------------------------------------------------------------------------
// ili_tsv_next_field tests
// ---------------------------------------------------------------------------

TEST(test_plain_text) {
    const char *data = "hello";
    const char *cursor = data;
    const char *end = data + strlen(data);
    ili_tsv_field field;

    bool ok = ili_tsv_next_field(&cursor, end, &field);
    ASSERT(ok);
    ASSERT(!field.is_null);
    ASSERT(field.length == 5);
    ASSERT(strncmp(field.data, "hello", 5) == 0);
}

TEST(test_empty_field) {
    const char *data = "\t";
    const char *cursor = data;
    const char *end = data + strlen(data);
    ili_tsv_field field;

    bool ok = ili_tsv_next_field(&cursor, end, &field);
    ASSERT(ok);
    ASSERT(!field.is_null);
    ASSERT(field.length == 0);
}

TEST(test_null_sentinel) {
    const char *data = "\\N";
    const char *cursor = data;
    const char *end = data + strlen(data);
    ili_tsv_field field;

    bool ok = ili_tsv_next_field(&cursor, end, &field);
    ASSERT(ok);
    ASSERT(field.is_null);
    ASSERT(field.length == 2);
}

TEST(test_multiple_fields) {
    const char *data = "a\tb\tc";
    const char *cursor = data;
    const char *end = data + strlen(data);
    ili_tsv_field field;

    ASSERT(ili_tsv_next_field(&cursor, end, &field));
    ASSERT(field.length == 1 && field.data[0] == 'a');

    ASSERT(ili_tsv_next_field(&cursor, end, &field));
    ASSERT(field.length == 1 && field.data[0] == 'b');

    ASSERT(ili_tsv_next_field(&cursor, end, &field));
    ASSERT(field.length == 1 && field.data[0] == 'c');
}

// ---------------------------------------------------------------------------
// ili_tsv_unescape_copy tests
// ---------------------------------------------------------------------------

TEST(test_unescape_tab) {
    ili_tsv_field field = {.data = "a\\tb", .length = 4, .is_null = false};
    char *decoded = ili_tsv_unescape_copy(&field);
    ASSERT(decoded != NULL);
    ASSERT(strcmp(decoded, "a\tb") == 0);
    free(decoded);
}

TEST(test_unescape_newline) {
    ili_tsv_field field = {.data = "a\\nb", .length = 4, .is_null = false};
    char *decoded = ili_tsv_unescape_copy(&field);
    ASSERT(decoded != NULL);
    ASSERT(strcmp(decoded, "a\nb") == 0);
    free(decoded);
}

TEST(test_unescape_carriage_return) {
    ili_tsv_field field = {.data = "a\\rb", .length = 4, .is_null = false};
    char *decoded = ili_tsv_unescape_copy(&field);
    ASSERT(decoded != NULL);
    ASSERT(strcmp(decoded, "a\rb") == 0);
    free(decoded);
}

TEST(test_unescape_backslash) {
    ili_tsv_field field = {.data = "a\\\\b", .length = 4, .is_null = false};
    char *decoded = ili_tsv_unescape_copy(&field);
    ASSERT(decoded != NULL);
    ASSERT(strcmp(decoded, "a\\b") == 0);
    free(decoded);
}

TEST(test_literal_backslash_n) {
    ili_tsv_field field = {.data = "\\\\N", .length = 3, .is_null = false};
    char *decoded = ili_tsv_unescape_copy(&field);
    ASSERT(decoded != NULL);
    ASSERT(strcmp(decoded, "\\N") == 0);
    free(decoded);
}

TEST(test_unescape_null_field) {
    ili_tsv_field field = {.data = "x", .length = 1, .is_null = true};
    char *decoded = ili_tsv_unescape_copy(&field);
    ASSERT(decoded == NULL);
}

TEST(test_unescape_unicode_utf8) {
    ili_tsv_field field = {.data = "H\xc3\xb6he", .length = 5, .is_null = false};
    char *decoded = ili_tsv_unescape_copy(&field);
    ASSERT(decoded != NULL);
    ASSERT(strcmp(decoded, "H\xc3\xb6he") == 0);
    free(decoded);
}

// ---------------------------------------------------------------------------
// ili_tsv_reader tests (trailing empty fields)
// ---------------------------------------------------------------------------

TEST(test_reader_trailing_empty) {
    ili_tsv_reader reader;
    const char *data = "a\tb\t";
    ili_tsv_reader_init(&reader, data, strlen(data));
    ili_tsv_field field;

    ASSERT(ili_tsv_reader_next(&reader, &field));
    ASSERT(field.length == 1 && field.data[0] == 'a');

    ASSERT(ili_tsv_reader_next(&reader, &field));
    ASSERT(field.length == 1 && field.data[0] == 'b');

    ASSERT(ili_tsv_reader_next(&reader, &field));
    ASSERT(field.length == 0);
    ASSERT(!field.is_null);

    ASSERT(!ili_tsv_reader_next(&reader, &field));
}

TEST(test_reader_simple) {
    ili_tsv_reader reader;
    const char *data = "hello\tworld";
    ili_tsv_reader_init(&reader, data, strlen(data));
    ili_tsv_field field;

    ASSERT(ili_tsv_reader_next(&reader, &field));
    ASSERT(field.length == 5 && strncmp(field.data, "hello", 5) == 0);

    ASSERT(ili_tsv_reader_next(&reader, &field));
    ASSERT(field.length == 5 && strncmp(field.data, "world", 5) == 0);

    ASSERT(!ili_tsv_reader_next(&reader, &field));
}

// ---------------------------------------------------------------------------
// ili_tsv_parse_nullable_int32 tests
// ---------------------------------------------------------------------------

TEST(test_int32_valid) {
    ili_tsv_field field = {.data = "42", .length = 2, .is_null = false};
    int32_t val = 0;
    ili_tsv_int_status s = ili_tsv_parse_nullable_int32(&field, &val);
    ASSERT(s == ILI_TSV_INT_OK);
    ASSERT(val == 42);
}

TEST(test_int32_null) {
    ili_tsv_field field = {.data = "\\N", .length = 2, .is_null = true};
    int32_t val = 999;
    ili_tsv_int_status s = ili_tsv_parse_nullable_int32(&field, &val);
    ASSERT(s == ILI_TSV_INT_NULL);
}

TEST(test_int32_empty) {
    ili_tsv_field field = {.data = "", .length = 0, .is_null = false};
    int32_t val = 0;
    ili_tsv_int_status s = ili_tsv_parse_nullable_int32(&field, &val);
    ASSERT(s == ILI_TSV_INT_INVALID);
}

TEST(test_int32_negative) {
    ili_tsv_field field = {.data = "-7", .length = 2, .is_null = false};
    int32_t val = 0;
    ili_tsv_int_status s = ili_tsv_parse_nullable_int32(&field, &val);
    ASSERT(s == ILI_TSV_INT_OK);
    ASSERT(val == -7);
}

TEST(test_int32_garbage) {
    ili_tsv_field field = {.data = "abc", .length = 3, .is_null = false};
    int32_t val = 0;
    ili_tsv_int_status s = ili_tsv_parse_nullable_int32(&field, &val);
    ASSERT(s == ILI_TSV_INT_INVALID);
}

TEST(test_int32_overflow_positive) {
    ili_tsv_field field = {.data = "9999999999", .length = 10, .is_null = false};
    int32_t val = 0;
    ili_tsv_int_status s = ili_tsv_parse_nullable_int32(&field, &val);
    ASSERT(s == ILI_TSV_INT_INVALID);
}

TEST(test_int32_overflow_negative) {
    ili_tsv_field field = {.data = "-9999999999", .length = 11, .is_null = false};
    int32_t val = 0;
    ili_tsv_int_status s = ili_tsv_parse_nullable_int32(&field, &val);
    ASSERT(s == ILI_TSV_INT_INVALID);
}

TEST(test_int32_zero) {
    ili_tsv_field field = {.data = "0", .length = 1, .is_null = false};
    int32_t val = -1;
    ili_tsv_int_status s = ili_tsv_parse_nullable_int32(&field, &val);
    ASSERT(s == ILI_TSV_INT_OK);
    ASSERT(val == 0);
}

// ---------------------------------------------------------------------------

int main(void) {
    test_plain_text();
    test_empty_field();
    test_null_sentinel();
    test_multiple_fields();
    test_unescape_tab();
    test_unescape_newline();
    test_unescape_carriage_return();
    test_unescape_backslash();
    test_literal_backslash_n();
    test_unescape_null_field();
    test_unescape_unicode_utf8();
    test_reader_trailing_empty();
    test_reader_simple();
    test_int32_valid();
    test_int32_null();
    test_int32_empty();
    test_int32_negative();
    test_int32_garbage();
    test_int32_overflow_positive();
    test_int32_overflow_negative();
    test_int32_zero();

    if (g_failures == 0) {
        printf("All TSV tests passed.\n");
        return 0;
    } else {
        printf("%d TSV test(s) FAILED.\n", g_failures);
        return 1;
    }
}
