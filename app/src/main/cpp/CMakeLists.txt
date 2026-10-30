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
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
# Without an explicit arch the quantized dot-product kernels are compiled out. Every phone with the
# 16 GB the decision model needs has dotprod (ARMv8.2+).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
set(LLAMA_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
add_subdirectory("${LLAMA_SOURCE}" llama.cpp)
# Debug APKs otherwise build ggml at -O0, which makes a 9B forward pass take many minutes.
foreach(target ggml-base ggml-cpu ggml llama)
    target_compile_options(${target} PRIVATE -O3)
endforeach()

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
)
target_include_directories(omni_decision PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${CMAKE_CURRENT_LIST_DIR}/../../../../qwenimage21/src/main/cpp/include"
    "${LLAMA_SOURCE}/include"
    "${LLAMA_SOURCE}/ggml/include"
    "${LLAMA_SOURCE}/vendor/nlohmann"
)
target_compile_features(omni_decision PRIVATE cxx_std_17)
target_link_libraries(omni_decision PRIVATE llama ggml android log)
target_link_options(omni_decision PRIVATE
    "LINKER:--version-script=${CMAKE_CURRENT_LIST_DIR}/omni_decision.map"
)
