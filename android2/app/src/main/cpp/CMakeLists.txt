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
target_link_libraries(magviewer_ncnn ncnn android log)
