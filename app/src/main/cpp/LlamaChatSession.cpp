#include "LlamaChatSession.h"

#include "ggml-backend.h"
#include "llama.h"
#if defined(OMNI_LLAMA_HAS_MTMD)
#include "mtmd.h"
#include "mtmd-helper.h"
#endif

#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <filesystem>
#include <fstream>
#include <limits>
#include <mutex>
#include <random>
#include <sstream>
#include <thread>

namespace omni {
namespace {

constexpr char kLogTag[] = "OmniLlamaChat";
constexpr char kReplacement[] = "\xEF\xBF\xBD";
constexpr int32_t kBatchSize = 512;

void LlamaAndroidLog(ggml_log_level level, const char* text, void*) {
    if (text == nullptr || text[0] == '\0') return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", text);
    } else if (level == GGML_LOG_LEVEL_INFO &&
               (std::strstr(text, "ggml_opencl") != nullptr ||
                std::strstr(text, "ggml_vulkan") != nullptr ||
                std::strstr(text, "ggml_hexagon") != nullptr ||
                std::strstr(text, "kleidiai") != nullptr)) {
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", text);
    }
}

bool StartsWithCaseInsensitive(const char* text, const char* prefix) {
    if (text == nullptr || prefix == nullptr) return false;
    while (*prefix != '\0') {
        if (*text == '\0') return false;
        const char a = (*text >= 'A' && *text <= 'Z') ? static_cast<char>(*text - 'A' + 'a') : *text;
        const char b = (*prefix >= 'A' && *prefix <= 'Z') ? static_cast<char>(*prefix - 'A' + 'a') : *prefix;
        if (a != b) return false;
        ++text;
        ++prefix;
    }
    return true;
}

bool ContainsCaseInsensitive(const char* text, const char* needle) {
    if (text == nullptr || needle == nullptr || needle[0] == '\0') return false;
    for (const char* p = text; *p != '\0'; ++p) {
        if (StartsWithCaseInsensitive(p, needle)) return true;
    }
    return false;
}

std::string DeviceDisplayLabel(ggml_backend_dev_t dev, const char* fallback) {
    if (dev == nullptr) return fallback;
    const char* desc = ggml_backend_dev_description(dev);
    const char* name = ggml_backend_dev_name(dev);
    if (desc != nullptr && desc[0] != '\0') return std::string(desc);
    if (name != nullptr && name[0] != '\0') return std::string(name);
    return fallback;
}

int DetectRecommendedPerfThreads() {
    std::vector<long> maxFreqs;
    for (int cpu = 0; cpu < 16; ++cpu) {
        std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq");
        long khz = 0;
        if (f >> khz && khz > 0) {
            maxFreqs.push_back(khz);
        }
    }
    if (maxFreqs.size() >= 6) {
        const long minFreq = *std::min_element(maxFreqs.begin(), maxFreqs.end());
        const long maxFreq = *std::max_element(maxFreqs.begin(), maxFreqs.end());
        if (maxFreq > minFreq) {
            int fastCores = 0;
            for (long khz : maxFreqs) {
                if (khz > minFreq) ++fastCores;
            }
            if (fastCores >= 4) {
                return std::clamp(fastCores, 4, 6);
            }
        }
    }
    const unsigned int hw = std::thread::hardware_concurrency();
    if (hw >= 8) return 6;
    if (hw >= 4) return 4;
    return 4;
}

bool ProbeHexagonFastRpcAvailable() {
    static std::once_flag once;
    static bool available = false;
    std::call_once(once, [] {
        const bool devAccessible =
            (access("/dev/adsprpc-smd", R_OK | W_OK) == 0) ||
            (access("/dev/adsprpc-smd-secure", R_OK | W_OK) == 0) ||
            (access("/dev/fastrpc-cdsp", R_OK | W_OK) == 0);
        void* handle = dlopen("libcdsprpc.so", RTLD_LAZY | RTLD_LOCAL);
        if (handle == nullptr) {
            handle = dlopen("/vendor/lib64/libcdsprpc.so", RTLD_LAZY | RTLD_LOCAL);
        }
        if (handle != nullptr) {
            dlclose(handle);
            available = true;
            __android_log_print(ANDROID_LOG_INFO, kLogTag,
                                "Hexagon FastRPC library detected (devAccessible=%d)", devAccessible ? 1 : 0);
        } else {
            available = devAccessible;
        }
    });
    return available;
}

ggml_backend_dev_t FindBackendDevice(GgufBackendPref pref) {
    const size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;
        const char* name = ggml_backend_dev_name(dev);
        const char* desc = ggml_backend_dev_description(dev);
        const auto type = ggml_backend_dev_type(dev);
        switch (pref) {
            case GgufBackendPref::Vulkan:
                if ((type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) &&
                    (StartsWithCaseInsensitive(name, "Vulkan") || ContainsCaseInsensitive(desc, "Vulkan"))) {
                    return dev;
                }
                break;
            case GgufBackendPref::OpenCl:
                if ((type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) &&
                    (ContainsCaseInsensitive(name, "OpenCL") || ContainsCaseInsensitive(desc, "OpenCL"))) {
                    return dev;
                }
                break;
            case GgufBackendPref::HexagonNpu:
                if (StartsWithCaseInsensitive(name, "HTP") || ContainsCaseInsensitive(name, "Hexagon") ||
                    ContainsCaseInsensitive(desc, "Hexagon")) {
                    return dev;
                }
                break;
            case GgufBackendPref::Auto:
            case GgufBackendPref::Cpu:
                break;
        }
    }
    return nullptr;
}

