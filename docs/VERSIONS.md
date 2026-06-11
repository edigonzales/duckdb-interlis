# Versions

This document clarifies the distinct version types used in the `duckdb-interlis` project.

## Version Types

| Version | Description | Defined In | Current Value |
|---------|-------------|------------|---------------|
| **DuckDB product version** | The DuckDB binary version used in CI, remote repository paths, and local extension install directories | CI matrix, AGENTS.md | `1.5.3` |
| **DuckDB C_STRUCT ABI version** | The DuckDB C API ABI version used in extension footer metadata and the native-library cache path | `duckdb-extension/CMakeLists.txt`, CI | `v1.2.0` |
| **Extension version** | Semantic version of this DuckDB extension | `VERSION` (root) | `0.1.0-dev` |
| **Native library version** | Version of the GraalVM native shared library (currently matches extension version) | `duckdb-extension/CMakeLists.txt` | Same as extension |
| **Native ABI version** | Protocol version for the ABI handshake between C extension and native library | `duckdb-extension/src/include/ili_request.h` | `1` |
| **INTERLIS core versions** | Versions of INTERLIS Java libraries (ili2c, ilivalidator, iox-ili) | `java/ili-core/build.gradle` | See dependencies |
| **GraalVM version** | JDK version used for native image compilation | `env.sh`, CI | `25` |
| **Gradle version** | Build system version | `gradle/wrapper/gradle-wrapper.properties` | `9.5.1` |

## Single Source of Truth

The extension version is defined in the `VERSION` file at the repository root. This file is consumed by:

- **Gradle** (`build.gradle`): Reads `VERSION` as the project version
- **CMake** (`duckdb-extension/CMakeLists.txt`): Reads `VERSION` as `EXTENSION_VERSION`
- **Build scripts** (`scripts/build-extension.sh`): Reads `VERSION` as `EXT_VERSION`
- **CI** (`.github/workflows/ci.yml`): Reads `VERSION` for extension metadata

## Build Metadata

The following metadata is embedded as compile definitions in the C extension:

| Define | Description | Source |
|--------|-------------|--------|
| `ILI_EXTENSION_VERSION` | Extension version | `VERSION` file |
| `ILI_NATIVE_LIB_VERSION` | Native library version | Same as extension |
| `ILI_ABI_VERSION` | DuckDB C_STRUCT ABI version | CMake variable |
| `ILI_DUCKDB_PLATFORM` | Target platform identifier | Detected by CMake |
| `ILI_GIT_SHA` | Git commit SHA | `git rev-parse --short HEAD` |
| `ILI_BUILD_TIMESTAMP` | UTC build timestamp | CMake `string(TIMESTAMP ...)` |
| `ILI_BUILD_PLATFORM` | Build host platform | CMake system detection |

## Version Compatibility

- The DuckDB product version determines which remote repository path DuckDB requests, for example `v1.5.3/osx_arm64/interlis.duckdb_extension`
- The DuckDB product version also determines where DuckDB installs the downloaded extension locally, for example `~/.duckdb/extensions/v1.5.3/osx_arm64/interlis.duckdb_extension`
- The C extension ABI version (`ILI_ABI_VERSION`) is embedded in the extension footer metadata and must be supported by DuckDB's `C_STRUCT` extension API
- The same extension binary may be published under multiple DuckDB product-version directories when the `C_STRUCT` ABI and runtime behavior remain compatible
- The Native ABI version (`ILI_NATIVE_ABI_VERSION`) must match between extension and native library

## Path Examples

For DuckDB `1.5.3` on macOS ARM64:

- Remote repository lookup: `https://duckdb-ext.interlis.guru/v1.5.3/osx_arm64/interlis.duckdb_extension`
- Local installed extension: `~/.duckdb/extensions/v1.5.3/osx_arm64/interlis.duckdb_extension`
- Native-library cache: `~/.duckdb/extensions/v1.2.0/osx_arm64/{hashed_filename}`

## Dependency Locking

Java dependency versions are locked via Gradle lockfiles:

- `gradle.lockfile` (root)
- `java/ili-core/gradle.lockfile`
- `java/ili-native/gradle.lockfile`

To update locks: `./gradlew dependencies --write-locks`

## SBOM

Software Bill of Materials (CycloneDX JSON) are generated for each Java subproject:

- `java/ili-core/build/sbom/ili-core-sbom.json`
- `java/ili-native/build/sbom/ili-native-sbom.json`
