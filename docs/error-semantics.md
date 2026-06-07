# Error Semantics — Current State

> **Phase 0 Baseline Document.** Generated 2026-06-07.
> Documents current error handling patterns across all three layers (C, Java/Native, SQL).
> Each pattern includes a severity assessment and reference to the Phase that will fix it.

## 1. Native ABI Status Codes

### Current Convention

The C-Java ABI uses a single `int` return value and a `char**` output parameter:

```c
int fn(graal_isolatethread_t*, char* input, char** out_payload);
```

| Return | Meaning | Payload Content |
|--------|---------|----------------|
| `0` | "Success" | Result data (JSON, TSV, SQL) |
| `1` | "Error" | Error description (varies) |

### Actual Usage (Inconsistent)

| Entry Point | Success (0) | Error (1) |
|-------------|------------|-----------|
| `ili_native_version` | JSON version info | Never returns error |
| `ili_native_validate` | JSON `{"valid":...,"errorCount":...}` | JSON `{"valid":false,"error":"..."}` |
| `ili_native_validate_tsv` | TSV with header `errors\twarnings\tinfos` | TSV `"-1\t0\t0\nError message"` |
| `ili_native_model_info` | TSV data **or** `"ERROR: ..."` string | Never returns error (always 0) |
| `ili_native_read_xtf` | TSV data **or** `"ERROR: ..."` string | Returns 1 with `"ERROR: ..."` |
| `ili_native_read_xtf_class` | TSV data **or** `"ERROR: ..."` string | Returns 1 with `"ERROR: ..."` |
| `ili_native_read_xtf_class_schema` | TSV header | Returns 1 with empty string |
| `ili_native_read_xtf_structures` | TSV data | Returns 1 with `"ERROR: ..."` |
| `ili_native_read_xtf_association` | TSV data | Returns 1 with `"ERROR: ..."` |
| `ili_native_read_xtf_association_schema` | TSV header | Returns 1 with empty string |
| `ili_native_import_xtf` | SQL statements **or** `"ERROR: ..."` string | Returns 1 with `"ERROR: ..."` |

### Severity: HIGH (Phase 2)

Three distinct patterns make error detection on the C side unreliable:
1. Status 1 + structured JSON (`nativeValidate`)
2. Status 0 + `"ERROR:"` prefix (`nativeModelInfo`)
3. Status 1 + `"ERROR:"` prefix (XTF readers, import)

The C side must use **string prefix heuristics** to distinguish errors from valid data.

---

## 2. Error Payload Discarding (Memory Leak)

### Pattern

In `interlis_extension.c:299-317`, the `call_native_str` and `call_native_with_input` functions:

```c
static char *call_native_with_input(graal_isolatethread_t *thread,
                                     int (*fn)(graal_isolatethread_t *, char *, char **),
                                     const char *input) {
    char *result = NULL;
    int rc = fn(thread, (char *)input, &result);
    if (rc != 0 || !result) {
        return NULL;   // <--- result pointer is LOST, memory LEAKED
    }
    return result;
}
```

### Consequence

- The Java side always allocates a payload via `UnmanagedMemory.malloc()`, even for errors.
- When `rc != 0`, the C side discards the pointer.
- The allocated memory is **never freed**.
- The **actual error message** is lost — DuckDB only sees `"X failed"`.

### Affected Call Sites

Every table function `*_init` and scalar function that checks `result == NULL`:

| Location | Line | Lost Payload |
|----------|------|-------------|
| `ili_native_version_fn_cb` | 383 | `"ili_native_version failed"` |
| `ili_validate_summary_json_fn` | 449 | `"Validation call failed"` |
| `ili_validate_init` | 664 | `"Validation call failed"` |
| `mi_init` | 857 | `"Model info call failed"` |
| `xtf_objects_init` | 999 | `"XTF read call failed"` |
| `xtf_class_init` | 1120 | `"XTF class read failed"` |
| `xtf_structures_init` | 1208 | `"XTF structures read failed"` |
| `xtf_assoc_init` | 1325 | `"XTF association read failed"` |
| `import_init_func` | 1411 | `"Native import call failed"` |

