// JNI bridge for com.scsonic.qwenimage21.QwenImage21 -> MNN QwenImage21Diffusion.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <exception>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>
#include "diffusion/qwen_image21_diffusion.hpp"

using namespace MNN::DIFFUSION;

#define LOG_TAG "QwenImage21"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
constexpr jint kCancelled = 4;
constexpr int kRunIdle = 0;
constexpr int kRunActive = 1;
constexpr int kRunCancelling = 2;
struct GenerationCancelled {};
struct JavaExceptionPending {};

struct Handle {
    std::unique_ptr<Diffusion> model;
    QwenImage21Diffusion* qwen = nullptr;  // same object, typed
    std::mutex mutex;
    std::atomic<int> runState{kRunIdle};
    int errorCode = 0;
    std::string error;
};

std::string toString(JNIEnv* env, jstring s) {
    if (env == nullptr || s == nullptr) return std::string();
    const jsize len = env->GetStringLength(s);
    if (len <= 0) return std::string();
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string r;
    r.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = static_cast<uint32_t>(chars[i]);
        if (cp >= 0xD800u && cp <= 0xDBFFu && i + 1 < len) {
            const uint32_t low = static_cast<uint32_t>(chars[i + 1]);
            if (low >= 0xDC00u && low <= 0xDFFFu) {
                cp = 0x10000u + (((cp - 0xD800u) << 10) | (low - 0xDC00u));
                ++i;
            } else {
                cp = 0xFFFDu;
            }
        } else if (cp >= 0xD800u && cp <= 0xDFFFu) {
            cp = 0xFFFDu;
        }
        if (cp <= 0x7Fu) {
            r.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FFu) {
            r.push_back(static_cast<char>(0xC0u | (cp >> 6)));
            r.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        } else if (cp <= 0xFFFFu) {
            r.push_back(static_cast<char>(0xE0u | (cp >> 12)));
            r.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
            r.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        } else {
            r.push_back(static_cast<char>(0xF0u | (cp >> 18)));
            r.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
            r.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
            r.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        }
    }
    env->ReleaseStringChars(s, chars);
    return r;
}

