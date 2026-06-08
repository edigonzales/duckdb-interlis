# Security

## Native Library Integrity

The GraalVM native library is embedded in the extension binary and extracted on first use. The following measures protect against tampering:

### Hash Verification (SHA-256)

Before a cached native library is used, its SHA-256 hash is verified against the hash embedded at build time. If the hash does not match, the cached file is deleted and re-extracted.

### Symlink Rejection

The extension uses `lstat()` (or equivalent on Windows) to verify that cached files are regular files, not symlinks or junctions. This prevents symlink-based attacks where an attacker replaces the cache entry with a link to a malicious library.

### Atomic Extraction

Extraction follows a safe protocol:

1. Write to a temporary file (`{path}.tmp.{pid}`)
2. Flush and fsync to disk
3. Set permissions (`0755`)
4. Verify SHA-256 hash of the temporary file
5. Atomically rename to the final path

If the rename fails (e.g., another process already extracted the file concurrently), the temporary file is removed and the existing file's hash is verified. If the hash matches, the cached file is used; otherwise, it is deleted.

### Cache Directory Permissions

The extract cache directory is created with permissions `0700` (owner read/write/execute only), preventing other users on the same system from modifying or reading cached libraries.

## Debug Output

When `DUCKDB_ILI_DEBUG=1` is set, the extension writes diagnostic information to stderr. This output never contains:

- User data (XTF file contents)
- Model source code
- Validation results
- Passwords or credentials

Debug output is limited to metadata: library paths, version information, cache hit/miss status, payload sizes, and timing.

## Runtime Considerations

### GraalVM Isolate

The native library runs inside a GraalVM isolate, providing memory isolation between the extension and the host DuckDB process. The isolate is created once and shared across all DuckDB connections.

### Thread Safety

All native Java calls are serialized through a single mutex because the INTERLIS libraries (ili2c, ilivalidator) are not guaranteed to be thread-safe. This intentionally limits parallelism as a safety measure.

### No Network Requests (by default)

Model compilation and validation do not make network requests unless a model repository URL (starting with `http://` or `https://`) is specified in the `modeldir` parameter. The default `ILI_DEFAULT_MODELDIR` is `https://models.interlis.ch` — set it to a local directory to avoid network access entirely.

## Reporting Security Issues

Please report security vulnerabilities privately to the maintainers. Do not file public issues for security-sensitive topics.
