cmake_minimum_required(VERSION 3.22.1)
project(rendera_native CXX)

# ---------------------------------------------------------------------------
# Rendera native vision engine
# ---------------------------------------------------------------------------
# The engine is deliberately split into two translation units:
#
#   rendera_core.cpp   - the hand written, dependency free computer vision core.
#                       Global motion estimation by phase correlation, motion
#                       compensated background subtraction, connected component
#                       blob extraction, constant velocity Kalman tracking and
#                       closest-point-of-approach collision solving. This is the
#                       real hot path and it must always build.
#
#   rendera_opencv.cpp - optional accelerated implementations that are compiled
#                       ONLY when a real OpenCV Android SDK is provided (see
#                       RENDERA_OPENCV_SDK below). When it is absent the core
#                       fallbacks are used; no behaviour silently degrades to a
#                       stub, the core is complete on its own.
#
# RENDERA_OPENCV_SDK resolution order:
#   1. -DRENDERA_OPENCV_SDK=/path/to/OpenCV-android-sdk
#   2. $ENV{RENDERA_OPENCV_SDK}
#   3. ${CMAKE_CURRENT_SOURCE_DIR}/opencv   (vendored OpenCV-android-sdk)
# ---------------------------------------------------------------------------

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

add_library(rendera_native SHARED
  rendera_core.cpp
  rendera_jni.cpp
)

target_compile_options(rendera_native PRIVATE
  -Wall
  -Wextra
  -Wno-unused-parameter
  $<$<CONFIG:Release>:-O3>
  $<$<CONFIG:RelWithDebInfo>:-O2>
  # NOTE: -ffast-math is deliberately NOT enabled. It implies
  # -ffinite-math-only, which folds std::isfinite() into a constant `true` and
  # would let a NaN shift reach std::lround (undefined behaviour). The
  # per-pixel kernels here are memory bound rather than ALU bound, so -O3 wins
  # nearly the same amount anyway and the NaN guard keeps working.
)

target_link_libraries(rendera_native
  log
  android
)

# ---------------------------------------------------------------------------
# Optional: real OpenCV Android SDK.
#
# NOTE ON APK SIZE: bundling the prebuilt `org.opencv:opencv` AAR inflates the
# APK by ~70 MB but does NOT export the C++ `cv::` symbols (OpenCV's Android
# builds compile with -fvisibility=hidden), so NDK code cannot call into it.
# Calling `cv::*` from our own NDK code therefore requires the OpenCV Android
# SDK (static libs + headers), which is what this block wires up.
# ---------------------------------------------------------------------------
set(_opencv_sdk "")
if(RENDERA_OPENCV_SDK)
  set(_opencv_sdk "${RENDERA_OPENCV_SDK}")
elseif(DEFINED ENV{RENDERA_OPENCV_SDK})
  set(_opencv_sdk "$ENV{RENDERA_OPENCV_SDK}")
elseif(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/opencv/sdk/jni")
  set(_opencv_sdk "${CMAKE_CURRENT_SOURCE_DIR}/opencv")
endif()

if(_opencv_sdk AND EXISTS "${_opencv_sdk}/sdk/jni/abi-arm64-v8a")
  set(OpenCV_DIR "${_opencv_sdk}/sdk/jni" CACHE PATH "" FORCE)
  # QUIET, not REQUIRED: an incomplete SDK must fall through to the core
  # rather than aborting CMake configuration outright.
  find_package(OpenCV QUIET MODULE_PATHS "${_opencv_sdk}/sdk/jni")
  if(OpenCV_FOUND)
    target_sources(rendera_native PRIVATE rendera_opencv.cpp)
    target_compile_definitions(rendera_native PRIVATE RENDERA_WITH_OPENCV=1)
    target_include_directories(rendera_native PRIVATE ${OpenCV_INCLUDE_DIRS})
    target_link_libraries(rendera_native ${OpenCV_LIBS})
    message(STATUS "Rendera: OpenCV accelerated path ENABLED (${OpenCV_VERSION})")
  else()
    message(STATUS "Rendera: OpenCV SDK found but incomplete; using dependency free core.")
  endif()
else()
  message(STATUS "Rendera: no OpenCV Android SDK; using dependency free core. "
                 "Set RENDERA_OPENCV_SDK to enable cv:: accelerated kernels.")
endif()
