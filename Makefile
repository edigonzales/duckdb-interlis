PROJ_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

EXT_NAME=interlis
EXT_CONFIG=${PROJ_DIR}extension_config.cmake

DUCKDB_SRCDIR ?= "./third_party/duckdb/"

include third_party/extension-ci-tools/makefiles/duckdb_extension.Makefile

# Visual Studio uses a multi-config generator and emits the Release test
# executable below a configuration-specific directory.
ifeq ($(DUCKDB_PLATFORM),windows_amd64)
TEST_PATH="/test/Release/unittest.exe"
endif

# CI only needs the distributable INTERLIS extension and the SQLLogicTest
# runner. Avoid the generic release target, which builds every DuckDB target.
.PHONY: ci_release
ci_release: ${EXTENSION_CONFIG_STEP}
	mkdir -p build/release
	cmake $(GENERATOR) $(BUILD_FLAGS) $(EXT_RELEASE_FLAGS) $(VCPKG_MANIFEST_FLAGS) -DCMAKE_BUILD_TYPE=Release -S $(DUCKDB_SRCDIR) -B build/release
	cmake --build build/release --config Release --target interlis_loadable_extension unittest
