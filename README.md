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

The extension ships as a single self-contained file — the GraalVM native library is embedded.

### From a Custom Repository

```sql
INSTALL interlis FROM 'https://duckdb-ext.sogeo.services';
LOAD interlis;
```

### From GitHub Releases

Download the `interlis.duckdb_extension` for your platform from the [latest GitHub Release](https://github.com/SOAG/duckdb-interlis/releases):

| Platform | File |
|---|---|
| Linux x86_64 | `interlis-linux-x86_64/interlis.duckdb_extension` |
| Linux ARM64 | `interlis-linux-aarch64/interlis.duckdb_extension` |
| macOS ARM64 | `interlis-osx-aarch64/interlis.duckdb_extension` |
| Windows x86_64 | `interlis-windows-x86_64/interlis.duckdb_extension` |

Then load it:

```sql
LOAD '/path/to/interlis.duckdb_extension';
SELECT ili_native_version();
```

The native library is extracted automatically to `~/.duckdb/extensions/` on first load.

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
