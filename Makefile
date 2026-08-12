PROJ_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

EXT_NAME=interlis
EXT_CONFIG=${PROJ_DIR}extension_config.cmake

DUCKDB_SRCDIR ?= "./third_party/duckdb/"

include third_party/extension-ci-tools/makefiles/duckdb_extension.Makefile

# Visual Studio uses a multi-config generator and emits the Release test
# executable below a configuration-specific directory. Ninja is single-config
# and places the executable directly below test/.
ifeq ($(DUCKDB_PLATFORM),windows_amd64)
ifeq ($(GEN),ninja)
TEST_PATH="/test/unittest.exe"
else
TEST_PATH="/test/Release/unittest.exe"
endif
endif

# CI only needs the distributable INTERLIS extension and the SQLLogicTest
# runner. Avoid the generic release target, which builds every DuckDB target.
# Parquet is one of DuckDB's default built-in extensions, but duckdb-interlis
# does not use it. Skipping it avoids compiling Parquet and its bundled codec
# dependencies on a cold CI runner while keeping core_functions and interlis
# linked into the SQLLogicTest runner.
.PHONY: ci_release
ci_release: ${EXTENSION_CONFIG_STEP}
	mkdir -p build/release
	cmake $(GENERATOR) $(BUILD_FLAGS) $(EXT_RELEASE_FLAGS) $(VCPKG_MANIFEST_FLAGS) -DSKIP_EXTENSIONS=parquet -DCMAKE_BUILD_TYPE=Release -S $(DUCKDB_SRCDIR) -B build/release
	cmake --build build/release --config Release --target interlis_loadable_extension unittest
