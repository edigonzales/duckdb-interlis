# Native ABI Reference

> **Phase 0 Baseline Document.** Generated 2026-06-07.
> Documents the current native ABI between the DuckDB C extension and the GraalVM shared library.

## 1. Architecture

```
DuckDB C Extension (interlis_extension.c)
    │
    │ dlopen / dlsym
    ▼
GraalVM Shared Library (libduckdb_ili_native.{so,dylib,dll})
    │
    │ @CEntryPoint calls
    ▼
Java Core (ili-core)
```

The C extension dynamically loads the GraalVM-compiled shared library at first use. All communication uses C strings (null-terminated UTF-8).

## 2. Library Loading

### Platform Abstractions

| Platform | Loader | Symbol Lookup | Unloader | File Check |
|----------|--------|---------------|----------|------------|
| macOS | `dlopen(path, RTLD_LAZY)` | `dlsym()` | `dlclose()` | `access(F_OK)` |
| Linux | `dlopen(path, RTLD_LAZY)` | `dlsym()` | `dlclose()` | `access(F_OK)` |
| Windows | `LoadLibraryA(path)` | `GetProcAddress()` | `FreeLibrary()` | `GetFileAttributesA()` |

### Library Name by Platform

| Platform | Name |
|----------|------|
| macOS | `libduckdb_ili_native.dylib` |
| Linux | `libduckdb_ili_native.so` |
| Windows | `libduckdb_ili_native.dll` |

### Search Order

1. **Environment variable `DUCKDB_ILI_NATIVE_LIB`** (explicit development override).  
   When set, the path is used directly without hash verification.  
   This is intended for development only; productive builds should not rely on this variable.

2. **Hashed extension cache** (primary productive path):  
   `{HOME}/.duckdb/extensions/{ILI_ABI_VERSION}/{ILI_DUCKDB_PLATFORM}/{hashed_filename}`  
   
   The hashed filename format is:  
   `{EXTENSION_VERSION}_{ABI_VERSION}_{PLATFORM}_{SHA256_SHORT}_{LIB_NAME}`  
   
   Example: `0.1.0-dev_v1.2.0_osx_arm64_17fe5b2e0c13_libduckdb_ili_native.dylib`

3. **Dev fallback paths** (debug builds only):  
   `build/native/current/{LIB_NAME}`, `../java/ili-native/build/native/{LIB_NAME}`, `../../java/ili-native/build/native/{LIB_NAME}`  
   These paths are only active when compiled without `-DNDEBUG` (debug builds).  
   Release builds do not search these paths, preventing accidental use of local developer libraries.

### Extraction Process (Phase 8)

The embedded native library is extracted with full integrity guarantees:

1. **Temp file**: Written to `{cache_path}.tmp.{pid}` in the same directory to ensure filesystem-local atomic operations.
2. **Full write**: The entire `embedded_native_lib_data[]` blob is written.
3. **Flush and sync**: `fflush()` + `fsync()` (POSIX) or `FlushFileBuffers()` (Windows) ensures data reaches disk.
4. **Hash verification**: SHA-256 of the temp file is computed and compared against the build-time hash (`embedded_native_lib_hash`).  
   If the hash does not match, the temp file is deleted and an error is reported.
5. **Permissions**: `chmod 0755` is applied to the temp file (POSIX only).
6. **Atomic rename**: `rename()` (POSIX) atomically moves the temp file to the final cache path.  
   If another process concurrently extracted the same library, the existing file is verified and used.
7. **Cache reuse**: On subsequent loads, the cache file's SHA-256 is verified before use.  
   If verification fails (corrupted file, wrong hash), the file is deleted and re-extracted.
8. **Symlink rejection**: `lstat()` (POSIX) or reparse point check (Windows) rejects symlinks/junctions in the cache.  
   Symlinks are deleted and the library is re-extracted.
9. **Directory permissions**: Cache directory is created with `0700` permissions.
10. **Parallel safety**: Each process writes to its own temp file, then atomically renames.  
    Concurrent extraction by multiple processes is safe.

### Symbol Resolution

