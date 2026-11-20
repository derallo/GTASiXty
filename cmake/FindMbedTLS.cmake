# FindMbedTLS.cmake - Provides both MbedTLS::* targets and uppercase/mixed-case variables
# required by libdatachannel and libsrtp.

include(FindPackageHandleStandardArgs)

find_path(MbedTLS_INCLUDE_DIR
  NAMES mbedtls/ssl.h
  PATH_SUFFIXES include
)

find_library(MbedTLS_LIBRARY
  NAMES mbedtls libmbedtls
  PATH_SUFFIXES lib
)

find_library(MbedCrypto_LIBRARY
  NAMES mbedcrypto libmbedcrypto
  PATH_SUFFIXES lib
)

find_library(MbedX509_LIBRARY
  NAMES mbedx509 libmbedx509
  PATH_SUFFIXES lib
)

if(EXISTS "${MbedTLS_INCLUDE_DIR}/mbedtls/build_info.h")
  file(STRINGS "${MbedTLS_INCLUDE_DIR}/mbedtls/build_info.h" _VERSION_STRING
       REGEX "#define[ \t]+MBEDTLS_VERSION_STRING[ \t]+.+")
  string(REGEX REPLACE ".*#define[ \t]+MBEDTLS_VERSION_STRING[ \t]+\"(.+)\".*" "\\1" MbedTLS_VERSION
                       "${_VERSION_STRING}")
else()
  set(MbedTLS_VERSION "3.6.2")
endif()

find_package_handle_standard_args(MbedTLS
  REQUIRED_VARS MbedTLS_LIBRARY MbedCrypto_LIBRARY MbedX509_LIBRARY MbedTLS_INCLUDE_DIR
  VERSION_VAR MbedTLS_VERSION
)

if(MbedTLS_FOUND)
  if(NOT TARGET MbedTLS::MbedCrypto)
    add_library(MbedTLS::MbedCrypto STATIC IMPORTED)
    set_target_properties(MbedTLS::MbedCrypto PROPERTIES
      IMPORTED_LOCATION "${MbedCrypto_LIBRARY}"
      INTERFACE_INCLUDE_DIRECTORIES "${MbedTLS_INCLUDE_DIR}"
    )
  endif()
  if(NOT TARGET MbedTLS::MbedX509)
    add_library(MbedTLS::MbedX509 STATIC IMPORTED)
    set_target_properties(MbedTLS::MbedX509 PROPERTIES
      IMPORTED_LOCATION "${MbedX509_LIBRARY}"
      INTERFACE_INCLUDE_DIRECTORIES "${MbedTLS_INCLUDE_DIR}"
      INTERFACE_LINK_LIBRARIES "MbedTLS::MbedCrypto"
    )
  endif()
  if(NOT TARGET MbedTLS::MbedTLS)
    add_library(MbedTLS::MbedTLS STATIC IMPORTED)
    set_target_properties(MbedTLS::MbedTLS PROPERTIES
      IMPORTED_LOCATION "${MbedTLS_LIBRARY}"
      INTERFACE_INCLUDE_DIRECTORIES "${MbedTLS_INCLUDE_DIR}"
      INTERFACE_LINK_LIBRARIES "MbedTLS::MbedCrypto;MbedTLS::MbedX509;ws2_32;bcrypt"
    )
  endif()

  set(MbedTLS_LIBRARIES MbedTLS::MbedTLS MbedTLS::MbedCrypto MbedTLS::MbedX509)
  set(MBEDTLS_LIBRARIES MbedTLS::MbedTLS MbedTLS::MbedCrypto MbedTLS::MbedX509)
  set(MBEDTLS_INCLUDE_DIRS "${MbedTLS_INCLUDE_DIR}")
  set(MbedTLS_INCLUDE_DIRS "${MbedTLS_INCLUDE_DIR}")
endif()
