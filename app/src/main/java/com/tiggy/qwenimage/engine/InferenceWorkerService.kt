package com.tiggy.qwenimage.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Isolated `android:process=":engine"` worker service hosting JNI diffusion and audio inference
 * (`libsd_jni.so` and `libyue2_jni.so`). Running inside a dedicated Android Service process gives
 * full OOM/crash isolation and instant native cancellation without `ProcessBuilder` or executable
 * binaries in `jniLibs`.
 */
class InferenceWorkerService : Service() {
    private lateinit var ipcThread: HandlerThread
    private lateinit var ipcHandler: Handler
    private lateinit var messenger: Messenger
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jobRunning = AtomicBoolean(false)

    private val shutdownRunnable = Runnable {
        if (!jobRunning.get()) {
            stopSelf()
            Process.killProcess(Process.myPid())
        }
    }

    override fun onCreate() {
        super.onCreate()
        mainHandler.removeCallbacks(shutdownRunnable)
        ipcThread = HandlerThread("omni-engine-ipc").apply { start() }
        ipcHandler = object : Handler(ipcThread.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    InferenceWorkerProtocol.MSG_RUN -> handleRun(msg)
                    InferenceWorkerProtocol.MSG_CANCEL -> {
                        Log.i(TAG, "Cancel requested; terminating :engine process cleanly")
                        Process.killProcess(Process.myPid())
                    }
                }
            }
        }
        messenger = Messenger(ipcHandler)
    }

    override fun onBind(intent: Intent?): IBinder {
        mainHandler.removeCallbacks(shutdownRunnable)
        return messenger.binder
    }

    override fun onRebind(intent: Intent?) {
        mainHandler.removeCallbacks(shutdownRunnable)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Reclaim all native mmap / OpenCL / Vulkan memory once the client disconnects so each
        // subsequent inference job starts with a clean native heap and backend state.
        mainHandler.removeCallbacks(shutdownRunnable)
        mainHandler.postDelayed(shutdownRunnable, IDLE_SHUTDOWN_DELAY_MS)
        return true
    }

    override fun onDestroy() {
        if (::ipcThread.isInitialized) {
            ipcThread.quitSafely()
        }
        mainHandler.removeCallbacks(shutdownRunnable)
        mainHandler.postDelayed(shutdownRunnable, IDLE_SHUTDOWN_DELAY_MS)
        super.onDestroy()
    }

    private fun handleRun(msg: Message) {
        mainHandler.removeCallbacks(shutdownRunnable)
        val replyTo = msg.replyTo ?: return
        val data = msg.data ?: Bundle.EMPTY
        val jobId = data.getString(InferenceWorkerProtocol.KEY_JOB_ID).orEmpty().ifBlank { "job" }
        val engine = data.getString(InferenceWorkerProtocol.KEY_ENGINE).orEmpty()
        val args = data.getStringArrayList(InferenceWorkerProtocol.KEY_ARGS)?.toTypedArray()
        val openClCacheDir = data.getString(InferenceWorkerProtocol.KEY_OPENCL_CACHE_DIR).orEmpty()
        val workDir = data.getString(InferenceWorkerProtocol.KEY_WORK_DIR).orEmpty()

        if (args == null || args.isEmpty()) {
            sendFailure(replyTo, "缺少推理参数")
            return
        }
        if (!jobRunning.compareAndSet(false, true)) {
            sendFailure(replyTo, "已有本机推理任务正在执行")
            return
        }

        Thread({
            try {
                val callback = LogByteCallback { chunk ->
                    if (chunk.isEmpty()) return@LogByteCallback
                    val outMsg = Message.obtain(null, InferenceWorkerProtocol.MSG_OUTPUT).apply {
                        this.data = Bundle(1).apply {
                            putByteArray(InferenceWorkerProtocol.KEY_CHUNK, chunk)
                        }
                    }
                    runCatching { replyTo.send(outMsg) }
                }
                val exitCode = when (engine) {
                    InferenceWorkerProtocol.ENGINE_SD ->
                        NativeEngineBridge.runSd(args, openClCacheDir, callback)
                    InferenceWorkerProtocol.ENGINE_YUE2 ->
                        NativeEngineBridge.runYue2(args, workDir, callback)
                    else -> throw IllegalArgumentException("未知推理引擎：$engine")
                }
                val doneMsg = Message.obtain(null, InferenceWorkerProtocol.MSG_COMPLETED).apply {
                    this.data = Bundle(1).apply {
                        putInt(InferenceWorkerProtocol.KEY_EXIT_CODE, exitCode)
                    }
                }
                runCatching { replyTo.send(doneMsg) }
            } catch (error: Throwable) {
                Log.e(TAG, "Inference failed in :engine ($engine)", error)
                sendFailure(replyTo, error.message ?: error.javaClass.simpleName)
            } finally {
                jobRunning.set(false)
            }
        }, "omni-engine-$jobId").start()
    }

    private fun sendFailure(replyTo: Messenger, detail: String) {
        val failMsg = Message.obtain(null, InferenceWorkerProtocol.MSG_FAILED).apply {
            data = Bundle(1).apply {
                putString(InferenceWorkerProtocol.KEY_ERROR, detail)
            }
        }
        runCatching { replyTo.send(failMsg) }
    }

    private companion object {
        const val TAG = "OmniEngineWorker"
        const val IDLE_SHUTDOWN_DELAY_MS = 250L
    }
}
