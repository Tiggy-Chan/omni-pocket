#pragma once

#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llm/decision.hpp"

namespace omni {

class DecisionSession {
public:
    DecisionSession() = default;
    ~DecisionSession() = default;
    DecisionSession(const DecisionSession&) = delete;
    DecisionSession& operator=(const DecisionSession&) = delete;

    bool Load(const std::string& configPath, const std::string& cachePath,
              int threadCount, std::string& error);
    MNN::Transformer::DecisionResult Decide(
        const std::string& state, const std::vector<MNN::Transformer::DecisionQuestion>& questions,
        const std::function<bool(int, int)>& progress);
    std::vector<std::string> SlotLabels();
    std::string Render(const std::string& state, const std::string& suffix);
    std::vector<int> Tokenize(const std::string& text);
    void Cancel();
    void Reset();

private:
    std::unique_ptr<MNN::Transformer::DecisionLlm> session_;
    std::mutex executionMutex_;
    // Guards only pointer swaps, so Cancel() never waits for a load or decision to finish.
    std::mutex pointerMutex_;
};

} // namespace omni
