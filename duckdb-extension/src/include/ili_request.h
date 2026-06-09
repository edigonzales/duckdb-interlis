#ifndef ILI_REQUEST_H
#define ILI_REQUEST_H

#include <stdint.h>
#include "graal_isolate_dynamic.h"

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
 *   Operation            | input | modeldir | cmd | class_name | models | model | association | schema | nested | mapping | max_messages | profile | mode
 *   ---------------------+-------+----------+-----+------------+--------+-------+-------------+--------+--------+---------+--------------+---------+-----
 *   validate             |   X   |    X     |     |            |        |       |             |        |        |         |     X     |    X    |
 *   validate_tsv         |   X   |    X     |     |            |        |       |             |        |        |         |     X     |    X    |
 *   model_info           |       |    X     |  X  |     X      |        |   X   |             |        |        |         |
 *   read_xtf             |   X   |    X     |     |            |   X    |       |             |        |        |         |
 *   read_xtf_class       |   X   |    X     |     |     X      |        |       |             |        |   X    |         |
 *   read_xtf_class_schema|       |    X     |     |     X      |        |       |             |        |   X    |         |
 *   read_xtf_structures  |       |    X     |     |     X      |        |       |             |        |        |         |
 *   read_xtf_association |   X   |    X     |     |            |        |       |      X      |        |        |         |
 *   read_xtf_assoc_schema|       |    X     |     |            |        |       |      X      |        |        |         |
 *   import_xtf           |   X   |    X     |     |            |        |       |             |    X   |        |    X    |              |    X  |
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
    const char *profile;
    const char *mode;
} ili_request;

/*
 * ABI Negotiation
 */

#define ILI_NATIVE_ABI_VERSION 1

/* Capability bits */
#define ILI_CAP_VERSION               (1ULL << 0)
#define ILI_CAP_VALIDATE              (1ULL << 1)
#define ILI_CAP_VALIDATE_TSV          (1ULL << 2)
#define ILI_CAP_MODEL_INFO            (1ULL << 3)
#define ILI_CAP_READ_XTF              (1ULL << 4)
#define ILI_CAP_READ_XTF_CLASS        (1ULL << 5)
#define ILI_CAP_READ_XTF_CLASS_SCHEMA (1ULL << 6)
#define ILI_CAP_READ_XTF_STRUCTURES   (1ULL << 7)
#define ILI_CAP_READ_XTF_ASSOCIATION  (1ULL << 8)
#define ILI_CAP_READ_XTF_ASSOC_SCHEMA (1ULL << 9)
#define ILI_CAP_IMPORT_XTF            (1ULL << 10)
#define ILI_CAP_FREE_STRING           (1ULL << 11)

/*
 * All capabilities required for the core ILI extension functionality.
 * ABI v1 treats all 12 public API functions as mandatory.
 * Bits 0-11 must all be present.
 */
#define ILI_CAP_REQUIRED_MASK \
    (ILI_CAP_VERSION | ILI_CAP_VALIDATE | ILI_CAP_VALIDATE_TSV | \
     ILI_CAP_MODEL_INFO | ILI_CAP_READ_XTF | ILI_CAP_READ_XTF_CLASS | \
     ILI_CAP_READ_XTF_CLASS_SCHEMA | ILI_CAP_READ_XTF_STRUCTURES | \
     ILI_CAP_READ_XTF_ASSOCIATION | ILI_CAP_READ_XTF_ASSOC_SCHEMA | \
     ILI_CAP_IMPORT_XTF | ILI_CAP_FREE_STRING)

/*
 * Expected struct sizes for ABI validation.
 * Used by both C extension and Java NativeRequestValidator.
 */
#define ILI_REQUEST_STRUCT_SIZE 112  /* sizeof(ili_request) on LP64 */

#ifdef __cplusplus
static_assert(sizeof(ili_request) == ILI_REQUEST_STRUCT_SIZE,
    "ILI_REQUEST_STRUCT_SIZE does not match sizeof(ili_request)");
#else
_Static_assert(sizeof(ili_request) == ILI_REQUEST_STRUCT_SIZE,
    "ILI_REQUEST_STRUCT_SIZE does not match sizeof(ili_request)");
#endif

typedef struct ili_api_v1 {
    uint32_t struct_size;
    uint32_t abi_version;
    uint64_t capabilities;
} ili_api_v1;

int ili_get_api(graal_isolatethread_t *thread, uint32_t requested_abi_version, char **out_payload);

#ifdef __cplusplus
}
#endif

#endif
