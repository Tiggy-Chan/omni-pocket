cmake_minimum_required(VERSION 3.22)
project(OmniLlmHost CXX)

get_filename_component(OMNI_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_BUILD_OPENCV ON CACHE BOOL "" FORCE)
set(MNN_IMGCODECS ON CACHE BOOL "" FORCE)
set(MNN_BUILD_AUDIO OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DIFFUSION OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_LLM_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(LLM_SUPPORT_HTTP_RESOURCE OFF CACHE BOOL "" FORCE)
set(MNN_METAL ON CACHE BOOL "" FORCE)
set(MNN_OPENCL OFF CACHE BOOL "" FORCE)

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
set(GGML_NATIVE ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

add_subdirectory("${OMNI_ROOT}/third_party/MNN" mnn)
add_subdirectory("${OMNI_ROOT}/third_party/llama.cpp" llama.cpp)

add_executable(omni_llm_smoke
    smoke.cpp
    "${OMNI_ROOT}/app/src/main/cpp/LocalLlmSession.cpp"
)
target_include_directories(omni_llm_smoke PRIVATE
    "${OMNI_ROOT}/app/src/main/cpp"
    "${OMNI_ROOT}/third_party/MNN/include"
    "${OMNI_ROOT}/third_party/MNN/transformers/llm/engine/include"
)
target_link_libraries(omni_llm_smoke PRIVATE MNN)

add_executable(omni_decision_smoke
    ../decision-host/smoke.cpp
    "${OMNI_ROOT}/app/src/main/cpp/DecisionSession.cpp"
    "${OMNI_ROOT}/app/src/main/cpp/decision_backend.cpp"
)
target_include_directories(omni_decision_smoke PRIVATE
    "${OMNI_ROOT}/app/src/main/cpp"
    "${OMNI_ROOT}/third_party/MNN/include"
    "${OMNI_ROOT}/third_party/llama.cpp/include"
    "${OMNI_ROOT}/third_party/llama.cpp/ggml/include"
    "${OMNI_ROOT}/third_party/llama.cpp/vendor/nlohmann"
)
target_link_libraries(omni_decision_smoke PRIVATE MNN llama ggml)