4 GraalVM lifecycle symbols + 12 API symbols are resolved with `dlsym`.
All 12 API symbols are validated (non-NULL checked) after ABI handshake.
Additionally, `ili_free_string` is validated before the handshake.

## 3. GraalVM Isolate Lifecycle (Phase 4)

### Initialization (Thread-Safe)

```
ensure_native_ready():
    ili_mutex_lock(&g_init_lock)
    if (g_init_done) → unlock, return true
    if (g_init_error) → unlock, return false (don't retry)
    
    init_native_library_locked():
        dlopen()
        dlsym() × 16
        graal_create_isolate(NULL, &g_isolate, &init_thread)
        graal_detach_thread(init_thread)
    
    success → g_init_done = true
    failure → g_init_error = strdup(error_msg)  (persistent, never retried)
    ili_mutex_unlock(&g_init_lock)
```

Initialization is protected by `g_init_lock` (pthread_mutex_t / CRITICAL_SECTION).  
Failed initialization is stored persistently in `g_init_error` and **never retried** on subsequent calls — avoiding leaked isolates or repeated failures.

### Per-Call Lifecycle (Consolidated)

Each native call now uses a single attach/detach cycle:

```
ili_call_struct_str():
    ili_mutex_lock(&g_java_lock)          ← serialize Java access
    graal_attach_thread(g_isolate, &thread)
    fn(thread, req, &native_payload)
    result = strdup(native_payload)        ← copy to C heap
    g_native_free(thread, native_payload)  ← free GraalVM memory while attached
    graal_detach_thread(thread)
    ili_mutex_unlock(&g_java_lock)
    return result                          ← C-owned, caller uses free()
```

**Key improvements:**
- Attach/call/copy/free/detach in **one cycle** (was 2 cycles / 4 attach-detach pairs).
- Results are `strdup`'d to C heap — no more GraalVM-owned pointer dangling after detach.
- All Java calls are serialized by `g_java_lock` — safe for INTERLIS libraries with unknown thread-safety.

### Shutdown

`shutdown_native_library()` exists for manual use in tests.  
DuckDB does not provide a reliable extension-unload lifecycle, so **cleanup relies on process exit** (OS reclaims isolate, dlclose, memory). This is documented as a known limitation.

### Conservative Parallelism

All Java calls are serialized through a single global mutex (`g_java_lock`).  
Reason: thread-safety of INTERLIS libraries (ili2c, ilivalidator, EhiLogger) is not guaranteed.  
Correctness is prioritized over parallelism. This restriction is documented.

## 4. Global State (Phase 4)

```c
static void *g_native_handle = NULL;           // dlopen handle
static graal_isolate_t *g_isolate = NULL;      // GraalVM isolate
static graal_create_isolate_fn_t g_create_isolate = NULL;
static graal_attach_thread_fn_t g_attach_thread = NULL;
static graal_detach_thread_fn_t g_detach_thread = NULL;
static graal_tear_down_isolate_fn_t g_tear_down = NULL;
static native_version_fn g_native_version = NULL;
static native_struct_fn g_native_validate = NULL;
// ... 11 more function pointers ...
static bool g_init_done = false;
static char *g_init_error = NULL;              // ← dynamically allocated, set once
static ili_mutex_t g_init_lock;                // ← protects initialization
static ili_mutex_t g_java_lock;                // ← serializes Java calls
```

### Thread-Safety Properties

- `g_init_done` and `g_init_error` are set **under `g_init_lock`** and read with lock on slow path.
- Failed init is stored once in `g_init_error` (dynamically allocated); retries are impossible.
- Function pointers are set during locked init and never modified afterwards.
- `g_java_lock` ensures only one thread enters Java code at a time.
- `g_init_error` replaced the former `g_error_buf[512]` — no more fixed-size static buffer.

## 5. Entry Points (12 Functions)

All functions use the same basic pattern:

```c
int fn_name(graal_isolatethread_t* thread, char* input_json, char** out_payload);
```

### 5.0 Unified Error Contract (Phase 2)

Return values follow a strict, consistent contract:

