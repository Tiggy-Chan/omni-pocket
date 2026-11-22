#include "LlamaChatSession.h"

#include "ggml-backend.h"
#include "llama.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <limits>
#include <mutex>
#include <random>
#include <sstream>

namespace omni {
namespace {

constexpr char kReplacement[] = "\xEF\xBF\xBD";
constexpr int32_t kBatchSize = 512;

int64_t NowUs() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

int Utf8Length(const std::string& text, size_t offset) {
    const auto first = static_cast<unsigned char>(text[offset]);
    if (first <= 0x7F) return 1;
    if (first >= 0xC2 && first <= 0xDF) return 2;
    if (first >= 0xE0 && first <= 0xEF) return 3;
    if (first >= 0xF0 && first <= 0xF4) return 4;
    return 0;
}

bool ValidUtf8(const std::string& text, size_t offset, int length) {
    if (length == 1) return true;
    if (offset + static_cast<size_t>(length) > text.size()) return false;
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

bool ValidUtf8Prefix(const std::string& text, size_t offset, int length) {
    if (offset + 1 >= text.size()) return true;
    const auto first = static_cast<unsigned char>(text[offset]);
    const auto second = static_cast<unsigned char>(text[offset + 1]);
    if (second < 0x80 || second > 0xBF) return false;
    if (first == 0xE0 && second < 0xA0) return false;
    if (first == 0xED && second > 0x9F) return false;
    if (first == 0xF0 && second < 0x90) return false;
    if (first == 0xF4 && second > 0x8F) return false;
    for (size_t i = offset + 2; i < text.size() && i < offset + static_cast<size_t>(length); ++i) {
        const auto next = static_cast<unsigned char>(text[i]);
        if (next < 0x80 || next > 0xBF) return false;
    }
    return true;
}

class Utf8Emitter {
public:
    explicit Utf8Emitter(std::function<bool(const std::string&)> callback)
        : callback_(std::move(callback)) {}

    bool Feed(const char* data, size_t size) {
        pending_.append(data, size);
        size_t offset = 0;
        while (offset < pending_.size()) {
            const int length = Utf8Length(pending_, offset);
            if (length == 0) {
                if (!Emit(kReplacement)) return false;
                ++offset;
                continue;
            }
            if (offset + static_cast<size_t>(length) > pending_.size()) {
                if (!ValidUtf8Prefix(pending_, offset, length)) {
                    if (!Emit(kReplacement)) return false;
                    ++offset;
                }
                break;
            }
            if (!ValidUtf8(pending_, offset, length)) {
                if (!Emit(kReplacement)) return false;
                ++offset;
                continue;
            }
            if (!Emit(pending_.substr(offset, static_cast<size_t>(length)))) return false;
            offset += static_cast<size_t>(length);
        }
        pending_.erase(0, offset);
        return true;
    }

    bool Finish(bool discardIncomplete) {
        if (!pending_.empty()) {
            pending_.clear();
            return discardIncomplete || Emit(kReplacement);
        }
        return true;
    }

    const std::string& text() const { return text_; }

private:
    bool Emit(const std::string& piece) {
        if (piece.empty()) return true;
        text_ += piece;
        return !callback_ || callback_(piece);
    }

    std::string pending_;
    std::string text_;
    std::function<bool(const std::string&)> callback_;
};

std::string FallbackFormatMessages(const LlamaChatMessages& messages, const std::string& tmplHint) {
    std::ostringstream out;
    if (tmplHint.find("<start_of_turn>") != std::string::npos) {
        std::string systemPrefix;
        for (const auto& [role, content] : messages) {
            if (role == "system") {
                systemPrefix = content + "\n\n";
            } else if (role == "user") {
                out << "<start_of_turn>user\n" << systemPrefix << content << "<end_of_turn>\n";
                systemPrefix.clear();
            } else {
                out << "<start_of_turn>model\n" << content << "<end_of_turn>\n";
            }
        }
        out << "<start_of_turn>model\n";
        return out.str();
    }
    if (tmplHint.find("<|start_header_id|>") != std::string::npos) {
        out << "<|begin_of_text|>";
        for (const auto& [role, content] : messages) {
            out << "<|start_header_id|>" << role << "<|end_header_id|>\n\n" << content << "<|eot_id|>";
        }
        out << "<|start_header_id|>assistant<|end_header_id|>\n\n";
        return out.str();
    }
    // Default ChatML format (Qwen, Yi, InternLM, SmolLM, etc.)
    for (const auto& [role, content] : messages) {
        out << "<|im_start|>" << role << "\n" << content << "<|im_end|>\n";
    }
    out << "<|im_start|>assistant\n";
    return out.str();
}

} // namespace

struct LlamaChatSession::Impl {
    llama_model* model = nullptr;
    llama_context* context = nullptr;
    const llama_vocab* vocab = nullptr;
    int contextTokens = 4096;
    int threadCount = 4;
    std::string conversationId;
    bool thinking = false;
    std::vector<llama_token> kvTokens;
    std::atomic<bool> cancelled{false};
    std::mutex executionMutex;

