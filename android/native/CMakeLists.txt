# 楼外端侧小模型推理库：把 llama.cpp 静态链接进单个 liboffloor_llm.so。
# 由 android/build_native.py 调用；LLAMA_DIR 指向固定版本的 llama.cpp 源码。
cmake_minimum_required(VERSION 3.22)
project(offloor_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT LLAMA_DIR)
    message(FATAL_ERROR "需要 -DLLAMA_DIR=<llama.cpp 源码目录>")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(ANDROID)
    # 2018 年以后的主流手机（Cortex-A55/A75 起）都支持；Java 侧加载前会检查 asimddp，旧手机直接不提供本机模型。
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} llama EXCLUDE_FROM_ALL)

add_library(offloor_llm SHARED offloor_llm.cpp)
target_link_libraries(offloor_llm PRIVATE llama)
if(ANDROID)
    target_link_libraries(offloor_llm PRIVATE log)
    target_link_options(offloor_llm PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -s
                        "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(offloor_llm PRIVATE ${JNI_INCLUDE_DIRS})
endif()