void EnsureLlamaRuntimeInitialized() {
    static std::once_flag backendOnce;
    std::call_once(backendOnce, [] {
        llama_log_set(LlamaAndroidLog, nullptr);
        ggml_backend_load_all();
    });
}

struct BatchGuard {
    llama_batch batch;
    explicit BatchGuard(int32_t nTokens) : batch(llama_batch_init(nTokens, 0, 1)) {}
    ~BatchGuard() { llama_batch_free(batch); }
    BatchGuard(const BatchGuard&) = delete;
    BatchGuard& operator=(const BatchGuard&) = delete;
};

struct SamplerGuard {
    llama_sampler* sampler;
    explicit SamplerGuard(llama_sampler* s) : sampler(s) {}
    ~SamplerGuard() {
        if (sampler != nullptr) llama_sampler_free(sampler);
    }
    SamplerGuard(const SamplerGuard&) = delete;
    SamplerGuard& operator=(const SamplerGuard&) = delete;
};

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
#if defined(OMNI_LLAMA_HAS_MTMD)
    mtmd_context* mtmdCtx = nullptr;
#endif
    std::atomic<bool> hasVision{false};
    std::atomic<int> contextTokens{4096};
    std::atomic<int> trainContextTokens{0};
    int threadCount = 4;
    std::string activeBackendLabel = "CPU";
    std::string conversationId;
    bool thinking = false;
    std::vector<llama_token> kvTokens;
    std::atomic<bool> cancelled{false};
    std::mutex executionMutex;

    ~Impl() { Clear(); }

    void Clear() {
#if defined(OMNI_LLAMA_HAS_MTMD)
        if (mtmdCtx != nullptr) {
            mtmd_free(mtmdCtx);
            mtmdCtx = nullptr;
        }
#endif
        hasVision.store(false);
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

    std::pair<std::string, bool> RenderPrompt(const LlamaChatMessages& messages, bool enableThinking) const {
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

        auto endsWith = [&](const std::string& suffix) {
            return rendered.size() >= suffix.size() &&
                   rendered.compare(rendered.size() - suffix.size(), suffix.size(), suffix) == 0;
        };

        const bool templateHasThink = tmplHint.find("<think>") != std::string::npos ||
                                      tmplHint.find("enable_thinking") != std::string::npos;
        const bool endsWithThinkTag = endsWith("<think>") || endsWith("<think>\n");
        bool promptOpenedThink = false;

        if (templateHasThink || endsWithThinkTag) {
            if (!enableThinking) {
                if (endsWith("<think>")) {
                    rendered += "\n</think>\n\n";
                } else if (endsWith("<think>\n")) {
                    rendered += "</think>\n\n";
                } else if (rendered.rfind("<think>") == std::string::npos ||
                           rendered.rfind("</think>") != std::string::npos) {
                    rendered += "<think>\n\n</think>\n\n";
                }
            } else if (endsWithThinkTag) {
                promptOpenedThink = true;
            }
        }
        return {std::move(rendered), promptOpenedThink};
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

    std::string TokenToPiece(llama_token token, bool special = false) const {
        char stackBuf[128];
        int32_t n = llama_token_to_piece(vocab, token, stackBuf, sizeof(stackBuf), 0, special);
        if (n >= 0) {
            return std::string(stackBuf, static_cast<size_t>(n));
        }
        const size_t required = static_cast<size_t>(-n);
        std::string heapBuf(required, '\0');
        n = llama_token_to_piece(vocab, token, heapBuf.data(), static_cast<int32_t>(heapBuf.size()), 0, special);
        if (n < 0) return {};
        heapBuf.resize(static_cast<size_t>(n));
        return heapBuf;
    }

    bool DecodeTokens(const llama_token* tokens, size_t count, int32_t startPos, std::string& error) {
        try {
            BatchGuard guard(kBatchSize);
            llama_batch& batch = guard.batch;
            size_t offset = 0;
            while (offset < count) {
                if (cancelled.load()) {
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
                    return false;
                }
                if (status != 0) {
                    error = "GGUF 推理前向计算失败（状态码 " + std::to_string(status) + "）";
                    return false;
                }
                offset += chunk;
            }
            return true;
        } catch (const std::exception& e) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "DecodeTokens exception: %s", e.what());
            error = std::string("GGUF 后端计算异常：") + e.what();
            return false;
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "DecodeTokens unknown native exception");
            error = "GGUF 后端发生未知计算异常";
            return false;
        }
    }
};

