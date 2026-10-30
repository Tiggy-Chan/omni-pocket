#include <jni.h>

#include <iomanip>
#include <locale>
#include <new>
#include <sstream>
#include <string>
#include <vector>

#include "DecisionSession.h"

namespace {

omni::DecisionSession* Session(jlong handle) {
    return reinterpret_cast<omni::DecisionSession*>(handle);
}

std::string StringValue(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    const std::string result(chars, static_cast<size_t>(env->GetStringUTFLength(value)));
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void Throw(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
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
    auto* session = new (std::nothrow) omni::DecisionSession();
    if (session == nullptr) Throw(env, "无法分配决策会话");
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL Java_com_tiggy_qwenimage_engine_LocalDecisionEngine_nativeLoad(
    JNIEnv* env, jclass, jlong handle, jstring config, jstring cache, jint threads) {
    if (handle == 0) return env->NewStringUTF("决策会话已释放");
    std::string error;
    const bool loaded = Session(handle)->Load(StringValue(env, config), StringValue(env, cache), threads, error);
    if (env->ExceptionCheck()) return nullptr;
    return loaded ? nullptr : env->NewStringUTF(error.c_str());
}

JNIEXPORT jobjectArray JNICALL Java_com_tiggy_qwenimage_engine_LocalDecisionEngine_nativeSlotLabels(
    JNIEnv* env, jclass, jlong handle) {
    if (handle == 0) {
        Throw(env, "决策模型尚未加载");
        return nullptr;
    }
    const auto labels = Session(handle)->SlotLabels();
    const jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(labels.size()), stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (result == nullptr) return nullptr;
    for (size_t index = 0; index < labels.size(); ++index) {
        const jstring value = env->NewStringUTF(labels[index].c_str());
        if (value == nullptr) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}

JNIEXPORT jobject JNICALL Java_com_tiggy_qwenimage_engine_LocalDecisionEngine_nativeDecide(
    JNIEnv* env, jclass, jlong handle, jstring state, jobjectArray suffixes, jintArray counts, jobject callback) {
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
    const jmethodID constructor = env->GetMethodID(resultClass, "<init>",
        "(ZLjava/lang/String;Ljava/lang/String;)V");
    const jstring json = env->NewStringUTF(ResultJson(result).c_str());
    const jstring error = env->NewStringUTF(result.error.c_str());
    jobject value = env->NewObject(resultClass, constructor, result.cancelled ? JNI_TRUE : JNI_FALSE, json, error);
    env->DeleteLocalRef(json);
    env->DeleteLocalRef(error);
    env->DeleteLocalRef(resultClass);
    return value;
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
