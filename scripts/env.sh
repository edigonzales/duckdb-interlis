#!/usr/bin/env bash
# scripts/env.sh - local environment (gitignored)
export GRAALVM_HOME="/Users/stefan/.sdkman/candidates/java/25.0.3-graal"
export JAVA_HOME="$GRAALVM_HOME"
export DUCKDB_VERSION="1.5.3"
export DUCKDB_CLI="$HOME/bin/duckdb"
export CMAKE="$HOME/cmake-4.1.0/CMake.app/Contents/bin/cmake"
export DUCKDB_ILI_NATIVE_LIB="$PWD/java/ili-native/build/native/libduckdb_ili_native.dylib"
export DUCKDB_ILI_EXTENSION="$PWD/duckdb-extension/build/interlis.duckdb_extension"
