#include <jni.h>

#include <memory>
#include <string>

#include "LocalLlmSession.h"

namespace {

omni::LocalLlmSession* Session(jlong handle) {
    return reinterpret_cast<omni::LocalLlmSession*>(handle);
}

std::string StringValue(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    const std::string result(chars, static_cast<size_t>(env->GetStringUTFLength(value)));
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool Throw(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
    return false;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_tiggy_qwenimage_engine_LocalLlmEngine_nativeCreate(JNIEnv* env, jclass) {
    auto* session = new (std::nothrow) omni::LocalLlmSession();
    if (session == nullptr) Throw(env, "无法分配 LLM 会话");
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalLlmEngine_nativeLoad(
    JNIEnv* env, jclass, jlong handle, jstring config, jstring cache, jint threads, jint contextTokens) {
    if (handle == 0) return env->NewStringUTF("LLM 会话已释放");
    std::string error;
    const bool loaded = Session(handle)->Load(StringValue(env, config), StringValue(env, cache),
                                              threads, contextTokens, error);
    if (env->ExceptionCheck()) return nullptr;
    return loaded ? nullptr : env->NewStringUTF(error.c_str());
}

JNIEXPORT jobject JNICALL Java_com_tiggy_qwenimage_engine_LocalLlmEngine_nativeGenerate(
    JNIEnv* env, jclass, jlong handle, jstring conversationId, jobjectArray roles, jobjectArray contents,
    jint maxNewTokens, jboolean thinking, jobject callback) {
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
    const jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(callbackClass);
    if (onToken == nullptr) return nullptr;
    const auto result = Session(handle)->Generate(
        omni::LlmRequest{StringValue(env, conversationId), messages, maxNewTokens, thinking == JNI_TRUE},
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
    const jmethodID constructor = env->GetMethodID(resultClass, "<init>",
        "(ILjava/lang/String;Ljava/lang/String;IIIJJ)V");
    const jstring text = env->NewStringUTF(result.text.c_str());
    const jstring error = env->NewStringUTF(result.error.c_str());
    jobject value = env->NewObject(resultClass, constructor, static_cast<jint>(result.finish), text, error,
        result.promptTokens, result.generatedTokens, result.droppedMessages,
        static_cast<jlong>(result.prefillUs), static_cast<jlong>(result.decodeUs));
    env->DeleteLocalRef(text);
    env->DeleteLocalRef(error);
    env->DeleteLocalRef(resultClass);
    return value;
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
