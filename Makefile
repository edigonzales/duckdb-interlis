PROJ_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

EXT_NAME=interlis
EXT_CONFIG=${PROJ_DIR}extension_config.cmake

DUCKDB_SRCDIR ?= "./third_party/duckdb/"

include third_party/extension-ci-tools/makefiles/duckdb_extension.Makefile

# Visual Studio uses a multi-config generator and emits the test executable
# with a .exe suffix. Keep the shared test recipes usable on Windows as well.
ifeq ($(DUCKDB_PLATFORM),windows_amd64)
TEST_PATH="/test/unittest.exe"
endif
