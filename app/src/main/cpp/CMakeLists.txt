cmake_minimum_required(VERSION 3.22)
project(omni_llm C CXX)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)

# Auto-enable universal Android Vulkan GPU acceleration when Vulkan-Headers + SPIRV-Headers
# (prepared by scripts/build_yue2_android.sh into build/vulkan-deps) and NDK glslc are present.
set(OMNI_VULKAN_DEPS "${CMAKE_CURRENT_LIST_DIR}/../../../../build/vulkan-deps")
find_program(OMNI_NDK_GLSLC NAMES glslc
    HINTS
        "${ANDROID_NDK}/shader-tools/darwin-x86_64"
        "${ANDROID_NDK}/shader-tools/linux-x86_64"
        "${ANDROID_NDK}/shader-tools/windows-x86_64"
    NO_CMAKE_FIND_ROOT_PATH
)
set(OMNI_LLAMA_VULKAN OFF)
if (EXISTS "${OMNI_VULKAN_DEPS}/include/vulkan/vulkan.hpp"
    AND EXISTS "${OMNI_VULKAN_DEPS}/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake"
    AND OMNI_NDK_GLSLC)
    set(OMNI_LLAMA_VULKAN ON)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(Vulkan_INCLUDE_DIR "${OMNI_VULKAN_DEPS}/include" CACHE PATH "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE "${OMNI_NDK_GLSLC}" CACHE FILEPATH "" FORCE)
    set(SPIRV-Headers_DIR "${OMNI_VULKAN_DEPS}/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
    list(PREPEND CMAKE_PREFIX_PATH "${OMNI_VULKAN_DEPS}")
    message(STATUS "Omni Pocket: enabling ggml-vulkan GPU backend for llama.cpp (glslc=${OMNI_NDK_GLSLC})")
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    message(STATUS "Omni Pocket: building llama.cpp CPU-only (run scripts/build_yue2_android.sh to stage Vulkan headers)")
endif()

# Without an explicit arch the quantized dot-product kernels are compiled out. Every phone with the
# 16 GB the decision model needs has dotprod (ARMv8.2+).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
set(LLAMA_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
add_subdirectory("${LLAMA_SOURCE}" llama.cpp)
# Debug APKs otherwise build ggml at -O0, which makes a 9B forward pass take many minutes.
foreach(target ggml-base ggml-cpu ggml llama)
    target_compile_options(${target} PRIVATE -O3)
endforeach()
if (TARGET ggml-vulkan)
    target_compile_options(ggml-vulkan PRIVATE -O3)
    target_include_directories(ggml-vulkan PRIVATE "${OMNI_VULKAN_DEPS}/include")
endif()

add_library(omni_llm SHARED
    llm_jni.cpp
    LocalLlmSession.cpp
)
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/../../../../qwenimage21/src/main/jniLibs/${ANDROID_ABI}/libMNN.so"
)
target_include_directories(omni_llm PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${CMAKE_CURRENT_LIST_DIR}/../../../../qwenimage21/src/main/cpp/include"
)
target_compile_features(omni_llm PRIVATE cxx_std_17)
target_link_libraries(omni_llm PRIVATE MNN android log)

add_library(omni_decision SHARED
    decision_jni.cpp
    DecisionSession.cpp
    decision_backend.cpp
    llama_chat_jni.cpp
    LlamaChatSession.cpp
)
target_include_directories(omni_decision PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${CMAKE_CURRENT_LIST_DIR}/../../../../qwenimage21/src/main/cpp/include"
    "${LLAMA_SOURCE}/include"
    "${LLAMA_SOURCE}/ggml/include"
    "${LLAMA_SOURCE}/vendor/nlohmann"
)
target_compile_features(omni_decision PRIVATE cxx_std_17)
if (OMNI_LLAMA_VULKAN)
    target_compile_definitions(omni_decision PRIVATE OMNI_LLAMA_HAS_VULKAN=1)
endif()
target_link_libraries(omni_decision PRIVATE llama ggml android log)
target_link_options(omni_decision PRIVATE
    "LINKER:--version-script=${CMAKE_CURRENT_LIST_DIR}/omni_decision.map"
)
