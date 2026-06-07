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

1. Environment variable `DUCKDB_ILI_NATIVE_LIB` (explicit override)
2. DuckDB extension cache: `~/.duckdb/extensions/{ILI_ABI_VERSION}/{ILI_DUCKDB_PLATFORM}/libduckdb_ili_native.{dylib|so|dll}`
3. Extract embedded blob to cache directory (from `embedded_native_lib_data[]`)
4. Fallback dev paths: `build/native/current/`, `../java/ili-native/build/native/`, `../../java/ili-native/build/native/`

### Extraction Process (Embrittled)

The embedded native library is extracted by:
1. Writing `embedded_native_lib_data[]` directly to the cache path
2. Setting executable permissions (`chmod 0755`)
3. No hash verification, no atomic rename, no fsync

**Issues:**
- Parallel processes may write the same file simultaneously.
- Partially written files may be used by subsequent runs.
- No integrity check.
- No version/hash in the filename.

### Symbol Resolution

4 GraalVM lifecycle symbols + 12 API symbols are resolved with `dlsym`.  
4 of the 12 API symbols are **not validated** in the null-check (structures, both association variants, import).

## 3. GraalVM Isolate Lifecycle

```
init_native_library():
    dlopen()
    dlsym() × 16
    graal_create_isolate(NULL, &g_isolate, &init_thread)
    graal_detach_thread(init_thread)

native_call_with_input_str():
    graal_attach_thread(g_isolate, &thread)
    fn(thread, input, &result)
    graal_detach_thread(thread)

native_free_str():
    graal_attach_thread(g_isolate, &thread)
    g_native_free(thread, str)
    graal_detach_thread(thread)

shutdown_native_library():  ← NEVER CALLED
    graal_attach_thread(g_isolate, &thread)
    graal_tear_down_isolate(thread)
    dlclose(g_native_handle)
```

**Issues:**
- Attach/detach happens **per call** — no thread pooling or reuse.
- Free requires a separate attach/detach cycle — 4 total per native call.
- `shutdown_native_library` is implemented but never invoked (DuckDB has no reliable extension unload hook).
- Initialization is **not thread-safe** — no mutex around `g_initialized` check.

## 4. Global State

```c
static void *g_native_handle = NULL;           // dlopen handle
static graal_isolate_t *g_isolate = NULL;      // GraalVM isolate
static graal_create_isolate_fn_t g_create_isolate = NULL;
static graal_attach_thread_fn_t g_attach_thread = NULL;
static graal_detach_thread_fn_t g_detach_thread = NULL;
static graal_tear_down_isolate_fn_t g_tear_down = NULL;
static native_version_fn g_native_version = NULL;
// ... 10 more function pointers ...
static bool g_initialized = false;
static char g_error_buf[512];                  // ← shared, not thread-safe
static duckdb_connection g_connection = NULL;
```

**Issues:**
- `g_error_buf` is a 512-byte static buffer — concurrent init or error paths will corrupt messages.
- No atomic/barrier between setting `g_initialized = true` and function pointers being populated.
- `g_connection` is stored but never read.

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
- **Issues:** `endsWith` class matching and NULL semantics to be fixed in Phase 7. Read exceptions throw (no more partial results).

### 5.8 `ili_native_read_xtf_class_schema`

```c
int ili_native_read_xtf_class_schema(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Get column names for a class.
- **Input:** `{"class":"Model.Topic.Class","modeldir":"...","nested":"json"}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** Tab-separated column names.
- **Error payload:** `{"status":"INVALID_ARGUMENT","operation":"read_xtf_class_schema","message":"Missing required field","detail":"class"}` (status 1)

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
- **Issues:** `endsWith` class matching to be fixed in Phase 7. Read exceptions throw (no partial results).

### 5.11 `ili_native_read_xtf_association_schema`

```c
int ili_native_read_xtf_association_schema(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Get column names for an association.
- **Input:** `{"association":"Model.Topic.Assoc","modeldir":"..."}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** Tab-separated column names.
- **Error payload:** `{"status":"INVALID_ARGUMENT","operation":"read_xtf_association_schema","message":"Missing required field","detail":"association"}` (status 1)

### 5.12 `ili_native_import_xtf`

```c
int ili_native_import_xtf(graal_isolatethread_t*, char* request_json, char** out_payload);
```

- **Purpose:** Generate SQL DDL/DML for XTF import.
- **Input:** `{"input":"path.xtf","schema":"target","modeldir":"...","mapping":"relational"}`
- **Returns:** 0 on success, 1 on error.
- **Success payload:** SQL statements (one per line).
- **Error payload:** `{"status":"MODEL_ERROR","operation":"import_xtf","message":"...","detail":"...","path":"..."}` (status 3)
- **Issues:** Allocator mismatch fixed in Phase 1 (C-side now uses `strdup` + `ili_free_result`).