### Severity: CRITICAL (Phase 1)

Every failed native call leaks memory and discards the original error message.

---

## 3. Allocator Mismatch (C `free()` on GraalVM Memory)

### Pattern

In `interlis_extension.c:1414-1417`:

```c
char *sql_result = native_call_with_input_str(g_native_import_xtf, req);
// ...
import_init_data *id = malloc(sizeof(import_init_data));
id->sql_script = sql_result;   // GraalVM-allocated pointer
id->cursor = id->sql_script;
duckdb_init_set_init_data(info, id, import_init_destroy);
```

In `import_init_destroy` (line 1361-1363):

```c
static void import_init_destroy(void *d) {
    import_init_data *id = (import_init_data *)d;
    if (id) { free(id->sql_script); free(id); }  // free() on GraalVM memory!
}
```

### Consequence

`sql_result` is allocated by `allocCString()` in Java → `UnmanagedMemory.malloc()`.  
C `free()` is invoked on this pointer → **undefined behaviour**.  
May crash, corrupt heap, or appear to work depending on platform/allocator implementation.

### Severity: CRITICAL (Phase 1)

---

## 4. Java Exception Swallowing

### Pattern A: `compileIli` returns `null`

In `IliModelService.java:56`:

```java
} catch (Exception e) { return null; }
```

The caller receives `null` and returns an empty result string `""`. The actual compilation error is **completely lost**.

### Pattern B: XTF read exceptions

In `XtfObjectReader.java:208-211`:

```java
} catch (Exception ex) {
    if (IliLogger.isDebugEnabled()) {
        System.err.println("XTF read: " + ex.getMessage());
    }
}
// ... continues to return partial result from StringBuilder
```

The `StringBuilder` contains all objects read *before* the exception. This partial result is returned as a **success** to the C side. The caller has no way to know the read was incomplete.

### Pattern C: XTF association read exceptions

In `XtfObjectReader.java:698-705`: identical pattern to Pattern B.

### Pattern D: Geometry conversion

In `XtfObjectReader.java:583-585`:

```java
} catch (Exception e) {
    return "";  // geometry silently discarded
}
```

### Severity: CRITICAL (Phase 2, Phase 7)

---

## 5. `"ERROR:"` Prefix as Success

### Pattern

In `NativeEntryPoints.java:317-321` (`nativeModelInfo`):

```java
} catch (Exception e) {
    result = "ERROR: " + e.getMessage();
}
// ...
outPayload.write(allocCString(result));
return 0;  // <--- STATUS 0 (success) with ERROR: string!
```

The C side receives status `0` and treats the payload as valid model data. The TSV parser will interpret `"ERROR:"` as a single-column row.

### Affected Functions

- `nativeModelInfo` — status 0 with `"ERROR: ..."` string
- `nativeReadXtf` — status 1 with `"ERROR: ..."` (correct status code but wrong payload format)
- `nativeReadXtfClass` — status 1 with `"ERROR: ..."`
- `nativeReadXtfStructures` — status 1 with `"ERROR: ..."`
- `nativeReadXtfAssociation` — status 1 with `"ERROR: ..."`
- `nativeImportXtf` — status 1 with `"ERROR: ..."`

### Severity: HIGH (Phase 2)

---

## 6. Empty Result as Error Concealment

### Pattern

In `IliModelService.java:66`:

```java
TransferDescription td = compileIli(modelDir);
if (td == null) return "";  // empty string = "no error, no data"
```

The C side receives an empty TSV string. `mi_init` counts 0 rows and returns an empty result set **without any error**. The user sees an empty table with no indication that model compilation failed.

### Also applies to