| Return | Meaning | Payload Format |
|--------|---------|---------------|
| `0` | Success | Valid result data (JSON, TSV, or SQL) |
| `1` | INVALID_ARGUMENT | `{"status":"INVALID_ARGUMENT","operation":"...","message":"...","detail":"..."}` |
| `2` | IO_ERROR | `{"status":"IO_ERROR","operation":"...","message":"...","detail":"...","path":"..."}` |
| `3` | MODEL_ERROR | `{"status":"MODEL_ERROR","operation":"...","message":"...","detail":"...","path":"..."}` |
| `4` | PARSE_ERROR | `{"status":"PARSE_ERROR",...}` |
| `5` | VALIDATION_ERROR | `{"status":"VALIDATION_ERROR",...}` |
| `6` | UNSUPPORTED | `{"status":"UNSUPPORTED",...}` |
| `100` | INTERNAL_ERROR | `{"status":"INTERNAL_ERROR","operation":"...","message":"...","exception":"..."}` |

**Rules:**
- Status 0 means payload is a valid result — never an error
- Status > 0 means payload is a structured JSON error (NativeError)
- No `"ERROR:"` or similar prefixes in any payload
- Error payloads are always allocated by Java (`UnmanagedMemory.malloc()`) and must be freed with `ili_free_string()`
- Validation messages (XTF has errors) are normal data rows — status 0

### 5.1 `ili_native_version`

```c
int ili_native_version(graal_isolatethread_t*, char** out_payload);
```

- **Purpose:** Returns version/platform JSON.
- **Input:** None.
- **Returns:** 0 + JSON payload.
- **Payload format:** `{"native_version":"0.1.0","core_version":"0.1.0","graalvm_version":"...","platform":"macos-aarch64","native_lib":"libduckdb_ili_native.dylib"}`
- **Errors:** Never returns error (always 0).

### 5.2 `ili_native_echo`

```c
int ili_native_echo(graal_isolatethread_t*, char* input_json, char** out_payload);
```

- **Purpose:** Test/debug echo function (not used in production).
- **Input:** Arbitrary JSON string.
- **Returns:** 0 + echoed JSON.
- **Payload format:** `{"echo": "..."}`
- **Errors:** Never returns error (always 0).

### 5.3 `ili_native_validate`