void LlamaChatSession::SetKernelCacheDir(const std::string& cacheDir) {
    if (cacheDir.empty()) return;
    std::error_code ec;
    std::filesystem::create_directories(cacheDir, ec);
    setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cacheDir.c_str(), 1);
}

BackendCapabilities LlamaChatSession::ProbeBackends() {
    BackendCapabilities caps;
#if defined(OMNI_LLAMA_HAS_KLEIDIAI)
    caps.hasKleidiAi = true;
#endif
#if defined(OMNI_LLAMA_HAS_HEXAGON)
    caps.hasHexagonCompiled = true;
#endif
    caps.recommendedThreads = DetectRecommendedPerfThreads();
    caps.hasHexagonFastRpc = ProbeHexagonFastRpcAvailable();
    try {
        EnsureLlamaRuntimeInitialized();
        if (ggml_backend_dev_t vk = FindBackendDevice(GgufBackendPref::Vulkan)) {
            caps.hasVulkan = true;
            caps.vulkanName = DeviceDisplayLabel(vk, "Vulkan GPU");
        }
        if (ggml_backend_dev_t cl = FindBackendDevice(GgufBackendPref::OpenCl)) {
            caps.hasOpenCl = true;
            caps.openClName = DeviceDisplayLabel(cl, "OpenCL GPU");
        }
        if (FindBackendDevice(GgufBackendPref::HexagonNpu) != nullptr) {
            caps.hasHexagonRuntime = true;
        }
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Backend probe exception: %s", e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Backend probe unknown exception");
    }
    return caps;
}

std::string LlamaChatSession::RuntimeGpuDeviceName() {
    const auto caps = ProbeBackends();
    if (caps.hasVulkan) return caps.vulkanName;
    if (caps.hasOpenCl) return caps.openClName;
    return {};
}

bool LlamaChatSession::HasRuntimeGpuDevice() {
    const auto caps = ProbeBackends();
    return caps.hasVulkan || caps.hasOpenCl || caps.hasHexagonRuntime;
}

LlamaChatSession::LlamaChatSession() : impl_(std::make_unique<Impl>()) {}
LlamaChatSession::~LlamaChatSession() {
    Cancel();
    std::lock_guard<std::mutex> lock(impl_->executionMutex);
    impl_->Clear();
}

