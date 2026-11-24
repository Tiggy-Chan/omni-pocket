package com.tiggy.qwenimage.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException

/**
 * Coroutine-friendly client for running a single native inference job inside [InferenceWorkerService]
 * (`android:process=":engine"`). Streams UTF-8 decoded log output in real time and supports clean
 * cancellation that tears down the isolated `:engine` process without disturbing the UI process.
 */
internal class InferenceWorkerClient(
    private val context: Context
) {
    private val appContext = context.applicationContext

    @Volatile private var serviceMessenger: Messenger? = null
    @Volatile private var cancelled = false
    @Volatile private var activeCompletion: CompletableDeferred<Int>? = null

    suspend fun run(
        jobId: String,
        engine: String,
        args: List<String>,
        openClCacheDir: String = "",
        workDir: String = "",
        onOutputChunk: (String) -> Unit
    ): Int {
        cancelled = false
        val completion = CompletableDeferred<Int>()
        activeCompletion = completion
        val decoder = Utf8StreamDecoder()
        val replyThread = HandlerThread("omni-ipc-$jobId").apply { start() }
        val replyHandler = object : Handler(replyThread.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    InferenceWorkerProtocol.MSG_OUTPUT -> {
                        val bytes = msg.data?.getByteArray(InferenceWorkerProtocol.KEY_CHUNK) ?: return
                        val text = decoder.decode(bytes)
                        if (text.isNotEmpty()) {
                            runCatching { onOutputChunk(text) }
                        }
                    }
                    InferenceWorkerProtocol.MSG_COMPLETED -> {
                        val tail = decoder.finish()
                        if (tail.isNotEmpty()) {
                            runCatching { onOutputChunk(tail) }
                        }
                        val exitCode = msg.data?.getInt(InferenceWorkerProtocol.KEY_EXIT_CODE, 1) ?: 1
                        completion.complete(exitCode)
                    }
                    InferenceWorkerProtocol.MSG_FAILED -> {
                        val tail = decoder.finish()
                        if (tail.isNotEmpty()) {
                            runCatching { onOutputChunk(tail) }
                        }
                        val error = msg.data?.getString(InferenceWorkerProtocol.KEY_ERROR).orEmpty()
                            .ifBlank { "本机推理服务执行失败" }
                        completion.completeExceptionally(IOException(error))
                    }
                }
            }
        }
        val replyMessenger = Messenger(replyHandler)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val remote = Messenger(service)
                serviceMessenger = remote
                if (cancelled) {
                    sendCancel(remote)
                    completion.completeExceptionally(CancellationException("已取消生成"))
                    return
                }
                val runMsg = Message.obtain(null, InferenceWorkerProtocol.MSG_RUN).apply {
                    replyTo = replyMessenger
                    data = Bundle(5).apply {
                        putString(InferenceWorkerProtocol.KEY_JOB_ID, jobId)
                        putString(InferenceWorkerProtocol.KEY_ENGINE, engine)
                        putStringArrayList(InferenceWorkerProtocol.KEY_ARGS, ArrayList(args))
                        putString(InferenceWorkerProtocol.KEY_OPENCL_CACHE_DIR, openClCacheDir)
                        putString(InferenceWorkerProtocol.KEY_WORK_DIR, workDir)
                    }
                }
                try {
                    remote.send(runMsg)
                } catch (error: Throwable) {
                    completion.completeExceptionally(
                        if (cancelled) CancellationException("已取消生成")
                        else IOException("无法向推理服务发送任务：${error.message ?: error.javaClass.simpleName}", error)
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceMessenger = null
                if (!completion.isCompleted) {
                    completion.completeExceptionally(
                        if (cancelled) CancellationException("已取消生成")
                        else IOException("推理进程异常中止（可能因内存不足被系统回收）")
                    )
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                serviceMessenger = null
                if (!completion.isCompleted) {
                    completion.completeExceptionally(
                        if (cancelled) CancellationException("已取消生成")
                        else IOException("推理进程已退出")
                    )
                }
            }

            override fun onNullBinding(name: ComponentName?) {
                serviceMessenger = null
                if (!completion.isCompleted) {
                    completion.completeExceptionally(IOException("无法绑定推理服务"))
                }
            }
        }

        val intent = Intent(appContext, InferenceWorkerService::class.java)
        var bound = false
        try {
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                throw IOException("无法启动本机推理服务")
            }
            return completion.await()
        } catch (error: CancellationException) {
            cancel()
            throw error
        } finally {
            serviceMessenger = null
            activeCompletion = null
            if (bound) {
                runCatching { appContext.unbindService(connection) }
            }
            replyThread.quitSafely()
        }
    }

    fun cancel() {
        cancelled = true
        val remote = serviceMessenger
        if (remote != null) {
            sendCancel(remote)
        }
        activeCompletion?.completeExceptionally(CancellationException("已取消生成"))
    }

    private fun sendCancel(remote: Messenger) {
        runCatching {
            remote.send(Message.obtain(null, InferenceWorkerProtocol.MSG_CANCEL))
        }
    }
}
