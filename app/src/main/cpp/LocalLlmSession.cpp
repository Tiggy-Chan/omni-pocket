#include "LocalLlmSession.h"

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <sstream>
#include <utility>

namespace omni {
namespace {

constexpr char kEndMarker[] = "<eop>";
constexpr char kReplacement[] = "\xEF\xBF\xBD";

std::string JsonString(const std::string& value) {
    std::string result;
    result.reserve(value.size() + 2);
    result += '"';
    for (unsigned char c : value) {
        switch (c) {
            case '"': result += "\\\""; break;
            case '\\': result += "\\\\"; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default:
                if (c < 0x20) {
                    constexpr char digits[] = "0123456789abcdef";
                    result += "\\u00";
                    result += digits[c >> 4];
                    result += digits[c & 15];
                } else {
                    result += static_cast<char>(c);
                }
        }
    }
    result += '"';
    return result;
}

int SequenceLength(const std::string& text, size_t offset) {
    const auto first = static_cast<unsigned char>(text[offset]);
    if (first <= 0x7F) return 1;
    if (first >= 0xC2 && first <= 0xDF) return 2;
    if (first >= 0xE0 && first <= 0xEF) return 3;
    if (first >= 0xF0 && first <= 0xF4) return 4;
    return 0;
}

bool ValidSequence(const std::string& text, size_t offset, int length) {
    if (length == 1) return true;
    if (offset + length > text.size()) return false;
    const auto first = static_cast<unsigned char>(text[offset]);
    const auto second = static_cast<unsigned char>(text[offset + 1]);
    if (second < 0x80 || second > 0xBF) return false;
    if (first == 0xE0 && second < 0xA0) return false;
    if (first == 0xED && second > 0x9F) return false;
    if (first == 0xF0 && second < 0x90) return false;
    if (first == 0xF4 && second > 0x8F) return false;
    for (int i = 2; i < length; ++i) {
        const auto next = static_cast<unsigned char>(text[offset + i]);
        if (next < 0x80 || next > 0xBF) return false;
    }
    return true;
}

bool HasValidPrefix(const std::string& text, size_t offset, int length) {
    if (offset + 1 >= text.size()) return true;
    const auto first = static_cast<unsigned char>(text[offset]);
    const auto second = static_cast<unsigned char>(text[offset + 1]);
    if (second < 0x80 || second > 0xBF) return false;
    if (first == 0xE0 && second < 0xA0) return false;
    if (first == 0xED && second > 0x9F) return false;
    if (first == 0xF0 && second < 0x90) return false;
    if (first == 0xF4 && second > 0x8F) return false;
    for (size_t i = offset + 2; i < text.size() && i < offset + length; ++i) {
        const auto next = static_cast<unsigned char>(text[i]);
        if (next < 0x80 || next > 0xBF) return false;
    }
    return true;
}

class OutputProcessor : public std::streambuf {
public:
    explicit OutputProcessor(std::function<bool(const std::string&)> callback)
        : utf8_([this](const std::string& text) { return Emit(text); }), callback_(std::move(callback)) {}

protected:
    std::streamsize xsputn(const char* data, std::streamsize size) override {
        Feed(data, static_cast<size_t>(size));
        return size;
    }

    int overflow(int value) override {
        if (value == traits_type::eof()) return traits_type::not_eof(value);
        const char data = static_cast<char>(value);
        return Feed(&data, 1) ? value : traits_type::eof();
    }

public:
    bool Feed(const char* data, size_t size) {
        if (ended_ || stopped_) return false;
        pending_.append(data, size);
        const auto marker = pending_.find(kEndMarker);
        if (marker != std::string::npos) {
            ended_ = true;
            const bool keepGoing = utf8_.Feed(pending_.data(), marker);
            pending_.clear();
            utf8_.Finish();
            return keepGoing;
        }
        size_t keep = 0;
        const size_t markerLength = sizeof(kEndMarker) - 1;
        const size_t limit = std::min(markerLength - 1, pending_.size());
        for (size_t length = 1; length <= limit; ++length) {
            if (pending_.compare(pending_.size() - length, length, kEndMarker, length) == 0) {
                keep = length;
            }
        }
        if (pending_.size() > keep) {
            const size_t count = pending_.size() - keep;
            if (!utf8_.Feed(pending_.data(), count)) {
                stopped_ = true;
                pending_.clear();
                return false;
            }
            pending_.erase(0, count);
        }
        return !stopped_;
    }

    bool Finish(bool discardIncomplete = false) {
        if (!pending_.empty()) {
            if (!discardIncomplete) utf8_.Feed(pending_.data(), pending_.size());
            pending_.clear();
        }
        utf8_.Finish(discardIncomplete);
        return !stopped_;
    }

