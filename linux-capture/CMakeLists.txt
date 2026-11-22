cmake_minimum_required(VERSION 3.20)
project(gta60-capture VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(PkgConfig REQUIRED)
find_package(Threads REQUIRED)

pkg_check_modules(AVCODEC REQUIRED IMPORTED_TARGET libavcodec)
pkg_check_modules(AVUTIL REQUIRED IMPORTED_TARGET libavutil)
pkg_check_modules(SWSCALE REQUIRED IMPORTED_TARGET libswscale)
pkg_check_modules(ALSA IMPORTED_TARGET alsa)

add_executable(gta60-capture
  src/main.cpp
  src/Gt60Server.cpp
  src/VaapiEncoder.cpp
  src/V4l2Capture.cpp
  src/AlsaCapture.cpp
)

target_include_directories(gta60-capture PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
)

target_link_libraries(gta60-capture PRIVATE
  PkgConfig::AVCODEC
  PkgConfig::AVUTIL
  PkgConfig::SWSCALE
  Threads::Threads
)

if(ALSA_FOUND)
  target_compile_definitions(gta60-capture PRIVATE HAVE_ALSA=1)
  target_link_libraries(gta60-capture PRIVATE PkgConfig::ALSA)
  message(STATUS "ALSA found: HDMI UAC audio capture enabled")
else()
  message(WARNING "ALSA dev headers (libasound2-dev) not found: building without audio capture")
endif()
