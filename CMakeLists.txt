cmake_minimum_required(VERSION 3.20)

project(interlis LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(INTERLIS_EXTENSION_VERSION "" CACHE STRING "duckdb-interlis extension version")
if(NOT INTERLIS_EXTENSION_VERSION)
    file(READ "${CMAKE_CURRENT_LIST_DIR}/VERSION" INTERLIS_EXTENSION_VERSION)
    string(STRIP "${INTERLIS_EXTENSION_VERSION}" INTERLIS_EXTENSION_VERSION)
endif()
set(INTERLIS_ILIC_SOURCE_DIR "" CACHE PATH "Path to ilic-fork")
set(INTERLIS_IOX_SOURCE_DIR "" CACHE PATH "Path to iox-cpp")

set(INTERLIS_ILIC_COMMIT "bd4625485a990629366a770d630db5d9ffa15cc5" CACHE STRING
    "Pinned ilic-fork commit used when no local source directory is supplied")
set(INTERLIS_IOX_COMMIT "ff098e26b0da7697208ad02d3fbb48d60810d300" CACHE STRING
    "Pinned iox-cpp commit used when no local source directory is supplied")

set(INTERLIS_ILIC_VERSION "0.10.0-SNAPSHOT" CACHE STRING "ilic source version")
set(INTERLIS_IOX_VERSION "0.2.0" CACHE STRING "iox-cpp source version")
set(INTERLIS_DUCKDB_VERSION "1.5.3" CACHE STRING "DuckDB product version")
set(INTERLIS_GEOS_VERSION "3.x" CACHE STRING "GEOS component version family")
option(INTERLIS_BUILD_NATIVE_TESTS "Build native duckdb-interlis model-source tests" OFF)

include(FetchContent)

if(INTERLIS_ILIC_SOURCE_DIR)
    if(NOT EXISTS "${INTERLIS_ILIC_SOURCE_DIR}/CMakeLists.txt")
        message(FATAL_ERROR
            "INTERLIS_ILIC_SOURCE_DIR does not contain ilic-fork: ${INTERLIS_ILIC_SOURCE_DIR}")
    endif()
    set(_interlis_ilic_source_dir "${INTERLIS_ILIC_SOURCE_DIR}")
    message(STATUS "Using local ilic-fork: ${_interlis_ilic_source_dir}")
else()
    FetchContent_Declare(interlis_ilic
        GIT_REPOSITORY https://github.com/edigonzales/ilic-fork.git
        GIT_TAG "${INTERLIS_ILIC_COMMIT}"
        GIT_SHALLOW FALSE
    )
    FetchContent_MakeAvailable(interlis_ilic)
    set(_interlis_ilic_source_dir "${interlis_ilic_SOURCE_DIR}")
    message(STATUS "Using fetched ilic-fork commit: ${INTERLIS_ILIC_COMMIT}")
endif()

if(INTERLIS_IOX_SOURCE_DIR)
    if(NOT EXISTS "${INTERLIS_IOX_SOURCE_DIR}/CMakeLists.txt")
        message(FATAL_ERROR
            "INTERLIS_IOX_SOURCE_DIR does not contain iox-cpp: ${INTERLIS_IOX_SOURCE_DIR}")
    endif()
    set(_interlis_iox_source_dir "${INTERLIS_IOX_SOURCE_DIR}")
    message(STATUS "Using local iox-cpp: ${_interlis_iox_source_dir}")
else()
    FetchContent_Declare(interlis_iox
        GIT_REPOSITORY https://codeberg.org/edigonzales/iox-cpp.git
        GIT_TAG "${INTERLIS_IOX_COMMIT}"
        GIT_SHALLOW FALSE
    )
    FetchContent_MakeAvailable(interlis_iox)
    set(_interlis_iox_source_dir "${interlis_iox_SOURCE_DIR}")
    message(STATUS "Using fetched iox-cpp commit: ${INTERLIS_IOX_COMMIT}")
endif()

# iox is embedded once as a dependency of this extension. In particular, pass the
# chosen ilic source into iox instead of adding ilic-core a second time here.
set(BUILD_TESTING OFF CACHE BOOL "Disable nested iox tests in DuckDB extension builds" FORCE)
set(IOX_ENABLE_ILIC ON CACHE BOOL "Build iox ilic integration" FORCE)
set(IOX_ENABLE_GEOS ON CACHE BOOL "Build GEOS-backed iox geometry" FORCE)
set(IOX_BUILD_WASM OFF CACHE BOOL "Disable nested iox WASM target" FORCE)
set(IOX_BUILD_EXAMPLES OFF CACHE BOOL "Disable nested iox examples" FORCE)
set(IOX_BUILD_TOOLS OFF CACHE BOOL "Disable nested iox tools" FORCE)
set(IOX_ENABLE_JSON_FORMAT ON CACHE BOOL "Build iox JSON format support" FORCE)
set(IOX_ILIC_SOURCE_DIR "${_interlis_ilic_source_dir}" CACHE PATH
    "ilic source passed to iox" FORCE)

add_subdirectory(
    "${_interlis_iox_source_dir}"
    "${CMAKE_BINARY_DIR}/interlis-iox-build"
    EXCLUDE_FROM_ALL
)

set(EXTENSION_SOURCES
    src/interlis_extension.cpp
    src/functions/version_functions.cpp
    src/functions/model_functions.cpp
    src/functions/xtf_scan.cpp
    src/functions/xtf_values.cpp
    src/functions/xtf_set.cpp
    src/model/model_source_resolver.cpp
    src/model/compiled_model.cpp
)

include_directories(src/include)

build_static_extension(interlis ${EXTENSION_SOURCES})
build_loadable_extension(interlis " " ${EXTENSION_SOURCES})

target_compile_definitions(interlis_extension PRIVATE
    INTERLIS_EXTENSION_VERSION="${INTERLIS_EXTENSION_VERSION}"
    INTERLIS_ILIC_VERSION="${INTERLIS_ILIC_VERSION}"
    INTERLIS_IOX_VERSION="${INTERLIS_IOX_VERSION}"
    INTERLIS_DUCKDB_VERSION="${INTERLIS_DUCKDB_VERSION}"
    INTERLIS_GEOS_VERSION="${INTERLIS_GEOS_VERSION}"
)
target_compile_definitions(interlis_loadable_extension PRIVATE
    INTERLIS_EXTENSION_VERSION="${INTERLIS_EXTENSION_VERSION}"
    INTERLIS_ILIC_VERSION="${INTERLIS_ILIC_VERSION}"
    INTERLIS_IOX_VERSION="${INTERLIS_IOX_VERSION}"
    INTERLIS_DUCKDB_VERSION="${INTERLIS_DUCKDB_VERSION}"
    INTERLIS_GEOS_VERSION="${INTERLIS_GEOS_VERSION}"
)

set_property(TARGET interlis_extension APPEND PROPERTY LINK_LIBRARIES iox::ilic ilic::core)
set_property(TARGET interlis_loadable_extension APPEND PROPERTY LINK_LIBRARIES iox::ilic ilic::core)

set(INTERLIS_COMPONENT_REVISIONS
    "duckdb-interlis=working-tree"
    "ilic=${INTERLIS_ILIC_COMMIT}"
    "iox-cpp=${INTERLIS_IOX_COMMIT}"
    "duckdb=14eca11bd9d4a0de2ea0f078be588a9c1c5b279c"
)

install(
    TARGETS interlis_extension
    EXPORT "${DUCKDB_EXPORT_SET}"
    LIBRARY DESTINATION "${INSTALL_LIB_DIR}"
    ARCHIVE DESTINATION "${INSTALL_LIB_DIR}")

if(INTERLIS_BUILD_NATIVE_TESTS)
    enable_testing()
    add_executable(interlis_model_source_test test/model/model_source_resolver_test.cpp)
    target_include_directories(interlis_model_source_test PRIVATE src src/include)
    target_link_libraries(interlis_model_source_test PRIVATE
        interlis_extension
        duckdb_static
        duckdb_generated_extension_loader
        core_functions_extension
        parquet_extension
        iox::ilic
        ilic::core
    )
    add_test(NAME interlis_model_source_test COMMAND interlis_model_source_test)
endif()
