#include <jni.h>

#include <exception>
#include <iomanip>
#include <locale>
#include <new>
#include <sstream>
#include <string>
#include <vector>

#include "DecisionSession.h"
#include "jni_utf8.h"

namespace {

omni::DecisionSession* Session(jlong handle) {
    return reinterpret_cast<omni::DecisionSession*>(handle);
}

std::string StringValue(JNIEnv* env, jstring value) {
    return omni::jni::JStringToUtf8(env, value);
}

void Throw(JNIEnv* env, const std::string& message) {
    omni::jni::ThrowIllegalState(env, message);
}

std::string ResultJson(const MNN::Transformer::DecisionResult& result) {
    std::ostringstream json;
    json.imbue(std::locale::classic());
    json << "{\"prefixTokens\":" << result.prefixTokens
         << ",\"prefixPrefillUs\":" << result.prefixPrefillUs << ",\"branches\":[";
    for (size_t index = 0; index < result.branches.size(); ++index) {
        const auto& branch = result.branches[index];
        if (index != 0) json << ',';
        json << "{\"suffixTokens\":" << branch.suffixTokens << ",\"prefillUs\":" << branch.prefillUs
             << ",\"allowedMass\":" << std::setprecision(17) << branch.allowedMass << ",\"logits\":[";
        for (size_t item = 0; item < branch.logits.size(); ++item) {
            if (item != 0) json << ',';
            json << std::setprecision(9) << branch.logits[item];
        }
        json << "],\"probabilities\":[";
        for (size_t item = 0; item < branch.probabilities.size(); ++item) {
            if (item != 0) json << ',';
            json << std::setprecision(9) << branch.probabilities[item];
        }
        json << "]}";
    }
    json << "]}";
    return json.str();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_tiggy_qwenimage_engine_LocalDecisionEngine_nativeCreate(JNIEnv* env, jclass) {
    try {
        auto* session = new (std::nothrow) omni::DecisionSession();
        if (session == nullptr) Throw(env, "无法分配决策会话");
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception& e) {
        Throw(env, std::string("决策会话初始化异常：") + e.what());
        return 0;
    } catch (...) {
        Throw(env, "决策会话初始化发生未知异常");
        return 0;
    }
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalDecisionEngine_nativeLoad(
    JNIEnv* env, jclass, jlong handle, jstring config, jstring cache, jint threads) {
    try {
        if (handle == 0) return omni::jni::Utf8ToJString(env, "Decision session released");
        std::string error;
        const bool loaded = Session(handle)->Load(StringValue(env, config), StringValue(env, cache), threads, error);
        if (env->ExceptionCheck()) return nullptr;
        return loaded ? nullptr : omni::jni::Utf8ToJString(env, error);
    } catch (const std::exception& e) {
        return omni::jni::Utf8ToJString(env, std::string("Decision load error: ") + e.what());
    } catch (...) {
        return omni::jni::Utf8ToJString(env, "Decision load unknown error");
    }
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalDecisionEngine_nativeLoadGguf(
    JNIEnv* env, jclass, jlong handle, jstring ggufPath, jstring cache, jint threads, jboolean causalAttention) {
    try {
        if (handle == 0) return omni::jni::Utf8ToJString(env, "Decision session released");
        std::string error;
        const bool loaded = Session(handle)->LoadGguf(
            StringValue(env, ggufPath), StringValue(env, cache), threads, causalAttention == JNI_TRUE, error);
        if (env->ExceptionCheck()) return nullptr;
        return loaded ? nullptr : omni::jni::Utf8ToJString(env, error);
    } catch (const std::exception& e) {
        return omni::jni::Utf8ToJString(env, std::string("Decision GGUF load error: ") + e.what());
    } catch (...) {
        return omni::jni::Utf8ToJString(env, "Decision GGUF load unknown error");
    }
}

JNIEXPORT jobjectArray JNICALL Java_com_tiggy_qwenimage_engine_LocalDecisionEngine_nativeSlotLabels(
    JNIEnv* env, jclass, jlong handle) {
    try {
        if (handle == 0) {
            Throw(env, "决策模型尚未加载");
            return nullptr;
        }
        const auto labels = Session(handle)->SlotLabels();
        const jclass stringClass = env->FindClass("java/lang/String");
        if (stringClass == nullptr) return nullptr;
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(labels.size()), stringClass, nullptr);
        env->DeleteLocalRef(stringClass);
        if (result == nullptr) return nullptr;
        for (size_t index = 0; index < labels.size(); ++index) {
            const jstring value = omni::jni::Utf8ToJString(env, labels[index]);
            if (value == nullptr) return nullptr;
            env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) return nullptr;
        }
        return result;
    } catch (const std::exception& e) {
        Throw(env, std::string("读取决策标签异常：") + e.what());
        return nullptr;
    } catch (...) {
        Throw(env, "读取决策标签发生未知异常");
        return nullptr;
    }
}

JNIEXPORT jobject JNICALL Java_com_tiggy_qwenimage_engine_LocalDecisionEngine_nativeDecide(
    JNIEnv* env, jclass, jlong handle, jstring state, jobjectArray suffixes, jintArray counts, jobject callback) {
    try {
        if (handle == 0 || suffixes == nullptr || counts == nullptr ||
            env->GetArrayLength(suffixes) != env->GetArrayLength(counts)) {
            Throw(env, "决策请求参数无效");
            return nullptr;
        }
        std::vector<MNN::Transformer::DecisionQuestion> questions;
        const jsize count = env->GetArrayLength(suffixes);
        jint* optionCounts = env->GetIntArrayElements(counts, nullptr);
        if (optionCounts == nullptr) return nullptr;
        for (jsize index = 0; index < count; ++index) {
            auto suffix = static_cast<jstring>(env->GetObjectArrayElement(suffixes, index));
            if (suffix == nullptr) {
                env->ReleaseIntArrayElements(counts, optionCounts, JNI_ABORT);
                Throw(env, "决策问题不能为空");
                return nullptr;
            }
            questions.push_back({StringValue(env, suffix), optionCounts[index]});
            env->DeleteLocalRef(suffix);
            if (env->ExceptionCheck()) {
                env->ReleaseIntArrayElements(counts, optionCounts, JNI_ABORT);
                return nullptr;
            }
        }
        env->ReleaseIntArrayElements(counts, optionCounts, JNI_ABORT);
        std::function<bool(int, int)> progress;
        jmethodID onProgress = nullptr;
        if (callback != nullptr) {
            const jclass callbackClass = env->GetObjectClass(callback);
            if (callbackClass == nullptr) return nullptr;
            onProgress = env->GetMethodID(callbackClass, "onProgress", "(II)Z");
            env->DeleteLocalRef(callbackClass);
            if (onProgress == nullptr) return nullptr;
            progress = [&](int completed, int total) {
                if (env->ExceptionCheck()) return false;
                return env->CallBooleanMethod(callback, onProgress, completed, total) == JNI_TRUE &&
                    !env->ExceptionCheck();
            };
        }
        const auto result = Session(handle)->Decide(StringValue(env, state), questions, progress);
        if (env->ExceptionCheck()) return nullptr;
        const jclass resultClass = env->FindClass("com/tiggy/qwenimage/engine/NativeDecisionResult");
        if (resultClass == nullptr) return nullptr;
        const jmethodID constructor = env->GetMethodID(resultClass, "<init>",
            "(ZLjava/lang/String;Ljava/lang/String;)V");
        if (constructor == nullptr) {
            env->DeleteLocalRef(resultClass);
            return nullptr;
        }
        const jstring json = omni::jni::Utf8ToJString(env, ResultJson(result));
        const jstring error = omni::jni::Utf8ToJString(env, result.error);
        jobject value = (json != nullptr && error != nullptr)
            ? env->NewObject(resultClass, constructor, result.cancelled ? JNI_TRUE : JNI_FALSE, json, error)
            : nullptr;
        if (json != nullptr) env->DeleteLocalRef(json);
        if (error != nullptr) env->DeleteLocalRef(error);
        env->DeleteLocalRef(resultClass);
        return value;
    } catch (const std::exception& e) {
        Throw(env, std::string("决策推理异常：") + e.what());
        return nullptr;
    } catch (...) {
        Throw(env, "决策推理发生未知异常");
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_tiggy_qwenimage_engine_LocalDecisionEngine_nativeCancel(
    JNIEnv*, jclass, jlong handle) {
    if (handle != 0) Session(handle)->Cancel();
}

JNIEXPORT void JNICALL Java_com_tiggy_qwenimage_engine_LocalDecisionEngine_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete Session(handle);
}

} // extern "C"
