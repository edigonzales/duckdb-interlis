cmake_minimum_required(VERSION 3.20)

# This file is evaluated as DuckDB's out-of-tree extension configuration, not
# as a standalone top-level project. Keep DuckDB's own C++ standard intact and
# request C++17 only on the Interlis targets below.

set(INTERLIS_EXTENSION_VERSION "" CACHE STRING "duckdb-interlis extension version")
if(NOT INTERLIS_EXTENSION_VERSION)
    file(READ "${CMAKE_CURRENT_LIST_DIR}/VERSION" INTERLIS_EXTENSION_VERSION)
    string(STRIP "${INTERLIS_EXTENSION_VERSION}" INTERLIS_EXTENSION_VERSION)
endif()
set(INTERLIS_ILIC_SOURCE_DIR "" CACHE PATH "Path to ilic-fork")
set(INTERLIS_IOX_SOURCE_DIR "" CACHE PATH "Path to iox-cpp")

set(INTERLIS_ILIC_COMMIT "cd74490b1fddfe38ac80288067e1af0dd800e8da" CACHE STRING
    "Pinned ilic-fork commit used when no local source directory is supplied")
set(INTERLIS_IOX_COMMIT "600d191e387405b3e957617f7a1e6dd7a29a1d94" CACHE STRING
    "Pinned iox-cpp commit used when no local source directory is supplied")

set(INTERLIS_ILIC_VERSION "0.10.0-SNAPSHOT" CACHE STRING "ilic source version")
set(INTERLIS_IOX_VERSION "0.2.0" CACHE STRING "iox-cpp source version")
set(INTERLIS_DUCKDB_VERSION "1.5.3" CACHE STRING "DuckDB product version")
set(INTERLIS_GEOS_VERSION "3.x" CACHE STRING "GEOS component version family")
option(INTERLIS_BUILD_NATIVE_TESTS "Build native duckdb-interlis model-source tests" OFF)
option(INTERLIS_ENABLE_GEOS "Enable native GEOS geometry validation" OFF)
option(INTERLIS_USE_INSTALLED_IOX "Use an installed iox package instead of embedding iox/ilic sources" OFF)

if(INTERLIS_ENABLE_GEOS)
    set(_interlis_geos_component_version "${INTERLIS_GEOS_VERSION}")
else()
    set(_interlis_geos_component_version "disabled")
endif()

if(INTERLIS_USE_INSTALLED_IOX)
    if(INTERLIS_ILIC_SOURCE_DIR OR INTERLIS_IOX_SOURCE_DIR)
        message(FATAL_ERROR
            "INTERLIS_USE_INSTALLED_IOX cannot be combined with INTERLIS_ILIC_SOURCE_DIR or INTERLIS_IOX_SOURCE_DIR")
    endif()

    find_package(iox CONFIG REQUIRED)
    if(NOT TARGET iox::ilic)
        message(FATAL_ERROR "Installed iox package does not provide the required iox::ilic target")
    endif()
    if(NOT TARGET ilic::core)
        message(FATAL_ERROR "Installed iox package did not resolve the required ilic::core target")
    endif()
    message(STATUS "Using installed iox-cpp and ilic packages")
else()
    include(FetchContent)

    # ilic is embedded as a compiler library. Its optional native repository
    # client is not part of the DuckDB extension and must not pull CURL (or any
    # other repository transport dependency) into this build.
    set(ILIC_BUILD_CLI OFF CACHE BOOL "Disable ilic CLI in the extension build" FORCE)
    set(ILIC_BUILD_TESTS OFF CACHE BOOL "Disable nested ilic tests" FORCE)
    set(ILIC_ENABLE_NATIVE_REPOSITORY OFF CACHE BOOL
        "Disable ilic repository support in the extension build" FORCE)

    if(INTERLIS_ILIC_SOURCE_DIR)
        if(NOT EXISTS "${INTERLIS_ILIC_SOURCE_DIR}/CMakeLists.txt")
            message(FATAL_ERROR
                "INTERLIS_ILIC_SOURCE_DIR does not contain ilic-fork: ${INTERLIS_ILIC_SOURCE_DIR}")
        endif()
        set(_interlis_ilic_source_dir "${INTERLIS_ILIC_SOURCE_DIR}")
        message(STATUS "Using local ilic-fork: ${_interlis_ilic_source_dir}")
    else()
        # Let iox fetch and add ilic exactly once. Fetching ilic here as well would
        # create duplicate global targets such as ilic::core and antlr4-runtime.
        set(_interlis_ilic_source_dir "")
        set(_interlis_ilic_fetched TRUE)
        message(STATUS "ilic-fork will be fetched by iox at: ${INTERLIS_ILIC_COMMIT}")
    endif()

    # Configure iox before FetchContent can add it as a subdirectory. This is
    # important for the fetched-source path: FetchContent_MakeAvailable already
    # performs add_subdirectory and must not be followed by a second add.
    set(BUILD_TESTING OFF CACHE BOOL "Disable nested iox tests in DuckDB extension builds" FORCE)
    set(IOX_ENABLE_ILIC ON CACHE BOOL "Build iox ilic integration" FORCE)
    set(IOX_ENABLE_GEOS "${INTERLIS_ENABLE_GEOS}" CACHE BOOL
        "Build GEOS-backed iox geometry" FORCE)
    set(IOX_BUILD_WASM OFF CACHE BOOL "Disable nested iox WASM target" FORCE)
    set(IOX_BUILD_EXAMPLES OFF CACHE BOOL "Disable nested iox examples" FORCE)
    set(IOX_BUILD_TOOLS OFF CACHE BOOL "Disable nested iox tools" FORCE)
    set(IOX_ENABLE_JSON_FORMAT ON CACHE BOOL "Build iox JSON format support" FORCE)
    if(_interlis_ilic_fetched)
        set(IOX_ILIC_SOURCE_DIR "" CACHE PATH
            "Path to ilic source passed to iox" FORCE)
        set(IOX_FETCH_ILIC ON CACHE BOOL "Fetch the pinned ilic source through iox" FORCE)
        set(IOX_ILIC_VERSION "${INTERLIS_ILIC_VERSION}" CACHE STRING
            "ilic runtime version used by the extension" FORCE)
        set(IOX_ILIC_GIT_TAG "${INTERLIS_ILIC_COMMIT}" CACHE STRING
            "Exact ilic commit used by the extension" FORCE)
    else()
        set(IOX_ILIC_SOURCE_DIR "${_interlis_ilic_source_dir}" CACHE PATH
            "Path to ilic source passed to iox" FORCE)
        set(IOX_FETCH_ILIC OFF CACHE BOOL "Fetch the pinned ilic source through iox" FORCE)
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
        set(_interlis_iox_already_added TRUE)
        message(STATUS "Using fetched iox-cpp commit: ${INTERLIS_IOX_COMMIT}")
    endif()

    # iox is embedded once as a dependency of this extension. In particular, pass the
    # chosen ilic source into iox instead of adding ilic-core a second time here.
    if(NOT _interlis_iox_already_added)
        add_subdirectory(
            "${_interlis_iox_source_dir}"
            "${CMAKE_BINARY_DIR}/interlis-iox-build"
            EXCLUDE_FROM_ALL
        )
    endif()