    ~Impl() { Clear(); }

    void Clear() {
        if (context != nullptr) {
            llama_free(context);
            context = nullptr;
        }
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
        vocab = nullptr;
        conversationId.clear();
        kvTokens.clear();
    }

    std::string RenderPrompt(const LlamaChatMessages& messages, bool enableThinking) const {
        const char* rawTemplate = llama_model_chat_template(model, nullptr);
        std::string tmplHint = rawTemplate ? std::string(rawTemplate) : std::string();

        std::vector<llama_chat_message> chat;
        chat.reserve(messages.size());
        for (const auto& msg : messages) {
            chat.push_back(llama_chat_message{msg.first.c_str(), msg.second.c_str()});
        }

        size_t totalChars = 256;
        for (const auto& msg : messages) totalChars += msg.second.size() + 32;
        std::vector<char> buffer(totalChars * 2);

        std::string rendered;
        int32_t needed = llama_chat_apply_template(
            rawTemplate, chat.data(), chat.size(), /*add_ass=*/true,
            buffer.data(), static_cast<int32_t>(buffer.size()));
        if (needed > static_cast<int32_t>(buffer.size())) {
            buffer.resize(static_cast<size_t>(needed) + 1);
            needed = llama_chat_apply_template(
                rawTemplate, chat.data(), chat.size(), /*add_ass=*/true,
                buffer.data(), static_cast<int32_t>(buffer.size()));
        }
        if (needed > 0 && needed <= static_cast<int32_t>(buffer.size())) {
            rendered.assign(buffer.data(), static_cast<size_t>(needed));
        } else {
            rendered = FallbackFormatMessages(messages, tmplHint);
        }

        // For thinking-capable templates (e.g. Qwen3 / DeepSeek-R1), inject empty think block when thinking is off.
        const bool templateHasThink = tmplHint.find("<think>") != std::string::npos ||
                                      tmplHint.find("enable_thinking") != std::string::npos;
        if (templateHasThink && !enableThinking) {
            const std::string marker = "<think>";
            if (rendered.size() >= marker.size() &&
                rendered.compare(rendered.size() - marker.size(), marker.size(), marker) == 0) {
                rendered += "\n</think>\n\n";
            } else if (rendered.find("<think>") == std::string::npos) {
                rendered += "<think>\n\n</think>\n\n";
            }
        }
        return rendered;
    }

