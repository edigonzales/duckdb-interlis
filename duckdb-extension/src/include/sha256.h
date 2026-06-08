#ifndef ILI_SHA256_H
#define ILI_SHA256_H

#include <stddef.h>
#include <stdint.h>

#define ILI_SHA256_HEX_SIZE 65

int sha256_file(const char *path, char out_hex[ILI_SHA256_HEX_SIZE]);
void sha256_buffer(const uint8_t *data, size_t len, char out_hex[ILI_SHA256_HEX_SIZE]);

#endif
