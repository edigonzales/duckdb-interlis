# Installation & Setup

## Prerequisites

| Component | Requirement |
|---|---|
| DuckDB | `1.5.3` is the current pinned development/test version; additional DuckDB product versions can be supported when the repository publishes matching release directories |
| Operating System | Linux x86_64, Linux aarch64, macOS aarch64, Windows x86_64 |

The extension is self-contained — the GraalVM native library is embedded and extracted automatically on first use. No additional runtime dependencies are required.

## Loading the Extension

The extension is **unsigned**. You must start DuckDB with the `-unsigned` flag:

```bash
duckdb -unsigned
```

### Production: Install from Repository

```sql
INSTALL interlis FROM 'https://duckdb-ext.interlis.guru';
LOAD interlis;
```

If DuckDB is already running without `-unsigned`, enable unsigned extensions first:

```sql
SET allow_unsigned_extensions = true;
INSTALL interlis FROM 'https://duckdb-ext.interlis.guru';
LOAD interlis;
```

DuckDB treats `https://duckdb-ext.interlis.guru` as the repository root and appends the product-version path automatically. For example, DuckDB `1.5.3` requests `.../v1.5.3/osx_arm64/interlis.duckdb_extension` and installs the extension to `~/.duckdb/extensions/v1.5.3/osx_arm64/interlis.duckdb_extension`.

The embedded GraalVM native library is cached separately. On first `LOAD`, it is extracted to the ABI-based cache, for example `~/.duckdb/extensions/v1.2.0/osx_arm64/{hashed_filename}`.

If the `C_STRUCT` ABI remains compatible, the same extension binary may be published under multiple DuckDB release directories such as `v1.5.3/` and `v1.5.4/`. If compatibility changes, publish a newly built binary for that DuckDB product version.

### Production: Manual Load from Release Binary

Download `interlis.duckdb_extension` for your platform from [GitHub Releases](https://github.com/SOAG/duckdb-interlis/releases):

| Platform | File |
|---|---|
| Linux x86_64 | `interlis-linux-x86_64/interlis.duckdb_extension` |
| Linux aarch64 | `interlis-linux-aarch64/interlis.duckdb_extension` |
| macOS aarch64 | `interlis-osx-aarch64/interlis.duckdb_extension` |
| Windows x86_64 | `interlis-windows-x86_64/interlis.duckdb_extension` |

```sql
LOAD '/path/to/interlis.duckdb_extension';
```

## Verifying Installation

```sql
SELECT ili_extension_version();
-- => 0.1.0

SELECT ili_native_version();
-- => {"nativeVersion": "0.1.0", ...}
```

## Native Library Resolution

The extension locates the GraalVM native library in this order:

1. **Environment variable** `DUCKDB_ILI_NATIVE_LIB` — absolute path to the native library (primarily for development)
2. **DuckDB extension cache** — `~/.duckdb/extensions/{abi_version}/{platform}/{hashed_filename}` — extracted automatically from the embedded blob
3. **Local fallback paths** — `build/native/current/`, `../java/ili-native/build/native/` (debug builds only)

## Environment Variables

| Variable | Purpose |
|---|---|
| `DUCKDB_ILI_NATIVE_LIB` | Override native library path (development only) |
| `DUCKDB_ILI_DEBUG=1` | Enable debug diagnostics on stderr |
| `ILI_ALLOW_MISSING_NATIVE_LIB=1` | Allow loading without native library (not recommended for production) |
| `ILI_DEFAULT_MODELDIR` | Default INTERLIS model repository (default: `https://models.interlis.ch`) |

## Building from Source

```bash
cp scripts/env.example.sh scripts/env.sh
# Edit paths if needed
source scripts/env.sh
scripts/build-all.sh
```

## Uninstalling

```sql
UNLOAD interlis;
```

To remove cached files:
```bash
if [ -d "${HOME}/.duckdb/extensions" ]; then
  find "${HOME}/.duckdb/extensions" -type f \
    \( -name 'interlis.duckdb_extension' \
    -o -name 'interlis.duckdb_extension.info' \
    -o -name '*libduckdb_ili_native.*' \) -delete
fi
```
