#include "llm/decision.hpp"

#include "llama.h"
#include "ggml-backend.h"
#include "json.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <limits>
#include <mutex>
#include <set>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace MNN {
namespace Transformer {
namespace {

constexpr int kMaxBranchTokens = 16384;
constexpr int kMaxRequestTokens = 65536;
constexpr size_t kMaxTextBytes = 131072;
constexpr char kSystemPrompt[] =
    "Apply the question to the state. Choose exactly one of the listed options. "
    "Respond with only its uppercase letter, with no explanation or reasoning.";
constexpr const char* kExcludedTwoLetterLabels[] = {
    "BQ", "BZ", "CJ", "CQ", "CZ", "DQ", "DZ", "EJ", "EY", "FJ", "FQ", "FV", "FZ",
    "GJ", "GK", "GQ", "GZ", "HJ", "IY", "JF", "JG", "JH", "JL", "JN", "JQ",
};

std::vector<std::string> makeSlotLabels() {
    std::vector<std::string> labels;
    for (char letter = 'A'; letter <= 'Z'; ++letter) labels.emplace_back(1, letter);
    for (char first = 'A'; first <= 'Z' && labels.size() < 256; ++first) {
        for (char second = 'A'; second <= 'Z' && labels.size() < 256; ++second) {
            std::string label{first, second};
            bool excluded = false;
            for (const auto* item : kExcludedTwoLetterLabels) {
                if (label == item) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) labels.push_back(label);
        }
    }
    return labels;
}

int64_t nowUs() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

nlohmann::json readJson(const std::filesystem::path& path) {
    try {
        if (!std::filesystem::is_regular_file(path) || std::filesystem::file_size(path) > 1024 * 1024) {
            throw std::runtime_error("invalid configuration");
        }
        std::ifstream input(path, std::ios::binary);
        auto document = nlohmann::json::parse(input);
        if (!input.good() && !input.eof()) throw std::runtime_error("invalid configuration");
        if (!document.is_object()) throw std::runtime_error("invalid configuration");
        return document;
    } catch (const std::exception&) {
        throw std::runtime_error("模型图或决策运行配置不兼容");
    }
}

} // namespace

class DecisionLlm::Impl {
public:
    Impl(const std::string& configPath, const std::string& cachePath, int threadCount)
        : configPath(configPath), cachePath(cachePath), threadCount(threadCount) {}

    ~Impl() {
        clear();
    }

    void clear() {
        releaseContext();
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
        vocab = nullptr;
        loaded = false;
    }

    void releaseContext() {
        if (context != nullptr) {
            llama_free(context);
            context = nullptr;
        }
        contextTokens = 0;
    }

    llama_model* model = nullptr;
    llama_context* context = nullptr;
    uint32_t contextTokens = 0;
    const llama_vocab* vocab = nullptr;
    std::string configPath;
    std::string cachePath;
    std::filesystem::path modelPath;
    int threadCount;
    std::atomic<bool> cancelled{false};
    bool loaded = false;
    std::vector<std::string> slotLabels;
    std::vector<int> slotIds;
    std::mutex executionMutex;

    std::string render(const std::string& state, const std::string& suffix) const {
        return std::string("<|im_start|>system\n") + kSystemPrompt + "<|im_end|>\n<|im_start|>user\n<state>\n" +
            state + "\n</state>\n\n" + suffix + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
    }

    std::vector<int> tokenize(const std::string& text) const {
        if (text.empty()) return {};
        if (vocab == nullptr || text.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
            throw std::runtime_error("决策输入无法分词");
        }
        const int size = static_cast<int32_t>(text.size());
        const int count = llama_tokenize(vocab, text.data(), size, nullptr, 0, false, true);
        if (count >= 0) throw std::runtime_error("决策输入无法分词");
        const int required = -count;
        std::vector<int> tokens(static_cast<size_t>(required));
        const int written = llama_tokenize(vocab, text.data(), size, tokens.data(), required, false, true);
        if (written != required) throw std::runtime_error("决策输入无法分词");
        return tokens;
    }

