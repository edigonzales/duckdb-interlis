#include "ili_duckdb_tsv.h"
#include <ctype.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

DUCKDB_EXTENSION_EXTERN

static bool ili_assign_null(duckdb_vector vector, idx_t row) {
    duckdb_vector_ensure_validity_writable(vector);
    uint64_t *validity = duckdb_vector_get_validity(vector);
    duckdb_validity_set_row_invalid(validity, row);
    return true;
}

static bool copy_field_string(const ili_tsv_field *field, char **out) {
    if (!field || !out || field->is_null) return false;
    *out = ili_tsv_unescape_copy(field);
    return *out != NULL;
}

static bool parse_int64_value(const char *text, int64_t *out) {
    if (!text || !*text || !out) return false;
    errno = 0;
    char *end = NULL;
    long long value = strtoll(text, &end, 10);
    if (errno != 0 || !end || *end != '\0') return false;
    *out = (int64_t)value;
    return true;
}

static bool parse_double_value(const char *text, double *out) {
    if (!text || !*text || !out) return false;
    errno = 0;
    char *end = NULL;
    double value = strtod(text, &end);
    if (errno != 0 || !end || *end != '\0') return false;
    *out = value;
    return true;
}

static bool parse_boolean_value(const char *text, bool *out) {
    if (!text || !out) return false;
    if (strcmp(text, "true") == 0 || strcmp(text, "TRUE") == 0 || strcmp(text, "1") == 0) {
        *out = true;
        return true;
    }
    if (strcmp(text, "false") == 0 || strcmp(text, "FALSE") == 0 || strcmp(text, "0") == 0) {
        *out = false;
        return true;
    }
    return false;
}

static bool parse_n_digits(const char **cursor, int count, int *out) {
    if (!cursor || !*cursor || !out) return false;
    int value = 0;
    for (int i = 0; i < count; i++) {
        char ch = (*cursor)[i];
        if (!isdigit((unsigned char)ch)) return false;
        value = (value * 10) + (ch - '0');
    }
    *cursor += count;
    *out = value;
    return true;
}

static bool parse_fractional_micros(const char **cursor, int32_t *out_micros) {
    if (!cursor || !*cursor || !out_micros) return false;
    const char *p = *cursor;
    int digits = 0;
    int32_t micros = 0;
    while (*p && isdigit((unsigned char)*p) && digits < 6) {
        micros = (micros * 10) + (*p - '0');
        p++;
        digits++;
    }
    while (*p && isdigit((unsigned char)*p)) {
        p++;
        digits++;
    }
    if (digits == 0) return false;
    while (digits < 6) {
        micros *= 10;
        digits++;
    }
    *cursor = p;
    *out_micros = micros;
    return true;
}

static bool parse_date_text(const char *text, duckdb_date_struct *out) {
    if (!text || !out) return false;
    const char *p = text;
    int year = 0, month = 0, day = 0;
    if (!parse_n_digits(&p, 4, &year) || *p++ != '-'
            || !parse_n_digits(&p, 2, &month) || *p++ != '-'
            || !parse_n_digits(&p, 2, &day) || *p != '\0') {
        return false;
    }
    if (month < 1 || month > 12 || day < 1 || day > 31) return false;
    out->year = year;
    out->month = (int8_t)month;
    out->day = (int8_t)day;
    return true;
}

static bool parse_time_text(const char *text, duckdb_time_struct *out, int32_t *out_tz_offset_minutes) {
    if (!text || !out) return false;
    const char *p = text;
    int hour = 0, min = 0, sec = 0;
    int32_t micros = 0;
    int32_t tz_offset_minutes = 0;

    if (!parse_n_digits(&p, 2, &hour) || *p++ != ':'
            || !parse_n_digits(&p, 2, &min) || *p++ != ':'
            || !parse_n_digits(&p, 2, &sec)) {
        return false;
    }
    if (hour < 0 || hour > 23 || min < 0 || min > 59 || sec < 0 || sec > 59) return false;

    if (*p == '.') {
        p++;
        if (!parse_fractional_micros(&p, &micros)) return false;
    }
    if (*p == 'Z') {
        p++;
    } else if (*p == '+' || *p == '-') {
        int sign = (*p == '-') ? -1 : 1;
        int off_hour = 0;
        int off_min = 0;
        p++;
        if (!parse_n_digits(&p, 2, &off_hour)) return false;
        if (*p == ':') p++;
        if (!parse_n_digits(&p, 2, &off_min)) return false;
        tz_offset_minutes = sign * (off_hour * 60 + off_min);
    }
    if (*p != '\0') return false;

    out->hour = (int8_t)hour;
    out->min = (int8_t)min;
    out->sec = (int8_t)sec;
    out->micros = micros;
    if (out_tz_offset_minutes) *out_tz_offset_minutes = tz_offset_minutes;
    return true;
}

