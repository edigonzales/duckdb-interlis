#ifndef ILI_REQUEST_H
#define ILI_REQUEST_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * ili_request - Unified request struct for all native API calls.
 *
 * Field semantics:
 *   NULL pointer  = field not provided
 *   ""            = field provided but empty string
 *
 * Fields map to specific operations:
 *
 *   Operation            | input | modeldir | cmd | class_name | models | model | association | schema | nested | mapping | max_messages
 *   ---------------------+-------+----------+-----+------------+--------+-------+-------------+--------+--------+---------+-------------
 *   validate             |   X   |    X     |     |            |        |       |             |        |        |         |     X
 *   validate_tsv         |   X   |    X     |     |            |        |       |             |        |        |         |     X
 *   model_info           |       |    X     |  X  |     X      |        |   X   |             |        |        |         |
 *   read_xtf             |   X   |    X     |     |            |   X    |       |             |        |        |         |
 *   read_xtf_class       |   X   |    X     |     |     X      |        |       |             |        |   X    |         |
 *   read_xtf_class_schema|       |    X     |     |     X      |        |       |             |        |   X    |         |
 *   read_xtf_structures  |       |    X     |     |     X      |        |       |             |        |        |         |
 *   read_xtf_association |   X   |    X     |     |            |        |       |      X      |        |        |         |
 *   read_xtf_assoc_schema|       |    X     |     |            |        |       |      X      |        |        |         |
 *   import_xtf           |   X   |    X     |     |            |        |       |             |    X   |        |    X    |
 *
 * The struct_size field MUST be set to sizeof(ili_request) by the caller.
 * It allows forward-compatible ABI version detection.
 */
typedef struct ili_request {
    uint32_t struct_size;
    const char *input;
    const char *modeldir;
    const char *cmd;
    const char *class_name;
    const char *models;
    const char *model;
    const char *association;
    const char *schema;
    const char *nested;
    const char *mapping;
    int32_t max_messages;
} ili_request;

#ifdef __cplusplus
}
#endif

#endif
