# Native part of Daily Whisper: only the Hy-MT translator (llama.cpp, CPU, arm64).
# llama.cpp is vendored (pruned) in ./llama.cpp, pinned to PR #22836 (STQ1_0 kernel) - see llama.cpp/VENDORED.md.
cmake_minimum_required(VERSION 3.22.1)
project(dwhymt CXX C)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
if (ANDROID)
    # Dot-product NEON (every arm64 phone since ~2018, incl. the Fold 8): uses the faster STQ1_0 kernel path.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
add_subdirectory(llama.cpp)

if (ANDROID)
    add_library(dwhymt SHARED hymt_jni.cpp hymt_engine.cpp)
    target_link_libraries(dwhymt PRIVATE llama)
    target_link_options(dwhymt PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()