static bool parse_timestamp_text(const char *text, duckdb_timestamp *out) {
    if (!text || !out) return false;

    const char *sep = strchr(text, 'T');
    if (!sep) sep = strchr(text, ' ');
    if (!sep) return false;

    size_t date_len = (size_t)(sep - text);
    char *date_part = (char *)malloc(date_len + 1);
    if (!date_part) return false;
    memcpy(date_part, text, date_len);
    date_part[date_len] = '\0';

    duckdb_date_struct date_struct;
    duckdb_time_struct time_struct;
    int32_t tz_offset_minutes = 0;
    bool ok = parse_date_text(date_part, &date_struct)
            && parse_time_text(sep + 1, &time_struct, &tz_offset_minutes);
    free(date_part);
    if (!ok) return false;

    duckdb_timestamp_struct timestamp_struct;
    timestamp_struct.date = date_struct;
    timestamp_struct.time = time_struct;
    duckdb_timestamp ts = duckdb_to_timestamp(timestamp_struct);
    if (tz_offset_minutes != 0) {
        ts.micros -= ((int64_t)tz_offset_minutes) * 60 * 1000000;
    }
    *out = ts;
    return true;
}

bool ili_tsv_assign_varchar(
        duckdb_vector vector,
        idx_t row,
        const ili_tsv_field *field) {

    if (!vector || !field) {
        return false;
    }

    if (field->is_null) {
        return ili_assign_null(vector, row);
    }

    char *decoded = ili_tsv_unescape_copy(field);
    if (!decoded) {
        return false;
    }

    duckdb_vector_assign_string_element(vector, row, decoded);

    free(decoded);
    return true;
}

bool ili_tsv_assign_bigint(
        duckdb_vector vector,
        idx_t row,
        const ili_tsv_field *field) {
    if (!vector || !field) return false;
    if (field->is_null) return ili_assign_null(vector, row);

    char *decoded = NULL;
    if (!copy_field_string(field, &decoded)) return false;
    int64_t value = 0;
    bool ok = parse_int64_value(decoded, &value);
    free(decoded);
    if (!ok) return false;

    int64_t *data = (int64_t *)duckdb_vector_get_data(vector);
    data[row] = value;
    return true;
}

bool ili_tsv_assign_int32(
        duckdb_vector vector,
        idx_t row,
        const ili_tsv_field *field) {
    if (!vector || !field) return false;
    if (field->is_null) return ili_assign_null(vector, row);

    int32_t value = 0;
    ili_tsv_int_status status = ili_tsv_parse_nullable_int32(field, &value);
    if (status != ILI_TSV_INT_OK) return false;

    int32_t *data = (int32_t *)duckdb_vector_get_data(vector);
    data[row] = value;
    return true;
}

bool ili_tsv_assign_double(
        duckdb_vector vector,
        idx_t row,
        const ili_tsv_field *field) {
    if (!vector || !field) return false;
    if (field->is_null) return ili_assign_null(vector, row);

    char *decoded = NULL;
    if (!copy_field_string(field, &decoded)) return false;
    double value = 0;
    bool ok = parse_double_value(decoded, &value);
    free(decoded);
    if (!ok) return false;

    double *data = (double *)duckdb_vector_get_data(vector);
    data[row] = value;
    return true;
}

bool ili_tsv_assign_boolean(
        duckdb_vector vector,
        idx_t row,
        const ili_tsv_field *field) {
    if (!vector || !field) return false;
    if (field->is_null) return ili_assign_null(vector, row);

    char *decoded = NULL;
    if (!copy_field_string(field, &decoded)) return false;
    bool value = false;
    bool ok = parse_boolean_value(decoded, &value);
    free(decoded);
    if (!ok) return false;

    bool *data = (bool *)duckdb_vector_get_data(vector);
    data[row] = value;
    return true;
}

bool ili_tsv_assign_date(
        duckdb_vector vector,
        idx_t row,
        const ili_tsv_field *field) {
    if (!vector || !field) return false;
    if (field->is_null) return ili_assign_null(vector, row);

    char *decoded = NULL;
    if (!copy_field_string(field, &decoded)) return false;
    duckdb_date_struct date_struct;
    bool ok = parse_date_text(decoded, &date_struct);
    free(decoded);
    if (!ok) return false;

    duckdb_date *data = (duckdb_date *)duckdb_vector_get_data(vector);
    data[row] = duckdb_to_date(date_struct);
    return true;
}

bool ili_tsv_assign_time(
        duckdb_vector vector,
        idx_t row,
        const ili_tsv_field *field) {
    if (!vector || !field) return false;
    if (field->is_null) return ili_assign_null(vector, row);

    char *decoded = NULL;
    if (!copy_field_string(field, &decoded)) return false;
    duckdb_time_struct time_struct;
    int32_t tz_offset_minutes = 0;
    bool ok = parse_time_text(decoded, &time_struct, &tz_offset_minutes);
    free(decoded);
    if (!ok || tz_offset_minutes != 0) return false;

    duckdb_time *data = (duckdb_time *)duckdb_vector_get_data(vector);
    data[row] = duckdb_to_time(time_struct);
    return true;
}

bool ili_tsv_assign_timestamp(
        duckdb_vector vector,
        idx_t row,
        const ili_tsv_field *field) {
    if (!vector || !field) return false;
    if (field->is_null) return ili_assign_null(vector, row);

    char *decoded = NULL;
    if (!copy_field_string(field, &decoded)) return false;
    duckdb_timestamp value;
    bool ok = parse_timestamp_text(decoded, &value);
    free(decoded);
    if (!ok) return false;

    duckdb_timestamp *data = (duckdb_timestamp *)duckdb_vector_get_data(vector);
    data[row] = value;
    return true;
}
