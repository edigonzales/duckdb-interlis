# duckdb-interlis

DuckDB extension providing INTERLIS/XTF functionality via GraalVM Native Image.

## Setup (macOS ARM64)

### Prerequisites

| Tool | Min Version | Install |
|---|---|---|
| GraalVM JDK | 25.0.3 | `sdk install java 25.0.3-graal` |
| DuckDB CLI | 1.5.3 | `brew install duckdb` or download from GitHub |
| CMake | 3.15+ | `brew install cmake` or download from Kitware |
| Xcode CLT | any | `xcode-select --install` |

### Environment

```bash
cp scripts/env.example.sh scripts/env.sh
# Edit scripts/env.sh if your paths differ
source scripts/env.sh
```

### Build

```bash
scripts/build-all.sh
```

### Manual Testing

```bash
scripts/dev-duckdb.sh
```

Then in DuckDB:

```sql
SELECT ili_extension_version();
```

### Smoke Test

```bash
scripts/smoke-test.sh
```

## Installation (Pre-built Binaries)

Download the extension and native library from the [latest GitHub Release](https://github.com/SOAG/duckdb-interlis/releases). Pick the archive matching your platform:

| Platform | Download |
|---|---|
| Linux x86_64 | `interlis-linux-x86_64` |
| Linux ARM64 | `interlis-linux-aarch64` |
| macOS ARM64 | `interlis-osx-aarch64` |
| Windows x86_64 | `interlis-windows-x86_64` |

Each archive contains:
- `interlis.duckdb_extension` — the DuckDB extension
- `libduckdb_ili_native.{so,dylib,dll}` — the GraalVM native shared library

### Load the Extension

```bash
# Point DuckDB to the native library
export DUCKDB_ILI_NATIVE_LIB=/path/to/libduckdb_ili_native.dylib

# Start DuckDB with unsigned extension loading
duckdb -unsigned -cmd "LOAD '/path/to/interlis.duckdb_extension'"
```

Or load from within DuckDB:

```sql
LOAD '/path/to/interlis.duckdb_extension';

SELECT ili_extension_version();
SELECT ili_native_version();
```

## Project Structure

```
duckdb-ili/
├── java/                  # Java business logic (GraalVM)
│   ├── ili-core/          # Core services
│   └── ili-native/        # GraalVM native image C API
├── duckdb-extension/      # DuckDB C API extension
├── scripts/               # Build & dev scripts
├── sql/                   # SQL examples
├── testdata/              # Test data (synthetic + external)
└── docs/                  # Architecture & design docs
```

## License

MIT - see [LICENSE](LICENSE)

## DuckDB Version

This extension targets **DuckDB 1.5.3**. Extension binaries are version-sensitive.
