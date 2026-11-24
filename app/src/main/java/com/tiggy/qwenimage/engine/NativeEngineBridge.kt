package com.tiggy.qwenimage.engine

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Callback invoked by native JNI bridges (`libsd_jni.so`, `libyue2_jni.so`) with raw stdout/stderr
 * byte chunks captured from the native engine inside the `:engine` worker process.
 */
fun interface LogByteCallback {
    fun onBytes(chunk: ByteArray)
}

/**
 * Direct JNI bridge to `libsd_jni.so` (stable-diffusion.cpp) and `libyue2_jni.so` (audio.cpp).
 * Must be invoked only from `InferenceWorkerService` (`android:process=":engine"`) so large
 * native allocations and mid-run cancellations remain strictly isolated from the UI process.
 */
object NativeEngineBridge {
    const val SD_LIBRARY_NAME = "sd_jni"
    const val SD_LIBRARY_FILE = "libsd_jni.so"
    const val YUE2_LIBRARY_NAME = "yue2_jni"
    const val YUE2_LIBRARY_FILE = "libyue2_jni.so"

    private val packagedCache = ConcurrentHashMap<String, Boolean>()
    private val sdLoadLock = Any()
    private val yue2LoadLock = Any()

    @Volatile private var sdLoaded = false
    @Volatile private var yue2Loaded = false

    fun isSdEngineAvailable(context: Context): Boolean = isLibraryPackaged(context, SD_LIBRARY_FILE)

    fun isYue2EngineAvailable(context: Context): Boolean = isLibraryPackaged(context, YUE2_LIBRARY_FILE)

    /**
     * Checks whether a native library is installed. Supports both extracted `nativeLibraryDir`
     * layouts and modern `extractNativeLibs=false` APKs where `.so` files are mmap'd directly
     * from `base.apk` or split APKs.
     */
    fun isLibraryPackaged(context: Context, libFileName: String): Boolean {
        packagedCache[libFileName]?.let { return it }
        val appInfo = context.applicationInfo
        val extracted = appInfo.nativeLibraryDir?.let { File(it, libFileName).isFile } == true
        val present = if (extracted) {
            true
        } else {
            val apks = buildList {
                appInfo.sourceDir?.let(::add)
                appInfo.splitSourceDirs?.let(::addAll)
            }
            apks.any { apkPath ->
                runCatching {
                    ZipFile(apkPath).use { zip ->
                        zip.getEntry("lib/arm64-v8a/$libFileName") != null
                    }
                }.getOrDefault(false)
            }
        }
        packagedCache[libFileName] = present
        return present
    }

    fun runSd(
        args: Array<String>,
        openClCacheDir: String,
        callback: LogByteCallback
    ): Int {
        ensureSdLoaded()
        return nativeRunSd(args, openClCacheDir, callback)
    }

    fun runYue2(
        args: Array<String>,
        workDir: String,
        callback: LogByteCallback
    ): Int {
        ensureYue2Loaded()
        return nativeRunYue2(args, workDir, callback)
    }

    private fun ensureSdLoaded() {
        if (sdLoaded) return
        synchronized(sdLoadLock) {
            if (!sdLoaded) {
                System.loadLibrary(SD_LIBRARY_NAME)
                sdLoaded = true
            }
        }
    }

    private fun ensureYue2Loaded() {
        if (yue2Loaded) return
        synchronized(yue2LoadLock) {
            if (!yue2Loaded) {
                System.loadLibrary(YUE2_LIBRARY_NAME)
                yue2Loaded = true
            }
        }
    }

    @JvmStatic
    private external fun nativeRunSd(
        args: Array<String>,
        openClCacheDir: String,
        callback: LogByteCallback
    ): Int

    @JvmStatic
    private external fun nativeRunYue2(
        args: Array<String>,
        workDir: String,
        callback: LogByteCallback
    ): Int
}
