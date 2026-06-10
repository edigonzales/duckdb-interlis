#ifndef INTERLIS_GEOMETRY_H
#define INTERLIS_GEOMETRY_H

#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Converts WKT text to a DuckDB GEOMETRY internal buffer.
 *
 * The caller must free *out_data with interlis_geometry_free_buffer().
 * Returns true on success, false on failure (error message in *out_error).
 */
bool interlis_geometry_from_wkt(
    const char *wkt_data, size_t wkt_size,
    char **out_data, size_t *out_size,
    char **out_error);

/*
 * Converts WKB binary to a DuckDB GEOMETRY internal buffer.
 *
 * The caller must free *out_data with interlis_geometry_free_buffer().
 * Returns true on success, false on failure (error message in *out_error).
 */
bool interlis_geometry_from_wkb(
    const char *wkb_data, size_t wkb_size,
    char **out_data, size_t *out_size,
    char **out_error);

/*
 * Frees a buffer allocated by interlis_geometry_from_wkt or _from_wkb.
 */
void interlis_geometry_free_buffer(char *data);

#ifdef __cplusplus
}
#endif

#endif /* INTERLIS_GEOMETRY_H */
