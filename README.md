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

## Installation & Loading

The extension ships as a single self-contained file — the GraalVM native library is embedded and extracted automatically on first use.

> **Important**: The extension is **unsigned**. You must start DuckDB with the `-unsigned` flag when loading.

### Verifying After Load

```sql
SELECT ili_extension_version();
-- => 0.1.0-dev

SELECT ili_native_version();
-- => {"nativeVersion": "0.1.0", "coreVersion": ...}
```

---

### Production: Install from Repository

```sql
INSTALL interlis FROM 'https://duckdb-ext.interlis.guru';
LOAD interlis;
```

This installs the extension into `~/.duckdb/extensions/` and loads it.

### Production: Manual Load from Release Binary

Download the `interlis.duckdb_extension` for your platform from [GitHub Releases](https://github.com/SOAG/duckdb-interlis/releases):

| Platform | File |
|---|---|
| Linux x86_64 | `interlis-linux-x86_64/interlis.duckdb_extension` |
| Linux ARM64 | `interlis-linux-aarch64/interlis.duckdb_extension` |
| macOS ARM64 | `interlis-osx-aarch64/interlis.duckdb_extension` |
| Windows x86_64 | `interlis-windows-x86_64/interlis.duckdb_extension` |

```sql
LOAD '/path/to/interlis.duckdb_extension';
SELECT ili_extension_version();
```

The native library is extracted automatically to `~/.duckdb/extensions/v1.2.0/{PLATFORM}/` on first load.

### Development: Build & Load Locally

```bash
# 1. Setup environment (once)
cp scripts/env.example.sh scripts/env.sh
# Edit paths if needed
source scripts/env.sh

# 2. Full build
scripts/build-all.sh

# 3. Start DuckDB with extension pre-loaded
scripts/dev-duckdb.sh
```

The dev script sets `DUCKDB_ILI_NATIVE_LIB` so the extension finds the native library at build time. In production, the native library is embedded in the extension binary.

### Native Library Resolution Order

The extension finds the GraalVM native library in this order:

1. **Environment variable** `DUCKDB_ILI_NATIVE_LIB` — used for local development
2. **DuckDB extension cache** `~/.duckdb/extensions/v1.2.0/{PLATFORM}/libduckdb_ili_native.dylib` — extracted from the embedded blob on first load
3. **Local fallback paths** `build/native/current/`, `../java/ili-native/build/native/`

---

## Usage

See **[docs/functions.md](docs/functions.md)** for a complete function reference with examples for every function.

Quick start examples are in **`sql/examples/`** — run them with:

```bash
duckdb -unsigned -cmd "LOAD '/path/to/interlis.duckdb_extension';" < sql/examples/01-version.sql
```

or with the dev helper:

```bash
scripts/dev-duckdb.sh < sql/examples/01-version.sql
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