```c
int ili_native_validate(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Validate XTF file, return JSON summary.
- **Input:** `{"input":"path.xtf","modeldir":"dir"}`
- **Returns:** 0 on success (valid or invalid XTF), 1 on error.
- **Success payload:** `{"valid":true/false,"errorCount":N,"warningCount":N,"infoCount":N,"messages":[...]}`
- **Error payload:** `{"status":"INTERNAL_ERROR","operation":"validate","message":"...","exception":"..."}` (status 100)
- **Issues:** `CONSTRAINT` + `AREA` validation disabled (Phase 6).

### 5.4 `ili_native_validate_tsv`

```c
int ili_native_validate_tsv(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Validate XTF file, return TSV result.
- **Input:** `{"input":"path.xtf","modeldir":"dir"}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** TSV with header `errors\twarnings\tinfos\n` + message rows.
- **Error payload:** `{"status":"INTERNAL_ERROR","operation":"validate_tsv","message":"...","exception":"..."}` (status 100)
- **Issues:** `CONSTRAINT` + `AREA` disabled. CSV log parsed with `split(",")`.

### 5.5 `ili_native_model_info`

```c
int ili_native_model_info(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Model introspection (models, topics, classes, attributes, enumerations).
- **Input:** `{"cmd":"models"|"topics"|"classes"|"attributes"|"enums","modeldir":"...","model":"...","class":"..."}`
- **Returns:** 0 on success, >0 on error.
- **Success payload:** TSV data.
- **Error payload:** `{"status":"MODEL_ERROR","operation":"model_info","message":"...","detail":"...","path":"..."}` — status 3
- **Issues:** None (error contract fully implemented in Phase 2).

### 5.6 `ili_native_read_xtf`

```c
int ili_native_read_xtf(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Generic XTF object reader.
- **Input:** `{"input":"path.xtf","modeldir":"...","models":"..."}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** TSV data (9 columns).
- **Error payload:** `{"status":"INTERNAL_ERROR",...}` (status 100) — partial results are never returned as success.

### 5.7 `ili_native_read_xtf_class`

```c
int ili_native_read_xtf_class(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Read specific class from XTF.
- **Input:** `{"input":"path.xtf","class":"Model.Topic.Class","modeldir":"...","nested":"json"}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** TSV with header line + data rows.
- **Error payload:** `{"status":"IO_ERROR","operation":"read_xtf_class","message":"...","detail":"...","path":"..."}` (status 2)
- **Notes:** Exact FQN matching is used. Missing optional values are encoded as TSV NULL sentinel `\N`.

### 5.8 `ili_native_read_xtf_class_schema`

```c
int ili_native_read_xtf_class_schema(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Get column names for a class.
- **Input:** `{"class":"Model.Topic.Class","modeldir":"...","nested":"json"}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** Tab-separated column names.
- **Error payload:** `{"status":"INVALID_ARGUMENT","operation":"read_xtf_class_schema","message":"Missing required field","detail":"class"}` (status 1)

### 5.8a `ili_native_read_xtf_class_schema_v2`

```c
int ili_native_read_xtf_class_schema_v2(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Get the typed v2 schema descriptor for a class.
- **Input:** `{"class":"Model.Topic.Class","modeldir":"...","nested":"json"}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** One TSV row per column with fields `name`, `logical_type`, `wire_encoding`, `nullable`, `geometry_kind`, `crs_auth_name`, `crs_code`.
- **Availability:** Optional capability `ILI_CAP_TYPED_CLASS_SCAN` (bit 12).

### 5.8b `ili_native_read_xtf_class_v2`

```c
int ili_native_read_xtf_class_v2(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Read a class from XTF using the typed v2 transport.
- **Input:** `{"input":"path.xtf","class":"Model.Topic.Class","modeldir":"...","nested":"json"}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** TSV with header line + data rows. Scalars use textual wire values, geometry uses hex-WKB, missing optional values use `\N`.
- **Availability:** Optional capability `ILI_CAP_TYPED_CLASS_SCAN` (bit 12).

### 5.9 `ili_native_read_xtf_structures`

```c
int ili_native_read_xtf_structures(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Get STRUCTURE definitions for a class.
- **Input:** `{"class":"Model.Topic.Class","modeldir":"..."}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** TSV with header + data rows.
- **Error payload:** `{"status":"MODEL_ERROR","operation":"read_xtf_structures","message":"...","detail":"...","path":"..."}` (status 3)

### 5.10 `ili_native_read_xtf_association`

```c
int ili_native_read_xtf_association(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Read association objects from XTF.
- **Input:** `{"input":"path.xtf","association":"Model.Topic.Assoc","modeldir":"..."}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** TSV with header + data rows.
- **Error payload:** `{"status":"IO_ERROR","operation":"read_xtf_association","message":"...","detail":"...","path":"..."}` (status 2)
- **Notes:** Exact FQN matching is used. Missing optional values are encoded as TSV NULL sentinel `\N`.

### 5.11 `ili_native_read_xtf_association_schema`

```c
int ili_native_read_xtf_association_schema(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Get column names for an association.
- **Input:** `{"association":"Model.Topic.Assoc","modeldir":"..."}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** Tab-separated column names.
- **Error payload:** `{"status":"INVALID_ARGUMENT","operation":"read_xtf_association_schema","message":"Missing required field","detail":"association"}` (status 1)

### 5.11a `ili_native_read_xtf_association_schema_v2`

```c
int ili_native_read_xtf_association_schema_v2(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Get the typed v2 schema descriptor for an association.
- **Input:** `{"association":"Model.Topic.Assoc","modeldir":"..."}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** One TSV row per column with fields `name`, `logical_type`, `wire_encoding`, `nullable`, `geometry_kind`, `crs_auth_name`, `crs_code`.
- **Availability:** Optional capability `ILI_CAP_TYPED_ASSOC_SCAN` (bit 13).

### 5.11b `ili_native_read_xtf_association_v2`

```c
int ili_native_read_xtf_association_v2(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Read an association from XTF using the typed v2 transport.
- **Input:** `{"input":"path.xtf","association":"Model.Topic.Assoc","modeldir":"..."}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** TSV with header line + data rows. Scalars use textual wire values, missing optional values use `\N`.
- **Availability:** Optional capability `ILI_CAP_TYPED_ASSOC_SCAN` (bit 13).

### 5.12 `ili_native_generate_import_sql`

```c
int ili_native_generate_import_sql(graal_isolatethread_t*, ili_request*, char** out_payload);
```

- **Purpose:** Generate SQL DDL/DML for XTF import.
- **Input:** Uses `input`, `schema`, `modeldir`, `mapping`, `mode` fields from `ili_request`.
- **Returns:** 0 on success, non-zero on error.
- **Success payload:** SQL statements (`BEGIN TRANSACTION;` / DDL / INSERT / `COMMIT;`, one per line).
- **Error payload:** `{"status":"MODEL_ERROR","operation":"import_xtf","message":"...","detail":"...","path":"..."}` (status 3)
- **Mapping validation:** `mapping` must be `"relational"` or NULL (defaults to `"relational"`). Unsupported values return `INVALID_ARGUMENT` (status 1).
- **Mode support:** `mode` can be `"create"` (default), `"replace"`, or `"append"`.
- **Breaking change (Phase 10):** Renamed from `ili_native_import_xtf`. Table names now use `topic__class` convention.

### 5.13 `ili_free_string`

```c
void ili_free_string(graal_isolatethread_t* thread, char* str);
```

- **Purpose:** Free a string allocated by any of the above functions.
- **Implementation:** Calls `UnmanagedMemory.free(str)` in GraalVM.
- **Requirements:** Must be called with an attached thread. String must have been allocated by a native entry point.

## 6. Memory Ownership Rules (Phase 4)

| Resource | Allocator | Owner After Call | Free Mechanism |
|----------|-----------|-----------------|----------------|
| Input strings (`char*` passed to entry points) | Caller (C) | Caller | Caller manages (stack/strdup) |
| Native result strings | `UnmanagedMemory.malloc()` | Consolidated helper | `g_native_free()` inside same attach/detach cycle |
| Returned C copies | C `strdup()` (→ `malloc`) | Caller (C) | `free()` |
| Init error messages | C `malloc()` / `strdup` | Extension (persistent) | `free()` in `shutdown_native_library()` |

**No violations** — all Allocator mismatches fixed in Phases 1+4.

## 7. C-Side Call Helpers (Phase 4)

### Consolidated Helpers

All native calls now go through three consolidated helpers that combine lock, attach, call, copy, free, and detach in a single cycle:

```c
// No-input call (e.g., ili_native_version)
// Returns C-allocated copy, caller must free(). Sets *out_status.
static char *ili_call_str(
    int (*fn)(graal_isolatethread_t *, char **),
    int *out_status);

// With-input call (e.g., ili_native_echo)
static char *ili_call_input_str(
    int (*fn)(graal_isolatethread_t *, char *, char **),
    const char *input, int *out_status);

// With-ili_request call (most API functions)
static char *ili_call_struct_str(
    native_struct_fn fn, const ili_request *req,
    int *out_status);
```

**Flow per helper:**
1. `ili_mutex_lock(&g_java_lock)` — serialize Java access
2. `graal_attach_thread` — attach to GraalVM isolate
3. Call native function
4. `strdup` the GraalVM-allocated result → C heap
5. `g_native_free(thread, native_str)` — free GraalVM memory (still attached)
6. `graal_detach_thread` — detach
7. `ili_mutex_unlock(&g_java_lock)`
8. Return C-allocated string (NULL on failure), set `*out_status`

### Memory Ownership

| Resource | Allocator | Freed By | When |
|----------|-----------|----------|------|
| Native result string | GraalVM `UnmanagedMemory.malloc()` | `g_native_free()` inside helper | Same attach/detach cycle |
| Returned C copy | C `strdup()` (→ `malloc`) | Caller via `free()` | After processing |
| Init error message | C `strdup()` / `malloc` | Never (persistent) or in `shutdown_native_library()` | — |

**No more `ili_free_result()` — all freeing happens inside the consolidated helpers.**

## 8. TSV Format

Most data flows use tab-separated values with escape sequences:

| Sequence | Meaning |
|----------|---------|
| `\t` | Tab character |
| `\n` | Newline |
| `\r` | Carriage return |
| `\\` | Backslash |

### Parser (C Side)

- `ili_tsv_next_field()`: Extracts a single field and preserves whether it was encoded as the TSV NULL sentinel `\N`.
- Typed assignment helpers decode NULL sentinels into SQL `NULL`.
- Unescaping remains textual for non-null scalar payloads.

## 9. Request Format (JSON)

All requests are JSON objects built manually with `snprintf` in C and parsed with `String.indexOf` in Java:

```json
{"input": "path", "modeldir": "dir", "class": "Model.Topic.Class"}
```

**Vulnerabilities:**
- No proper JSON escaping on either side.
- Fixed buffer sizes (4096 or 8192 bytes).
- No validation of JSON structure in Java.
- No distinction between missing key and `null` value.
- No handling of nested objects or arrays.

## 10. ABI Versioning

### Implemented (Phase 9, Hardened Phase 5.5)

The ABI handshake negotiates compatibility between the C extension and the GraalVM
native library through a single entry point, `ili_get_api()`, which returns a JSON
payload with ABI version and capability bitmask. Individual API functions are then
resolved via `dlsym` using the negotiated capabilities for validation.

**Header:** `duckdb-extension/src/include/ili_request.h`

```c
#define ILI_NATIVE_ABI_VERSION 1
#define ILI_REQUEST_STRUCT_SIZE 112  /* sizeof(ili_request) on LP64 */

typedef struct ili_api_v1 {
    uint32_t struct_size;   // sizeof(ili_api_v1), set by caller
    uint32_t abi_version;   // populated by callee: ILI_NATIVE_ABI_VERSION
    uint64_t capabilities;  // bitmask of ILI_CAP_* flags
} ili_api_v1;

int ili_get_api(graal_isolatethread_t *thread, uint32_t requested_abi_version, char **out_payload);
```

**Compile-time struct size assertion:** The C header enforces that `ILI_REQUEST_STRUCT_SIZE` matches `sizeof(ili_request)` at compile time via `_Static_assert` (C11) or `static_assert` (C++). This prevents the hard-coded constant from drifting out of sync with the actual struct layout.

**ABI payload format (JSON):**
```json
{"abi_version":1,"capabilities":4095}
```

**Initialization flow:**

1. `dlopen(lib)` → resolve `graal_create_isolate`, `graal_attach_thread`,
   `graal_detach_thread`, `graal_tear_down_isolate`, `ili_free_string`, and `ili_get_api` (6 symbols)
2. Verify `ili_free_string` is non-NULL (required for freeing ABI error payloads)
3. `create_isolate` → get init_thread
4. Call `ili_get_api(init_thread, ILI_NATIVE_ABI_VERSION, &abi_payload)`
5. Parse JSON payload: `abi_version` and `capabilities`
6. Validate: `abi_version`, `capabilities` against `ILI_CAP_REQUIRED_MASK`
7. Resolve all 12 API function pointers via individual `dlsym` calls
8. Null-check all 12 function pointers (all mandatory for ABI v1)
9. `detach_thread(init_thread)`

### Request struct_size validation (Java side)

Every `@CEntryPoint` method that accepts an `IliRequest` validates the struct
before dereferencing any fields. Validation is centralized in
`NativeRequestValidator`:

```java
// java/ili-native/.../nativeapi/NativeRequestValidator.java
public final class NativeRequestValidator {
    public static final long EXPECTED_STRUCT_SIZE = 112L;

    public static NativeError requireRequest(
        IliRequest request, long minimumStructSize, String operation);

    public static NativeError requireField(
        String value, String fieldName, String operation);
}
```

**Checks performed:**
- `request.isNull()` → `INTERNAL_ERROR` (NULL pointer rejected)
- `request.struct_size() < minimumStructSize` → `INVALID_ARGUMENT`
- Required fields (where applicable) checked via `requireField`

**Validation checks (C extension):**

- `ili_free_string` must be resolvable before the ABI handshake
- `abi_version == ILI_NATIVE_ABI_VERSION` — version match required
- `(ILI_CAP_REQUIRED_MASK & ~capabilities) == 0` — all 12 capabilities required
- All 12 function pointers must be non-NULL after `dlsym`
- JSON parsing uses `sscanf` for robustness against whitespace variations

**Capability bits:**

| Bit | Constant | Function |
|-----|----------|----------|
| 0 | `ILI_CAP_VERSION` | `version` |
| 1 | `ILI_CAP_VALIDATE` | `validate` |
| 2 | `ILI_CAP_VALIDATE_TSV` | `validate_tsv` |
| 3 | `ILI_CAP_MODEL_INFO` | `model_info` |
| 4 | `ILI_CAP_READ_XTF` | `read_xtf` |
| 5 | `ILI_CAP_READ_XTF_CLASS` | `read_xtf_class` |
| 6 | `ILI_CAP_READ_XTF_CLASS_SCHEMA` | `read_xtf_class_schema` |
| 7 | `ILI_CAP_READ_XTF_STRUCTURES` | `read_xtf_structures` |
| 8 | `ILI_CAP_READ_XTF_ASSOCIATION` | `read_xtf_association` |
| 9 | `ILI_CAP_READ_XTF_ASSOC_SCHEMA` | `read_xtf_association_schema` |
| 10 | `ILI_CAP_IMPORT_XTF` | `generate_import_sql` |
| 11 | `ILI_CAP_FREE_STRING` | `free_string` |
| 12 | `ILI_CAP_TYPED_CLASS_SCAN` | `read_xtf_class_schema_v2` + `read_xtf_class_v2` |
| 13 | `ILI_CAP_TYPED_ASSOC_SCAN` | `read_xtf_association_schema_v2` + `read_xtf_association_v2` |

**Required capabilities (ABI v1 — 12 mandatory, typed scans optional):**
`ILI_CAP_VERSION | ILI_CAP_VALIDATE | ILI_CAP_VALIDATE_TSV | ILI_CAP_MODEL_INFO | ILI_CAP_READ_XTF | ILI_CAP_READ_XTF_CLASS | ILI_CAP_READ_XTF_CLASS_SCHEMA | ILI_CAP_READ_XTF_STRUCTURES | ILI_CAP_READ_XTF_ASSOCIATION | ILI_CAP_READ_XTF_ASSOC_SCHEMA | ILI_CAP_IMPORT_XTF | ILI_CAP_FREE_STRING`

Optional capabilities:
- `ILI_CAP_TYPED_CLASS_SCAN`
- `ILI_CAP_TYPED_ASSOC_SCAN`

**Backward/forward compatibility:**

- `struct_size` in `ili_request` allows forward-compatible field additions. Clients
  pass the actual struct size; the callee rejects sizes smaller than its known minimum
  but tolerates larger sizes (future extension).
- New capabilities appended at higher bit positions.
- Clients verify required capabilities are present before using associated functions.

### Acceptance tests (native_smoke_test.c)

| Test | Description |
|------|-------------|
| A1 | Matching ABI — all required capabilities present |
| A2 | ABI v0 rejected |
| A3 | ABI v99 rejected |
| A4 | `ili_free_string` symbol present and non-NULL |
| L1 | `struct_size=0` → INVALID_ARGUMENT |
| L2 | `struct_size=4` → INVALID_ARGUMENT |
| L5 | `struct_size=8` → INVALID_ARGUMENT |
| L6 | `struct_size=64` → INVALID_ARGUMENT (partial struct) |
| L7 | `struct_size=112` → OK (exact match, operation succeeds) |
| L8 | `struct_size=200` → OK (larger, forward-compatible) |

## 11. Platform Constants

| Constant | Source | Value |
|----------|--------|-------|
| `DUCKDB_ILI_NATIVE_LIB` | Environment variable | User-specified path |
| `ILI_ABI_VERSION` | `extension_config.cmake` | `v1.2.0` |
| `ILI_DUCKDB_PLATFORM` | CMake (detected at build time) | `osx_arm64`, `linux_amd64`, `linux_arm64`, `windows_amd64` |
| `NATIVE_LIB_NAME` | Preprocessor `#define` | `libduckdb_ili_native.{dylib|so|dll}` |
| `EXT_VERSION` | `interlis_extension.c:71` | `0.1.0-dev` |
