# C ABI Design

## Design Principles

- The C ABI is minimal and stable.
- All complex data passes through JSON strings.
- No Java objects leak across the C boundary.
- All allocated strings are owned by the native library and freed via `ili_free_result()`.

## Result Struct

```c
typedef struct ili_result {
    int32_t code;              // 0 = OK, non-zero = error
    const char *payload;       // JSON result string, owned by native library
    const char *error_message; // Error description, owned by native library
} ili_result;
```

## Entry Points

| Function | Request | Response |
|---|---|---|
| `ili_native_version(thread, result)` | none | JSON with version, platform info |
| `ili_native_echo(thread, request_json, result)` | any JSON string | echo of the input |
| `ili_free_result(thread, result)` | result struct | frees payload and error_message strings |

## Thread Safety

```c
typedef int graal_isolatethread_t; // GraalVM isolate thread
```

Each entry point receives a `graal_isolatethread_t*` parameter for thread-safe access to the GraalVM isolate.

## Memory Ownership

| Resource | Owner | Free via |
|---|---|---|
| `result.payload` | Native library | `ili_free_result()` |
| `result.error_message` | Native library | `ili_free_result()` |
| `request_json` | Caller (DuckDB extension) | Caller frees after call returns |
| `result` struct | Caller (DuckDB extension) | Caller manages stack/heap |

## Future Extensions

Streaming API (Phase 5+):

```c
typedef int (*ili_row_callback)(void *user_data, const char **values, int value_count);

int ili_read_xtf_objects_stream(
    graal_isolatethread_t *thread,
    const char *request_json,
    ili_row_callback callback,
    void *user_data,
    ili_result *out
);
```
