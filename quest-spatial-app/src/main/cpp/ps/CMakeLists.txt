cmake_minimum_required(VERSION 3.22)
project(gtaps LANGUAGES C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED CHIAKI_NG_DIR OR NOT EXISTS "${CHIAKI_NG_DIR}/lib/CMakeLists.txt")
    message(FATAL_ERROR "CHIAKI_NG_DIR is missing or invalid: ${CHIAKI_NG_DIR}")
endif()

find_package(Python3 COMPONENTS Interpreter REQUIRED)
set(PYTHON_EXECUTABLE "${Python3_EXECUTABLE}" CACHE FILEPATH "" FORCE)

list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" "${CHIAKI_NG_DIR}/cmake")

include(FetchContent)

# 1. MbedTLS 2.28.9 (matching chiaki-ng lib/src/ecdh.c and lib/src/rpcrypt.c APIs)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
set(ENABLE_ZLIB_SUPPORT OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    mbedtls
    URL https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v2.28.9.tar.gz
    URL_HASH SHA256=e4dbcf86a4fb31506482888560f02b161e0ecfb82fee0643abcfc86abee5817e
)
FetchContent_MakeAvailable(mbedtls)
set(CHIAKI_MBEDTLS_INCLUDE_DIR "${mbedtls_SOURCE_DIR}/include" CACHE PATH "" FORCE)
set(MBEDTLS_INCLUDE_DIR "${mbedtls_SOURCE_DIR}/include" CACHE PATH "" FORCE)

# Wrap static mbedTLS targets in IMPORTED INTERFACE targets so curl's export(TARGETS ...) does not
# fail complaining that mbedtls/mbedx509/mbedcrypto are not in curl's export set.
add_library(ChiakiMbed::tls INTERFACE IMPORTED GLOBAL)
set_property(TARGET ChiakiMbed::tls PROPERTY INTERFACE_INCLUDE_DIRECTORIES "${mbedtls_SOURCE_DIR}/include")
set_property(TARGET ChiakiMbed::tls PROPERTY INTERFACE_LINK_DIRECTORIES "${mbedtls_BINARY_DIR}/library")
set_property(TARGET ChiakiMbed::tls PROPERTY INTERFACE_LINK_LIBRARIES "-lmbedtls;-lmbedx509;-lmbedcrypto")

add_library(ChiakiMbed::x509 INTERFACE IMPORTED GLOBAL)
set_property(TARGET ChiakiMbed::x509 PROPERTY INTERFACE_LINK_DIRECTORIES "${mbedtls_BINARY_DIR}/library")
set_property(TARGET ChiakiMbed::x509 PROPERTY INTERFACE_LINK_LIBRARIES "-lmbedx509")

add_library(ChiakiMbed::crypto INTERFACE IMPORTED GLOBAL)
set_property(TARGET ChiakiMbed::crypto PROPERTY INTERFACE_LINK_DIRECTORIES "${mbedtls_BINARY_DIR}/library")
set_property(TARGET ChiakiMbed::crypto PROPERTY INTERFACE_LINK_LIBRARIES "-lmbedcrypto")

set(MBEDTLS_LIBRARY ChiakiMbed::tls CACHE STRING "" FORCE)
set(MBEDX509_LIBRARY ChiakiMbed::x509 CACHE STRING "" FORCE)
set(MBEDCRYPTO_LIBRARY ChiakiMbed::crypto CACHE STRING "" FORCE)
set(MBEDTLS "mbedtls" CACHE STRING "" FORCE)
set(MBEDX509 "mbedx509" CACHE STRING "" FORCE)
set(MBEDCRYPTO "mbedcrypto" CACHE STRING "" FORCE)

