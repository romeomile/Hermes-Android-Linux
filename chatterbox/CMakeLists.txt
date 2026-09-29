# Out-of-tree Android build for the Chatterbox (ggml) engine.
#
# Inputs: CHATTERBOX_CPP_DIR (a checkout of the chatterbox.cpp repo, ggml set up),
#         ANDROID_NDK / toolchain file from the Android SDK.
# Output: libchatterbox.so (arm64-v8a) with the JNI bridge inside.
cmake_minimum_required(VERSION 3.22)
project(chatterbox_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

if (NOT CHATTERBOX_CPP_DIR)
    message(FATAL_ERROR "Set CHATTERBOX_CPP_DIR to the chatterbox.cpp checkout.")
endif()

# Library only: no CLI, no per-stage test harnesses, no install rules.
set(TTS_CPP_BUILD_LIBRARY ON CACHE BOOL "" FORCE)
set(TTS_CPP_BUILD_EXECUTABLES OFF CACHE BOOL "" FORCE)
set(TTS_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(TTS_CPP_INSTALL OFF CACHE BOOL "" FORCE)
# Android has no OpenMP runtime in the NDK; ggml's own thread pool does the work.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(CMAKE_DISABLE_FIND_PACKAGE_OpenMP ON CACHE BOOL "" FORCE)
# One shared object for the APK: link ggml statically instead of shipping libggml*.so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# No host paths may reach the shipped artifact: __FILE__ / debug strings inside ggml and tts-cpp
# would otherwise carry the build machine's directory layout into the APK. Must be set BEFORE the
# subdirectories are added so their targets pick the flags up. Map the source checkout and the
# build tree to neutral prefixes.
add_compile_options(
    "-ffile-prefix-map=${CHATTERBOX_CPP_DIR}=/src"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=/build"
)

add_subdirectory(${CHATTERBOX_CPP_DIR} ${CMAKE_BINARY_DIR}/chatterbox_cpp)

add_library(chatterbox SHARED jni/chatterbox_jni.cpp)
target_link_libraries(chatterbox PRIVATE tts-cpp)

# 16 KB page size (Android 15+ devices) and a lean shared object.
target_compile_options(chatterbox PRIVATE -fvisibility=hidden -fexceptions -frtti)
target_link_options(chatterbox PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-Wl,--exclude-libs,ALL"
)