    std::vector<llama_token> Tokenize(const std::string& text) const {
        if (text.empty() || vocab == nullptr) return {};
        bool addBos = llama_vocab_get_add_bos(vocab);
        if (addBos) {
            const llama_token bos = llama_vocab_bos(vocab);
            if (bos >= 0) {
                char bosBuf[64];
                const int bosLen = llama_token_to_piece(vocab, bos, bosBuf, sizeof(bosBuf), 0, true);
                if (bosLen > 0 && text.size() >= static_cast<size_t>(bosLen) &&
                    text.compare(0, static_cast<size_t>(bosLen), bosBuf, static_cast<size_t>(bosLen)) == 0) {
                    addBos = false;
                }
            }
        }
        const int32_t textLen = static_cast<int32_t>(std::min<size_t>(
            text.size(), static_cast<size_t>(std::numeric_limits<int32_t>::max())));
        int32_t count = llama_tokenize(vocab, text.data(), textLen, nullptr, 0, addBos, /*parse_special=*/true);
        if (count >= 0) return {};
        const int32_t required = -count;
        std::vector<llama_token> tokens(static_cast<size_t>(required));
        const int32_t written = llama_tokenize(vocab, text.data(), textLen, tokens.data(), required, addBos, true);
        if (written < 0) return {};
        tokens.resize(static_cast<size_t>(written));
        return tokens;
    }

    std::string TokenToPiece(llama_token token) const {
        char stackBuf[128];
        int32_t n = llama_token_to_piece(vocab, token, stackBuf, sizeof(stackBuf), 0, /*special=*/false);
        if (n >= 0) {
            return std::string(stackBuf, static_cast<size_t>(n));
        }
        const size_t required = static_cast<size_t>(-n);
        std::string heapBuf(required, '\0');
        n = llama_token_to_piece(vocab, token, heapBuf.data(), static_cast<int32_t>(heapBuf.size()), 0, false);
        if (n < 0) return {};
        heapBuf.resize(static_cast<size_t>(n));
        return heapBuf;
    }

