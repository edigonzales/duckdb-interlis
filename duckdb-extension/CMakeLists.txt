cmake_minimum_required(VERSION 3.15)
project(interlis_extension LANGUAGES C)

set(EXTENSION_NAME "interlis")

# Required for DUCKDB_EXTENSION_ENTRYPOINT macro
add_definitions(-DDUCKDB_EXTENSION_NAME=${EXTENSION_NAME})

# ---------------------------------------------------------------------------
# Version: read from repo-root VERSION file (single source of truth)
# ---------------------------------------------------------------------------
file(READ "${CMAKE_SOURCE_DIR}/../VERSION" EXTENSION_VERSION)
string(STRIP "${EXTENSION_VERSION}" EXTENSION_VERSION)

# Native library version matches extension version
set(NATIVE_LIB_VERSION "${EXTENSION_VERSION}")

# DuckDB C_STRUCT ABI version used in extension cache path
set(ILI_ABI_VERSION "v1.2.0")

# ---------------------------------------------------------------------------
# Build metadata
# ---------------------------------------------------------------------------
execute_process(
    COMMAND git -C "${CMAKE_SOURCE_DIR}/.." rev-parse --short HEAD
    OUTPUT_VARIABLE ILI_GIT_SHA
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)
if(NOT ILI_GIT_SHA)
    set(ILI_GIT_SHA "unknown")
endif()

string(TIMESTAMP ILI_BUILD_TIMESTAMP UTC)
set(ILI_BUILD_PLATFORM "${CMAKE_SYSTEM_NAME}-${CMAKE_SYSTEM_PROCESSOR}")

# ---------------------------------------------------------------------------
# Platform detection for DuckDB platform name (used in extension cache path)
# ---------------------------------------------------------------------------
if(APPLE)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "arm64|aarch64")
        set(ILI_DUCKDB_PLATFORM "osx_arm64")
    else()
        set(ILI_DUCKDB_PLATFORM "osx_amd64")
    endif()
elseif(WIN32)
    set(ILI_DUCKDB_PLATFORM "windows_amd64")
else()
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
        set(ILI_DUCKDB_PLATFORM "linux_arm64")
    else()
        set(ILI_DUCKDB_PLATFORM "linux_amd64")
    endif()
endif()

# ---------------------------------------------------------------------------
# DuckDB C API headers
# ---------------------------------------------------------------------------
set(DUCKDB_CAPI_DIR "${CMAKE_SOURCE_DIR}/src/include")

# ---------------------------------------------------------------------------
# Extension source files
# ---------------------------------------------------------------------------

set(EXTENSION_SOURCES
    src/interlis_extension.c
    src/sha256.c
    src/ili_duckdb_utils.c
)

# ---------------------------------------------------------------------------
# Embed native library as blob
# ---------------------------------------------------------------------------
option(ILI_ALLOW_MISSING_NATIVE_LIB "Allow building without embedded native library (development only)" OFF)

set(NATIVE_LIB_DIR "${CMAKE_SOURCE_DIR}/build/native/current")
set(NATIVE_LIB_PATH "${NATIVE_LIB_DIR}/libduckdb_ili_native${CMAKE_SHARED_LIBRARY_SUFFIX}")
set(EMBEDDED_C_FILE "${CMAKE_CURRENT_BINARY_DIR}/embedded_native_lib.c")

if(EXISTS "${EMBEDDED_C_FILE}")
    message(STATUS "Using pre-generated embedded native lib: ${EMBEDDED_C_FILE}")
    list(APPEND EXTENSION_SOURCES "${EMBEDDED_C_FILE}")
elseif(EXISTS "${NATIVE_LIB_PATH}")
    find_package(Python3 QUIET COMPONENTS Interpreter)
    if(Python3_FOUND)
        add_custom_command(
            OUTPUT "${EMBEDDED_C_FILE}"
            COMMAND Python3::Interpreter "${CMAKE_SOURCE_DIR}/../scripts/embed_native_lib.py" "${NATIVE_LIB_PATH}" "${EMBEDDED_C_FILE}"
            DEPENDS "${NATIVE_LIB_PATH}" "${CMAKE_SOURCE_DIR}/../scripts/embed_native_lib.py"
            COMMENT "Embedding native library (Python): ${NATIVE_LIB_PATH}"
        )
        message(STATUS "Native library found: ${NATIVE_LIB_PATH} (will embed via Python)")
        list(APPEND EXTENSION_SOURCES "${EMBEDDED_C_FILE}")
    else()
        if(NOT ILI_ALLOW_MISSING_NATIVE_LIB)
            message(FATAL_ERROR "Native library found but Python3 not available. Install Python3 or set -DILI_ALLOW_MISSING_NATIVE_LIB=ON")
        endif()
        message(WARNING "Native library found but Python3 not available, building without embedded lib")
    endif()
else()
    if(NOT ILI_ALLOW_MISSING_NATIVE_LIB)
        message(FATAL_ERROR "Native library not found at ${NATIVE_LIB_PATH}. Build the native library first (scripts/build-native.sh) or set -DILI_ALLOW_MISSING_NATIVE_LIB=ON for development")
    endif()
    message(WARNING "Native library not found, building extension without embedded lib (ILI_ALLOW_MISSING_NATIVE_LIB=ON)")
endif()

if(NOT EXISTS "${EMBEDDED_C_FILE}" AND NOT (EXISTS "${NATIVE_LIB_PATH}" AND Python3_FOUND))
    # Dummy to satisfy external symbol references (only when explicitly allowed)
    file(WRITE "${EMBEDDED_C_FILE}"
        "// Dummy - native library not embedded (ILI_ALLOW_MISSING_NATIVE_LIB=ON)\n"
        "#include <stddef.h>\n"
        "const unsigned char embedded_native_lib_data[1] = {0};\n"
        "const size_t embedded_native_lib_size = 0;\n"
        "const char embedded_native_lib_hash[65] = \"\";\n"
        "const char embedded_native_lib_hash_short[13] = \"\";\n"
    )
    list(APPEND EXTENSION_SOURCES "${EMBEDDED_C_FILE}")
endif()

# ---------------------------------------------------------------------------
# Build shared library
# ---------------------------------------------------------------------------
add_library(${EXTENSION_NAME} SHARED ${EXTENSION_SOURCES})

target_include_directories(${EXTENSION_NAME} PRIVATE ${DUCKDB_CAPI_DIR})

target_compile_definitions(${EXTENSION_NAME} PRIVATE
    ILI_DUCKDB_PLATFORM="${ILI_DUCKDB_PLATFORM}"
    ILI_ABI_VERSION="${ILI_ABI_VERSION}"
    ILI_EXTENSION_VERSION="${EXTENSION_VERSION}"
    ILI_NATIVE_LIB_VERSION="${NATIVE_LIB_VERSION}"
    ILI_GIT_SHA="${ILI_GIT_SHA}"
    ILI_BUILD_TIMESTAMP="${ILI_BUILD_TIMESTAMP}"
    ILI_BUILD_PLATFORM="${ILI_BUILD_PLATFORM}"
)

# Link system libraries
if(APPLE)
    # macOS: dlopen/dlsym are in libSystem, pthread included, no extra linking needed
elseif(WIN32)
    # Windows: LoadLibrary/GetProcAddress and CRITICAL_SECTION in kernel32 (linked implicitly)
else()
    # Linux: need -ldl for dlopen/dlsym and -lpthread for mutex
    target_link_libraries(${EXTENSION_NAME} PRIVATE dl pthread)
endif()

set_target_properties(${EXTENSION_NAME} PROPERTIES
    OUTPUT_NAME "interlis"
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