    bool ended() const { return ended_; }
    bool stopped() const { return stopped_; }
    const std::string& text() const { return text_; }

private:
    bool Emit(const std::string& text) {
        text_ += text;
        if (callback_ && !callback_(text)) {
            stopped_ = true;
            return false;
        }
        return true;
    }

    Utf8Chunker utf8_;
    std::function<bool(const std::string&)> callback_;
    std::string pending_;
    std::string text_;
    bool ended_ = false;
    bool stopped_ = false;
};

} // namespace

Utf8Chunker::Utf8Chunker(std::function<bool(const std::string&)> callback)
    : callback_(std::move(callback)) {}

bool Utf8Chunker::Emit(const std::string& text) {
    return text.empty() || !callback_ || callback_(text);
}

bool Utf8Chunker::Feed(const char* data, size_t size) {
    pending_.append(data, size);
    size_t offset = 0;
    while (offset < pending_.size()) {
        const int length = SequenceLength(pending_, offset);
        if (length == 0) {
            if (!Emit(kReplacement)) return false;
            ++offset;
            continue;
        }
        if (offset + length > pending_.size()) {
            if (!HasValidPrefix(pending_, offset, length)) {
                if (!Emit(kReplacement)) return false;
                ++offset;
            }
            break;
        }
        if (!ValidSequence(pending_, offset, length)) {
            if (!Emit(kReplacement)) return false;
            ++offset;
            continue;
        }
        if (!Emit(pending_.substr(offset, length))) return false;
        offset += length;
    }
    pending_.erase(0, offset);
    return true;
}

bool Utf8Chunker::Finish(bool discardIncomplete) {
    if (!pending_.empty()) {
        pending_.clear();
        return discardIncomplete || Emit(kReplacement);
    }
    return true;
}

bool LocalLlmSession::Load(const std::string& configPath, const std::string& cachePath,
                           int threadCount, int contextTokens, std::string& error) {
    std::lock_guard<std::mutex> lock(executionMutex_);
    cancelled_.store(false);
    error.clear();
    if (threadCount < 1 || threadCount > 16 || contextTokens < 256 || contextTokens > 16384) {
        error = "推理线程或上下文预算无效";
        return false;
    }
    std::error_code directoryError;
    std::filesystem::create_directories(cachePath, directoryError);
    if (directoryError || !std::filesystem::is_directory(cachePath)) {
        error = "无法创建 LLM 缓存目录";
        return false;
    }
    llm_.reset(MNN::Transformer::Llm::createLLM(configPath));
    if (!llm_) {
        error = "无法读取 LLM 配置";
        return false;
    }
    contextTokens_ = contextTokens;
    std::ostringstream config;
    config << "{\"backend_type\":\"cpu\",\"thread_num\":" << threadCount
           << ",\"async\":false,\"use_mmap\":true,\"tmp_path\":" << JsonString(cachePath)
           << ",\"max_all_tokens\":" << contextTokens
           << ",\"presence_penalty\":1.5,\"repetition_penalty\":1.0"
           << ",\"reuse_kv\":true,\"prompt_cache\":true}";
    if (!llm_->set_config(config.str())) {
        error = "无法应用 LLM 运行配置";
        llm_.reset();
        return false;
    }
    if (!llm_->load()) {
        error = "LLM 模型加载失败，请检查模型文件和可用内存";
        llm_.reset();
        return false;
    }
    if (!llm_->set_config("{\"jinja\":{\"context\":{\"enable_thinking\":false}}}")) {
        error = "无法配置对话模板";
        llm_.reset();
        return false;
    }
    auto activation = llm_->getExecutor()->activte();
    llm_->tuning(MNN::Transformer::OP_ENCODER_NUMBER, {1, 5, 10, 20, 30, 50, 100});
    if (cancelled_.load()) {
        error = "模型加载已取消";
        llm_.reset();
        return false;
    }
    conversationId_.clear();
    return true;
}

void LocalLlmSession::Cancel() {
    cancelled_.store(true);
}

void LocalLlmSession::Reset() {
    std::lock_guard<std::mutex> lock(executionMutex_);
    if (llm_) llm_->reset();
    conversationId_.clear();
}

int LocalLlmSession::PromptTokenCount(const ChatMessages& messages) {
    return static_cast<int>(llm_->tokenizer_encode(llm_->apply_chat_template(messages)).size());
}

std::string LocalLlmSession::RenderPrompt(const ChatMessages& messages) {
    return llm_->apply_chat_template(messages);
}

void LocalLlmSession::RestoreStepState() {
    auto* context = llm_->getContext();
    if (context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED ||
        context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED) {
        // This prebuilt runtime marks each single-token call as a terminal step.
        const_cast<MNN::Transformer::LlmContext*>(context)->status = MNN::Transformer::LlmStatus::RUNNING;
    }
}

LlmResult LocalLlmSession::Generate(const LlmRequest& request,
                                    const std::function<bool(const std::string&)>& onToken) {
    std::lock_guard<std::mutex> lock(executionMutex_);
    cancelled_.store(false);
    LlmResult result;
    if (!llm_) {
        result.error = "模型尚未加载";
        return result;
    }
    if (request.messages.size() < 2 || request.messages.size() > 1002 ||
        request.conversationId.empty() || request.messages.front().first != "system" ||
        request.messages.back().first != "user") {
        result.error = "对话必须以 system 开始，并以 user 消息结束";
        return result;
    }
    for (size_t i = 1; i < request.messages.size(); ++i) {
        const char* expected = i % 2 == 1 ? "user" : "assistant";
        if (request.messages[i].first != expected || request.messages[i].second.empty()) {
            result.error = "对话中的用户和助手消息必须交替排列，且内容不能为空";
            return result;
        }
    }
    if (request.maxNewTokens < 1 || request.maxNewTokens >= contextTokens_) {
        result.error = "输出长度必须小于上下文预算";
        return result;
    }
    if (conversationId_ != request.conversationId || thinking_ != request.thinking) {
        llm_->reset();
        conversationId_ = request.conversationId;
        thinking_ = request.thinking;
        llm_->set_config(std::string("{\"jinja\":{\"context\":{\"enable_thinking\":") +
                         (thinking_ ? "true" : "false") + "}}}");
    }

    ChatMessages history = request.messages;
    const size_t characterBudget = static_cast<size_t>(contextTokens_ - request.maxNewTokens) * 3;
    size_t estimatedCharacters = history.front().second.size() + 64;
    for (size_t i = 1; i < history.size(); ++i) estimatedCharacters += history[i].second.size() + 16;
    while (estimatedCharacters > characterBudget && history.size() > 2) {
        const size_t count = history.size() > 3 ? 2 : 1;
        for (size_t i = 0; i < count; ++i) estimatedCharacters -= history[1 + i].second.size() + 16;
        history.erase(history.begin() + 1, history.begin() + 1 + count);
        result.droppedMessages += static_cast<int>(count);
    }
    result.promptTokens = PromptTokenCount(history);
    while (result.promptTokens + request.maxNewTokens > contextTokens_ && history.size() > 2) {
        size_t count = 1;
        if (history.size() > 3 && history[1].first == "user" && history[2].first == "assistant") count = 2;
        history.erase(history.begin() + 1, history.begin() + 1 + count);
        result.droppedMessages += static_cast<int>(count);
        result.promptTokens = PromptTokenCount(history);
    }
    if (result.promptTokens + request.maxNewTokens > contextTokens_) {
        result.error = "当前输入超过上下文预算，请缩短输入或新建会话";
        return result;
    }

    OutputProcessor output(onToken);
    std::ostream stream(&output);
    RestoreStepState();
    llm_->response(history, &stream, kEndMarker, 0);
    int generated = 0;
    while (!cancelled_.load() && !output.ended() && generated < request.maxNewTokens) {
        const auto* context = llm_->getContext();
        if (context->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR ||
            context->status == MNN::Transformer::LlmStatus::TIMEOUT ||
            context->status == MNN::Transformer::LlmStatus::NOT_LOADED) {
            result.error = "LLM 推理引擎返回错误状态";
            break;
        }
        if (llm_->stoped()) break;
        RestoreStepState();
        llm_->generate(1);
        ++generated;
        if (output.stopped()) cancelled_.store(true);
    }
    output.Finish(cancelled_.load() || output.stopped());
    const auto* context = llm_->getContext();
    result.text = output.text();
    result.generatedTokens = context->gen_seq_len > 0 ? context->gen_seq_len : generated;
    result.prefillUs = context->prefill_us;
    result.decodeUs = context->decode_us;
    if (!result.error.empty()) {
        result.finish = LlmFinish::Error;
    } else if (context->status == MNN::Transformer::LlmStatus::INTERNAL_ERROR ||
               context->status == MNN::Transformer::LlmStatus::TIMEOUT) {
        result.finish = LlmFinish::Error;
        result.error = "LLM 推理中断，请重试";
    } else if (cancelled_.load() || output.stopped()) {
        result.finish = LlmFinish::Cancelled;
    } else if (output.ended() || llm_->stoped()) {
        result.finish = LlmFinish::Finished;
    } else {
        result.finish = LlmFinish::MaxTokens;
    }
    if (result.finish != LlmFinish::Error && !result.text.empty()) {
        history.emplace_back("assistant", result.text);
        llm_->syncPromptCache(history);
    }
    if (result.finish != LlmFinish::Finished) conversationId_.clear();
    return result;
}

} // namespace omni
