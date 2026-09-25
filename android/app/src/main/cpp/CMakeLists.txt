#
#  CMakeLists.txt — builds the Yijing JNI bridge (`libyijingllm.so`) against the
#  prebuilt MNN 3.6.1 runtime that ships inside `src/main/jniLibs/<abi>/`.
#
#  Why no MNN submodule / no MNN sources here:
#  the official MNN Android release archive
#  (mnn_3.6.1_android_armv7_armv8_cpu_opencl_vulkan) already contains `libllm.so`
#  (MNN::Transformer) and every backend we need (CPU, OpenCL, Vulkan). Only the
#  ~100 lines of Kotlin-facing glue have to be compiled locally, so the app build
#  stays fast and the repo stays small.
#
#  ABI coverage: arm64-v8a (Qualcomm Snapdragon / Huawei Kirin 64-bit)
#                armeabi-v7a (older 32-bit devices). Both were imported from the
#                same official archive, so the bridged API is identical.
#

cmake_minimum_required(VERSION 3.22.1)

project(yijingllm CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "Use the Android NDK toolchain (see app/build.gradle.kts).")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ------------------------------------------------------------------ prebuilts
# `jniLibs` is the single source of truth for the MNN runtime. Using ${ANDROID_ABI}
# keeps the 32-bit and 64-bit builds honest: a missing folder is a hard error
# instead of a silently skipped ABI.
set(YJ_PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

if(NOT EXISTS "${YJ_PREBUILT_DIR}/libllm.so")
    message(FATAL_ERROR "Prebuilt MNN runtime missing: ${YJ_PREBUILT_DIR}/libllm.so")
endif()

# Everything libllm.so needs at load time (verified with llvm-readelf -d):
#   libllm.so -> libMNN_Vulkan.so, libMNN_CL.so, libMNNOpenCV.so, libMNNAudio.so,
#                libMNN_Express.so, libMNN.so, libc++_shared.so
# so all of them must be visible to the linker and, later, to the app's
# native-library search path (jniLibs == the APK's lib/<abi>/ folder).
set(YJ_MNN_LIBS llm MNN MNN_Express MNN_CL MNN_Vulkan MNNOpenCV MNNAudio mnncore)

foreach(yj_lib IN LISTS YJ_MNN_LIBS)
    add_library(${yj_lib} SHARED IMPORTED)
    set_target_properties(${yj_lib} PROPERTIES
        IMPORTED_LOCATION "${YJ_PREBUILT_DIR}/lib${yj_lib}.so")
endforeach()

# ------------------------------------------------------------------- the bridge
add_library(yijingllm SHARED
    yijing_llm_jni.cpp
)

target_include_directories(yijingllm PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

# MNN_USE_LOGCAT routes MNN_ERROR / MNN_PRINT (which the engine headers use
# heavily) into logcat under the "MNNJNI" tag instead of stdout, where Android
# would otherwise swallow them. Kept in sync with the prebuilt .so behaviour.
target_compile_definitions(yijingllm PRIVATE
    MNN_USE_LOGCAT=1
)

# NOTE: no -fno-rtti — MNN's Expr headers rely on std::dynamic_pointer_cast.
target_compile_options(yijingllm PRIVATE
    -fvisibility=hidden
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_libraries(yijingllm
    android
    log
    ${YJ_MNN_LIBS}
)

# Android 15+ requires 16 KiB page alignment; without this the APK installs but
# native libraries are rejected on such devices.
target_link_options(yijingllm PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
)
