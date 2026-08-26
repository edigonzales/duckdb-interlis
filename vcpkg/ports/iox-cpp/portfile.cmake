vcpkg_check_linkage(ONLY_STATIC_LIBRARY)

vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO edigonzales/iox-cpp
    REF c82fd5f5a2cd8c1a06eef7b98f492055fb954460
    SHA512 ef03614d2c1b3d048187e47d966e3174121f97e6a2bed6d0430487951ac8282c9dbe8813a17334fa0bdfb438da4821d80c01d31e15503c66656b80a9bae4898b
    HEAD_REF main
)

vcpkg_check_features(
    OUT_FEATURE_OPTIONS FEATURE_OPTIONS
    FEATURES
        ilic IOX_ENABLE_ILIC
        geos IOX_ENABLE_GEOS
)

vcpkg_cmake_configure(
    SOURCE_PATH "${SOURCE_PATH}"
    OPTIONS
        -DBUILD_TESTING=OFF
        -DIOX_BUILD_WASM=OFF
        -DIOX_BUILD_EXAMPLES=OFF
        -DIOX_BUILD_TOOLS=OFF
        -DIOX_FETCH_ILIC=OFF
        -DIOX_USE_SYSTEM_EXPAT=ON
        -DIOX_USE_SYSTEM_YYJSON=ON
        -DIOX_ENABLE_INSTALL=ON
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        ${FEATURE_OPTIONS}
)

vcpkg_cmake_install()
vcpkg_cmake_config_fixup(
    PACKAGE_NAME iox
    CONFIG_PATH lib/cmake/iox
)

file(REMOVE_RECURSE "${CURRENT_PACKAGES_DIR}/debug/include")
file(INSTALL "${CMAKE_CURRENT_LIST_DIR}/usage"
    DESTINATION "${CURRENT_PACKAGES_DIR}/share/${PORT}")
vcpkg_install_copyright(FILE_LIST "${SOURCE_PATH}/LICENSE")
