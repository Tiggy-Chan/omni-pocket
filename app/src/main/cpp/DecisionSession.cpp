#include "DecisionSession.h"

namespace omni {

bool DecisionSession::Load(const std::string& configPath, const std::string& cachePath,
                           int threadCount, std::string& error) {
    std::lock_guard<std::mutex> lock(executionMutex_);
    std::unique_ptr<MNN::Transformer::DecisionLlm> created(
        MNN::Transformer::DecisionLlm::create(configPath, cachePath, threadCount));
    if (!created) {
        error = "无法创建决策会话";
        return false;
    }
    std::unique_ptr<MNN::Transformer::DecisionLlm> previous;
    {
        std::lock_guard<std::mutex> pointer(pointerMutex_);
        previous = std::move(session_);
        session_ = std::move(created);
    }
    previous.reset();
    if (session_->load(error)) return true;
    std::unique_ptr<MNN::Transformer::DecisionLlm> failed;
    {
        std::lock_guard<std::mutex> pointer(pointerMutex_);
        failed = std::move(session_);
    }
    return false;
}

MNN::Transformer::DecisionResult DecisionSession::Decide(
    const std::string& state, const std::vector<MNN::Transformer::DecisionQuestion>& questions,
    const std::function<bool(int, int)>& progress) {
    std::lock_guard<std::mutex> lock(executionMutex_);
    MNN::Transformer::DecisionResult result;
    if (!session_) {
        result.error = "决策模型尚未加载";
        return result;
    }
    const bool completed = session_->decide(state, questions, result, progress);
    if (!completed && !result.cancelled && result.error.empty()) {
        result.error = "决策前向计算失败";
    }
    return result;
}

std::vector<std::string> DecisionSession::SlotLabels() {
    std::lock_guard<std::mutex> lock(executionMutex_);
    return session_ ? session_->slotLabels() : std::vector<std::string>();
}

std::string DecisionSession::Render(const std::string& state, const std::string& suffix) {
    std::lock_guard<std::mutex> lock(executionMutex_);
    return session_ ? session_->render(state, suffix) : std::string();
}

std::vector<int> DecisionSession::Tokenize(const std::string& text) {
    std::lock_guard<std::mutex> lock(executionMutex_);
    return session_ ? session_->tokenize(text) : std::vector<int>();
}

void DecisionSession::Cancel() {
    std::lock_guard<std::mutex> pointer(pointerMutex_);
    if (session_) session_->cancel();
}

void DecisionSession::Reset() {
    std::lock_guard<std::mutex> lock(executionMutex_);
    if (session_) session_->reset();
}

} // namespace omni
