#pragma once

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llm/llm.hpp"

namespace omni {

using ChatMessage = MNN::Transformer::ChatMessage;
using ChatMessages = MNN::Transformer::ChatMessages;

class Utf8Chunker {
public:
    explicit Utf8Chunker(std::function<bool(const std::string&)> callback);
    bool Feed(const char* data, size_t size);
    bool Finish(bool discardIncomplete = false);

private:
    bool Emit(const std::string& text);
    std::string pending_;
    std::function<bool(const std::string&)> callback_;
};

enum class LlmFinish {
    Finished,
    Cancelled,
    MaxTokens,
    Error,
};

struct LlmResult {
    LlmFinish finish = LlmFinish::Error;
    std::string text;
    std::string error;
    int promptTokens = 0;
    int generatedTokens = 0;
    int droppedMessages = 0;
    int64_t prefillUs = 0;
    int64_t decodeUs = 0;
};

struct LlmRequest {
    std::string conversationId;
    ChatMessages messages;
    int maxNewTokens = 512;
    bool thinking = false;
};

class LocalLlmSession {
public:
    LocalLlmSession() = default;
    ~LocalLlmSession() = default;
    LocalLlmSession(const LocalLlmSession&) = delete;
    LocalLlmSession& operator=(const LocalLlmSession&) = delete;

    bool Load(const std::string& configPath, const std::string& cachePath,
              int threadCount, int contextTokens, std::string& error);
    LlmResult Generate(const LlmRequest& request,
                       const std::function<bool(const std::string&)>& onToken);
    void Cancel();
    void Reset();
    int PromptTokenCount(const ChatMessages& messages);
    std::string RenderPrompt(const ChatMessages& messages);

private:
    void RestoreStepState();
    std::unique_ptr<MNN::Transformer::Llm> llm_;
    std::mutex executionMutex_;
    std::atomic<bool> cancelled_{false};
    int contextTokens_ = 4096;
    bool thinking_ = false;
    std::string conversationId_;
};

} // namespace omni
