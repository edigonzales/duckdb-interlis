#!/usr/bin/env bash
# Template for scripts/env.sh (copy to scripts/env.sh and adjust paths)
export GRAALVM_HOME="/path/to/graalvm-jdk-25"
export JAVA_HOME="$GRAALVM_HOME"
export DUCKDB_CLI="$HOME/bin/duckdb"
export CMAKE="$HOME/cmake-4.1.0/CMake.app/Contents/bin/cmake"
export DUCKDB_ILI_NATIVE_LIB="$PWD/java/ili-native/build/native/libduckdb_ili_native.dylib"
export DUCKDB_ILI_EXTENSION="$PWD/duckdb-extension/build/interlis.duckdb_extension"
# C_STRUCT ABI version for extension metadata (check your DuckDB CLI's supported version)
export DUCKDB_ABI_VERSION="v1.2.0"