bool LlamaChatSession::Load(const std::string& modelPath, int threadCount, int contextTokens, std::string& error,
                            int kvQuantMode, int backendPrefRaw, const std::string& mmprojPath) {
    std::lock_guard<std::mutex> lock(impl_->executionMutex);
    impl_->cancelled.store(false);
    error.clear();
    if (threadCount < 1 || threadCount > 16 || contextTokens < 512 || contextTokens > 131072) {
        error = "推理线程或上下文预算无效（支持 512–131072 tokens）";
        return false;
    }
    std::error_code ec;
    if (!std::filesystem::is_regular_file(modelPath, ec) || ec) {
        error = "GGUF 模型文件不存在";
        return false;
    }
    try {
        impl_->Clear();
        EnsureLlamaRuntimeInitialized();

        const auto pref = (backendPrefRaw >= 0 && backendPrefRaw <= 4)
            ? static_cast<GgufBackendPref>(backendPrefRaw)
            : GgufBackendPref::Auto;

        auto modelParams = llama_model_default_params();
        ggml_backend_dev_t selectedDevs[2] = {nullptr, nullptr};
        bool offloadActive = false;
        std::string backendLabel =
#if defined(OMNI_LLAMA_HAS_KLEIDIAI)
            "CPU · KleidiAI";
#else
            "CPU";
#endif

        if (pref == GgufBackendPref::Vulkan || pref == GgufBackendPref::OpenCl || pref == GgufBackendPref::HexagonNpu) {
            ggml_backend_dev_t target = FindBackendDevice(pref);
            if (target == nullptr) {
                if (pref == GgufBackendPref::HexagonNpu) {
                    error = ProbeHexagonFastRpcAvailable()
                        ? "当前公开版未内嵌高通 Hexagon HTP 算子库（需 Hexagon SDK 实验构建）"
                        : "当前设备未检测到高通 Hexagon HTP NPU";
                } else if (pref == GgufBackendPref::Vulkan) {
                    error = "当前设备未检测到可用的 Vulkan GPU 后端";
                } else {
                    error = "当前设备未检测到可用的 OpenCL GPU 后端";
                }
                return false;
            }
            selectedDevs[0] = target;
            modelParams.devices = selectedDevs;
            modelParams.n_gpu_layers = -1;
            offloadActive = true;
            const char* prefix = pref == GgufBackendPref::Vulkan
                ? "Vulkan"
                : (pref == GgufBackendPref::OpenCl ? "OpenCL" : "Hexagon HTP");
            backendLabel = std::string(prefix) + " · " + DeviceDisplayLabel(target, prefix);
        } else {
            // Auto & Cpu: run on CPU (KleidiAI + FP16) which delivers the highest decode stability
            // and lowest driver-fault risk across Snapdragon/Dimensity/Exynos quantizations.
            modelParams.n_gpu_layers = 0;
            offloadActive = false;
        }

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

        const int trainCtx = llama_model_n_ctx_train(impl_->model);
        impl_->trainContextTokens = trainCtx;
        const int effectiveCtx = trainCtx > 0 ? std::min(contextTokens, std::max(512, trainCtx)) : contextTokens;

        const int perfThreads = DetectRecommendedPerfThreads();
        // Use performance core count for batched prompt prefill (pp) even when decode uses fewer threads.
        const int batchThreads = std::clamp(std::max(threadCount, perfThreads), 2, 8);

        auto ctxParams = llama_context_default_params();
        ctxParams.n_ctx = static_cast<uint32_t>(effectiveCtx);
        ctxParams.n_batch = static_cast<uint32_t>(kBatchSize);
        ctxParams.n_ubatch = static_cast<uint32_t>(kBatchSize);
        ctxParams.n_threads = threadCount;
        ctxParams.n_threads_batch = batchThreads;
        ctxParams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        if (kvQuantMode == 1) {
            ctxParams.type_k = GGML_TYPE_Q8_0;
            ctxParams.type_v = GGML_TYPE_Q8_0;
        } else if (kvQuantMode == 2) {
            ctxParams.type_k = GGML_TYPE_Q4_0;
            ctxParams.type_v = GGML_TYPE_Q4_0;
        }
        ctxParams.offload_kqv = offloadActive;
        ctxParams.op_offload = offloadActive;
        ctxParams.abort_callback = [](void* data) -> bool {
            return static_cast<std::atomic<bool>*>(data)->load();
        };
        ctxParams.abort_callback_data = &impl_->cancelled;

        impl_->context = llama_init_from_model(impl_->model, ctxParams);
        if (impl_->context == nullptr && kvQuantMode != 0) {
            // Some hybrid/SSM architectures only support F16 KV cache; retry cleanly with default F16.
            ctxParams.type_k = GGML_TYPE_F16;
            ctxParams.type_v = GGML_TYPE_F16;
            impl_->context = llama_init_from_model(impl_->model, ctxParams);
        }
        if (impl_->context == nullptr) {
            error = "无法创建 " + std::to_string(effectiveCtx) + " token 推理上下文，可用内存不足";
            impl_->Clear();
            return false;
        }
        llama_set_causal_attn(impl_->context, true);
        impl_->contextTokens = effectiveCtx;
        impl_->threadCount = threadCount;
        impl_->activeBackendLabel = backendLabel;
#if defined(OMNI_LLAMA_HAS_MTMD)
        if (!mmprojPath.empty()) {
            std::error_code mmEc;
            if (std::filesystem::is_regular_file(mmprojPath, mmEc) && !mmEc) {
                mtmd_helper_log_set(LlamaAndroidLog, nullptr);
                auto mparams = mtmd_context_params_default();
                mparams.use_gpu = offloadActive;
                mparams.device = offloadActive ? selectedDevs[0] : nullptr;
                mparams.n_threads = batchThreads;
                mparams.print_timings = false;
                mparams.warmup = false;
                mparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
                impl_->mtmdCtx = mtmd_init_from_file(mmprojPath.c_str(), impl_->model, mparams);
                if (impl_->mtmdCtx == nullptr && offloadActive) {
                    __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                        "mtmd GPU init failed for %s; retrying on CPU", mmprojPath.c_str());
                    mparams.use_gpu = false;
                    mparams.device = nullptr;
                    impl_->mtmdCtx = mtmd_init_from_file(mmprojPath.c_str(), impl_->model, mparams);
                }
                if (impl_->mtmdCtx != nullptr) {
                    const bool supportsVision = mtmd_support_vision(impl_->mtmdCtx);
                    impl_->hasVision.store(supportsVision);
                    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                                        "Loaded mtmd projector (%s) supportsVision=%d",
                                        mmprojPath.c_str(), supportsVision ? 1 : 0);
                } else {
                    __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                        "Failed to initialize mtmd projector: %s", mmprojPath.c_str());
                }
            }
        }
