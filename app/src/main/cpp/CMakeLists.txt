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
set(GGML_METAL OFF CACHE BOOL "" FORCE)

# Auto-enable Arm KleidiAI micro-kernels (runtime HWCAP dispatch across dotprod/i8mm/SVE/SME2)
# whenever scripts/prepare_llama.sh has staged KleidiAI sources locally.
set(OMNI_KLEIDIAI_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../build/deps/kleidiai-src")
set(OMNI_LLAMA_KLEIDIAI OFF)
if (EXISTS "${OMNI_KLEIDIAI_SRC}/CMakeLists.txt")
    set(OMNI_LLAMA_KLEIDIAI ON)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(FETCHCONTENT_SOURCE_DIR_KLEIDIAI "${OMNI_KLEIDIAI_SRC}" CACHE PATH "" FORCE)
    message(STATUS "Omni Pocket: enabling Arm KleidiAI CPU micro-kernels from ${OMNI_KLEIDIAI_SRC}")
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    message(STATUS "Omni Pocket: KleidiAI not staged (run scripts/prepare_llama.sh to enable)")
endif()

# Auto-enable OpenCL 3.0 GPU offload for GGUF chat when Khronos OpenCL headers
# (staged by scripts/build_sd_android.sh into build/sd-android/deps/opencl-headers-src) are present.
# Uses a lightweight dlopen shim (opencl_dlopen_loader.c) instead of DT_NEEDED: libOpenCL.so so
# libomni_decision.so loads cleanly on every Android device (including emulators without OpenCL),
# and keeps GGML_OPENCL_USE_ADRENO_KERNELS=OFF because Adreno-specific OpenCL decode kernels
# trigger KGSL Fault id:2 GPU hangs on Adreno 750 during token generation.
set(OMNI_OPENCL_HEADERS "${CMAKE_CURRENT_LIST_DIR}/../../../../build/sd-android/deps/opencl-headers-src")
set(OMNI_LLAMA_OPENCL OFF)
if (EXISTS "${OMNI_OPENCL_HEADERS}/CL/cl.h")
    set(OMNI_LLAMA_OPENCL ON)
    add_library(omni_opencl_loader STATIC opencl_dlopen_loader.c)
    target_include_directories(omni_opencl_loader PRIVATE "${OMNI_OPENCL_HEADERS}")
    target_link_libraries(omni_opencl_loader PRIVATE dl log)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL_PROFILING OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL_TARGET_VERSION 300 CACHE STRING "" FORCE)
    set(OpenCL_INCLUDE_DIR "${OMNI_OPENCL_HEADERS}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY "omni_opencl_loader" CACHE STRING "" FORCE)
    message(STATUS "Omni Pocket: enabling ggml-opencl GPU backend for llama.cpp (dlopen loader)")
else()
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    message(STATUS "Omni Pocket: building llama.cpp without OpenCL (run scripts/build_sd_android.sh to stage headers)")
endif()

# Auto-enable Vulkan GPU offload when Khronos Vulkan/SPIRV headers (staged by scripts/build_yue2_android.sh
# into build/vulkan-deps/) and NDK glslc are present. Android API 28+ ships libvulkan.so in system ABI.
set(OMNI_VULKAN_STAGE "${CMAKE_CURRENT_LIST_DIR}/../../../../build/vulkan-deps")
set(OMNI_SPIRV_CMAKE_DIR "${OMNI_VULKAN_STAGE}/share/cmake/SPIRV-Headers")
if (CMAKE_HOST_APPLE)
    set(OMNI_NDK_HOST_TAG "darwin-x86_64")
else()
    set(OMNI_NDK_HOST_TAG "linux-x86_64")
endif()
set(OMNI_GLSLC "${ANDROID_NDK}/shader-tools/${OMNI_NDK_HOST_TAG}/glslc")
set(OMNI_LLAMA_VULKAN OFF)
if (EXISTS "${OMNI_VULKAN_STAGE}/include/vulkan/vulkan.h" AND EXISTS "${OMNI_SPIRV_CMAKE_DIR}/SPIRV-HeadersConfig.cmake" AND EXISTS "${OMNI_GLSLC}")
    set(OMNI_LLAMA_VULKAN ON)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(SPIRV-Headers_DIR "${OMNI_SPIRV_CMAKE_DIR}" CACHE PATH "" FORCE)
    set(Vulkan_INCLUDE_DIR "${OMNI_VULKAN_STAGE}/include" CACHE PATH "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE "${OMNI_GLSLC}" CACHE FILEPATH "" FORCE)
    include_directories(SYSTEM "${OMNI_VULKAN_STAGE}/include")
    message(STATUS "Omni Pocket: enabling ggml-vulkan GPU backend for llama.cpp")
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    message(STATUS "Omni Pocket: building llama.cpp without Vulkan (run scripts/build_yue2_android.sh to stage headers)")
endif()

# Optional experimental Qualcomm Hexagon HTP NPU backend (only when HEXAGON_SDK_ROOT is configured locally).
set(OMNI_LLAMA_HEXAGON OFF)
if (DEFINED ENV{HEXAGON_SDK_ROOT} AND IS_DIRECTORY "$ENV{HEXAGON_SDK_ROOT}")
    set(OMNI_LLAMA_HEXAGON ON)
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    set(HEXAGON_SDK_ROOT "$ENV{HEXAGON_SDK_ROOT}" CACHE PATH "" FORCE)
    message(STATUS "Omni Pocket: enabling experimental ggml-hexagon HTP NPU backend from $ENV{HEXAGON_SDK_ROOT}")
else()
    set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
endif()

# Enable ARMv8.2+ dotprod and FP16 vector arithmetic for quantized & FP16 GEMM/attention kernels;
# KleidiAI handles runtime dispatch for i8mm/SVE/SME2 via getauxval(AT_HWCAP) without breaking older ARMv8.2 SoCs.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(LLAMA_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
add_subdirectory("${LLAMA_SOURCE}" llama.cpp)
# Debug APKs otherwise build ggml at -O0, which makes a 9B forward pass take many minutes.
foreach(target ggml-base ggml-cpu ggml llama)
    target_compile_options(${target} PRIVATE -O3)
endforeach()
if (TARGET ggml-opencl)
    target_compile_options(ggml-opencl PRIVATE -O3)
endif()
if (TARGET ggml-vulkan)
    target_compile_options(ggml-vulkan PRIVATE -O3)
endif()
if (TARGET kleidiai)
    target_compile_options(kleidiai PRIVATE -O3)
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
target_link_options(omni_llm PRIVATE "LINKER:-z,max-page-size=16384")

add_library(omni_decision SHARED
    decision_jni.cpp
    DecisionSession.cpp
    decision_backend.cpp
    llama_chat_jni.cpp
    LlamaChatSession.cpp
    llama_embedding_jni.cpp
    LlamaEmbeddingSession.cpp
)
target_include_directories(omni_decision PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${CMAKE_CURRENT_LIST_DIR}/../../../../qwenimage21/src/main/cpp/include"
    "${LLAMA_SOURCE}/include"
    "${LLAMA_SOURCE}/ggml/include"
    "${LLAMA_SOURCE}/vendor/nlohmann"
)
target_compile_features(omni_decision PRIVATE cxx_std_17)
if (OMNI_LLAMA_KLEIDIAI)
    target_compile_definitions(omni_decision PRIVATE OMNI_LLAMA_HAS_KLEIDIAI=1)
endif()
if (OMNI_LLAMA_VULKAN)
    target_compile_definitions(omni_decision PRIVATE OMNI_LLAMA_HAS_VULKAN=1)
endif()
if (OMNI_LLAMA_HEXAGON)
    target_compile_definitions(omni_decision PRIVATE OMNI_LLAMA_HAS_HEXAGON=1)
endif()
if (OMNI_LLAMA_OPENCL)
    target_compile_definitions(omni_decision PRIVATE OMNI_LLAMA_HAS_OPENCL=1)
    target_link_libraries(omni_decision PRIVATE llama ggml omni_opencl_loader android dl log)
else()
    target_link_libraries(omni_decision PRIVATE llama ggml android dl log)
endif()
target_link_options(omni_decision PRIVATE
    "LINKER:--version-script=${CMAKE_CURRENT_LIST_DIR}/omni_decision.map"
    "LINKER:-z,max-page-size=16384"
)