jstring fromUtf8(JNIEnv* env, const std::string& s) {
    if (env == nullptr) return nullptr;
    if (s.empty()) return env->NewString(nullptr, 0);
    std::vector<jchar> utf16;
    utf16.reserve(s.size());
    const auto* bytes = reinterpret_cast<const uint8_t*>(s.data());
    size_t i = 0;
    while (i < s.size()) {
        const uint8_t b0 = bytes[i];
        uint32_t cp = 0xFFFDu;
        if (b0 <= 0x7Fu) {
            cp = b0;
            i += 1;
        } else if ((b0 & 0xE0u) == 0xC0u && i + 1 < s.size() && (bytes[i + 1] & 0xC0u) == 0x80u) {
            const uint32_t v = ((b0 & 0x1Fu) << 6) | (bytes[i + 1] & 0x3Fu);
            if (v >= 0x80u) { cp = v; i += 2; } else { i += 1; }
        } else if ((b0 & 0xF0u) == 0xE0u && i + 2 < s.size() &&
                   (bytes[i + 1] & 0xC0u) == 0x80u && (bytes[i + 2] & 0xC0u) == 0x80u) {
            const uint32_t v = ((b0 & 0x0Fu) << 12) | ((bytes[i + 1] & 0x3Fu) << 6) | (bytes[i + 2] & 0x3Fu);
            if (v >= 0x800u && !(v >= 0xD800u && v <= 0xDFFFu)) { cp = v; i += 3; } else { i += 1; }
        } else if ((b0 & 0xF8u) == 0xF0u && i + 3 < s.size() &&
                   (bytes[i + 1] & 0xC0u) == 0x80u && (bytes[i + 2] & 0xC0u) == 0x80u &&
                   (bytes[i + 3] & 0xC0u) == 0x80u) {
            const uint32_t v = ((b0 & 0x07u) << 18) | ((bytes[i + 1] & 0x3Fu) << 12) |
                               ((bytes[i + 2] & 0x3Fu) << 6) | (bytes[i + 3] & 0x3Fu);
            if (v >= 0x10000u && v <= 0x10FFFFu) { cp = v; i += 4; } else { i += 1; }
        } else {
            i += 1;
        }
        if (cp <= 0xFFFFu) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            const uint32_t adj = cp - 0x10000u;
            utf16.push_back(static_cast<jchar>(0xD800u + (adj >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00u + (adj & 0x3FFu)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_scsonic_qwenimage21_QwenImage21_nativeCreate(JNIEnv* env, jclass, jstring modelDir, jboolean useGpu,
                                                      jboolean textEncoderOnCpu, jboolean vaeOnCpu,
                                                      jint memoryMode, jint threads) {
    std::string dir = toString(env, modelDir);
    auto* handle = new (std::nothrow) Handle;
    if (!handle) return 0;
    try {
        handle->model.reset(Diffusion::createDiffusion(dir, QWEN_IMAGE_21,
                                                       useGpu ? MNN_FORWARD_OPENCL : MNN_FORWARD_CPU, memoryMode, 512,
                                                       512, textEncoderOnCpu, vaeOnCpu, GPU_MEMORY_BUFFER,
                                                       PRECISION_LOW, CFG_MODE_AUTO, threads));
        handle->qwen = static_cast<QwenImage21Diffusion*>(handle->model.get());
        if (!handle->model || !handle->model->load()) {
            LOGE("failed to load Qwen-Image-2.1 from %s", dir.c_str());
            delete handle;
            return 0;
        }
    } catch (const std::bad_alloc&) {
        LOGE("out of memory while loading");
        delete handle;
        return 0;
    } catch (const std::exception& e) {
        LOGE("exception while loading: %s", e.what());
        delete handle;
        return 0;
    } catch (...) {
        LOGE("unknown native exception while loading");
        delete handle;
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

// Returns 0 = ok, 4 = cancelled, or QwenImage21Diffusion::ErrorCode; nativeLastError holds the detail.
extern "C" JNIEXPORT jint JNICALL
Java_com_scsonic_qwenimage21_QwenImage21_nativeGenerate(JNIEnv* env, jclass, jlong ptr, jstring prompt,
                                                        jstring inputImage, jstring inputImage2, jstring outputPng,
                                                        jint steps, jint seed, jint width, jint height,
                                                        jboolean turbo, jboolean officialTurbo,
                                                        jfloat refAreaScale, jboolean tinyVae, jboolean dit2Bit,
                                                        jobject listener) {
    auto handle = reinterpret_cast<Handle*>(ptr);
    if (!handle) return QwenImage21Diffusion::kRuntimeError;
    std::lock_guard<std::mutex> lock(handle->mutex);
    jmethodID onProgress = nullptr;
    if (listener) {
        jclass listenerClass = env->GetObjectClass(listener);
        if (!listenerClass) return QwenImage21Diffusion::kRuntimeError;
        onProgress = env->GetMethodID(listenerClass, "onProgress", "(I)V");
        env->DeleteLocalRef(listenerClass);
        if (!onProgress) return QwenImage21Diffusion::kRuntimeError;
    }
    int expected = kRunIdle;
    if (!handle->runState.compare_exchange_strong(expected, kRunActive, std::memory_order_relaxed)) {
        handle->errorCode = QwenImage21Diffusion::kRuntimeError;
        handle->error = "generation already active";
        return handle->errorCode;
    }
    auto cb = [env, listener, onProgress, handle](int p) {
        if (handle->runState.load(std::memory_order_relaxed) == kRunCancelling) throw GenerationCancelled{};
        if (onProgress) {
            env->CallVoidMethod(listener, onProgress, (jint)p);
            if (env->ExceptionCheck()) throw JavaExceptionPending{};
        }
        if (handle->runState.load(std::memory_order_relaxed) == kRunCancelling) throw GenerationCancelled{};
    };
    bool ok = false;
    try {
        handle->model->setTurbo(turbo);  // forces steps to 6 internally when true; no-op when unchanged
        handle->qwen->setOfficialTurbo(officialTurbo);
        handle->qwen->setTinyVae(tinyVae);
        handle->qwen->setDit2Bit(dit2Bit);
        if (width > 0 && height > 0) handle->qwen->setImageSize(width, height);
        handle->qwen->setRefAreaScale(refAreaScale > 0 ? refAreaScale : 1.0);
        std::string img1 = toString(env, inputImage);
        std::string img2 = toString(env, inputImage2);
        if (!img2.empty()) {
            // Second reference image: bypass the single-path run() dispatcher.
            ok = handle->qwen->runEdit(toString(env, prompt), std::vector<std::string>{img1, img2},
                                       toString(env, outputPng), steps, seed, cb);
        } else {
            ok = handle->model->run(toString(env, prompt), toString(env, outputPng), steps, seed, 1.0f, cb, img1);
        }
        handle->errorCode = ok ? 0 : handle->qwen->lastErrorCode();
        handle->error = ok ? "" : handle->qwen->lastError();
        if (!ok && handle->errorCode == 0) {
            handle->errorCode = QwenImage21Diffusion::kRuntimeError;
            handle->error = "generation failed";
        }
    } catch (const GenerationCancelled&) {
        handle->errorCode = kCancelled;
        handle->error = "Generation cancelled";
    } catch (const JavaExceptionPending&) {
        handle->runState.store(kRunIdle, std::memory_order_relaxed);
        return QwenImage21Diffusion::kRuntimeError;
    } catch (const std::bad_alloc&) {
        handle->errorCode = QwenImage21Diffusion::kOutOfMemory;
        handle->error = "Out of memory (allocation failed)";
    } catch (const std::exception& e) {
        handle->errorCode = QwenImage21Diffusion::kRuntimeError;
        handle->error = std::string("Native error: ") + e.what();
    } catch (...) {
        handle->errorCode = QwenImage21Diffusion::kRuntimeError;
        handle->error = "Unknown native error";
    }
    if (handle->runState.exchange(kRunIdle, std::memory_order_relaxed) == kRunCancelling) {
        handle->errorCode = kCancelled;
        handle->error = "Generation cancelled";
    }
    LOGI("generate %s %s", handle->errorCode == 0 ? "ok" : (handle->errorCode == kCancelled ? "cancelled" : "failed"),
         handle->error.c_str());
    return handle->errorCode;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_scsonic_qwenimage21_QwenImage21_nativeLastError(JNIEnv* env, jclass, jlong ptr) {
    auto handle = reinterpret_cast<Handle*>(ptr);
    return fromUtf8(env, handle ? handle->error : std::string());
}

extern "C" JNIEXPORT jint JNICALL Java_com_scsonic_qwenimage21_QwenImage21_nativeAvailableMemoryMB(JNIEnv*, jclass) {
    return QwenImage21Diffusion::availableMemoryMB();
}

extern "C" JNIEXPORT void JNICALL Java_com_scsonic_qwenimage21_QwenImage21_nativeCancel(JNIEnv*, jclass, jlong ptr) {
    auto handle = reinterpret_cast<Handle*>(ptr);
    if (!handle) return;
    int expected = kRunActive;
    handle->runState.compare_exchange_strong(expected, kRunCancelling, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL Java_com_scsonic_qwenimage21_QwenImage21_nativeRelease(JNIEnv*, jclass, jlong ptr) {
    auto handle = reinterpret_cast<Handle*>(ptr);
    if (!handle) return;
    {
        std::lock_guard<std::mutex> lock(handle->mutex);
        handle->model.reset();
    }
    delete handle;
}
