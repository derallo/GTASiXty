# CMake find module bridging FetchContent mbedtls 2.28.x targets to bundled curl.
set(MBEDTLS_FOUND TRUE)
set(MbedTLS_FOUND TRUE)
set(MBEDTLS_VERSION "2.28.9")
set(MBEDTLS_INCLUDE_DIRS "${CHIAKI_MBEDTLS_INCLUDE_DIR}")
set(MBEDTLS_LIBRARIES mbedtls mbedx509 mbedcrypto)
set(MBEDTLS_LIBRARY_DIRS "")
set(MBEDTLS_PC_REQUIRES "")
