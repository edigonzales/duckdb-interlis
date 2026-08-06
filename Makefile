PROJ_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

EXT_NAME=interlis
EXT_CONFIG=${PROJ_DIR}extension_config.cmake

DUCKDB_SRCDIR ?= "./third_party/duckdb/"

include third_party/extension-ci-tools/makefiles/duckdb_extension.Makefile
