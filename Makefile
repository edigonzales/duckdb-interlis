PROJ_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

EXT_NAME=interlis
EXT_CONFIG=${PROJ_DIR}extension_config.cmake

# The DuckDB submodule is pinned to the v1.5.5 release commit. Extension CI
# commonly checks submodules out shallowly, so `git describe` cannot always see
# the release tag. DuckDB explicitly supports OVERRIDE_GIT_DESCRIBE for this
# case; callers can still override this default when testing another revision.
OVERRIDE_GIT_DESCRIBE ?= v1.5.5

include extension-ci-tools/makefiles/duckdb_extension.Makefile

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
.PHONY: ci_release
ci_release: ${EXTENSION_CONFIG_STEP}
	mkdir -p build/release
	cmake $(GENERATOR) $(BUILD_FLAGS) $(EXT_RELEASE_FLAGS) $(VCPKG_MANIFEST_FLAGS) -DCMAKE_BUILD_TYPE=Release -S $(DUCKDB_SRCDIR) -B build/release
	cmake --build build/release --config Release --target interlis_loadable_extension unittest