### 5.13 `ili_free_string`

```c
void ili_free_string(graal_isolatethread_t* thread, char* str);
```

- **Purpose:** Free a string allocated by any of the above functions.
- **Implementation:** Calls `UnmanagedMemory.free(str)` in GraalVM.
- **Requirements:** Must be called with an attached thread. String must have been allocated by a native entry point.

## 6. Memory Ownership Rules

### Rule (Current, Partially Broken)

| Resource | Allocator | Owner After Call | Free Mechanism |
|----------|-----------|-----------------|----------------|
| Input strings (`char*` passed to entry points) | Caller (C) | Caller | Caller manages (stack/strdup) |
| Output strings (`char**` returned by entry points) | `UnmanagedMemory.malloc()` | Caller (C) | `ili_free_string()` |
| C-side copies (`strdup`) | C `malloc()` | C extension | C `free()` |

### Violations

| Location | Violation |
|----------|-----------|
| `call_native_with_input()`, `call_native_str()` | Error payload is **not freed** when `rc != 0` |
| `import_init_destroy()` | C `free()` called on GraalVM `UnmanagedMemory.malloc()` pointer |
| All `*_init` error paths | Error payload **lost** when `result == NULL` check discards pointer |

## 7. C-Side Call Helpers

### `call_native_str`

```c
static char *call_native_str(graal_isolatethread_t *thread,
                              int (*fn)(graal_isolatethread_t *, char **)) {
    char *result = NULL;
    int rc = fn(thread, &result);
    if (rc != 0 || !result) {
        return NULL;  // BUG: leaks result if rc != 0 and result != NULL
    }
    return result;
}
```

### `call_native_with_input`

```c
static char *call_native_with_input(graal_isolatethread_t *thread,
                                     int (*fn)(graal_isolatethread_t *, char *, char **),
                                     const char *input) {
    char *result = NULL;
    int rc = fn(thread, (char *)input, &result);
    if (rc != 0 || !result) {
        return NULL;  // BUG: same as above
    }
    return result;
}
```

### `native_call_str`

```c
static char *native_call_str(int (*fn)(graal_isolatethread_t *, char **)) {
    // attach → call → detach
    // returns result or NULL
    // caller must native_free_str()
}
```

### `native_call_with_input_str`

```c
static char *native_call_with_input_str(
        int (*fn)(graal_isolatethread_t *, char *, char **), const char *input) {
    // attach → call → detach
    // returns result or NULL
    // caller must native_free_str()
}
```

### `native_free_str`

```c
static void native_free_str(char *str) {
    // attach → free → detach
    // does nothing if str is NULL or isolate not initialized
}
```

## 8. TSV Format

Most data flows use tab-separated values with escape sequences:

| Sequence | Meaning |
|----------|---------|
| `\t` | Tab character |
| `\n` | Newline |
| `\r` | Carriage return |
| `\\` | Backslash |

### Parser (C Side)

- `parse_tsv_field()`: Extracts a single field, unescapes sequences, returns `malloc`'d string.
- `parse_tsv_int()`: Parses an integer field.
- No handling of NULL sentinels — empty fields are returned as empty strings.

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

### Current (No ABI Version)

There is **no ABI version handshake**. The C extension resolves individual symbols with `dlsym` and assumes compatibility. If the native library is compiled from a different code version, the mismatch may cause:
- Wrong function signatures → crash
- Missing symbols → graceful error (null check)
- Changed payload format → parser errors silently ignored

### Proposed (Phase 9)

```c
#define ILI_NATIVE_ABI_VERSION 1

typedef struct ili_api_v1 {
    uint32_t struct_size;
    uint32_t abi_version;
    uint64_t capabilities;
    // function pointers...
} ili_api_v1;

int ili_get_api(uint32_t requested_abi_version, ili_api_v1 *out_api);
```

## 11. Platform Constants

| Constant | Source | Value |
|----------|--------|-------|
| `DUCKDB_ILI_NATIVE_LIB` | Environment variable | User-specified path |
| `ILI_ABI_VERSION` | `extension_config.cmake` | `v1.2.0` |
| `ILI_DUCKDB_PLATFORM` | CMake (detected at build time) | `osx_arm64`, `linux_amd64`, `linux_arm64`, `windows_amd64` |
| `NATIVE_LIB_NAME` | Preprocessor `#define` | `libduckdb_ili_native.{dylib|so|dll}` |
| `EXT_VERSION` | `interlis_extension.c:71` | `0.1.0-dev` |
