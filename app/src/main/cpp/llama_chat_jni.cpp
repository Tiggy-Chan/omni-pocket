#include <jni.h>

#include <new>
#include <string>

#include "LlamaChatSession.h"

namespace {

omni::LlamaChatSession* ChatSession(jlong handle) {
    return reinterpret_cast<omni::LlamaChatSession*>(handle);
}

std::string StringValue(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    const std::string result(chars, static_cast<size_t>(env->GetStringUTFLength(value)));
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void ThrowIllegalState(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeCreate(JNIEnv* env, jclass) {
    auto* session = new (std::nothrow) omni::LlamaChatSession();
    if (session == nullptr) ThrowIllegalState(env, "无法分配 GGUF 对话会话");
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeLoad(
    JNIEnv* env, jclass, jlong handle, jstring modelPath, jint threads, jint contextTokens,
    jint kvQuantMode, jint gpuLayers) {
    if (handle == 0) return env->NewStringUTF("GGUF 对话会话已释放");
    std::string error;
    const bool loaded = ChatSession(handle)->Load(
        StringValue(env, modelPath), threads, contextTokens, error, kvQuantMode, gpuLayers);
    if (env->ExceptionCheck()) return nullptr;
    return loaded ? nullptr : env->NewStringUTF(error.c_str());
}

JNIEXPORT jint JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeEffectiveContext(
    JNIEnv*, jclass, jlong handle) {
    return handle != 0 ? ChatSession(handle)->EffectiveContextTokens() : 0;
}

JNIEXPORT jint JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeTrainContext(
    JNIEnv*, jclass, jlong handle) {
    return handle != 0 ? ChatSession(handle)->ModelTrainContextTokens() : 0;
}

JNIEXPORT jobject JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeGenerate(
    JNIEnv* env, jclass, jlong handle, jstring conversationId, jobjectArray roles, jobjectArray contents,
    jint maxNewTokens, jboolean thinking, jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty,
    jobject callback) {
    if (handle == 0) {
        ThrowIllegalState(env, "GGUF 模型尚未加载");
        return nullptr;
    }
    if (roles == nullptr || contents == nullptr || callback == nullptr ||
        env->GetArrayLength(roles) != env->GetArrayLength(contents)) {
        ThrowIllegalState(env, "对话参数无效");
        return nullptr;
    }
    omni::LlamaChatMessages messages;
    const jsize count = env->GetArrayLength(roles);
    messages.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        if (role == nullptr || content == nullptr) {
            if (role != nullptr) env->DeleteLocalRef(role);
            if (content != nullptr) env->DeleteLocalRef(content);
            ThrowIllegalState(env, "对话消息不能为空");
            return nullptr;
        }
        messages.emplace_back(StringValue(env, role), StringValue(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        if (env->ExceptionCheck()) return nullptr;
    }
    const jclass callbackClass = env->GetObjectClass(callback);
    const jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(callbackClass);
    if (onToken == nullptr) return nullptr;

    const auto result = ChatSession(handle)->Generate(
        omni::LlamaChatRequest{
            StringValue(env, conversationId),
            std::move(messages),
            maxNewTokens,
            thinking == JNI_TRUE,
            temperature,
            topP,
            topK,
            repeatPenalty
        },
        [&](const std::string& token) -> bool {
            if (env->ExceptionCheck()) return false;
            const jstring value = env->NewStringUTF(token.c_str());
            if (value == nullptr) return false;
            const jboolean keepGoing = env->CallBooleanMethod(callback, onToken, value);
            env->DeleteLocalRef(value);
            return keepGoing == JNI_TRUE && !env->ExceptionCheck();
        });
    if (env->ExceptionCheck()) return nullptr;
    const jclass resultClass = env->FindClass("com/tiggy/qwenimage/engine/NativeLlmResult");
    const jmethodID constructor = env->GetMethodID(
        resultClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;IIIJJ)V");
    const jstring text = env->NewStringUTF(result.text.c_str());
    const jstring error = env->NewStringUTF(result.error.c_str());
    jobject value = env->NewObject(
        resultClass, constructor, static_cast<jint>(result.finish), text, error,
        result.promptTokens, result.generatedTokens, result.droppedMessages,
        static_cast<jlong>(result.prefillUs), static_cast<jlong>(result.decodeUs));
    env->DeleteLocalRef(text);
    env->DeleteLocalRef(error);
    env->DeleteLocalRef(resultClass);
    return value;
}

JNIEXPORT void JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeSetCacheDir(
    JNIEnv* env, jclass, jstring cacheDir) {
    omni::LlamaChatSession::SetKernelCacheDir(StringValue(env, cacheDir));
}

JNIEXPORT jboolean JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeHasGpuBackend(
    JNIEnv*, jclass) {
    return omni::LlamaChatSession::HasRuntimeGpuDevice() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeGpuDeviceName(
    JNIEnv* env, jclass) {
    const std::string name = omni::LlamaChatSession::RuntimeGpuDeviceName();
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT void JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeCancel(
    JNIEnv*, jclass, jlong handle) {
    if (handle != 0) ChatSession(handle)->Cancel();
}

JNIEXPORT void JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete ChatSession(handle);
}

} // extern "C"
