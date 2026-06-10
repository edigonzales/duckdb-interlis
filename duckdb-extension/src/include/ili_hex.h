#ifndef ILI_HEX_H
#define ILI_HEX_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Decode a hex string into binary bytes.
 *
 * Supports uppercase and lowercase hex digits [0-9A-Fa-f].
 * Input must have even length.
 *
 * On success, *out_buf is malloc'd and *out_len is set.
 * The caller MUST free *out_buf when done.
 *
 * On failure, *out_error contains a description (caller must free).
 * Returns false on failure, true on success.
 */
bool ili_hex_decode(const char *hex, size_t hex_len,
                    uint8_t **out_buf, size_t *out_len,
                    char **out_error);

/*
 * Validate a hex string without decoding.
 * Checks: non-null, even length, only valid hex characters, no overflow.
 *
 * Returns true if valid, false otherwise (with error in *out_error).
 */
bool ili_hex_validate(const char *hex, size_t hex_len, char **out_error);

#ifdef __cplusplus
}
#endif

#endif