#endif
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "Loaded GGUF model on [%s] n_ctx=%d threads=%d batch_threads=%d vision=%d",
                            backendLabel.c_str(), effectiveCtx, threadCount, batchThreads,
                            impl_->hasVision.load() ? 1 : 0);
        return true;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Load exception: %s", e.what());
        impl_->Clear();
        error = std::string("GGUF 模型加载异常：") + e.what();
        return false;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Load unknown native exception");
        impl_->Clear();
        error = "GGUF 模型加载发生未知异常";
        return false;
    }
}

int LlamaChatSession::EffectiveContextTokens() const {
    return impl_->contextTokens.load(std::memory_order_relaxed);
}

int LlamaChatSession::ModelTrainContextTokens() const {
    return impl_->trainContextTokens.load(std::memory_order_relaxed);
}

bool LlamaChatSession::HasVisionLoaded() const {
    return impl_->hasVision.load(std::memory_order_relaxed);
}

std::string LlamaChatSession::ActiveBackendLabel() const {
    std::lock_guard<std::mutex> lock(impl_->executionMutex);
    return impl_->activeBackendLabel;
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

    auto* memory = llama_get_memory(impl_->context);
    bool promptOpenedThink = false;
    bool isVisionTurn = !request.imagePaths.empty();
    int32_t currentPos = 0;

    if (isVisionTurn) {
#if defined(OMNI_LLAMA_HAS_MTMD)
        if (impl_->mtmdCtx == nullptr || !impl_->hasVision.load()) {
            result.error = "当前模型未绑定视觉投影文件（mmproj），请先在模型页导入或绑定配套的 mmproj*.gguf";
            return result;
        }
        mtmd::bitmaps bitmaps;
        auto initOpt = mtmd_helper_init_opt_default();
        for (const auto& imgPath : request.imagePaths) {
            auto wrapped = mtmd_helper_bitmap_init_from_file(impl_->mtmdCtx, imgPath.c_str(), false, initOpt);
            if (wrapped.bitmap == nullptr) {
                result.error = "无法读取所选图片（支持 JPG / PNG / BMP 格式）";
                return result;
            }
            bitmaps.entries.emplace_back(wrapped.bitmap);
        }
        const std::string marker = mtmd_default_marker();
        LlamaChatMessages history = request.messages;
        {
            // Ensure the final user message contains one <__media__> marker per attached image.
            std::string& lastUser = history.back().second;
            size_t found = 0;
            size_t pos = 0;
            while ((pos = lastUser.find(marker, pos)) != std::string::npos) {
                ++found;
                pos += marker.size();
            }
            if (found < bitmaps.entries.size()) {
                std::string prefix;
                for (size_t i = found; i < bitmaps.entries.size(); ++i) {
                    prefix += marker;
                    prefix += '\n';
                }
                lastUser = prefix + lastUser;
            }
        }
        auto bitmapPtrs = bitmaps.c_ptr();
        auto tokenizeVision = [&](const LlamaChatMessages& msgs, mtmd::input_chunks& outChunks,
                                  bool& outOpenedThink) -> int32_t {
            auto rendered = impl_->RenderPrompt(msgs, request.thinking);
            outOpenedThink = rendered.second;
            bool addBos = llama_vocab_get_add_bos(impl_->vocab);
            if (addBos) {
                const llama_token bos = llama_vocab_bos(impl_->vocab);
                if (bos >= 0) {
                    char bosBuf[64];
                    const int bosLen = llama_token_to_piece(impl_->vocab, bos, bosBuf, sizeof(bosBuf), 0, true);
                    if (bosLen > 0 && rendered.first.size() >= static_cast<size_t>(bosLen) &&
                        rendered.first.compare(0, static_cast<size_t>(bosLen), bosBuf, static_cast<size_t>(bosLen)) == 0) {
                        addBos = false;
                    }
                }
            }
            mtmd_input_text inpText;
            inpText.text = rendered.first.c_str();
            inpText.text_len = rendered.first.size();
            inpText.add_special = addBos;
            inpText.parse_special = true;
            outChunks.ptr.reset(mtmd_input_chunks_init());
            return mtmd_tokenize(impl_->mtmdCtx, outChunks.ptr.get(), &inpText,
                                 bitmapPtrs.data(), bitmapPtrs.size());
        };

        mtmd::input_chunks chunks;
        int32_t tokRc = tokenizeVision(history, chunks, promptOpenedThink);
        while (tokRc == 0 &&
               static_cast<int>(mtmd_helper_get_n_tokens(chunks.ptr.get())) + request.maxNewTokens > impl_->contextTokens &&
               history.size() > 2) {
            size_t count = 1;
            if (history.size() > 3 && history[1].first == "user" && history[2].first == "assistant") count = 2;
            history.erase(history.begin() + 1, history.begin() + 1 + count);
            result.droppedMessages += static_cast<int>(count);
            tokRc = tokenizeVision(history, chunks, promptOpenedThink);
        }
        if (tokRc != 0 || chunks.ptr == nullptr) {
            result.error = "多模态图文输入编码失败（错误码 " + std::to_string(tokRc) + "）";
            return result;
        }
        const size_t totalVisionTokens = mtmd_helper_get_n_tokens(chunks.ptr.get());
        if (static_cast<int>(totalVisionTokens) + request.maxNewTokens > impl_->contextTokens) {
            result.error = "图文输入超过当前上下文预算，请在设置中调大上下文窗口或缩短历史对话";
            return result;
        }
        result.promptTokens = static_cast<int>(totalVisionTokens);

        llama_memory_clear(memory, true);
        impl_->kvTokens.clear();
        impl_->conversationId = request.conversationId;
        impl_->thinking = request.thinking;

        const int64_t prefillStart = NowUs();
        llama_pos newNPast = 0;
        int32_t evalRc = -1;
        try {
            evalRc = mtmd_helper_eval_chunks(
                impl_->mtmdCtx, impl_->context, chunks.ptr.get(),
                /*n_past=*/0, /*seq_id=*/0, /*n_batch=*/kBatchSize,
                /*logits_last=*/true, &newNPast);
        } catch (const std::exception& e) {
            result.finish = LlamaChatFinish::Error;
            result.error = std::string("视觉编码计算异常：") + e.what();
            return result;
        } catch (...) {
            result.finish = LlamaChatFinish::Error;
            result.error = "视觉编码发生未知计算异常";
            return result;
        }
        if (impl_->cancelled.load()) {
            result.finish = LlamaChatFinish::Cancelled;
            return result;
        }
        if (evalRc != 0) {
            result.finish = LlamaChatFinish::Error;
            result.error = "多模态视觉前向计算失败（状态码 " + std::to_string(evalRc) + "）";
            return result;
        }
        llama_set_causal_attn(impl_->context, true);
        result.prefillUs = std::max<int64_t>(0, NowUs() - prefillStart);
        currentPos = static_cast<int32_t>(newNPast);
#else
        result.error = "当前构建未启用多模态视觉支持（mtmd）";
        return result;
#endif
    } else {
        LlamaChatMessages history = request.messages;
        auto [prompt, openedThink] = impl_->RenderPrompt(history, request.thinking);
        promptOpenedThink = openedThink;
        std::vector<llama_token> promptTokens = impl_->Tokenize(prompt);
        while (static_cast<int>(promptTokens.size()) + request.maxNewTokens > impl_->contextTokens && history.size() > 2) {
            size_t count = 1;
            if (history.size() > 3 && history[1].first == "user" && history[2].first == "assistant") count = 2;
            history.erase(history.begin() + 1, history.begin() + 1 + count);
            result.droppedMessages += static_cast<int>(count);
            auto rerendered = impl_->RenderPrompt(history, request.thinking);
            prompt = std::move(rerendered.first);
            promptOpenedThink = rerendered.second;
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
        currentPos = static_cast<int32_t>(promptTokens.size());
    }

    auto chainParams = llama_sampler_chain_default_params();
    SamplerGuard samplerGuard(llama_sampler_chain_init(chainParams));
    llama_sampler* sampler = samplerGuard.sampler;
    const float clampedTemp = std::clamp(request.temperature, 0.0f, 2.0f);
    const float clampedTopP = std::clamp(request.topP, 0.05f, 1.0f);
    const int clampedTopK = std::clamp(request.topK, 1, 200);
    const float clampedPenalty = std::clamp(request.repeatPenalty, 1.0f, 2.0f);
    if (clampedTemp <= 0.01f) {
        if (clampedPenalty > 1.001f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
                llama_vocab_n_tokens(impl_->vocab), 64, clampedPenalty, 0.0f, 0.0f));
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(clampedTopK));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(clampedTopP, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
        if (clampedPenalty > 1.001f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
                llama_vocab_n_tokens(impl_->vocab), 64, clampedPenalty, 0.0f, 0.0f));
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(clampedTemp));
        const uint32_t seed = static_cast<uint32_t>(
            std::chrono::high_resolution_clock::now().time_since_epoch().count() & 0xFFFFFFFFu);
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(seed));
    }

    Utf8Emitter emitter(onToken);
    if (promptOpenedThink) {
        constexpr char kOpenThink[] = "<think>\n";
        emitter.Feed(kOpenThink, sizeof(kOpenThink) - 1);
    }
    bool stoppedByCallback = false;
    bool hitEog = false;
    int generated = 0;
    const int64_t decodeStart = NowUs();
    std::string decodeError;

    constexpr const char* kStopMarkers[] = {
        "<|im_end|>", "<|eot_id|>", "<end_of_turn>", "<|endoftext|>", "<|end|>", "</s>"
    };

    try {
        while (!impl_->cancelled.load() && generated < request.maxNewTokens && currentPos < impl_->contextTokens) {
            const llama_token nextToken = llama_sampler_sample(sampler, impl_->context, -1);
            if (llama_vocab_is_eog(impl_->vocab, nextToken)) {
                hitEog = true;
                break;
            }
            const std::string specialPiece = impl_->TokenToPiece(nextToken, /*special=*/true);
            bool hitStopText = false;
            for (const char* marker : kStopMarkers) {
                if (specialPiece == marker) {
                    hitStopText = true;
                    break;
                }
            }
            if (hitStopText) {
                hitEog = true;
                break;
            }
            const std::string piece = (specialPiece == "<think>" || specialPiece == "</think>")
                ? specialPiece
                : impl_->TokenToPiece(nextToken, /*special=*/false);
            if (!piece.empty()) {
                if (!emitter.Feed(piece.data(), piece.size())) {
                    stoppedByCallback = true;
                    impl_->cancelled.store(true);
                    break;
                }
            }
            ++generated;
            // Stop BEFORE pushing to kvTokens if we won't decode this final token into KV memory,
            // keeping impl_->kvTokens strictly identical to the tokens actually stored in the KV cache.
            if (generated >= request.maxNewTokens || currentPos + 1 >= impl_->contextTokens) break;

            if (!impl_->DecodeTokens(&nextToken, 1, currentPos, decodeError)) {
                if (!impl_->cancelled.load()) {
                    result.error = decodeError;
                }
                break;
            }
            if (!isVisionTurn) {
                impl_->kvTokens.push_back(nextToken);
            }
            ++currentPos;
        }
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Generate exception: %s", e.what());
        result.error = std::string("GGUF 生成异常：") + e.what();
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Generate unknown native exception");
        result.error = "GGUF 生成发生未知异常";
    }

    result.decodeUs = std::max<int64_t>(0, NowUs() - decodeStart);

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