    bool DecodeTokens(const llama_token* tokens, size_t count, int32_t startPos, std::string& error) {
        llama_batch batch = llama_batch_init(kBatchSize, 0, 1);
        size_t offset = 0;
        while (offset < count) {
            if (cancelled.load()) {
                llama_batch_free(batch);
                return false;
            }
            const size_t chunk = std::min<size_t>(static_cast<size_t>(kBatchSize), count - offset);
            batch.n_tokens = static_cast<int32_t>(chunk);
            for (size_t i = 0; i < chunk; ++i) {
                batch.token[i] = tokens[offset + i];
                batch.pos[i] = startPos + static_cast<int32_t>(offset + i);
                batch.n_seq_id[i] = 1;
                batch.seq_id[i][0] = 0;
                batch.logits[i] = (offset + i == count - 1) ? 1 : 0;
            }
            const int status = llama_decode(context, batch);
            if (cancelled.load()) {
                llama_batch_free(batch);
                return false;
            }
            if (status != 0) {
                llama_batch_free(batch);
                error = "GGUF 推理前向计算失败（状态码 " + std::to_string(status) + "）";
                return false;
            }
            offset += chunk;
        }
        llama_batch_free(batch);
        return true;
    }
};

LlamaChatSession::LlamaChatSession() : impl_(std::make_unique<Impl>()) {}
LlamaChatSession::~LlamaChatSession() {
    Cancel();
    std::lock_guard<std::mutex> lock(impl_->executionMutex);
    impl_->Clear();
}

bool LlamaChatSession::Load(const std::string& modelPath, int threadCount, int contextTokens, std::string& error) {
    std::lock_guard<std::mutex> lock(impl_->executionMutex);
    impl_->cancelled.store(false);
    error.clear();
    if (threadCount < 1 || threadCount > 16 || contextTokens < 512 || contextTokens > 32768) {
        error = "推理线程或上下文预算无效";
        return false;
    }
    std::error_code ec;
    if (!std::filesystem::is_regular_file(modelPath, ec) || ec) {
        error = "GGUF 模型文件不存在";
        return false;
    }
    impl_->Clear();
    static std::once_flag backendOnce;
    std::call_once(backendOnce, [] { ggml_backend_load_all(); });

    auto modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;
    modelParams.progress_callback = [](float, void* data) -> bool {
        return !static_cast<std::atomic<bool>*>(data)->load();
    };
    modelParams.progress_callback_user_data = &impl_->cancelled;

    impl_->model = llama_model_load_from_file(modelPath.c_str(), modelParams);
    if (impl_->model == nullptr) {
        error = impl_->cancelled.load() ? "模型加载已取消" : "GGUF 模型加载失败，请检查文件完整性和可用内存";
        return false;
    }
    impl_->vocab = llama_model_get_vocab(impl_->model);
    if (impl_->vocab == nullptr) {
        error = "GGUF 词表无效";
        impl_->Clear();
        return false;
    }

    auto ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(contextTokens);
    ctxParams.n_batch = static_cast<uint32_t>(kBatchSize);
    ctxParams.n_ubatch = static_cast<uint32_t>(kBatchSize);
    ctxParams.n_threads = threadCount;
    ctxParams.n_threads_batch = threadCount;
    ctxParams.offload_kqv = false;
    ctxParams.op_offload = false;
    ctxParams.abort_callback = [](void* data) -> bool {
        return static_cast<std::atomic<bool>*>(data)->load();
    };
    ctxParams.abort_callback_data = &impl_->cancelled;

    impl_->context = llama_init_from_model(impl_->model, ctxParams);
    if (impl_->context == nullptr) {
        error = "无法创建 " + std::to_string(contextTokens) + " token 推理上下文，可用内存不足";
        impl_->Clear();
        return false;
    }
    llama_set_causal_attn(impl_->context, true);
    impl_->contextTokens = contextTokens;
    impl_->threadCount = threadCount;
    return true;
}

LlamaChatResult LlamaChatSession::Generate(
    const LlamaChatRequest& request,
    const std::function<bool(const std::string&)>& onToken) {
    std::lock_guard<std::mutex> lock(impl_->executionMutex);
    impl_->cancelled.store(false);
    LlamaChatResult result;
    if (impl_->model == nullptr || impl_->context == nullptr || impl_->vocab == nullptr) {
        result.error = "GGUF 模型尚未加载";
        return result;
    }
    if (request.messages.size() < 2 || request.messages.size() > 1002 ||
        request.conversationId.empty() || request.messages.front().first != "system" ||
        request.messages.back().first != "user") {
        result.error = "对话必须以 system 开始，并以 user 消息结束";
        return result;
    }
    if (request.maxNewTokens < 1 || request.maxNewTokens >= impl_->contextTokens) {
        result.error = "输出长度必须小于上下文预算";
        return result;
    }

    LlamaChatMessages history = request.messages;
    std::string prompt = impl_->RenderPrompt(history, request.thinking);
    std::vector<llama_token> promptTokens = impl_->Tokenize(prompt);
    while (static_cast<int>(promptTokens.size()) + request.maxNewTokens > impl_->contextTokens && history.size() > 2) {
        size_t count = 1;
        if (history.size() > 3 && history[1].first == "user" && history[2].first == "assistant") count = 2;
        history.erase(history.begin() + 1, history.begin() + 1 + count);
        result.droppedMessages += static_cast<int>(count);
        prompt = impl_->RenderPrompt(history, request.thinking);
        promptTokens = impl_->Tokenize(prompt);
    }
    if (promptTokens.empty()) {
        result.error = "对话输入分词失败";
        return result;
    }
    if (static_cast<int>(promptTokens.size()) + request.maxNewTokens > impl_->contextTokens) {
        result.error = "当前输入超过上下文预算，请缩短输入或新建会话";
        return result;
    }
    result.promptTokens = static_cast<int>(promptTokens.size());

    auto* memory = llama_get_memory(impl_->context);
    size_t common = 0;
    if (impl_->conversationId == request.conversationId && impl_->thinking == request.thinking) {
        while (common < impl_->kvTokens.size() &&
               common + 1 < promptTokens.size() &&
               impl_->kvTokens[common] == promptTokens[common]) {
            ++common;
        }
    } else {
        impl_->conversationId = request.conversationId;
        impl_->thinking = request.thinking;
    }

    if (common == 0) {
        llama_memory_clear(memory, true);
        impl_->kvTokens.clear();
    } else if (common < impl_->kvTokens.size()) {
        if (!llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(common), -1)) {
            // Recurrent / hybrid SSM models may reject partial KV eviction; clear cleanly.
            llama_memory_clear(memory, true);
            impl_->kvTokens.clear();
            common = 0;
        } else {
            impl_->kvTokens.resize(common);
        }
    }

