# Versions

This document clarifies the distinct version types used in the `duckdb-interlis` project.

## Version Types

| Version | Description | Defined In | Current Value |
|---------|-------------|------------|---------------|
| **DuckDB product version** | The DuckDB binary version used in CI and repository paths | CI matrix, AGENTS.md | `1.5.3` |
| **DuckDB C_STRUCT ABI version** | The DuckDB C API ABI version used for extension cache paths and metadata | `duckdb-extension/CMakeLists.txt`, CI | `v1.2.0` |
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

- The C extension ABI version (`ILI_ABI_VERSION`) must match the DuckDB CLI's C_STRUCT ABI version
- The Native ABI version (`ILI_NATIVE_ABI_VERSION`) must match between extension and native library
- The DuckDB product version (`1.5.3`) determines the CLI binary and repository structure

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
