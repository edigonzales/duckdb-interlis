vcpkg_check_linkage(ONLY_STATIC_LIBRARY)

vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO edigonzales/ilic-fork
    REF e901af64247082b5164252b675d87bd7a2aa829d
    SHA512 da4164656eee321e2d9c2f8d405624f2168eefcd6d34bba7211ebb5fe2bb016297e423df8ab5966431328ff67bd6440efb3a7381facdde4749b64966f59a62cd
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
