cmake_minimum_required(VERSION 3.22.1)
project(magviewer_ncnn)

set(CMAKE_CXX_STANDARD 17)

# Prebuilt ncnn with Vulkan (Tencent/ncnn release, arm64-v8a only — see lib/README.md)
get_filename_component(PROJ_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../.. ABSOLUTE)
set(NCNN_DIR ${PROJ_DIR}/lib/ncnn-20260526-android-vulkan/${ANDROID_ABI})
message(STATUS "ncnn from ${NCNN_DIR}")
set(ncnn_DIR ${NCNN_DIR}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(magviewer_ncnn SHARED ncnn_upscaler.cpp)
# Optimise even in the debug variant: AGP's Debug build type passes no -O at all, so
# clang built the per-frame input/palette loops at -O0 (post-processing measured 12 ms
# on the phone). Target options land after the build-type flags, so this wins.
target_compile_options(magviewer_ncnn PRIVATE -O2)
target_link_libraries(magviewer_ncnn ncnn android log)
