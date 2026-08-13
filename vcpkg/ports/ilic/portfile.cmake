vcpkg_check_linkage(ONLY_STATIC_LIBRARY)

vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO edigonzales/ilic-fork
    REF cd74490b1fddfe38ac80288067e1af0dd800e8da
    SHA512 093feb16c0bea02391380fedc6b1eedcaf067f40bfb82f26e377d69f638443c7c6bb2b857bc0ec17437611ae0ea70508d1524804b7f35c531ebdecf589afc79e
    HEAD_REF main
)

vcpkg_cmake_configure(
    SOURCE_PATH "${SOURCE_PATH}"
    OPTIONS
        -DBUILD_TESTING=OFF
        -DILIC_BUILD_TESTS=OFF
        -DILIC_BUILD_CLI=OFF
        -DILIC_ENABLE_NATIVE_REPOSITORY=OFF
        -DILIC_STATIC_DISTRIBUTION=OFF
        -DILIC_ENABLE_INSTALL=ON
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
)

vcpkg_cmake_install()
vcpkg_cmake_config_fixup(
    PACKAGE_NAME ilic
    CONFIG_PATH lib/cmake/ilic
)

file(REMOVE_RECURSE "${CURRENT_PACKAGES_DIR}/debug/include")
file(INSTALL "${CMAKE_CURRENT_LIST_DIR}/usage"
    DESTINATION "${CURRENT_PACKAGES_DIR}/share/${PORT}")
vcpkg_install_copyright(FILE_LIST "${SOURCE_PATH}/LICENSE")
