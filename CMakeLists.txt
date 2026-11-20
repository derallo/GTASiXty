cmake_minimum_required(VERSION 3.24)
project(GTASixty VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(MSVC)
  add_compile_options(/utf-8 /MP)
  add_definitions(-DNOMINMAX -DWIN32_LEAN_AND_MEAN -DUNICODE -D_UNICODE)
endif()

list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

set(GTA60_DXC_EXECUTABLE "" CACHE FILEPATH "Path to Windows SDK dxc.exe")
set(GTA60_OPENXR_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/openxr" CACHE PATH "Extracted OpenXR Loader nupkg root")
set(GTA60_NLOHMANN_JSON_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party" CACHE PATH "Directory containing nlohmann/json.hpp")

if(NOT GTA60_DXC_EXECUTABLE OR NOT EXISTS "${GTA60_DXC_EXECUTABLE}")
  find_program(GTA60_DXC_EXECUTABLE dxc REQUIRED)
endif()

find_package(MbedTLS 3 REQUIRED)

include(FetchContent)

# 1. Opus 1.5.2 (static)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
  opus
  GIT_REPOSITORY https://github.com/xiph/opus.git
  GIT_TAG v1.5.2
  GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(opus)

# 2. libdatachannel v0.22.5 (static, MbedTLS backend, media enabled)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(USE_MBEDTLS ON CACHE BOOL "" FORCE)
set(NO_WEBSOCKET ON CACHE BOOL "" FORCE)
set(NO_EXAMPLES ON CACHE BOOL "" FORCE)
set(NO_TESTS ON CACHE BOOL "" FORCE)
set(NO_MEDIA OFF CACHE BOOL "" FORCE)
set(WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)
set(USE_SYSTEM_SRTP OFF CACHE BOOL "" FORCE)
set(USE_SYSTEM_JUICE OFF CACHE BOOL "" FORCE)
set(USE_SYSTEM_USRSCTP OFF CACHE BOOL "" FORCE)
set(USE_SYSTEM_PLOG OFF CACHE BOOL "" FORCE)
set(USE_SYSTEM_JSON OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
  libdatachannel
  GIT_REPOSITORY https://github.com/paullouisageneau/libdatachannel.git
  GIT_TAG v0.22.5
  GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(libdatachannel)

# OpenXR Loader imported target
set(OPENXR_INCLUDE_DIR "${GTA60_OPENXR_ROOT}/include")
set(OPENXR_LIB_PATH "${GTA60_OPENXR_ROOT}/native/x64/release/lib/openxr_loader.lib")
set(OPENXR_DLL_PATH "${GTA60_OPENXR_ROOT}/native/x64/release/bin/openxr_loader.dll")

if(NOT EXISTS "${OPENXR_INCLUDE_DIR}/openxr/openxr.h")
  message(FATAL_ERROR "OpenXR headers missing under ${OPENXR_INCLUDE_DIR}. Run scripts/build.ps1.")
endif()

add_library(openxr_loader SHARED IMPORTED)
set_target_properties(openxr_loader PROPERTIES
  IMPORTED_IMPLIB "${OPENXR_LIB_PATH}"
  IMPORTED_LOCATION "${OPENXR_DLL_PATH}"
  INTERFACE_INCLUDE_DIRECTORIES "${OPENXR_INCLUDE_DIR}"
)

# Compile HLSL cs_6_0 shaders to DXIL
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/shaders")
set(GTA60_SHADERS
  Nv12ToRgb
  RgbToNv12
  LumaExtract
  DisOpticalFlow
  FlowRefine
  FlowFill
  FrameGenWarp
  EasuUpscale
  RcasSharpen
)
set(GTA60_SHADER_OUTPUTS "")
foreach(SHADER_NAME IN LISTS GTA60_SHADERS)
  set(HLSL_SRC "${CMAKE_CURRENT_SOURCE_DIR}/shaders/${SHADER_NAME}.hlsl")
  set(DXIL_OUT "${CMAKE_CURRENT_BINARY_DIR}/shaders/${SHADER_NAME}.dxil")
  add_custom_command(
    OUTPUT "${DXIL_OUT}"
    COMMAND "${GTA60_DXC_EXECUTABLE}" -T cs_6_0 -E main -O3 -WX -Fo "${DXIL_OUT}" "${HLSL_SRC}"
    DEPENDS "${HLSL_SRC}"
    COMMENT "Compiling HLSL shader ${SHADER_NAME}.hlsl -> ${SHADER_NAME}.dxil"
    VERBATIM
  )
  list(APPEND GTA60_SHADER_OUTPUTS "${DXIL_OUT}")
endforeach()
add_custom_target(gta60_shaders ALL DEPENDS ${GTA60_SHADER_OUTPUTS})

# Core library
add_library(gta60_core STATIC
  src/base/Log.cpp
  src/xbox/Https.cpp
  src/xbox/Account.cpp
  src/xbox/StreamApi.cpp
  src/xbox/WebRtcSession.cpp
  src/media/MfH264Decoder.cpp
  src/media/OpusWasapiAudio.cpp
  src/media/SyntheticGameSource.cpp
  src/nvof/NvOfSession.cpp
  src/pipeline/D3D12Context.cpp
  src/pipeline/EnhancementPipeline.cpp
  src/sink/NvencD3D12Encoder.cpp
  src/sink/QuestStreamServer.cpp
  src/xr/OpenXrQuadSink.cpp
)
add_dependencies(gta60_core gta60_shaders)

target_include_directories(gta60_core PUBLIC
  "${CMAKE_CURRENT_SOURCE_DIR}/include"
  "${GTA60_NLOHMANN_JSON_DIR}"
  "${OPENXR_INCLUDE_DIR}"
)

target_link_libraries(gta60_core PUBLIC
  datachannel-static
  opus
  openxr_loader
  d3d12
  d3d11
  dxgi
  dxguid
  winhttp
  crypt32
  bcrypt
  ws2_32
  shell32
  ole32
  oleaut32
  uuid
  mfplat
  mfuuid
  mf
  iphlpapi
  wmcodecdspuuid
  xinput
  winmm
)

# Main executable
add_executable(GTASixty src/main.cpp)
target_link_libraries(GTASixty PRIVATE gta60_core)
add_custom_command(TARGET GTASixty POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different "${OPENXR_DLL_PATH}" "$<TARGET_FILE_DIR:GTASixty>"
)

# Automated unit & GPU integration tests
add_executable(GTASixty_tests tests/PipelineAndXboxTests.cpp)
target_link_libraries(GTASixty_tests PRIVATE gta60_core)
add_custom_command(TARGET GTASixty_tests POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different "${OPENXR_DLL_PATH}" "$<TARGET_FILE_DIR:GTASixty_tests>"
)
