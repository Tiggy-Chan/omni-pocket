#pragma once

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

namespace omni::jni {

inline std::string JStringToUtf8(JNIEnv* env, jstring value) {
    if (env == nullptr || value == nullptr) return {};
    const jsize len = env->GetStringLength(value);
    if (len <= 0) return {};
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};

    std::string out;
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = static_cast<uint32_t>(chars[i]);
        if (cp >= 0xD800u && cp <= 0xDBFFu) {
            if (i + 1 < len) {
                const uint32_t low = static_cast<uint32_t>(chars[i + 1]);
                if (low >= 0xDC00u && low <= 0xDFFFu) {
                    cp = 0x10000u + (((cp - 0xD800u) << 10) | (low - 0xDC00u));
                    ++i;
                } else {
                    cp = 0xFFFDu;
                }
            } else {
                cp = 0xFFFDu;
            }
        } else if (cp >= 0xDC00u && cp <= 0xDFFFu) {
            cp = 0xFFFDu;
        }

        if (cp <= 0x7Fu) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FFu) {
            out.push_back(static_cast<char>(0xC0u | (cp >> 6)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        } else if (cp <= 0xFFFFu) {
            out.push_back(static_cast<char>(0xE0u | (cp >> 12)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        } else {
            out.push_back(static_cast<char>(0xF0u | (cp >> 18)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

inline jstring Utf8ToJString(JNIEnv* env, const char* data, size_t size) {
    if (env == nullptr) return nullptr;
    if (data == nullptr || size == 0) {
        return env->NewString(nullptr, 0);
    }

    std::vector<jchar> utf16;
    utf16.reserve(size);
    const auto* bytes = reinterpret_cast<const uint8_t*>(data);
    size_t i = 0;
    while (i < size) {
        const uint8_t b0 = bytes[i];
        uint32_t cp = 0xFFFDu;
        if (b0 <= 0x7Fu) {
            cp = b0;
            i += 1;
        } else if ((b0 & 0xE0u) == 0xC0u && i + 1 < size) {
            const uint8_t b1 = bytes[i + 1];
            if ((b1 & 0xC0u) == 0x80u) {
                const uint32_t v = ((b0 & 0x1Fu) << 6) | (b1 & 0x3Fu);
                if (v >= 0x80u) {
                    cp = v;
                    i += 2;
                } else {
                    i += 1;
                }
            } else {
                i += 1;
            }
        } else if ((b0 & 0xF0u) == 0xE0u && i + 2 < size) {
            const uint8_t b1 = bytes[i + 1];
            const uint8_t b2 = bytes[i + 2];
            if ((b1 & 0xC0u) == 0x80u && (b2 & 0xC0u) == 0x80u) {
                const uint32_t v = ((b0 & 0x0Fu) << 12) | ((b1 & 0x3Fu) << 6) | (b2 & 0x3Fu);
                if (v >= 0x800u && !(v >= 0xD800u && v <= 0xDFFFu)) {
                    cp = v;
                    i += 3;
                } else {
                    i += 1;
                }
            } else {
                i += 1;
            }
        } else if ((b0 & 0xF8u) == 0xF0u && i + 3 < size) {
            const uint8_t b1 = bytes[i + 1];
            const uint8_t b2 = bytes[i + 2];
            const uint8_t b3 = bytes[i + 3];
            if ((b1 & 0xC0u) == 0x80u && (b2 & 0xC0u) == 0x80u && (b3 & 0xC0u) == 0x80u) {
                const uint32_t v = ((b0 & 0x07u) << 18) | ((b1 & 0x3Fu) << 12) |
                                   ((b2 & 0x3Fu) << 6) | (b3 & 0x3Fu);
                if (v >= 0x10000u && v <= 0x10FFFFu) {
                    cp = v;
                    i += 4;
                } else {
                    i += 1;
                }
            } else {
                i += 1;
            }
        } else {
            i += 1;
        }

        if (cp <= 0xFFFFu) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            const uint32_t adjusted = cp - 0x10000u;
            utf16.push_back(static_cast<jchar>(0xD800u + (adjusted >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00u + (adjusted & 0x3FFu)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

inline jstring Utf8ToJString(JNIEnv* env, const std::string& utf8) {
    return Utf8ToJString(env, utf8.data(), utf8.size());
}

inline void ThrowIllegalState(JNIEnv* env, const std::string& message) {
    if (env == nullptr || env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls == nullptr) return;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V");
    jstring jmsg = Utf8ToJString(env, message);
    if (ctor != nullptr && jmsg != nullptr) {
        jobject ex = env->NewObject(cls, ctor, jmsg);
        if (ex != nullptr) {
            env->Throw(static_cast<jthrowable>(ex));
            env->DeleteLocalRef(ex);
        }
    } else {
        env->ThrowNew(cls, "Illegal state");
    }
    if (jmsg != nullptr) env->DeleteLocalRef(jmsg);
    env->DeleteLocalRef(cls);
}

} // namespace omni::jni
