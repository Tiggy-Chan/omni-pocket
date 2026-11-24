#include <jni.h>

#include <exception>
#include <new>
#include <string>

#include "LlamaChatSession.h"
#include "jni_utf8.h"

namespace {

omni::LlamaChatSession* ChatSession(jlong handle) {
    return reinterpret_cast<omni::LlamaChatSession*>(handle);
}

std::string StringValue(JNIEnv* env, jstring value) {
    return omni::jni::JStringToUtf8(env, value);
}

void ThrowIllegalState(JNIEnv* env, const std::string& message) {
    omni::jni::ThrowIllegalState(env, message);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeCreate(JNIEnv* env, jclass) {
    try {
        auto* session = new (std::nothrow) omni::LlamaChatSession();
        if (session == nullptr) ThrowIllegalState(env, "无法分配 GGUF 对话会话");
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception& e) {
        ThrowIllegalState(env, std::string("GGUF 初始化异常：") + e.what());
        return 0;
    } catch (...) {
        ThrowIllegalState(env, "GGUF 初始化发生未知异常");
        return 0;
    }
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeLoad(
    JNIEnv* env, jclass, jlong handle, jstring modelPath, jint threads, jint contextTokens,
    jint kvQuantMode, jint backendPref) {
    try {
        if (handle == 0) return omni::jni::Utf8ToJString(env, "GGUF 对话会话已释放");
        std::string error;
        const bool loaded = ChatSession(handle)->Load(
            StringValue(env, modelPath), threads, contextTokens, error, kvQuantMode, backendPref);
        if (env->ExceptionCheck()) return nullptr;
        return loaded ? nullptr : omni::jni::Utf8ToJString(env, error);
    } catch (const std::exception& e) {
        return omni::jni::Utf8ToJString(env, std::string("GGUF 加载异常：") + e.what());
    } catch (...) {
        return omni::jni::Utf8ToJString(env, "GGUF 加载发生未知异常");
    }
}

JNIEXPORT jint JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeEffectiveContext(
    JNIEnv*, jclass, jlong handle) {
    return handle != 0 ? ChatSession(handle)->EffectiveContextTokens() : 0;
}

JNIEXPORT jint JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeTrainContext(
    JNIEnv*, jclass, jlong handle) {
    return handle != 0 ? ChatSession(handle)->ModelTrainContextTokens() : 0;
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeActiveBackendLabel(
    JNIEnv* env, jclass, jlong handle) {
    try {
        const std::string label = handle != 0 ? ChatSession(handle)->ActiveBackendLabel() : "";
        return omni::jni::Utf8ToJString(env, label);
    } catch (...) {
        return omni::jni::Utf8ToJString(env, "");
    }
}

JNIEXPORT jobject JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeGenerate(
    JNIEnv* env, jclass, jlong handle, jstring conversationId, jobjectArray roles, jobjectArray contents,
    jint maxNewTokens, jboolean thinking, jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty,
    jobject callback) {
    try {
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
        if (callbackClass == nullptr) return nullptr;
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
                const jstring value = omni::jni::Utf8ToJString(env, token);
                if (value == nullptr) return false;
                const jboolean keepGoing = env->CallBooleanMethod(callback, onToken, value);
                env->DeleteLocalRef(value);
                return keepGoing == JNI_TRUE && !env->ExceptionCheck();
            });
        if (env->ExceptionCheck()) return nullptr;
        const jclass resultClass = env->FindClass("com/tiggy/qwenimage/engine/NativeLlmResult");
        if (resultClass == nullptr) return nullptr;
        const jmethodID constructor = env->GetMethodID(
            resultClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;IIIJJ)V");
        if (constructor == nullptr) {
            env->DeleteLocalRef(resultClass);
            return nullptr;
        }
        const jstring text = omni::jni::Utf8ToJString(env, result.text);
        const jstring error = omni::jni::Utf8ToJString(env, result.error);
        jobject value = (text != nullptr && error != nullptr)
            ? env->NewObject(
                resultClass, constructor, static_cast<jint>(result.finish), text, error,
                result.promptTokens, result.generatedTokens, result.droppedMessages,
                static_cast<jlong>(result.prefillUs), static_cast<jlong>(result.decodeUs))
            : nullptr;
        if (text != nullptr) env->DeleteLocalRef(text);
        if (error != nullptr) env->DeleteLocalRef(error);
        env->DeleteLocalRef(resultClass);
        return value;
    } catch (const std::exception& e) {
        ThrowIllegalState(env, std::string("GGUF 生成异常：") + e.what());
        return nullptr;
    } catch (...) {
        ThrowIllegalState(env, "GGUF 生成发生未知异常");
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeSetCacheDir(
    JNIEnv* env, jclass, jstring cacheDir) {
    try {
        omni::LlamaChatSession::SetKernelCacheDir(StringValue(env, cacheDir));
    } catch (...) {
    }
}

JNIEXPORT jobject JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeProbeBackends(
    JNIEnv* env, jclass) {
    try {
        const auto caps = omni::LlamaChatSession::ProbeBackends();
        const jclass cls = env->FindClass("com/tiggy/qwenimage/engine/GgufBackendCapabilities");
        if (cls == nullptr) return nullptr;
        const jmethodID ctor = env->GetMethodID(
            cls, "<init>", "(ZZLjava/lang/String;ZLjava/lang/String;ZZZI)V");
        if (ctor == nullptr) {
            env->DeleteLocalRef(cls);
            return nullptr;
        }
        const jstring vkName = omni::jni::Utf8ToJString(env, caps.vulkanName);
        const jstring clName = omni::jni::Utf8ToJString(env, caps.openClName);
        jobject obj = (vkName != nullptr && clName != nullptr)
            ? env->NewObject(
                cls, ctor,
                caps.hasKleidiAi ? JNI_TRUE : JNI_FALSE,
                caps.hasVulkan ? JNI_TRUE : JNI_FALSE,
                vkName,
                caps.hasOpenCl ? JNI_TRUE : JNI_FALSE,
                clName,
                caps.hasHexagonCompiled ? JNI_TRUE : JNI_FALSE,
                caps.hasHexagonRuntime ? JNI_TRUE : JNI_FALSE,
                caps.hasHexagonFastRpc ? JNI_TRUE : JNI_FALSE,
                static_cast<jint>(caps.recommendedThreads))
            : nullptr;
        if (vkName != nullptr) env->DeleteLocalRef(vkName);
        if (clName != nullptr) env->DeleteLocalRef(clName);
        env->DeleteLocalRef(cls);
        return obj;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jboolean JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeHasGpuBackend(
    JNIEnv*, jclass) {
    try {
        return omni::LlamaChatSession::HasRuntimeGpuDevice() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalLlamaChatEngine_nativeGpuDeviceName(
    JNIEnv* env, jclass) {
    try {
        const std::string name = omni::LlamaChatSession::RuntimeGpuDeviceName();
        return omni::jni::Utf8ToJString(env, name);
    } catch (...) {
        return omni::jni::Utf8ToJString(env, "");
    }
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
