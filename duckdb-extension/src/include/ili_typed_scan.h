#ifndef ILI_TYPED_SCAN_H
#define ILI_TYPED_SCAN_H

#include "duckdb_extension.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Typed class scan callbacks for read_xtf_class (v2).
 *
 * These replace the generic VARCHAR-only path when the native library
 * supports ILI_CAP_TYPED_CLASS_SCAN.
 *
 * The schema-v2 protocol provides per-column type information:
 *   - VARCHAR columns → DUCKDB_TYPE_VARCHAR
 *   - GEOMETRY columns → DUCKDB_TYPE_GEOMETRY (binary WKB via string_t)
 */

/*
 * Bind callback: resolves the schema v2 from the native library and
 * registers typed result columns.
 */
void xtf_class_typed_bind(duckdb_bind_info info);

/*
 * Init callback: calls the native v2 reader and materializes all rows.
 */
void xtf_class_typed_init(duckdb_init_info info);

/*
 * Function callback: writes chunks using per-column dispatch
 * (TEXT → VARCHAR assignment, HEX_WKB → GEOMETRY assignment).
 */
void xtf_class_typed_function(duckdb_function_info tfinfo, duckdb_data_chunk output);

#ifdef __cplusplus
}
#endif

#endif
