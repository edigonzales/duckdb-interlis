cmake_minimum_required(VERSION 3.15)
project(ili_extension LANGUAGES C)

set(EXTENSION_NAME "ili")

# Required for DUCKDB_EXTENSION_ENTRYPOINT macro
add_definitions(-DDUCKDB_EXTENSION_NAME=${EXTENSION_NAME})

# DuckDB C API headers
set(DUCKDB_CAPI_DIR "${CMAKE_SOURCE_DIR}/src/include")

# Extension source files
set(EXTENSION_SOURCES
    src/ili_extension.c
)

# Build shared library
add_library(${EXTENSION_NAME} SHARED ${EXTENSION_SOURCES})

target_include_directories(${EXTENSION_NAME} PRIVATE ${DUCKDB_CAPI_DIR})

set_target_properties(${EXTENSION_NAME} PROPERTIES
    OUTPUT_NAME "ili"
    PREFIX ""
    SUFFIX ".duckdb_extension"
)

# macOS specific
if(APPLE)
    set_target_properties(${EXTENSION_NAME} PROPERTIES
        INSTALL_RPATH "@loader_path"
        MACOSX_RPATH ON
    )
endif()

# Visibility
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(VISIBILITY_INLINES_HIDDEN ON)
