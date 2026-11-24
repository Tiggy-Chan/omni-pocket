#include <jni.h>

#include <exception>
#include <new>
#include <string>
#include <vector>

#include "LlamaEmbeddingSession.h"
#include "jni_utf8.h"

namespace {

omni::LlamaEmbeddingSession* EmbSession(jlong handle) {
    return reinterpret_cast<omni::LlamaEmbeddingSession*>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaEmbeddingEngine_nativeCreate(
    JNIEnv* env, jclass) {
    try {
        auto* session = new (std::nothrow) omni::LlamaEmbeddingSession();
        if (session == nullptr) omni::jni::ThrowIllegalState(env, "Failed to allocate embedding session");
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception& e) {
        omni::jni::ThrowIllegalState(env, e.what());
        return 0;
    }
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaEmbeddingEngine_nativeLoad(
    JNIEnv* env, jclass, jlong handle, jstring modelPath, jint threads, jint contextTokens) {
    try {
        if (handle == 0) return omni::jni::Utf8ToJString(env, "Embedding session released");
        std::string error;
        const bool ok = EmbSession(handle)->Load(
            omni::jni::JStringToUtf8(env, modelPath), threads, contextTokens, error);
        if (env->ExceptionCheck()) return nullptr;
        return ok ? nullptr : omni::jni::Utf8ToJString(env, error);
    } catch (const std::exception& e) {
        return omni::jni::Utf8ToJString(env, e.what());
    }
}

JNIEXPORT jint JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaEmbeddingEngine_nativeDimension(
    JNIEnv*, jclass, jlong handle) {
    return handle != 0 ? EmbSession(handle)->EmbeddingDimension() : 0;
}

JNIEXPORT jfloatArray JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaEmbeddingEngine_nativeEmbed(
    JNIEnv* env, jclass, jlong handle, jstring text) {
    try {
        if (handle == 0) return nullptr;
        std::vector<float> vec;
        std::string error;
        const bool ok = EmbSession(handle)->Embed(omni::jni::JStringToUtf8(env, text), vec, error);
        if (env->ExceptionCheck() || !ok || vec.empty()) return nullptr;
        jfloatArray arr = env->NewFloatArray(static_cast<jsize>(vec.size()));
        if (arr == nullptr) return nullptr;
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(vec.size()), vec.data());
        return arr;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaEmbeddingEngine_nativeCancel(
    JNIEnv*, jclass, jlong handle) {
    if (handle != 0) EmbSession(handle)->Cancel();
}

JNIEXPORT void JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaEmbeddingEngine_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete EmbSession(handle);
}

} // extern "C"