    bool verifySlots() {
        std::vector<std::string> labels = defaultSlotLabels();
        std::vector<int> ids;
        auto verify = [&](const std::vector<std::string>& candidates) {
            ids.clear();
            for (const auto& label : candidates) {
                const auto encoded = tokenize(label);
                if (encoded.size() != 1 || std::find(ids.begin(), ids.end(), encoded[0]) != ids.end()) return false;
                char buffer[64];
                const int size = llama_token_to_piece(vocab, encoded[0], buffer, sizeof(buffer), 0, false);
                if (size <= 0 || size >= static_cast<int>(sizeof(buffer)) ||
                    std::string(buffer, static_cast<size_t>(size)) != label) {
                    return false;
                }
                ids.push_back(encoded[0]);
            }
            return true;
        };
        if (verify(labels)) {
            slotLabels = std::move(labels);
            slotIds = std::move(ids);
            return true;
        }
        labels.resize(26);
        if (!verify(labels)) return false;
        slotLabels = std::move(labels);
        slotIds = std::move(ids);
        return true;
    }

    void resetContext() {
        cancelled.store(false);
        if (context != nullptr) {
            llama_memory_clear(llama_get_memory(context), true);
            llama_set_causal_attn(context, false);
        }
    }

    // Non-causal blocks must be decoded in one ubatch, so the graph reserve grows with the
    // square of the context. Size it to the request instead of the 16K worst case.
    bool ensureContext(size_t tokens, std::string& error) {
        uint32_t size = 512;
        while (size < tokens) size *= 2;
        if (context != nullptr && contextTokens == size) return true;
        releaseContext();
        auto params = llama_context_default_params();
        params.n_ctx = size;
        params.n_batch = size;
        params.n_ubatch = size;
        params.n_outputs_max = 1;
        params.n_threads = threadCount;
        params.n_threads_batch = threadCount;
        params.type_k = GGML_TYPE_F32;
        params.type_v = GGML_TYPE_F32;
        params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        params.offload_kqv = false;
        params.op_offload = false;
        params.abort_callback = [](void* data) -> bool {
            return static_cast<std::atomic<bool>*>(data)->load();
        };
        params.abort_callback_data = &cancelled;
        context = llama_init_from_model(model, params);
        if (context == nullptr) {
            error = "无法为 " + std::to_string(size) + " token 创建推理上下文，可用内存不足";
            return false;
        }
        contextTokens = size;
        llama_set_causal_attn(context, false);
        return true;
    }

    bool decode(const std::vector<int>& tokens, std::string& error) {
        if (tokens.empty()) {
            error = "决策输入为空";
            return false;
        }
        llama_set_causal_attn(context, false);
        const auto batch = llama_batch_get_one(const_cast<llama_token*>(tokens.data()),
                                               static_cast<int32_t>(tokens.size()));
        const int status = llama_decode(context, batch);
        llama_synchronize(context);
        if (cancelled.load()) return false;
        if (status != 0) {
            error = "模型前向计算失败: " + std::to_string(status);
            return false;
        }
        return true;
    }

    std::vector<uint8_t> snapshot(std::string& error) const {
        const size_t size = llama_state_get_size(context);
        std::vector<uint8_t> data(size);
        if (size == 0 || llama_state_get_data(context, data.data(), data.size()) != size) {
            error = "无法保存共享前缀状态";
            return {};
        }
        return data;
    }

    bool restore(const std::vector<uint8_t>& snapshot, std::string& error) {
        if (llama_state_set_data(context, snapshot.data(), snapshot.size()) != snapshot.size()) {
            error = "无法恢复共享前缀状态";
            return false;
        }
        return true;
    }

