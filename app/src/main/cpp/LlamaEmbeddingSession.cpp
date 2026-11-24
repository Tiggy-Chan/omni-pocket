#include "LlamaEmbeddingSession.h"

#include "ggml-backend.h"
#include "llama.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>

namespace omni {
namespace {

constexpr char kEmbLogTag[] = "OmniLlamaEmbed";

void EnsureEmbeddingBackendInit() {
    static std::once_flag once;
    std::call_once(once, [] {
        ggml_backend_load_all();
    });
}

void L2Normalize(std::vector<float>& vec) {
    double sumSq = 0.0;
    for (float v : vec) {
        sumSq += static_cast<double>(v) * static_cast<double>(v);
    }
    if (sumSq > 1e-12) {
        const float inv = static_cast<float>(1.0 / std::sqrt(sumSq));
        for (float& v : vec) v *= inv;
    }
}

} // namespace

struct LlamaEmbeddingSession::Impl {
    llama_model* model = nullptr;
    llama_context* context = nullptr;
    const llama_vocab* vocab = nullptr;
    int embeddingDim = 0;
    int maxTokens = 512;
    bool encoderOnly = false;
    std::atomic<bool> cancelled{false};
    std::mutex mutex;

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
        embeddingDim = 0;
        encoderOnly = false;
    }
};

LlamaEmbeddingSession::LlamaEmbeddingSession() : impl_(std::make_unique<Impl>()) {}
LlamaEmbeddingSession::~LlamaEmbeddingSession() = default;

bool LlamaEmbeddingSession::Load(const std::string& modelPath, int threads, int contextTokens, std::string& error) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    impl_->Clear();
    impl_->cancelled.store(false);
    EnsureEmbeddingBackendInit();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    impl_->model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (impl_->model == nullptr) {
        error = "Failed to load GGUF embedding model";
        return false;
    }

    if (llama_model_has_encoder(impl_->model) && llama_model_has_decoder(impl_->model)) {
        impl_->Clear();
        error = "Encoder-decoder models are not supported for embeddings";
        return false;
    }

    impl_->encoderOnly = llama_model_has_encoder(impl_->model) && !llama_model_has_decoder(impl_->model);
    impl_->vocab = llama_model_get_vocab(impl_->model);
    const int trainCtx = std::max(512, llama_model_n_ctx_train(impl_->model));
    const int reqCtx = std::clamp(contextTokens, 512, std::min(trainCtx, 4096));
    const int nThreads = std::clamp(threads, 1, 8);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(reqCtx);
    cparams.n_batch = static_cast<uint32_t>(reqCtx);
    cparams.n_ubatch = static_cast<uint32_t>(reqCtx);
    cparams.n_seq_max = 1;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    cparams.embeddings = true;
    cparams.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED;
    cparams.no_perf = true;

    impl_->context = llama_init_from_model(impl_->model, cparams);
    if (impl_->context == nullptr) {
        impl_->Clear();
        error = "Failed to initialize embedding context";
        return false;
    }

    impl_->maxTokens = static_cast<int>(llama_n_ctx(impl_->context));
    impl_->embeddingDim = llama_model_n_embd_out(impl_->model);
    if (impl_->embeddingDim <= 0) {
        impl_->embeddingDim = llama_model_n_embd(impl_->model);
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        kEmbLogTag,
        "Loaded embedding model dim=%d n_ctx=%d encoderOnly=%d pooling=%d",
        impl_->embeddingDim,
        impl_->maxTokens,
        impl_->encoderOnly ? 1 : 0,
        static_cast<int>(llama_pooling_type(impl_->context))
    );
    return impl_->embeddingDim > 0;
}

bool LlamaEmbeddingSession::Embed(const std::string& text, std::vector<float>& outVector, std::string& error) {
    std::lock_guard<std::mutex> lock(impl_->mutex);
    outVector.clear();
    if (impl_->model == nullptr || impl_->context == nullptr || impl_->vocab == nullptr) {
        error = "Embedding model is not loaded";
        return false;
    }
    if (impl_->cancelled.load()) {
        error = "Cancelled";
        return false;
    }

    const int maxTok = std::max(8, impl_->maxTokens);
    std::vector<llama_token> tokens(static_cast<size_t>(maxTok));
    int32_t nTokens = llama_tokenize(
        impl_->vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        /*add_special=*/true,
        /*parse_special=*/false
    );
    if (nTokens < 0) {
        const int32_t needed = -nTokens;
        tokens.resize(static_cast<size_t>(needed));
        nTokens = llama_tokenize(
            impl_->vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            needed,
            /*add_special=*/true,
            /*parse_special=*/false
        );
    }
    if (nTokens <= 0) {
        error = "Empty token sequence";
        return false;
    }
    if (nTokens > maxTok) {
        nTokens = maxTok;
        tokens.resize(static_cast<size_t>(maxTok));
        const llama_token sep = llama_vocab_sep(impl_->vocab);
        const llama_token eos = llama_vocab_eos(impl_->vocab);
        if (sep != LLAMA_TOKEN_NULL) {
            tokens.back() = sep;
        } else if (eos != LLAMA_TOKEN_NULL) {
            tokens.back() = eos;
        }
    } else {
        tokens.resize(static_cast<size_t>(nTokens));
    }

    llama_memory_t mem = llama_get_memory(impl_->context);
    if (mem != nullptr) {
        llama_memory_clear(mem, true);
    }

    llama_batch batch = llama_batch_init(nTokens, 0, 1);
    batch.n_tokens = nTokens;
    for (int32_t i = 0; i < nTokens; ++i) {
        batch.token[i] = tokens[static_cast<size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 1;
    }

    const int32_t rc = impl_->encoderOnly
        ? llama_encode(impl_->context, batch)
        : llama_decode(impl_->context, batch);
    llama_batch_free(batch);

    if (rc < 0) {
        error = "Embedding forward pass failed";
        return false;
    }

    const int dim = impl_->embeddingDim;
    const enum llama_pooling_type ptype = llama_pooling_type(impl_->context);
    outVector.assign(static_cast<size_t>(dim), 0.0f);

    if (ptype != LLAMA_POOLING_TYPE_NONE) {
        const float* seqEmb = llama_get_embeddings_seq(impl_->context, 0);
        if (seqEmb == nullptr) {
            error = "Sequence embedding unavailable";
            outVector.clear();
            return false;
        }
        std::memcpy(outVector.data(), seqEmb, static_cast<size_t>(dim) * sizeof(float));
    } else {
        int counted = 0;
        for (int32_t i = 0; i < nTokens; ++i) {
            const float* tokEmb = llama_get_embeddings_ith(impl_->context, i);
            if (tokEmb == nullptr) continue;
            for (int d = 0; d < dim; ++d) {
                outVector[static_cast<size_t>(d)] += tokEmb[d];
            }
            ++counted;
        }
        if (counted == 0) {
            error = "Token embeddings unavailable";
            outVector.clear();
            return false;
        }
        const float inv = 1.0f / static_cast<float>(counted);
        for (float& v : outVector) v *= inv;
    }

    L2Normalize(outVector);
    return true;
}

int LlamaEmbeddingSession::EmbeddingDimension() const {
    return impl_->embeddingDim;
}

void LlamaEmbeddingSession::Cancel() {
    impl_->cancelled.store(true);
}

} // namespace omni
