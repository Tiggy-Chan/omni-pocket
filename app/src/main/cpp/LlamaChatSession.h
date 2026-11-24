#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace omni {

using LlamaChatMessages = std::vector<std::pair<std::string, std::string>>;

enum class LlamaChatFinish {
    Finished = 0,
    Cancelled = 1,
    MaxTokens = 2,
    Error = 3,
};

struct LlamaChatRequest {
    std::string conversationId;
    LlamaChatMessages messages;
    int maxNewTokens = 512;
    bool thinking = false;
    float temperature = 0.7f;
    float topP = 0.9f;
    int topK = 40;
    float repeatPenalty = 1.1f;
};

struct LlamaChatResult {
    LlamaChatFinish finish = LlamaChatFinish::Error;
    std::string text;
    std::string error;
    int promptTokens = 0;
    int generatedTokens = 0;
    int droppedMessages = 0;
    int64_t prefillUs = 0;
    int64_t decodeUs = 0;
};

enum class GgufBackendPref : int {
    Auto = 0,
    Cpu = 1,
    Vulkan = 2,
    OpenCl = 3,
    HexagonNpu = 4,
};

struct BackendCapabilities {
    bool hasKleidiAi = false;
    bool hasVulkan = false;
    std::string vulkanName;
    bool hasOpenCl = false;
    std::string openClName;
    bool hasHexagonCompiled = false;
    bool hasHexagonRuntime = false;
    bool hasHexagonFastRpc = false;
    int recommendedThreads = 4;
};

class LlamaChatSession {
public:
    LlamaChatSession();
    ~LlamaChatSession();
    LlamaChatSession(const LlamaChatSession&) = delete;
    LlamaChatSession& operator=(const LlamaChatSession&) = delete;

    // Configure writable kernel cache directory (used by ggml-opencl) before backend init.
    static void SetKernelCacheDir(const std::string& cacheDir);
    static BackendCapabilities ProbeBackends();
    static bool HasRuntimeGpuDevice();
    static std::string RuntimeGpuDeviceName();

    // kvQuantMode: 0 = F16, 1 = Q8_0, 2 = Q4_0; backendPref: GgufBackendPref integer value.
    bool Load(const std::string& modelPath, int threadCount, int contextTokens, std::string& error,
              int kvQuantMode = 0, int backendPref = 0);
    int EffectiveContextTokens() const;
    int ModelTrainContextTokens() const;
    std::string ActiveBackendLabel() const;
    LlamaChatResult Generate(const LlamaChatRequest& request,
                             const std::function<bool(const std::string&)>& onToken);
    void Cancel();
    void Reset();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace omni
