# CMakeLists for the Rendera native vision engine.
#
# There is deliberately no OpenCV dependency. Everything the engine needs
# (SAD block matching, Sobel/NMS/hysteresis Canny, Hough ring voting, connected
# components, the alpha-beta tracker and the CPA solver) is a few hundred lines
# of tight, allocation-free integer/float code in rendera_native.cpp. Vendoring
# a ~70 MB OpenCV AAR would add size and licence obligations without making the
# per-frame cost any lower at our working resolution of 240 columns.

cmake_minimum_required(VERSION 3.22.1)

project("rendera_native" VERSION 3.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# The engine is a leaf library; link the STL statically so the APK does not have
# to ship libc++_shared.so alongside it.
set(ANDROID_STL c++_static)

# -ffast-math is NOT used: the tracker and the CPA solver rely on strict IEEE
# semantics for the hypot/sqrt calls, and the win is negligible for integer-
# dominated code.
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O1 -g")

find_library(log-lib log)
find_library(android-lib android)

add_library(rendera_native SHARED rendera_native.cpp)

target_include_directories(rendera_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(rendera_native PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    $<$<CONFIG:Release>:-flto>
)

# NEON is mandatory for the per-frame cost. arm64 always has it; on armv7 it has
# to be requested explicitly and the FPU ABI has to match the NDK default.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(rendera_native PRIVATE
        -march=armv7-a
        -mfpu=neon
        -mfloat-abi=softfp
    )
endif()

target_link_options(rendera_native PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
    -Wl,--as-needed
)

target_link_libraries(rendera_native
    ${log-lib}
    ${android-lib}
    android
)
