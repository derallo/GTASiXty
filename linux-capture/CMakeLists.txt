cmake_minimum_required(VERSION 3.20)
project(gta60-capture VERSION 0.1.0 LANGUAGES CXX)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(PkgConfig REQUIRED)
find_package(Threads REQUIRED)

pkg_check_modules(AVCODEC REQUIRED IMPORTED_TARGET libavcodec)
pkg_check_modules(AVUTIL REQUIRED IMPORTED_TARGET libavutil)
pkg_check_modules(SWSCALE REQUIRED IMPORTED_TARGET libswscale)
pkg_check_modules(ALSA IMPORTED_TARGET alsa)

add_library(gta60-capture-core STATIC
  src/Gt60Server.cpp
  src/VaapiEncoder.cpp
  src/V4l2Capture.cpp
  src/AlsaCapture.cpp
)

target_include_directories(gta60-capture-core PUBLIC
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
)

target_link_libraries(gta60-capture-core PUBLIC
  PkgConfig::AVCODEC
  PkgConfig::AVUTIL
  PkgConfig::SWSCALE
  Threads::Threads
)

if(ALSA_FOUND)
  target_compile_definitions(gta60-capture-core PUBLIC HAVE_ALSA=1)
  target_link_libraries(gta60-capture-core PUBLIC PkgConfig::ALSA)
  message(STATUS "ALSA found: HDMI UAC audio capture enabled")
else()
  message(WARNING "ALSA dev headers (libasound2-dev) not found: building without audio capture")
endif()

add_executable(gta60-capture
  src/main.cpp
)
target_link_libraries(gta60-capture PRIVATE gta60-capture-core)

enable_testing()
add_executable(gta60-capture-tests
  tests/CaptureLinuxTests.cpp
)
target_link_libraries(gta60-capture-tests PRIVATE gta60-capture-core)
add_test(NAME gta60_capture_tests COMMAND gta60-capture-tests)