    const int64_t prefillStart = NowUs();
    std::string decodeError;
    if (!impl_->DecodeTokens(promptTokens.data() + common, promptTokens.size() - common,
                             static_cast<int32_t>(common), decodeError)) {
        if (impl_->cancelled.load()) {
            result.finish = LlamaChatFinish::Cancelled;
        } else {
            result.finish = LlamaChatFinish::Error;
            result.error = decodeError;
        }
        impl_->kvTokens.clear();
        return result;
    }
    result.prefillUs = std::max<int64_t>(0, NowUs() - prefillStart);
    impl_->kvTokens = promptTokens;

    auto chainParams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(chainParams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        llama_vocab_n_tokens(impl_->vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    const uint32_t seed = static_cast<uint32_t>(
        std::chrono::high_resolution_clock::now().time_since_epoch().count() & 0xFFFFFFFFu);
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(seed));

    Utf8Emitter emitter(onToken);
    bool stoppedByCallback = false;
    bool hitEog = false;
    int generated = 0;
    int32_t currentPos = static_cast<int32_t>(promptTokens.size());
    const int64_t decodeStart = NowUs();

    while (!impl_->cancelled.load() && generated < request.maxNewTokens && currentPos < impl_->contextTokens) {
        const llama_token nextToken = llama_sampler_sample(sampler, impl_->context, -1);
        if (llama_vocab_is_eog(impl_->vocab, nextToken)) {
            hitEog = true;
            break;
        }
        const std::string piece = impl_->TokenToPiece(nextToken);
        if (!piece.empty()) {
            if (!emitter.Feed(piece.data(), piece.size())) {
                stoppedByCallback = true;
                impl_->cancelled.store(true);
                break;
            }
        }
        ++generated;
        impl_->kvTokens.push_back(nextToken);
        if (generated >= request.maxNewTokens || currentPos + 1 >= impl_->contextTokens) break;

        if (!impl_->DecodeTokens(&nextToken, 1, currentPos, decodeError)) {
            if (!impl_->cancelled.load()) {
                result.error = decodeError;
            }
            break;
        }
        ++currentPos;
    }

    result.decodeUs = std::max<int64_t>(0, NowUs() - decodeStart);
    llama_sampler_free(sampler);

    emitter.Finish(impl_->cancelled.load() || stoppedByCallback);
    result.text = emitter.text();
    result.generatedTokens = generated;

    if (!result.error.empty()) {
        result.finish = LlamaChatFinish::Error;
        impl_->kvTokens.clear();
    } else if (impl_->cancelled.load() || stoppedByCallback) {
        result.finish = LlamaChatFinish::Cancelled;
        impl_->kvTokens.clear();
    } else if (hitEog) {
        result.finish = LlamaChatFinish::Finished;
    } else {
        result.finish = LlamaChatFinish::MaxTokens;
    }
    return result;
}

void LlamaChatSession::Cancel() {
    impl_->cancelled.store(true);
}

void LlamaChatSession::Reset() {
    std::lock_guard<std::mutex> lock(impl_->executionMutex);
    if (impl_->context != nullptr) {
        llama_memory_clear(llama_get_memory(impl_->context), true);
    }
    impl_->kvTokens.clear();
    impl_->conversationId.clear();
}

} // namespace omni
