#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

extern int omni_sd_cli_main(int argc, const char* argv[]);

namespace {

constexpr char kTag[] = "OmniSdJni";

struct PipeReaderArgs {
    JavaVM* vm = nullptr;
    jobject callbackGlobal = nullptr;
    jmethodID onBytesMethod = nullptr;
    int readFd = -1;
};

void* PipeReaderThread(void* raw) {
    auto* args = static_cast<PipeReaderArgs*>(raw);
    JNIEnv* env = nullptr;
    bool attached = false;
    if (args->vm != nullptr && args->callbackGlobal != nullptr && args->onBytesMethod != nullptr) {
        if (args->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (args->vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                env = nullptr;
            }
        }
    }

    char buf[4096];
    while (true) {
        ssize_t n = read(args->readFd, buf, sizeof(buf));
        if (n <= 0) break;
        if (env != nullptr && !env->ExceptionCheck()) {
            jbyteArray arr = env->NewByteArray(static_cast<jsize>(n));
            if (arr != nullptr) {
                env->SetByteArrayRegion(arr, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(buf));
                env->CallVoidMethod(args->callbackGlobal, args->onBytesMethod, arr);
                env->DeleteLocalRef(arr);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                }
            }
        }
    }

    close(args->readFd);
    if (attached && args->vm != nullptr) {
        args->vm->DetachCurrentThread();
    }
    return nullptr;
}

std::string JStringToStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return "";
    const char* ptr = env->GetStringUTFChars(s, nullptr);
    if (ptr == nullptr) return "";
    std::string out(ptr);
    env->ReleaseStringUTFChars(s, ptr);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_com_tiggy_qwenimage_engine_NativeEngineBridge_nativeRunSd(
    JNIEnv* env,
    jclass,
    jobjectArray argsArray,
    jstring openClCacheDir,
    jobject callback
) {
    if (argsArray == nullptr) return 2;

    const std::string cacheDir = JStringToStd(env, openClCacheDir);
    if (!cacheDir.empty()) {
        setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cacheDir.c_str(), 1);
    }

    // Lower scheduling priority in-process (replaces legacy /system/bin/nice subprocess wrapper).
    nice(10);

    const jsize count = env->GetArrayLength(argsArray);
    std::vector<std::string> storage;
    storage.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(argsArray, i));
        storage.push_back(JStringToStd(env, item));
        if (item != nullptr) env->DeleteLocalRef(item);
    }
    std::vector<const char*> argv;
    argv.reserve(storage.size() + 1);
    for (const auto& s : storage) argv.push_back(s.c_str());
    argv.push_back(nullptr);

    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);

    jobject callbackGlobal = nullptr;
    jmethodID onBytesMethod = nullptr;
    if (callback != nullptr) {
        jclass cbCls = env->GetObjectClass(callback);
        if (cbCls != nullptr) {
            onBytesMethod = env->GetMethodID(cbCls, "onBytes", "([B)V");
            env->DeleteLocalRef(cbCls);
        }
        if (onBytesMethod != nullptr) {
            callbackGlobal = env->NewGlobalRef(callback);
        }
    }

    int pipeFds[2] = {-1, -1};
    int savedOut = -1;
    int savedErr = -1;
    pthread_t readerThread{};
    bool readerStarted = false;
    PipeReaderArgs readerArgs{vm, callbackGlobal, onBytesMethod, -1};

    if (pipe(pipeFds) == 0) {
        fflush(stdout);
        fflush(stderr);
        savedOut = dup(STDOUT_FILENO);
        savedErr = dup(STDERR_FILENO);
        dup2(pipeFds[1], STDOUT_FILENO);
        dup2(pipeFds[1], STDERR_FILENO);
        close(pipeFds[1]);
        setvbuf(stdout, nullptr, _IONBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);

        readerArgs.readFd = pipeFds[0];
        if (pthread_create(&readerThread, nullptr, PipeReaderThread, &readerArgs) == 0) {
            readerStarted = true;
        } else {
            close(pipeFds[0]);
        }
    }

    int rc = 1;
    try {
        rc = omni_sd_cli_main(static_cast<int>(storage.size()), argv.data());
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "sd exception: %s", e.what());
        fprintf(stderr, "sd exception: %s\n", e.what());
        rc = 1;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "sd unknown exception");
        fprintf(stderr, "sd unknown exception\n");
        rc = 1;
    }

    fflush(stdout);
    fflush(stderr);
    if (savedOut >= 0) {
        dup2(savedOut, STDOUT_FILENO);
        close(savedOut);
    }
    if (savedErr >= 0) {
        dup2(savedErr, STDERR_FILENO);
        close(savedErr);
    }
    if (readerStarted) {
        pthread_join(readerThread, nullptr);
    }
    if (callbackGlobal != nullptr) {
        env->DeleteGlobalRef(callbackGlobal);
    }
    return static_cast<jint>(rc);
}

} // extern "C"