# 2. Opus 1.5.2 (static libopus for ChiakiOpusDecoder)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    opus
    URL https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz
    URL_HASH SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
)
FetchContent_MakeAvailable(opus)
set_target_properties(opus PROPERTIES POSITION_INDEPENDENT_CODE ON)
file(COPY "${opus_SOURCE_DIR}/include/" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/opus-inc/opus")
set(Opus_FOUND TRUE CACHE BOOL "" FORCE)
set(Opus_INCLUDE_DIRS "${CMAKE_CURRENT_BINARY_DIR}/opus-inc" CACHE PATH "" FORCE)
set(Opus_LIBRARIES opus CACHE STRING "" FORCE)

# 3. json-c (copied header bridge instead of Windows symlinks)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_APPS OFF CACHE BOOL "" FORCE)
set(DISABLE_EXTRA_LIBS ON CACHE BOOL "" FORCE)
set(DISABLE_BSON_OUTPUT ON CACHE BOOL "" FORCE)
FetchContent_Declare(
    json-c
    URL https://github.com/json-c/json-c/archive/refs/tags/json-c-0.17-20230812.tar.gz
)
FetchContent_MakeAvailable(json-c)
set(JSONC_PREFIX_INCLUDE_DIR "${CMAKE_CURRENT_BINARY_DIR}/_deps/json-c-prefix-include")
file(MAKE_DIRECTORY "${JSONC_PREFIX_INCLUDE_DIR}/json-c")
file(GLOB JSONC_HEADERS "${json-c_SOURCE_DIR}/*.h" "${json-c_BINARY_DIR}/*.h")
file(COPY ${JSONC_HEADERS} DESTINATION "${JSONC_PREFIX_INCLUDE_DIR}/json-c")

# 4. miniupnpc (copied header bridge instead of Windows symlinks)
set(UPNPC_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(UPNPC_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(UPNPC_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(UPNPC_BUILD_SAMPLE OFF CACHE BOOL "" FORCE)
set(UPNPC_NO_INSTALL ON CACHE BOOL "" FORCE)
FetchContent_Declare(
    miniupnpc
    URL https://github.com/miniupnp/miniupnp/archive/refs/tags/miniupnpc_2_2_8.tar.gz
    SOURCE_SUBDIR miniupnpc
)
FetchContent_MakeAvailable(miniupnpc)
set(MINIUPNPC_PREFIX_INCLUDE_DIR "${CMAKE_CURRENT_BINARY_DIR}/_deps/miniupnpc-prefix-include")
file(MAKE_DIRECTORY "${MINIUPNPC_PREFIX_INCLUDE_DIR}/miniupnpc")
file(COPY "${miniupnpc_SOURCE_DIR}/miniupnpc/include/" DESTINATION "${MINIUPNPC_PREFIX_INCLUDE_DIR}/miniupnpc")

# 5. chiaki-ng flags + bundled curl (with mbedTLS) + libchiaki
set(CHIAKI_ENABLE_ANDROID ON CACHE BOOL "" FORCE)
set(CHIAKI_IS_SWITCH OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_CLI OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_GUI OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_SETSU OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_STEAM_SHORTCUT OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_STEAMDECK_NATIVE OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_FFMPEG_DECODER OFF CACHE BOOL "" FORCE)
set(CHIAKI_ENABLE_PI_DECODER OFF CACHE BOOL "" FORCE)
set(CHIAKI_LIB_ENABLE_OPUS ON CACHE BOOL "" FORCE)
set(CHIAKI_LIB_ENABLE_MBEDTLS ON CACHE BOOL "" FORCE)
set(CHIAKI_LIB_MBEDTLS_EXTERNAL_PROJECT OFF CACHE BOOL "" FORCE)
set(CHIAKI_LIB_OPENSSL_EXTERNAL_PROJECT OFF CACHE BOOL "" FORCE)
set(CHIAKI_LIB_JSONC_EXTERNAL_PROJECT ON CACHE BOOL "" FORCE)
set(CHIAKI_LIB_MINIUPNPC_EXTERNAL_PROJECT ON CACHE BOOL "" FORCE)
set(CHIAKI_USE_SYSTEM_JERASURE OFF CACHE STRING "" FORCE)
set(CHIAKI_USE_SYSTEM_NANOPB OFF CACHE STRING "" FORCE)
set(CHIAKI_USE_SYSTEM_CURL OFF CACHE STRING "" FORCE)

set(CURL_ENABLE_SSL ON CACHE BOOL "" FORCE)
set(CURL_USE_MBEDTLS ON CACHE BOOL "" FORCE)
set(CURL_USE_OPENSSL OFF CACHE BOOL "" FORCE)
set(CURL_DISABLE_LDAP ON CACHE BOOL "" FORCE)
set(CURL_DISABLE_LDAPS ON CACHE BOOL "" FORCE)
set(USE_LIBIDN2 OFF CACHE BOOL "" FORCE)
set(CURL_ZLIB OFF CACHE BOOL "" FORCE)
set(CURL_BROTLI OFF CACHE BOOL "" FORCE)
set(CURL_ZSTD OFF CACHE BOOL "" FORCE)
set(USE_NGHTTP2 OFF CACHE BOOL "" FORCE)
set(CURL_USE_LIBPSL OFF CACHE BOOL "" FORCE)
set(CURL_USE_LIBSSH2 OFF CACHE BOOL "" FORCE)
set(CURL_USE_LIBUV OFF CACHE BOOL "" FORCE)
set(CURL_CA_BUNDLE "/system/etc/security/cacerts" CACHE STRING "" FORCE)
set(CURL_CA_PATH "/system/etc/security/cacerts" CACHE STRING "" FORCE)

set(CHIAKI_VERSION_MAJOR 1)
set(CHIAKI_VERSION_MINOR 10)
set(CHIAKI_VERSION_PATCH 1)
set(CHIAKI_VERSION "1.10.1")

add_definitions(
    -DCHIAKI_LIB_ENABLE_MBEDTLS
    -DCHIAKI_VERSION_MAJOR=${CHIAKI_VERSION_MAJOR}
    -DCHIAKI_VERSION_MINOR=${CHIAKI_VERSION_MINOR}
    -DCHIAKI_VERSION_PATCH=${CHIAKI_VERSION_PATCH}
    -DCHIAKI_VERSION="${CHIAKI_VERSION}"
)

add_subdirectory("${CHIAKI_NG_DIR}/third-party" "${CMAKE_CURRENT_BINARY_DIR}/chiaki-third-party")
if(TARGET libcurl_static)
    add_dependencies(libcurl_static mbedtls mbedx509 mbedcrypto)
endif()
add_subdirectory("${CHIAKI_NG_DIR}/lib" "${CMAKE_CURRENT_BINARY_DIR}/chiaki-lib")

target_include_directories(chiaki-lib PRIVATE "${mbedtls_SOURCE_DIR}/include")
if(TARGET libcurl_static)
    target_include_directories(libcurl_static PRIVATE "${mbedtls_SOURCE_DIR}/include")
endif()

add_library(gtaps SHARED ps_chiaki_jni.cpp)
set_target_properties(gtaps PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    C_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
target_compile_options(gtaps PRIVATE -O2 -fno-rtti -Wall -Wextra -Wno-unused-parameter)
target_link_options(gtaps PRIVATE
    -static-libstdc++
    -static-libgcc
    -Wl,-z,max-page-size=16384
    -Wl,--no-undefined
)
target_include_directories(gtaps PRIVATE
    "${CHIAKI_NG_DIR}/lib/include"
    "${CMAKE_CURRENT_BINARY_DIR}/chiaki-lib/include"
)
find_library(ANDROID_LOG_LIB log)
target_link_libraries(gtaps PRIVATE
    chiaki-lib
    opus
    mbedtls
    mbedx509
    mbedcrypto
    "${ANDROID_LOG_LIB}"
)
