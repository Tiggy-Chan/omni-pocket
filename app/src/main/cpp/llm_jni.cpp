#include <jni.h>

#include <exception>
#include <memory>
#include <new>
#include <string>

#include "LocalLlmSession.h"
#include "jni_utf8.h"

namespace {

omni::LocalLlmSession* Session(jlong handle) {
    return reinterpret_cast<omni::LocalLlmSession*>(handle);
}

std::string StringValue(JNIEnv* env, jstring value) {
    return omni::jni::JStringToUtf8(env, value);
}

bool Throw(JNIEnv* env, const std::string& message) {
    omni::jni::ThrowIllegalState(env, message);
    return false;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_tiggy_qwenimage_engine_LocalLlmEngine_nativeCreate(JNIEnv* env, jclass) {
    try {
        auto* session = new (std::nothrow) omni::LocalLlmSession();
        if (session == nullptr) Throw(env, "无法分配 LLM 会话");
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception& e) {
        Throw(env, std::string("LLM 初始化异常：") + e.what());
        return 0;
    } catch (...) {
        Throw(env, "LLM 初始化发生未知异常");
        return 0;
    }
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalLlmEngine_nativeLoad(
    JNIEnv* env, jclass, jlong handle, jstring config, jstring cache, jint threads, jint contextTokens, jboolean useOpenCl) {
    try {
        if (handle == 0) return omni::jni::Utf8ToJString(env, "LLM 会话已释放");
        std::string error;
        const bool loaded = Session(handle)->Load(StringValue(env, config), StringValue(env, cache),
                                                  threads, contextTokens, error, useOpenCl == JNI_TRUE);
        if (env->ExceptionCheck()) return nullptr;
        return loaded ? nullptr : omni::jni::Utf8ToJString(env, error);
    } catch (const std::exception& e) {
        return omni::jni::Utf8ToJString(env, std::string("LLM 加载异常：") + e.what());
    } catch (...) {
        return omni::jni::Utf8ToJString(env, "LLM 加载发生未知异常");
    }
}

JNIEXPORT jobject JNICALL Java_com_tiggy_qwenimage_engine_LocalLlmEngine_nativeGenerate(
    JNIEnv* env, jclass, jlong handle, jstring conversationId, jobjectArray roles, jobjectArray contents,
    jint maxNewTokens, jboolean thinking, jfloat temperature, jfloat topP, jfloat repeatPenalty, jobject callback) {
    try {
        if (handle == 0) {
            Throw(env, "模型尚未加载");
            return nullptr;
        }
        if (roles == nullptr || contents == nullptr || callback == nullptr ||
            env->GetArrayLength(roles) != env->GetArrayLength(contents)) {
            Throw(env, "LLM 对话参数无效");
            return nullptr;
        }
        omni::ChatMessages messages;
        const jsize count = env->GetArrayLength(roles);
        for (jsize i = 0; i < count; ++i) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
            if (role == nullptr || content == nullptr) {
                if (role != nullptr) env->DeleteLocalRef(role);
                if (content != nullptr) env->DeleteLocalRef(content);
                Throw(env, "对话消息不能为空");
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
        const auto result = Session(handle)->Generate(
            omni::LlmRequest{
                StringValue(env, conversationId),
                messages,
                maxNewTokens,
                thinking == JNI_TRUE,
                temperature,
                topP,
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
        const jmethodID constructor = env->GetMethodID(resultClass, "<init>",
            "(ILjava/lang/String;Ljava/lang/String;IIIJJ)V");
        if (constructor == nullptr) {
            env->DeleteLocalRef(resultClass);
            return nullptr;
        }
        const jstring text = omni::jni::Utf8ToJString(env, result.text);
        const jstring error = omni::jni::Utf8ToJString(env, result.error);
        jobject value = (text != nullptr && error != nullptr)
            ? env->NewObject(resultClass, constructor, static_cast<jint>(result.finish), text, error,
                result.promptTokens, result.generatedTokens, result.droppedMessages,
                static_cast<jlong>(result.prefillUs), static_cast<jlong>(result.decodeUs))
            : nullptr;
        if (text != nullptr) env->DeleteLocalRef(text);
        if (error != nullptr) env->DeleteLocalRef(error);
        env->DeleteLocalRef(resultClass);
        return value;
    } catch (const std::exception& e) {
        Throw(env, std::string("LLM 生成异常：") + e.what());
        return nullptr;
    } catch (...) {
        Throw(env, "LLM 生成发生未知异常");
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_tiggy_qwenimage_engine_LocalLlmEngine_nativeCancel(
    JNIEnv*, jclass, jlong handle) {
    if (handle != 0) Session(handle)->Cancel();
}

JNIEXPORT void JNICALL Java_com_tiggy_qwenimage_engine_LocalLlmEngine_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete Session(handle);
}

} // extern "C"
