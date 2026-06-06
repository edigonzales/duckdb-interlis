cmake_minimum_required(VERSION 3.15)
project(interlis_extension LANGUAGES C)

set(EXTENSION_NAME "interlis")

# Required for DUCKDB_EXTENSION_ENTRYPOINT macro
add_definitions(-DDUCKDB_EXTENSION_NAME=${EXTENSION_NAME})

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

# C_STRUCT ABI version used in DuckDB extension cache path
set(ILI_ABI_VERSION "v1.2.0")

# ---------------------------------------------------------------------------
# DuckDB C API headers
# ---------------------------------------------------------------------------
set(DUCKDB_CAPI_DIR "${CMAKE_SOURCE_DIR}/src/include")

# ---------------------------------------------------------------------------
# Extension source files
# ---------------------------------------------------------------------------
set(EXTENSION_SOURCES
    src/interlis_extension.c
)

# ---------------------------------------------------------------------------
# Embed native library as blob
# ---------------------------------------------------------------------------
set(NATIVE_LIB_DIR "${CMAKE_SOURCE_DIR}/build/native/current")
set(NATIVE_LIB_PATH "${NATIVE_LIB_DIR}/libduckdb_ili_native${CMAKE_SHARED_LIBRARY_SUFFIX}")
set(EMBEDDED_C_FILE "${CMAKE_CURRENT_BINARY_DIR}/embedded_native_lib.c")

if(EXISTS "${NATIVE_LIB_PATH}")
    add_custom_command(
        OUTPUT "${EMBEDDED_C_FILE}"
        COMMAND "${CMAKE_SOURCE_DIR}/../scripts/embed_native_lib.py" "${NATIVE_LIB_PATH}" "${EMBEDDED_C_FILE}"
        DEPENDS "${NATIVE_LIB_PATH}" "${CMAKE_SOURCE_DIR}/../scripts/embed_native_lib.py"
        COMMENT "Embedding native library: ${NATIVE_LIB_PATH}"
    )
    list(APPEND EXTENSION_SOURCES "${EMBEDDED_C_FILE}")
    message(STATUS "Native library found: ${NATIVE_LIB_PATH} (will embed)")
else()
    message(WARNING "Native library not found: ${NATIVE_LIB_PATH} (extension will need external native lib)")
    # Generate a dummy file so compilation doesn't fail
    file(WRITE "${EMBEDDED_C_FILE}"
        "// Dummy - native library not available\n"
        "#include <stddef.h>\n"
        "const unsigned char embedded_native_lib_data[1] = {0};\n"
        "const size_t embedded_native_lib_size = 0;\n"
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
)

# Link system libraries
if(APPLE)
    # macOS: dlopen/dlsym are in libSystem, no extra linking needed
elseif(WIN32)
    # Windows: LoadLibrary/GetProcAddress are in kernel32 (linked implicitly)
else()
    # Linux: need -ldl for dlopen/dlsym
    target_link_libraries(${EXTENSION_NAME} PRIVATE dl)
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