- `XtfObjectReader` when model compilation fails
- `IliImportService` when model compilation fails (returns `"ERROR: Failed to compile model"` as SQL — slightly better but still a data row, not a DuckDB error)

### Severity: HIGH (Phase 2)

---

## 7. C-Side Error Handling Summary

### `duckdb_*_set_error` Usage

| Function Group | Uses `duckdb_*_set_error` | Error Message Quality |
|---------------|--------------------------|----------------------|
| `ili_extension_version` | Never | N/A (cannot fail) |
| `ili_native_version` | Yes (on null) | Generic, original lost |
| `ili_validate_summary_json` | Yes (on null) | Generic, original lost |
| `ili_validate` (table) | Yes (on null) | Generic, original lost |
| Model info (`mi_init`) | Yes (on null) | Generic, original lost |
| XTF readers (`*_init`) | Yes (on null) | Generic, original lost |
| Import (`import_init_func`) | Yes (on null) | Generic, original lost |

In **all** cases, when a native call fails:
1. The original Java error message is lost.
2. A generic `"X call failed"` message is set.
3. The GraalVM-allocated error payload is leaked.

### Row-Level Errors

`duckdb_validity_set_row_invalid()` is used in scalar functions (`ili_native_version_fn_cb`, `ili_validate_summary_json_fn`) to mark individual rows as NULL when a native call fails. This sets a per-row error, but the error message is generic.

---

## 8. Error Categories (Proposed)

For Phase 2, the following error categories will replace the current `0`/`1` scheme:

| Status Code | Name | Meaning |
|-------------|------|---------|
| 0 | `ILI_STATUS_OK` | Success, payload is valid result data |
| 1 | `ILI_STATUS_INVALID_ARGUMENT` | Missing or malformed parameter |
| 2 | `ILI_STATUS_IO_ERROR` | File not found, read error, temp file failure |
| 3 | `ILI_STATUS_MODEL_ERROR` | Model compilation/parsing error |
| 4 | `ILI_STATUS_PARSE_ERROR` | JSON parsing error |
| 5 | `ILI_STATUS_VALIDATION_ERROR` | XTF validation errors (for structured error payload) |
| 6 | `ILI_STATUS_UNSUPPORTED` | Requested feature not yet implemented |
| 100 | `ILI_STATUS_INTERNAL_ERROR` | Unexpected Java exception / internal failure |

---

## 9. Thread-Safety Issues Leading to Errors

| Issue | File:Line | Effect |
|-------|-----------|--------|
| Non-atomic `g_initialized` check | `interlis_extension.c:210` | Double init, corrupted function pointers |
| Shared `g_error_buf[512]` | `interlis_extension.c:105` | Error message corruption under concurrency |
| Global `System.setErr` | `IliLogger.java:79` | stderr from other threads lost |
| Non-synchronized `HashMap` cache | `IliModelService.java:20` | `ConcurrentModificationException` or corrupt cache |
| Static `win32_dlerror` buffer | `interlis_extension.c:53` | Error message corruption under concurrency |

### Severity: HIGH (Phase 4, Phase 5)

---

## 10. Error Paths Not Tested

The following error scenarios have **no test coverage**:

| Scenario | Layer |
|----------|-------|
| Native library not found | C |
| Corrupted native library | C |
| Native library ABI mismatch | C |
| Concurrent `init_native_library` calls | C |
| File not found for XTF | Java/C |
| Invalid XML in XTF | Java |
| Truncated XTF file | Java |
| Model compilation failure | Java |
| Temp file creation failure | Java |
| Invalid JSON request | Java |
| Path longer than 8192 bytes | C |
| Path with special characters (`"`, `\`, Unicode) | C/Java |
| `mapping` parameter with unsupported value | C/Java |
| Same class name in multiple topics | Java |
| SQL keyword as attribute name | Java |
| Empty XTF file | Java |
| Missing attribute vs empty attribute | Java |
\end{verbatim} | Geometry conversion error | Java |

(Phase 12 will add regression tests for all of these.)
