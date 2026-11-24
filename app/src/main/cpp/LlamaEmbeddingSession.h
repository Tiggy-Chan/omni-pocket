#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <vector>

namespace omni {

class LlamaEmbeddingSession {
public:
    LlamaEmbeddingSession();
    ~LlamaEmbeddingSession();

    LlamaEmbeddingSession(const LlamaEmbeddingSession&) = delete;
    LlamaEmbeddingSession& operator=(const LlamaEmbeddingSession&) = delete;

    bool Load(const std::string& modelPath, int threads, int contextTokens, std::string& error);
    bool Embed(const std::string& text, std::vector<float>& outVector, std::string& error);
    int EmbeddingDimension() const;
    void Cancel();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace omni
