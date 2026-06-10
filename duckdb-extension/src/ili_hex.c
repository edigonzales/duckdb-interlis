#include "ili_hex.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

static int hex_digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
    if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
    return -1;
}

static bool is_hex_digit(char c) {
    return hex_digit(c) >= 0;
}

bool ili_hex_validate(const char *hex, size_t hex_len, char **out_error) {
    if (out_error) *out_error = NULL;

    if (!hex) {
        if (out_error) *out_error = strdup("Hex input is NULL");
        return false;
    }

    if (hex_len == 0) {
        if (out_error) *out_error = strdup("Hex input is empty (zero-length WKB is invalid)");
        return false;
    }

    // Check for overflow: hex_len / 2 must not overflow
    if (hex_len > SIZE_MAX / 2) {
        if (out_error) {
            size_t mlen = 128;
            *out_error = (char *)malloc(mlen);
            if (*out_error) {
                snprintf(*out_error, mlen,
                    "Hex input too long: %zu bytes (would overflow binary buffer)", hex_len);
            }
        }
        return false;
    }

    // Check even length
    if (hex_len % 2 != 0) {
        if (out_error) {
            size_t mlen = 128;
            *out_error = (char *)malloc(mlen);
            if (*out_error) {
                snprintf(*out_error, mlen,
                    "Hex string has odd length: %zu (must be even)", hex_len);
            }
        }
        return false;
    }

    // Check all characters are valid hex digits
    for (size_t i = 0; i < hex_len; i++) {
        if (!is_hex_digit(hex[i])) {
            if (out_error) {
                size_t mlen = 256;
                *out_error = (char *)malloc(mlen);
                if (*out_error) {
                    snprintf(*out_error, mlen,
                        "Invalid hex character at position %zu: '\\x%02X' (expected [0-9A-Fa-f])",
                        i, (unsigned char)hex[i]);
                }
            }
            return false;
        }
    }

    return true;
}

bool ili_hex_decode(const char *hex, size_t hex_len,
                    uint8_t **out_buf, size_t *out_len,
                    char **out_error)
{
    *out_buf = NULL;
    *out_len = 0;
    if (out_error) *out_error = NULL;

    // Validate first (also checks for NULL, empty, odd length, overflow, invalid chars)
    char *verr = NULL;
    if (!ili_hex_validate(hex, hex_len, &verr)) {
        if (out_error) *out_error = verr;
        else free(verr);
        return false;
    }

    size_t bin_len = hex_len / 2;
    uint8_t *buf = (uint8_t *)malloc(bin_len);
    if (!buf) {
        if (out_error) *out_error = strdup("Out of memory allocating hex decode buffer");
        return false;
    }

    for (size_t i = 0; i < bin_len; i++) {
        int hi = hex_digit(hex[i * 2]);
        int lo = hex_digit(hex[i * 2 + 1]);
        buf[i] = (uint8_t)((hi << 4) | lo);
    }

    *out_buf = buf;
    *out_len = bin_len;
    return true;
}
