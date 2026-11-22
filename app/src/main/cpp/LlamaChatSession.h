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

class LlamaChatSession {
public:
    LlamaChatSession();
    ~LlamaChatSession();
    LlamaChatSession(const LlamaChatSession&) = delete;
    LlamaChatSession& operator=(const LlamaChatSession&) = delete;

    // kvQuantMode: 0 = F16, 1 = Q8_0, 2 = Q4_0; gpuLayers: 0 = CPU only, >0 = offload layers if GPU backend exists.
    bool Load(const std::string& modelPath, int threadCount, int contextTokens, std::string& error,
              int kvQuantMode = 0, int gpuLayers = 0);
    int EffectiveContextTokens() const;
    int ModelTrainContextTokens() const;
    LlamaChatResult Generate(const LlamaChatRequest& request,
                             const std::function<bool(const std::string&)>& onToken);
    void Cancel();
    void Reset();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace omni