endif()

set(EXTENSION_SOURCES
    src/interlis_extension.cpp
    src/functions/version_functions.cpp
    src/functions/model_functions.cpp
    src/functions/xtf_scan.cpp
    src/functions/xtf_values.cpp
    src/functions/xtf_set.cpp
    src/xtf/xtf_stream.cpp
    src/model/model_source_resolver.cpp
    src/model/compiled_model.cpp
)

include_directories(src/include)

build_static_extension(interlis ${EXTENSION_SOURCES})
build_loadable_extension(interlis " " ${EXTENSION_SOURCES})

target_compile_features(interlis_extension PRIVATE cxx_std_17)
target_compile_features(interlis_loadable_extension PRIVATE cxx_std_17)

target_include_directories(interlis_extension PRIVATE src)
target_include_directories(interlis_loadable_extension PRIVATE src)

target_compile_definitions(interlis_extension PRIVATE
    INTERLIS_EXTENSION_VERSION="${INTERLIS_EXTENSION_VERSION}"
    INTERLIS_ILIC_VERSION="${INTERLIS_ILIC_VERSION}"
    INTERLIS_IOX_VERSION="${INTERLIS_IOX_VERSION}"
    INTERLIS_DUCKDB_VERSION="${INTERLIS_DUCKDB_VERSION}"
    INTERLIS_GEOS_VERSION="${_interlis_geos_component_version}"
)
target_compile_definitions(interlis_loadable_extension PRIVATE
    INTERLIS_EXTENSION_VERSION="${INTERLIS_EXTENSION_VERSION}"
    INTERLIS_ILIC_VERSION="${INTERLIS_ILIC_VERSION}"
    INTERLIS_IOX_VERSION="${INTERLIS_IOX_VERSION}"
    INTERLIS_DUCKDB_VERSION="${INTERLIS_DUCKDB_VERSION}"
    INTERLIS_GEOS_VERSION="${_interlis_geos_component_version}"
)

target_link_libraries(interlis_extension iox::ilic ilic::core)
target_link_libraries(interlis_loadable_extension iox::ilic ilic::core)
# The DuckDB template exports the static extension target, so do not expose
# local ilic/iox targets through its install interface. The host executable
# still needs these libraries after the static extension archive.
set_property(TARGET interlis_extension PROPERTY INTERFACE_LINK_LIBRARIES "")
function(_interlis_append_native_link_libraries target_name)
    if(TARGET "${target_name}")
        # Host DuckDB binaries compile with DuckDB's C++ standard. LINK_ONLY
        # keeps the native dependency usage requirements out of compilation.
        # target_link_libraries is intentional here: when called from this
        # extension directory CMake preserves this directory's imported-target
        # lookup scope even though the DuckDB host target lives in its parent.
        target_link_libraries("${target_name}" PRIVATE
            "$<LINK_ONLY:iox::ilic>"
            "$<LINK_ONLY:ilic::core>"
        )
    endif()
endfunction()

cmake_language(DEFER DIRECTORY "${_DUCKDB_PROJECT_SRC_DIR}"
    CALL _interlis_append_native_link_libraries duckdb)
cmake_language(DEFER DIRECTORY "${_DUCKDB_PROJECT_SRC_DIR}"
    CALL _interlis_append_native_link_libraries unittest)
cmake_language(DEFER DIRECTORY "${_DUCKDB_PROJECT_SRC_DIR}"
    CALL _interlis_append_native_link_libraries shell)
cmake_language(DEFER DIRECTORY "${_DUCKDB_PROJECT_SRC_DIR}"
    CALL _interlis_append_native_link_libraries benchmark_runner)
cmake_language(DEFER DIRECTORY "${_DUCKDB_PROJECT_SRC_DIR}"
    CALL _interlis_append_native_link_libraries plan_serializer)

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
    target_compile_features(interlis_model_source_test PRIVATE cxx_std_17)
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