    bool readBranch(int optionCount, int suffixTokens, int64_t prefillUs, DecisionBranch& result,
                    std::string& error) const {
        const float* values = llama_get_logits_ith(context, -1);
        const int vocabulary = llama_vocab_n_tokens(vocab);
        if (values == nullptr || vocabulary <= 0 || optionCount < 1 || optionCount > static_cast<int>(slotIds.size())) {
            error = "决策模型返回了无效 logits 或选项数量";
            return false;
        }
        float fullMax = -std::numeric_limits<float>::infinity();
        for (int index = 0; index < vocabulary; ++index) {
            if (!std::isfinite(values[index])) {
                error = "决策模型 logits 包含非有限值";
                return false;
            }
            fullMax = std::max(fullMax, values[index]);
        }
        float selectedMax = -std::numeric_limits<float>::infinity();
        result.logits.reserve(optionCount);
        result.probabilities.reserve(optionCount);
        for (int index = 0; index < optionCount; ++index) {
            const int token = slotIds[index];
            if (token < 0 || token >= vocabulary) {
                error = "选项 token 超出模型词表";
                return false;
            }
            const float value = values[token];
            result.logits.push_back(value);
            selectedMax = std::max(selectedMax, value);
        }
        double fullSum = 0.0;
        for (int index = 0; index < vocabulary; ++index) {
            fullSum += std::exp(static_cast<double>(values[index] - fullMax));
        }
        double selectedSum = 0.0;
        for (const float value : result.logits) {
            const double weight = std::exp(static_cast<double>(value - selectedMax));
            result.probabilities.push_back(static_cast<float>(weight));
            selectedSum += weight;
        }
        if (!(fullSum > 0.0) || !(selectedSum > 0.0)) {
            error = "无法归一化决策概率";
            return false;
        }
        result.allowedMass = std::exp(static_cast<double>(selectedMax - fullMax)) * selectedSum / fullSum;
        for (auto& probability : result.probabilities) probability /= static_cast<float>(selectedSum);
        result.suffixTokens = suffixTokens;
        result.prefillUs = prefillUs;
        if (!std::isfinite(result.allowedMass) || result.allowedMass <= 0.0 || result.allowedMass > 1.000001) {
            error = "选项概率质量无效";
            return false;
        }
        return true;
    }
};

bool decisionAttentionAllowed(int prefixLength, int queryPosition, int keyPosition) {
    if (prefixLength <= 0 || queryPosition < 0 || keyPosition < 0) return false;
    const int queryBlock = queryPosition < prefixLength ? 0 : 1;
    const int keyBlock = keyPosition < prefixLength ? 0 : 1;
    return keyBlock <= queryBlock;
}

DecisionLlm::DecisionLlm(const std::string& configPath, const std::string& cachePath, int threadCount)
    : mImpl(new Impl(configPath, cachePath, threadCount)) {}

DecisionLlm::~DecisionLlm() {
    cancel();
    std::lock_guard<std::mutex> lock(mImpl->executionMutex);
    mImpl->clear();
}

DecisionLlm* DecisionLlm::create(const std::string& configPath, const std::string& cachePath, int threadCount) {
    return new DecisionLlm(configPath, cachePath, threadCount);
}

std::vector<std::string> DecisionLlm::defaultSlotLabels() {
    return makeSlotLabels();
}

bool DecisionLlm::load(std::string& error) {
    std::lock_guard<std::mutex> lock(mImpl->executionMutex);
    error.clear();
    if (mImpl->loaded) return true;
    try {
        if (mImpl->threadCount < 1 || mImpl->threadCount > 16 || mImpl->cachePath.empty()) {
            error = "决策模型运行参数无效";
            return false;
        }
        const auto config = readJson(mImpl->configPath);
        auto base = std::filesystem::absolute(mImpl->configPath).parent_path();
        const auto configuredBase = config.value("base_dir", std::string());
        if (!configuredBase.empty()) base = configuredBase;
        const auto metadata = readJson(base / "decision.json");
        const auto llmConfig = readJson(base / "llm_config.json");
        const auto& attention = metadata["attention"];
        const auto& readout = metadata["readout"];
        const std::vector<std::string> excluded(std::begin(kExcludedTwoLetterLabels), std::end(kExcludedTwoLetterLabels));
        const bool compatible = metadata.value("protocol", "") == "qev-labels-v2" &&
            metadata.value("modelType", "") == "qwen3_5" &&
            metadata.value("modelRepo", "") == "baselabs/elephant-1" &&
            metadata.value("modelRevision", "") == "b3f8a93462fb1051a9de031e94a7ff380e254072" &&
            metadata.value("qkvHeadOrder", "") == "gguf-tiled-v" &&
            metadata.value("excludedTwoLetterLabels", std::vector<std::string>()) == excluded &&
            attention.value("full_attention", "") == "block_bidirectional" &&
            attention.value("linear_attention", "") == "causal" &&
            readout.value("readout", "") == "slot_lm" && readout.value("head_dim", 0) == 256 &&
            readout.value("n_slots", 0) == 256 && !readout.contains("attention") &&
            metadata.value("maxSequenceTokens", 0) == kMaxBranchTokens &&
            metadata.value("chatTemplateSha256", "") ==
                "a4aee8afcf2e0711942cf848899be66016f8d14a889ff9ede07bca099c28f715" &&
            metadata.value("chatTemplate", "") == llmConfig["jinja"].value("chat_template", "") &&
            llmConfig.value("model_type", "") == "qwen3_5" && llmConfig.value("hidden_size", 0) == 4096;
        if (!compatible) {
            error = "模型图或决策运行配置不兼容";
            return false;
        }
        const auto manifest = readJson(base / "manifest.json");
        const auto& models = manifest["models"];
        const std::set<std::string> required = {
            "config.json", "llm_config.json", "decision.json", "provenance.json", "elephant-1.Q4_K_M.gguf"
        };
        std::set<std::string> paths;
        bool verified = models.is_array() && models.size() == 1 && models[0].is_object() &&
            models[0].value("id", "") == "elephant-1" && models[0].value("family", "") == "qwen3_5" &&
            models[0].value("kind", "") == "decision" && models[0].value("repo", "") == "baselabs/elephant-1" &&
            models[0].value("revision", "") == "b3f8a93462fb1051a9de031e94a7ff380e254072" &&
            models[0].value("localOnly", false) && models[0]["files"].is_array() &&
            models[0]["files"].size() == required.size();
        if (!verified) {
            error = "缺少已校验的 Elephant GGUF 模型";
            return false;
        }
        for (const auto& file : models[0]["files"]) {
            if (!file.is_object()) {
                verified = false;
                break;
            }
            const auto path = file.value("path", "");
            const auto digest = file.value("sha256", "");
            verified = verified && required.count(path) == 1 && paths.insert(path).second &&
                file.value("size", 0LL) > 0 && digest.size() == 64 &&
                digest.find_first_not_of("0123456789abcdef") == std::string::npos;
            if (path == "elephant-1.Q4_K_M.gguf") {
                verified = verified && digest == "764ef1834ffe7721f11ff7ed2ba42c3616140493b7505e2928eee057496c95a4" &&
                    file.value("size", 0LL) == 5629109376LL;
            }
        }
        verified = verified && paths == required;
        if (!verified) {
            error = "缺少已校验的 Elephant GGUF 模型";
            return false;
        }
        mImpl->modelPath = base / "elephant-1.Q4_K_M.gguf";
        if (!std::filesystem::is_regular_file(mImpl->modelPath) ||
            std::filesystem::file_size(mImpl->modelPath) != 5629109376LL) {
            error = "Elephant GGUF 文件大小不正确";
            return false;
        }
        std::filesystem::create_directories(mImpl->cachePath);
        static std::once_flag backendOnce;
        std::call_once(backendOnce, [] { ggml_backend_load_all(); });
        auto modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = 0;
        modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;
        modelParams.progress_callback = [](float, void* data) -> bool {
            return !static_cast<std::atomic<bool>*>(data)->load();
        };
        modelParams.progress_callback_user_data = &mImpl->cancelled;
        mImpl->model = llama_model_load_from_file(mImpl->modelPath.c_str(), modelParams);
        if (mImpl->model == nullptr) {
            error = mImpl->cancelled.load() ? "已取消加载" : "Elephant GGUF 加载失败，请检查文件和可用内存";
            return false;
        }
        mImpl->vocab = llama_model_get_vocab(mImpl->model);
        if (mImpl->vocab == nullptr || llama_vocab_n_tokens(mImpl->vocab) <= 0 || !mImpl->verifySlots()) {
            error = "无法验证决策选项 token";
            mImpl->clear();
            return false;
        }
        mImpl->loaded = true;
        return true;
    } catch (const nlohmann::json::exception&) {
        mImpl->clear();
        error = "模型图或决策运行配置不兼容";
        return false;
    } catch (const std::exception& exception) {
        mImpl->clear();
        error = exception.what();
        return false;
    }
}

void DecisionLlm::cancel() {
    mImpl->cancelled.store(true);
}

void DecisionLlm::reset() {
    std::lock_guard<std::mutex> lock(mImpl->executionMutex);
    mImpl->resetContext();
}

std::vector<std::string> DecisionLlm::slotLabels() {
    std::lock_guard<std::mutex> lock(mImpl->executionMutex);
    return mImpl->slotLabels;
}

std::string DecisionLlm::render(const std::string& state, const std::string& suffix) {
    std::lock_guard<std::mutex> lock(mImpl->executionMutex);
    return mImpl->render(state, suffix);
}

std::vector<int> DecisionLlm::tokenize(const std::string& text) {
    std::lock_guard<std::mutex> lock(mImpl->executionMutex);
    return mImpl->tokenize(text);
}

bool DecisionLlm::decide(const std::string& state, const std::vector<DecisionQuestion>& questions,
                         DecisionResult& result, const std::function<bool(int, int)>& progress) {
    std::lock_guard<std::mutex> lock(mImpl->executionMutex);
    result = DecisionResult{};
    try {
        if (!mImpl->loaded) {
            result.error = "决策模型尚未加载";
            return false;
        }
        if (questions.empty() || questions.size() > 32) {
            result.error = "单次需要 1–32 个决策问题";
            return false;
        }
        if (state.empty() || state.size() > kMaxTextBytes) {
            result.error = "状态文本为空或超过 131072 字节上限";
            return false;
        }
        mImpl->resetContext();
        const auto marked = mImpl->render(state, "QEV_PREFIX_MARK");
        const auto marker = marked.rfind("QEV_PREFIX_MARK");
        if (marker == std::string::npos) {
            result.error = "无法确定决策前缀边界";
            return false;
        }
        const auto prefix = mImpl->tokenize(marked.substr(0, marker));
        if (prefix.empty()) {
            result.error = "决策状态前缀为空";
            return false;
        }
        std::vector<std::vector<int>> suffixes;
        size_t totalTokens = prefix.size();
        size_t longest = prefix.size();
        for (const auto& question : questions) {
            if (question.optionCount < 1 || question.optionCount > static_cast<int>(mImpl->slotIds.size())) {
                result.error = "选项数量超出当前 slot 上限";
                return false;
            }
            if (question.suffix.size() > kMaxTextBytes) {
                result.error = "单个决策问题超过文本上限";
                return false;
            }
            const auto full = mImpl->tokenize(mImpl->render(state, question.suffix));
            const size_t common = prefix.size();
            // The cached prefix holds exactly these tokens, so every branch must extend them unchanged.
            if (full.size() < common + 2 || !std::equal(prefix.begin(), prefix.end(), full.begin())) {
                result.error = "决策状态与问题的分词边界不一致";
                return false;
            }
            std::vector<int> suffix(full.begin() + common, full.end());
            const size_t branchTokens = common + suffix.size();
            if (branchTokens > kMaxBranchTokens) {
                result.error = "状态与最长问题超过 16384 token 上限";
                return false;
            }
            if (totalTokens > kMaxRequestTokens - suffix.size()) {
                result.error = "决策请求超过总 token 上限";
                return false;
            }
            totalTokens += suffix.size();
            longest = std::max(longest, branchTokens);
            suffixes.push_back(std::move(suffix));
        }
        if (mImpl->cancelled.load()) {
            result.cancelled = true;
            return false;
        }
        std::string forwardError;
        if (!mImpl->ensureContext(longest, forwardError)) {
            result.error = forwardError;
            return false;
        }
        const int64_t prefixStart = nowUs();
        if (!mImpl->decode(prefix, forwardError)) {
            if (mImpl->cancelled.load()) {
                result.cancelled = true;
            } else {
                result.error = forwardError;
            }
            return false;
        }
        result.prefixTokens = static_cast<int>(prefix.size());
        result.prefixPrefillUs = std::max<int64_t>(0, nowUs() - prefixStart);
        auto snapshot = mImpl->snapshot(forwardError);
        if (snapshot.empty()) {
            result.error = forwardError;
            return false;
        }
        if (progress && !progress(0, static_cast<int>(questions.size()))) {
            result.cancelled = true;
            result.branches.clear();
            return false;
        }
        for (size_t index = 0; index < questions.size(); ++index) {
            if (mImpl->cancelled.load()) {
                result.cancelled = true;
                result.branches.clear();
                return false;
            }
            if (!mImpl->restore(snapshot, forwardError)) {
                result.error = forwardError;
                result.branches.clear();
                return false;
            }
            const auto& suffix = suffixes[index];
            const int64_t branchStart = nowUs();
            if (!mImpl->decode(suffix, forwardError)) {
                if (mImpl->cancelled.load()) {
                    result.cancelled = true;
                } else {
                    result.error = forwardError;
                }
                result.branches.clear();
                return false;
            }
            DecisionBranch branch;
            if (!mImpl->readBranch(questions[index].optionCount, static_cast<int>(suffix.size()),
                                   std::max<int64_t>(0, nowUs() - branchStart), branch, forwardError)) {
                result.error = forwardError;
                result.branches.clear();
                return false;
            }
            result.branches.push_back(std::move(branch));
            if (progress && !progress(static_cast<int>(index + 1), static_cast<int>(questions.size()))) {
                result.cancelled = true;
                result.branches.clear();
                return false;
            }
        }
        return true;
    } catch (const std::exception& exception) {
        result = DecisionResult{};
        result.error = exception.what();
        result.branches.clear();
        return false;
    }
}

} // namespace Transformer
} // namespace MNN
