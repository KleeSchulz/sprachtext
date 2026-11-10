cmake_minimum_required(VERSION 3.18)
project(sprachtext C CXX)

set(CMAKE_CXX_STANDARD 17)

# Immer optimiert bauen, auch bei der Debug-APK (sonst ist Whisper extrem langsam)
set(CMAKE_BUILD_TYPE Release)
foreach(cfg DEBUG RELEASE RELWITHDEBINFO)
    set(CMAKE_C_FLAGS_${cfg}   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS_${cfg} "-O3 -DNDEBUG" CACHE STRING "" FORCE)
endforeach()

# whisper.cpp als Quelltext holen (feste Version)
include(FetchContent)
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
# ARMv8.2 mit Dot-Product und FP16: alle gängigen Handys ab ca. 2018 (Cortex-A55/A75 und neuer)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

FetchContent_Declare(whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz)
FetchContent_MakeAvailable(whisper)

add_library(sprachtext SHARED jni.cpp)
target_link_libraries(sprachtext whisper log)
target_link_options(sprachtext PRIVATE -Wl,--gc-sections)
