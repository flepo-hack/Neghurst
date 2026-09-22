# CMakeLists.txt for Rendera Native Pipeline
# High-performance zero-copy C++ OpenCV / NDK Screen Threat Detection Engine

cmake_minimum_required(VERSION 3.22.1)

project("rendera_native" VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization flags for ARM64-v8a / NEON vectorization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -flto -DNDEBUG")

# Find Android log library
find_library(log-lib log)
find_library(android-lib android)

# Optional OpenCV integration:
# When OpenCV Android SDK is provided, set OpenCV_DIR to <path>/sdk/native/jni
# and uncomment find_package(OpenCV REQUIRED)
#
# if(OpenCV_DIR)
#     find_package(OpenCV REQUIRED)
#     include_directories(${OpenCV_INCLUDE_DIRS})
# endif()

# Source files
set(SOURCE_FILES
    rendera_native.cpp
)

# Header files
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
)

add_library(
    rendera_native
    SHARED
    ${SOURCE_FILES}
)

target_link_libraries(
    rendera_native
    ${log-lib}
    ${android-lib}
    # ${OpenCV_LIBS} # Link when OpenCV NDK SDK is configured
)
